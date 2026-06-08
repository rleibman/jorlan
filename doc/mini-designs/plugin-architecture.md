<!--
 Copyright (c) 2026 Roberto Leibman - All Rights Reserved
-->

# Plugin Architecture (Skills & Connectors) — Unified Design

**Status:** Design — 2026-06-05
**Scope:** Cross-phase vision. The runtime trait seam lands in Phase 11; the registry, dispatch,
and importers land across Phases 12–17.

---

## 1. Problem Statement

Jorlan uses "plugins" in several guises — **skills** (tools an agent invokes) and **connectors**
(bridges to external systems: Telegram, Slack, email, shell). Today they are modeled as two
unrelated things: the persisted `SkillRecord` / `SkillVersion` registry rows versus
`ConnectorInstance`. We want **one coherent plugin model** that:

- serves every place a plugin appears,
- ships useful basics out-of-the-box,
- makes it easy for **users and agents** to author their own, and
- spans every provenance: built-in Scala, installed JARs, declarative JSON, sandboxed scripts,
  imported MCP / SKILL.md bundles, and agent-authored drafts.

---

## 2. The Two-Faces Insight

A connector is **two faces of one plugin**:

- **Ingress** — a long-lived listener that receives external events → normalizes them → resolves
  identity → hands them to `AgentRunner`. This is the genuinely "connector" part. It is *not*
  request/response; data flows *toward* the agent.
- **Egress** — "send a message" / "send a photo" is just a **tool the agent calls** — i.e. a
  skill, backed by the same connector config (e.g. bot token). Data flows *out from* the agent.

So connectors are **not** a parallel hierarchy to skills. A connector is a plugin that contributes
egress skills *plus* an ingress listener. This collapses two subsystems into one.

---

## 3. Runtime Contract

In Jorlan a "skill" is already a **namespace of multiple tools**, not a single tool
(`MemorySkill` exposes remember/search/forget/markShared/markPrivate; `SchedulerSkill` exposes six
operations). The runtime trait reflects that: one `Skill` = one tool namespace; `invoke` dispatches
by tool name.

```scala
// model/src/main/scala/jorlan/service/Skill.scala

/** A loadable unit of agent capability: a namespace of one or more tools the agent may invoke. */
trait Skill {

  /** Static description: namespace name, tier, and the tools this skill exposes. */
  def descriptor: SkillDescriptor

  /** Invoke one tool in this skill's namespace. `args`/result are validated against the tool's
   * input/output JSON schema by the registry, not here. */
  def invoke(ctx: InvocationContext, tool: String, args: Json): IO[JorlanError, Json]

}

/** A skill that also bridges an external system: it adds the ingress lifecycle. Its egress is just
 * the inherited `invoke` tools (e.g. `telegram.send_message`). */
trait ConnectorSkill extends Skill {

  def connectorType: ConnectorType

  def instanceId: ConnectorInstanceId // the ConnectorInstance config (credentials) this is bound to

  /** Begin ingress: poll/listen → normalize → MessageIngress. Forked as a daemon at boot. */
  def start: IO[JorlanError, Unit]

  /** Stop ingress and release resources. */
  def stop: IO[JorlanError, Unit]

}
```

Supporting types (also in `jorlan.service`):

```scala
case class SkillDescriptor(
                            name: String, // namespace, e.g. "telegram", "memory"
                            tier: SkillTier,
                            tools: List[ToolDescriptor],
                          )

case class ToolDescriptor(
                           name: String, // fully qualified, e.g. "telegram.send_message"
                           description: String,
                           inputSchema: Json, // JSON Schema
                           outputSchema: Json, // JSON Schema
                           requiredCapabilities: List[CapabilityName],
                         )

/** The authority context a tool runs under — resolved by the runtime, never supplied by the model. */
case class InvocationContext(
                              actorId: UserId,
                              agentId: Option[AgentId],
                              sessionId: Option[AgentSessionId],
                            )
```

### Why subtyping (and where it stops)

- **Subtyping** (`ConnectorSkill extends Skill`) handles "this skill *also* does ingress." Egress
  tools flow through the same registry the ReAct loop already uses — **zero special-casing** in the
  agent loop. Boot wiring is a one-liner:
  `registry.all.collect { case c: ConnectorSkill => c }.foreach(_.start.forkDaemon)`.
- **Composition** handles the orthogonal axis: "one loadable artifact (a JAR, an MCP server)
  contributes *many* skills at once." That belongs to a future `PluginLoader` that registers N
  `Skill`s — it is layered *above* the runtime contract and is not needed until Tier 1/4 land.

The two axes are complementary; the trait shape above does not block the loader.

> **Naming note.** The clean names `Skill` / `ConnectorSkill` are the **runtime service traits**
> (`jorlan.service`). The persisted registry row is `SkillRecord` (`jorlan.domain`, renamed from the
> old `Skill` case class). One is callable code; the other is a DB record + manifest pointer.

---

## 4. Tiers Are Adapters

The six `SkillTier` values already in `domain/skill.scala` become **adapters** — each turns its
source format into `Skill`s that land in the same registry. The agent invokes everything
identically; the tier only governs trust, sandboxing, and approval gates.

