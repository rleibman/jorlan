# Phase 12 Mini-Design: Built-in Skills & ReAct Tool-Calling Loop

> Status: pre-implementation design
> Date: 2026-06-09
> Phase goal: Core Tier-0 skills that unlock real agent utility — the full tool-calling loop,
> a skill registry, notification routing, identity/contacts lookup, workspace file access,
> and shell execution.

---

## 1. Context and Motivation

Through Phase 11, `AgentRunnerImpl.processMessage` is a thin pass-through: user message in →
`ModelGateway.streamedResponse` → tokens out. The LLM has no way to request tool invocations,
so every skill built so far (MemorySkill, SchedulerSkill, TelegramConnectorSkill egress tools)
is unreachable from natural-language prompts.

Phase 12 fills that gap in two layers:

1. **Foundation** — the ReAct loop and SkillRegistry that let the LLM call any registered tool.
2. **Skills** — the concrete Tier-0 skills that make everyday agent actions possible.

Until the Foundation items land, none of the skill items below can be exercised via natural
language. The Foundation must be completed first.

---

## 2. Use Cases Driving This Phase

These are the concrete scenarios Phase 12 must make possible (see Section G of the manual
testing guide for the full test plans).

### UC-1: Remember a fact
> "Remember that my favorite color is blue."

The agent calls `memory.remember` with the fact text. The simplest end-to-end tool-calling
exercise — no external dependencies, one tool call, deterministic result.

### UC-2: Send a Telegram to Sarah
> "Send a telegram message to Sarah saying hello from Roberto."

Steps:
1. Agent calls `contacts.find("Sarah")` → receives `{ channelUserId: "123456789", channelType: "Telegram" }`.
2. Agent calls `telegram.send_message { chatId: "123456789", text: "Hello from Roberto." }`.
3. Message arrives in Sarah's Telegram DM.

Prerequisites: a `ChannelIdentity` row for Sarah with `channelType = Telegram`.

### UC-3: Scheduled morning Telegram
> "Send a telegram to Roberto every morning at 10am saying good morning."

Steps:
1. Agent calls `contacts.find("Roberto")` → chatId.
2. Agent calls `scheduler.create_job` with a cron trigger `0 10 * * *` and tool invocation
   `telegram.send_message { chatId, text: "Good morning!" }`.
3. At 10:00 the TriggerEngine fires the job → NotificationRouter sends the Telegram message.

---

## 3. Foundation: ReAct Tool-Calling Loop

### 3.1 Why "ReAct"

ReAct (Reason + Act) is the standard pattern:
- Submit conversation + list of available tools to the model.
- Model returns either a **final text answer** or a **tool call request** (tool name + args JSON).
- If tool call: invoke → append result → re-submit.
- Repeat until final answer or max-steps guard triggers.

### 3.2 `ModelGateway` extension

Add a second method alongside the existing `streamedResponse`:

```scala
// returns either a streaming final answer or a single tool call request
def chatStep(
  sessionId:   AgentSessionId,
  messages:    List[AgentMessage],   // full conversation incl. system prompt
  tools:       List[ToolDescriptor]
): IO[ModelError, ChatStep]

sealed trait ChatStep
case class FinalAnswer(stream: ZStream[Any, ModelError, String]) extends ChatStep
case class ToolCallRequested(name: String, argsJson: String)    extends ChatStep
```

`AgentMessage` is a local type (role + content) that maps to LangChain4j's `ChatMessage`.
The existing `streamedResponse` remains for callers that don't need tool calling.

**LangChain4j mapping:**
- Convert `List[ToolDescriptor]` → `List[ToolSpecification]` using the descriptor's JSON schema.
- Submit via `StreamingChatLanguageModel.chat(messages, toolSpecs, handler)`.
- LangChain4j calls `onToolCall(ToolExecutionRequest)` on the stream handler → return
  `ToolCallRequested`; or `onCompleteResponse(text)` → return `FinalAnswer`.

### 3.3 `AgentRunnerImpl` loop

Replace the current single-call pass-through with:

```
processMessage(sessionId, content, actorId):
  1. Load conversation history from ConversationRepository
  2. Load relevant memory from MemoryService
  3. Build system prompt (personality + memory context)
  4. Append user message to history
  5. Get allTools from SkillRegistry
  6. Loop (max MAX_STEPS = 10 iterations):
     a. Call ModelGateway.chatStep(sessionId, history, tools)
     b. If FinalAnswer:
          - Stream chunks to SessionHub
          - Persist assistant message to ConversationRepository
          - Log AgentResponseCompleted event
          - Break
     c. If ToolCallRequested(name, argsJson):
          - Log ToolInvoked event
          - Call SkillRegistry.invoke(name, argsJson, context)
          - Log ToolResult event
          - Append ToolCall + ToolResult messages to history
          - Continue loop
  7. If MAX_STEPS reached without FinalAnswer:
       - Publish error chunk; log ToolLoopExceeded event
```

**Max-steps guard**: configurable via `jorlan.agent.maxToolSteps` (default 10).
If the loop exceeds `maxToolSteps`, publish a system error chunk to the session and terminate
the turn. This prevents runaway chains consuming tokens indefinitely.

**Event log additions:**
- `ToolInvoked { sessionId, toolName, argsJson }`
- `ToolResult  { sessionId, toolName, resultJson }`
- `ToolLoopExceeded { sessionId, steps }`

### 3.4 `SkillRegistry` ZIO service

```scala
trait SkillRegistry {
  def register(skill: Skill): UIO[Unit]
  def allTools:  UIO[List[ToolDescriptor]]
  def lookup(toolName: String): IO[JorlanError, Skill]
  def invoke(
    toolName: String,
    argsJson: String,
    context:  InvocationContext
  ): IO[JorlanError, String]   // returns result JSON or human-readable text
}
```

`SkillRegistryLive` holds a `Ref[Map[String, Skill]]`. Registration is done at server startup
via `EnvironmentBuilder` before the `ConnectorManager` starts.

**Validation before invoke:**
- Parse `argsJson` against the tool's `inputSchema` (use zio-json schema validation).
- Gate on the tool's required capability using `CapabilityEvaluator`.
- If either fails, return a structured error result (not a JVM exception) so the model can
  report the failure naturally.

**Startup wiring (in EnvironmentBuilder):**
```scala
SkillRegistry.register(MemorySkill)        // Phase 9 skill, now wired
SkillRegistry.register(SchedulerSkill)     // Phase 10 skill, now wired
// TelegramConnectorSkill tools wired conditionally:
if telegramConfigured then
  TelegramConnectorSkill.toolDescriptors.foreach(SkillRegistry.register)
```

`MemorySkill` and `SchedulerSkill` already exist — this phase just wires them into the registry
and ensures their `invoke` methods are called through the new loop.

---

## 4. Notification Skill (`notify.*`)

`NotificationRouter` is the server-side ZIO service that resolves a user or channel to an
active connector and sends a message. It is the missing piece that makes "send a message to
Sarah" work end-to-end.

### 4.1 `NotificationRouter` service

```scala
trait NotificationRouter {
  // Resolve user's preferred channel identity → send via matching ConnectorSkill
  def notifyUser(userId: UserId, message: String): IO[JorlanError, Unit]

  // Send to an explicit channel (chatId + connectorType)
  def notifyChannel(
    chatId:        String,
    connectorType: ConnectorType,
    message:       String
  ): IO[JorlanError, Unit]
}
```

`NotificationRouterImpl`:
1. For `notifyUser`: query `ChannelIdentityRepository` for the user's linked identities.
   Preference order: Telegram → (future: Slack, Email). Call `notifyChannel` with the first
   active identity found.
2. For `notifyChannel`: look up the matching `ConnectorSkill` in `SkillRegistry` by
   `connectorType` → call `invoke("send_message", { chatId, text }, context)`.
3. If no matching connector is active: return `JorlanError.NoActiveConnector`.

### 4.2 `NotifySkill` tools

| Tool name          | Args                              | Effect                                      |
|--------------------|-----------------------------------|---------------------------------------------|
| `notify.user`      | `{ userId, message }`             | Routes to user's preferred channel          |
| `notify.channel`   | `{ chatId, connectorType, message }` | Sends to explicit channel               |

Both tools carry `RiskClass = ExternalEffect` and require `notify.send` capability.
The skill is registered in `SkillRegistry` at startup.

---

## 5. Identity and Contacts Skill (`contacts.*`, `identity.*`)

This skill bridges the gap between human names ("Sarah") and the machine IDs (`chatId = 123456789`)
that connectors require. Without it, the LLM cannot resolve a name to a destination.

### 5.1 `contacts.find`

```
Tool: contacts.find
Args: { "name": "Sarah" }
Returns: [
  { "userId": "...", "displayName": "Sarah Smith",
    "channelType": "Telegram", "channelUserId": "123456789" },
  ...
]
```

Implementation: `UserRepository.searchByDisplayName(name)` joined with
`ChannelIdentityRepository.byUserId`. Returns a list so the model can ask a clarifying
question if multiple Sarahs exist.

Risk class: `ReadOnly` (no external effects, no approval required).

### 5.2 `identity.*` tools

| Tool name              | Args                                     | Effect                               |
|------------------------|------------------------------------------|--------------------------------------|
| `identity.resolve`     | `{ channelType, channelUserId }`         | Returns canonical User or null       |
| `identity.link`        | `{ userId, channelType, channelUserId }` | Links external identity to user      |
| `identity.listAliases` | `{ userId }`                             | Returns all channel identities       |