| Tier | Name | Adapter | Isolation |
|------|------|---------|-----------|
| 0 | BuiltIn | Native Scala class wrapped directly (`MemorySkill`, `SchedulerSkill`, `TelegramConnectorSkill`) | in-process |
| 1 | Plugin | `java.util.ServiceLoader` SPI against a published `plugin-api` interface; JAR dropped on the plugin path | in-process |
| 2 | Declarative | Generic executors interpreting a JSON manifest (HTTP/API, prompt/template, workflow, query, command-template) | in-process |
| 3 | Scripted | Sandboxed subprocess executor (zio-process) | **out-of-process** |
| 4 | Imported | MCP client proxying `invoke` → JSON-RPC; Claude/OpenClaw SKILL.md bundles (see §6) | **out-of-process** |
| 5 | AgentDraft | An agent-authored declarative/scripted skill, not yet approved → fully gated until promoted | out-of-process |

Lifecycle reuses the existing `SkillStatus` gate
(`Draft → Validated → PermissionReviewed → SandboxTested → AwaitingApproval → Active`, with
`Deprecated`/`Revoked`). Capability gating and `ApprovalMode` remain the spine — the runtime, not
the agent, grants authority. This is also the answer to open design issue #5 (promoting
agent-authored skills): an `AgentDraft` advances through the same status gate under admin approval.

---

## 5. Tier-Driven Isolation

Isolation is a function of trust tier (resolves open design issue #2):

- **Tier 0/1 (native Scala)** run **in-process** — fully trusted, typed, in the ZIO environment.
- **Tier 2 (Declarative)** runs **in-process** via the generic executors (the manifest is data, not
  arbitrary code; the executors are trusted).
- **Tier 3 (Scripted)** and **Tier 4 (Imported/MCP)** run **out-of-process** — a sandboxed
  subprocess or an MCP transport (stdio subprocess / remote HTTP). Untrusted code never shares the
  server JVM.

MCP's transport model (subprocess or remote) gives out-of-process isolation for free, which is one
more reason it is the natural Tier-4 backbone.

---

## 6. Interop: MCP Backbone + SKILL.md Import

Two import formats solve **different** problems and integrate at **different seams**:

- **MCP** is a live wire *protocol* (JSON-RPC over stdio/HTTP). A tool = name + JSON-schema input +
  handler returning content. It maps **1:1 onto `Skill.invoke`**: an MCP server connection becomes a
  Tier-4 `Skill` whose `invoke` proxies a JSON-RPC `tools/call`. It gives the agent new *callable
  functions* and grants instant access to the existing MCP server ecosystem.
- **SKILL.md** (Claude / OpenClaw Agent Skills) is a *packaging* format — a folder with
  YAML-frontmatter markdown instructions plus optional bundled scripts. It is prompt-oriented: it
  gives the agent new *know-how*, not a schema'd function. It is the **easiest authoring path** (write
  markdown, no code) and the natural output format for agent-authored skills.

To keep the unified model clean, SKILL.md **decomposes** rather than stretching the `invoke`
contract:

1. its markdown instructions become an injectable **playbook fragment** (a prompt-injection seam), and
2. its bundled scripts register as **Tier-3 Scripted skills** (real `invoke` tools).

**Export:** Jorlan should also expose its own active skills **as an MCP server**, so external agents
(Claude Desktop, other orchestrators) can call Jorlan skills — symmetric with import.

All importers are Phase 17 work; the trait seam designed here is what they target.

---

## 7. Registry & Dispatch (Phase 12)

A `SkillRegistry` ZIO service holds all active `Skill`s, indexed by namespace/tool name and tier:
register, look up by id/tier, validate `manifestJson` against JSON schema. The ReAct loop builds the
model's tool list from `descriptor`s and dispatches model tool-calls through `invoke`, gating each
on `requiredCapabilities` via the existing `CapabilityEvaluator` / `ApprovalService`.

A minimal `ConnectorManager` (Phase 11) is the first sliver of this: it collects `ConnectorSkill`s
and `startAll`/`stopAll`s their ingress — enough to run Telegram before the full registry exists.

---

## 8. GraphQL Surface (Phase 14)

Capability discovery exposes plugins to orchestrators:

```graphql
query {
    skills      { id name version description trustLevel tools { name inputSchema outputSchema requiredCapabilities } }
    connectors  { id connectorType name status }
    capabilities{ name description supportedScopes defaultApprovalMode riskClass }
}
```

`configJson` / credentials are never returned to unprivileged callers (`ConnectorInstance.toString`
already redacts).

---

## 9. Phase Map

| Phase | Plugin-architecture deliverable |
|-------|---------------------------------|
| 11 | `Skill` / `ConnectorSkill` trait seam, reusable `MessageIngress`, minimal `ConnectorManager`, Telegram as first `ConnectorSkill` |
| 12 | `SkillRegistry`, manifest JSON-schema validation, ReAct tool dispatch, Tier-0 built-ins (workspace, shell, identity, notify), `NotificationRouter` (egress routing to `ConnectorSkill`s) |
| 13 | Email/Calendar/Drive as skills (provider-independent), OAuth2 credential service |
| 14 | GraphQL plugin discovery + orchestrator integration |
| 17 | Tier 1 JAR loader (`plugin-api` + ServiceLoader), Tier 2 declarative executors, Tier 3 sandbox, Tier 4 MCP client + SKILL.md importer, MCP-server export, AgentDraft promotion |

---

## 10. Resolved Decisions

1. Unified model via subtyping: `ConnectorSkill extends Skill`; one object per connector.
2. Persisted `Skill` record renamed `SkillRecord`; runtime traits keep `Skill` / `ConnectorSkill`.
3. Phase 11 builds the foundational seam only; registry + dispatch deferred to Phase 12.
4. Interop = MCP backbone (import + export) + SKILL.md import, integrating at different seams.
5. Isolation is tier-driven (native in-process; scripted/imported out-of-process).