`identity.link` requires `identity.manage` capability; others are `ReadOnly`.

---

## 6. Workspace / Filesystem Skill (`workspace.*`)

Agents can read, write, search, and snapshot files in a sandboxed workspace directory.

### 6.1 Workspace concept

Each workspace maps to a directory under `jorlan.workspace.root` (default `~/.jorlan/workspaces/`).
The resolved path depends on `jorlan.workspace.defaultScope`:

- `session` (default): `<root>/<sessionId>/`
- `user`: `<root>/<userId>/`

Agents may not escape the workspace root. Path traversal (`../`) is rejected at the skill
boundary before any filesystem operation.

### 6.2 Tools

| Tool name            | Args                              | Risk class     | Approval      |
|----------------------|-----------------------------------|----------------|---------------|
| `workspace.read`     | `{ path }`                        | ReadOnly       | None          |
| `workspace.write`    | `{ path, content }`               | LocalEffect    | PerInvocation |
| `workspace.search`   | `{ pattern, recursive? }`         | ReadOnly       | None          |
| `workspace.snapshot` | `{ tag? }`                        | LocalEffect    | None          |
| `workspace.delete`   | `{ path }`                        | Destructive    | Always        |

`workspace.snapshot` creates a tar.gz artifact under `ArtifactRepository` (in-workspace) so
the model can attach or reference it later.

`workspace.delete` always requires explicit approval regardless of capability grant mode.

### 6.3 Path sandboxing (implementation note)

Before any FS operation:
```scala
val resolved = workspaceRoot.resolve(userPath).normalize()
if (!resolved.startsWith(workspaceRoot))
  ZIO.fail(JorlanError.PathTraversal(userPath))
```

---

## 7. Shell Execution Skill (`shell.*`)

Agents can run structured shell commands inside the workspace. Raw `bash -c` invocation is
disabled by default; only binary + args form is supported.

### 7.1 Command structure

```scala
case class ShellInvocation(
  binary:  String,           // absolute path or PATH-resolved name; must be on allowlist
  args:    List[String],     // typed, not concatenated; prevents injection
  cwd:     Option[String],   // defaults to workspace root
  timeout: Option[Duration], // defaults to jorlan.shell.defaultTimeout (30s)
  env:     Map[String, String] // additional env vars, merged (not replaced)
)
```

### 7.2 Allowlist and risk classification

`jorlan.shell.allowedBinaries` (config, default: `["cat","ls","find","grep","wc","sort","uniq","head","tail","echo"]`)

`RiskClassifier` maps binaries to `RiskClass`:
- Tier 0 (ReadOnly): `cat`, `ls`, `find`, `grep`, `head`, `tail`, `wc` with no write flags
- Tier 2 (LocalEffect): `echo`, file-writing variants (output redirection blocked — no shell)
- Tier 4 (Dangerous): any binary not on the allowlist → requires explicit admin approval

Raw `bash -c` is always Tier 5 (Dangerous) and requires explicit admin capability grant.

### 7.3 Execution and artifact capture

Uses `zio-process` (already a dependency). Stdout + stderr captured as UTF-8 strings.
On timeout: process is killed, `ShellTimeout` error returned to model.
Exit code ≠ 0 → the result JSON includes `exitCode`, `stderr` — the model can handle it.

Result stored in `ArtifactRepository` if `sizeBytes > jorlan.shell.captureThreshold` (default 4 KB).
The artifact URI is included in the tool result for the model to reference.

### 7.4 Full trace written to event log

```
ShellCommandInvoked {
  sessionId, agentId, workspaceId, userId,
  binary, args, cwd, timeoutMs,
  approvalId?
}
ShellCommandCompleted {
  sessionId, exitCode, stdoutBytes, stderrBytes, durationMs, artifactId?
}
```

---

## 8. GraphQL and Shell Surface Changes

### 8.1 New GraphQL items

**Queries:**
- `skills: [SkillInfo!]!` — lists registered skills with name, tier, tools, required capabilities
- `notifyUser(userId: ID!, message: String!): Boolean!` — direct notification mutation (admin)

**Subscriptions:**
- `toolEvents(sessionId: Long!): ToolEvent` — streams `ToolInvoked` and `ToolResult` events
  to the shell so the user can see what the agent is doing during a multi-step turn.

### 8.2 Shell changes

- During a tool-calling turn, the shell shows a spinner line: `⟳ calling contacts.find…`
  updated for each tool invocation, then replaced by the final answer stream.
- `/skills` command: lists registered skills and their tools (calls `skills` GQL query).
- `/contacts find <name>` command: directly calls `contacts.find` for lookup without agent.

---

## 9. `InvocationContext` extension

`InvocationContext` (defined in `connector-api`) needs additional fields for skill execution:

```scala
case class InvocationContext(
  sessionId:   AgentSessionId,
  userId:      UserId,
  agentId:     AgentId,
  workspaceId: Option[WorkspaceId],
  approvalId:  Option[ApprovalId],      // set if approval was obtained
  traceId:     String                   // for event log correlation
)
```

---

## 10. Configuration additions

```hocon
jorlan {
  agent {
    maxToolSteps = 10          # max ReAct iterations per turn
  }
  workspace {
    root = "~/.jorlan/workspaces"
    defaultScope = "session"   # "session" or "user"
    captureThreshold = 4096    # bytes; above this, shell output goes to ArtifactRepository
  }
  shell {
    defaultTimeout = 30s
    allowedBinaries = ["cat", "ls", "find", "grep", "wc", "sort", "uniq", "head", "tail", "echo"]
    allowRawBash = false       # Tier 5 — requires admin capability grant to enable
  }
}
```

---

## 11. Migration

**V025**: Add `workspace_id` column to `agent_sessions` (nullable FK to `workspaces`).
Auto-assign a default workspace per session on first write operation.

---

## 12. Capability grants added to admin init

During `InitService.complete`, seed the following grants for the admin user:

- `notify.send`
- `contacts.read`
- `identity.manage`
- `workspace.read`, `workspace.write`
- `shell.execute`
- `agent.skill.invoke`

---

## 13. Implementation order

```
Step 1: ModelGateway.chatStep + LangChain4j tool spec wiring
Step 2: SkillRegistry + startup wiring of MemorySkill + SchedulerSkill
Step 3: AgentRunnerImpl ReAct loop (replaces thin pass-through)
Step 4: NotifySkill + NotificationRouter
Step 5: ContactsSkill + IdentitySkill
Step 6: WorkspaceSkill
Step 7: ShellSkill
Step 8: GraphQL/shell surface changes
Step 9: Tests, scalafmt, roadmap checkboxes
```

Steps 1–3 are the Foundation and are a hard prerequisite for UC-1 through UC-3 above.
Steps 4–5 are required for UC-2 and UC-3.
Steps 6–7 are independent and can be done in any order after Step 3.

---

## 14. Testing strategy

### Unit tests

- `SkillRegistrySpec`: register, lookup, invoke with valid/invalid args, capability denied
- `AgentRunnerReActSpec`: use `FakeModelGateway` that returns `ToolCallRequested` on first
  call and `FinalAnswer` on second; assert correct tool invocation and event log entries
- `NotificationRouterSpec`: `notifyUser` with known/unknown user identity; `notifyChannel`
  with active/inactive connector
- `ContactsSkillSpec`: `find` with match, no match, multiple matches
- `WorkspaceSkillSpec`: path traversal rejection, read/write round-trip, delete approval gate
- `ShellSkillSpec`: allowed binary executes, blocked binary fails, timeout fires, exit-code ≠ 0
  returns error result (not exception)

### Integration tests

- `ToolCallingLoopSpec` (Testcontainers + `FakeModelGateway`): full pipeline from
  `submitMessage` → two tool calls → final answer → `agentResponseStream` delivers chunks
- Capability gate: tool call with no grant → error result appended to conversation, not crash

### Manual tests (Section G of manual-testing-guide.md)

See the updated Section G (UC-1 through UC-3) for step-by-step manual verification plans.

---

## 15. Resolved design decisions

1. **Streaming final answer after tool calls**: streaming is used. The `FinalAnswer(ZStream)`
   design is the target — the shell streams the final answer token-by-token even after one or
   more preceding tool-call iterations.

2. **Workspace scope**: configurable per installation. The config key
   `jorlan.workspace.defaultScope` accepts `session` (default) or `user`. When `session`, each
   `AgentSession` gets its own workspace directory. When `user`, all sessions for the same user
   share a single workspace directory. Shared cross-user workspaces (the existing `Workspace`
   domain type) remain a separate concept and are deferred.

3. **Tool error handling**: when `SkillRegistry.invoke` fails, the error is appended to the
   conversation history as a `ToolResult` message (not a JVM exception, not a turn termination).
   This lets the model report the failure naturally or attempt a recovery. Only failures that
   indicate an unrecoverable system error (e.g. DB unavailable) terminate the turn.

4. **Contacts fuzzy search**: `contacts.find` in Phase 12 uses exact prefix/substring match on
   `User.displayName` (case-insensitive). Fuzzy/phonetic search is deferred to Phase 16
   (see development roadmap). For the Phase 12 use cases to work, the `User` record must have
   a `displayName` that contains the name used in the prompt (e.g. `displayName = "Sarah Smith"`
   for the prompt "Send a telegram to Sarah").
