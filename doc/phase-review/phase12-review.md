/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# Phase 12 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala, Code Simplicity, Performance Oracle, Pattern Recognition, Test Coverage, SRS/SDD Conformance, ScalaDoc Auditor)
**Date**: 2026-06-10
**Branch**: `phase-12/built-in-skills`
**Scope**: Phase 12 — Built-in Skills (SkillRegistry, AgentRunnerImpl ReAct loop, NotificationRouter, NotifySkill, ContactsSkill, WorkspaceSkill, ShellSkill)

---

## Executive Summary

Phase 12 delivered a clean, architecturally sound ReAct (Reason+Act) loop. `SkillRegistry` correctly separates tool discovery from dispatch, `AgentRunnerState` is a well-structured `Ref`-bundle, `WorkspaceSkill.safePath` enforces path-traversal bounds correctly, and the circular-dependency break between `NotificationRouter` and `SkillRegistry` is both correct and documented. All four new skills are properly registered at runtime in `startServices`, and the `MemorySkill`/`SchedulerSkill` pre-registration in `liveSkillRegistryLayer` cleanly separates startup order from dependency topology. 577 tests pass, statement coverage 85.03%.

Two security issues require attention before any production deployment: the capability gate in `SkillRegistry.invoke` is entirely absent, meaning any authenticated session can invoke any skill tool regardless of granted capabilities; and `workspace.delete` uses the same capability as `workspace.write` with no approval gate despite the spec's "Always Destructive" risk classification. A third correctness issue — the `payload` parameter passed to `logSkillEvent` is silently dropped, leaving the audit trail empty of tool arguments and results — was independently flagged by three agents. The blocking filesystem I/O pattern in `WorkspaceSkill` (four agents) and `Instant.now()` direct calls (four agents) are the highest-volume cross-cutting issues. Several spec features remain unimplemented (workspace scoping, `workspace.snapshot`, `InvocationContext` extension, GQL `toolEvents` subscription) and are tracked below.

**Overall health: Issues Present — ready to advance to Phase 13 with open items tracked.** The two security findings (P12-001, P12-002) must be resolved before production deployment.

ScalaDoc coverage is consistent with prior phases: new skill traits are well-described, but `AgentRunnerImpl` has no class-level ScalaDoc and the numbered ReAct-steps overview is attached to the wrong element (`AgentRunnerState` instead of `AgentRunnerImpl`).

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area              | Issue                                                                                                                             | File : Line                          | Recommended Action                                                                                                     |
|--------|------------|------------|-------------------|-----------------------------------------------------------------------------------------------------------------------------------|--------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| [x]    | P12-001    | Critical   | Security          | `SkillRegistry.invoke` never checks `tool.requiredCapabilities` against `CapabilityEvaluator`; any session can invoke any tool. (confirmed by 2 reviewers) | `SkillRegistry.scala:93`             | Inject `CapabilityEvaluator`; before dispatch, evaluate each required capability and return `Json.Str("Error: …")` on deny. |
| [x]    | P12-002    | Critical   | Security          | `workspace.delete` uses `workspace.write` capability and has no approval gate despite Destructive risk classification.            | `WorkspaceSkill.scala:78`            | Assign distinct `CapabilityName("workspace.delete")`; add `ApprovalService` call before executing deletion.           |
| [x]    | P12-003    | Critical   | Correctness       | `logSkillEvent` accepts `payload: String` but never writes it to `payloadJson`; tool args and results are silently dropped from the event log. (confirmed by 3 reviewers) | `AgentRunnerImpl.scala:192-204`      | Add `"payload" -> Json.Str(payload)` to the stored `payloadJson` object.                                               |
| [x]    | P12-004    | Warning    | Concurrency       | All four filesystem operations in `WorkspaceSkill` use `ZIO.attempt` (default pool), not `ZIO.attemptBlocking`; concurrent sessions can starve ZIO fibers. (confirmed by 4 reviewers) | `WorkspaceSkill.scala:128-186`       | Replace every `ZIO.attempt { Files.* }` with `ZIO.attemptBlocking { Files.* }`.                                       |
| [x]    | P12-005    | Warning    | Functional Purity | `Instant.now()` called directly (not via `Clock.instant`) in two places, breaking `TestClock` compatibility. (confirmed by 4 reviewers) | `ContactsSkill.scala:183`, `AgentRunnerImpl.scala:368` | Replace with `Clock.instant.flatMap { now => … }` matching the pattern in `AgentRunnerImpl.logSessionEvent`. |
| [x]    | P12-006    | Warning    | Performance       | `contactsFind` fetches up to 200 users then filters by name in the JVM; name predicate never reaches SQL; results silently truncated on larger deployments. (confirmed by 3 reviewers) | `ContactsSkill.scala:115-117`        | Add `nameContains: Option[String]` to `UserSearch`; push `LIKE` predicate to SQL; remove magic `200` constant.        |
| [x]    | P12-007    | Warning    | Code Quality      | `getStr` JSON-extraction helper is defined identically in four skill files; `parseChannelType` duplicated in two. (confirmed by 2 reviewers) | `NotifySkill.scala:67`, `ContactsSkill.scala:97`, `WorkspaceSkill.scala:99`, `ShellSkill.scala:60` | Extract to `private[service] object SkillArgs` in the `jorlan.service` package. |
| [x]    | P12-008    | Warning    | Code Quality      | `contactsFind` (line 139) is a one-line wrapper delegating to `contactFind` (line 109) with identical logic and slightly different names. (confirmed by 3 reviewers) | `ContactsSkill.scala:109-139`        | Delete the wrapper; rename `contactFind` to `contactsFind` and update the dispatch at line 90.                         |
| [x]    | P12-009    | Warning    | API Design        | `SkillInfo.tier` in the GQL `skills` resolver is hardcoded to `"BuiltIn"`; actual `SkillTier` from registry is ignored; `requiredCapabilities` not surfaced. | `JorlanAPI.scala:538-550`            | Read `SkillTier` from `SkillDescriptor`; add `requiredCapabilities: List[String]` to `SkillInfo`.                     |
| [x]    | P12-010    | Warning    | Architecture      | `workspace.snapshot` tool absent; spec §6.2 requires it as part of the `WorkspaceSkill` tool surface.                           | `WorkspaceSkill.scala`               | Implement `workspace.snapshot` writing a tar.gz artifact to `ArtifactRepository`; return the artifact URI.            |
| [x]    | P12-011    | Warning    | Architecture      | `InvocationContext` missing `workspaceId`, `approvalId`, `traceId` fields required by spec §9; blocks approval gate and workspace scoping. | `connector-api/.../Skill.scala:110`  | Add the three fields; gate `workspace.delete` on `approvalId`; scope `WorkspaceSkill` paths on `workspaceId`.         |
| [x]    | P12-012    | Warning    | Error Handling    | `SchedulerSkill.create_job` calls `addTrigger(...).ignore`; trigger creation failure leaves a job record with no trigger silently. | `SchedulerSkill.scala:136`           | Remove `.ignore`; propagate the error and delete the orphaned job record on trigger failure.                            |
| [x]    | P12-013    | Warning    | Architecture      | `NotificationRouter` constructs connector tool names by string convention (`s"${connType.toLower}.send_message"`); any connector naming its tool differently fails silently. | `NotificationRouter.scala:125`       | Add `sendMessageToolName: Option[String]` to `ConnectorSkill`; read from the skill instead of constructing the name.   |
| [x]    | P12-014    | Warning    | Documentation     | `AgentRunner.scala` still contains the sentence "The full ReAct planning loop is deferred to Phase 12." — now factually incorrect. | `model/.../AgentRunner.scala:22`     | Remove the sentence; the loop is fully implemented.                                                                     |
| [x]    | P12-015    | Warning    | Documentation     | The numbered ReAct-step overview (the most valuable implementation doc) is attached to `AgentRunnerState` (a private class) not to `AgentRunnerImpl` (the public class). | `AgentRunnerImpl.scala:23`           | Move the numbered steps to a ScalaDoc comment on `AgentRunnerImpl`; give `AgentRunnerState` a brief one-liner.         |
| [x]    | P12-016    | Suggestion | Code Quality      | `logSessionEvent` and `logSkillEvent` share the same `Clock.instant.flatMap { repo.eventLog.append(…) }` skeleton, differ only in 3 of 8 fields. (confirmed by 2 reviewers) | `AgentRunnerImpl.scala:164-208`      | Unify into a single private `logEvent(eventType, sessionId, actorId, agentId, payloadJson)` helper.                    |
| [x]    | P12-017    | Suggestion | Functional Purity | `safePath` uses `throw new SecurityException` inside `ZIO.attempt` to trigger the catch; idiomatic ZIO uses `ZIO.fail`. | `WorkspaceSkill.scala:114`           | Use `ZIO.attempt(…).flatMap { p => if (!p.startsWith(root)) ZIO.fail(…) else ZIO.succeed(p) }`.                       |
| [x]    | P12-018    | Suggestion | Functional Purity | `WorkspaceSkill.live` calls `Paths.get(s.root).toAbsolutePath.normalize()` inside `ZIO.serviceWith`, a pure function slot; `toAbsolutePath` reads CWD (a side effect). | `WorkspaceSkill.scala:197`           | Change to `ZIO.serviceWithZIO` + `ZIO.attempt(…).orDie`.                                                               |
| [x]    | P12-019    | Suggestion | Code Quality      | `finaliseResponse` opens with two nested `flatMap` calls before any logic; should be a for-comprehension. (confirmed by 2 reviewers) | `AgentRunnerImpl.scala:221`          | Rewrite as `for { errMsg <- errorRef.get; chunks <- chunksRef.get; … } yield ()`.                                      |
| [x]    | P12-020    | Suggestion | Code Quality      | `reactLoop` takes 9 parameters; 7 never change across recursive calls; the signature is a future-bug source. | `AgentRunnerImpl.scala:117`          | Extract a private `ReactLoopEnv(sessionId, tools, actorId, agentId, ctx, errorRef, chunksRef)` and pass it as one arg. |
| [x]    | P12-021    | Suggestion | Performance       | `SkillRegistry.allToolSpecs` traverses the skill map twice per `reactLoop` invocation; the result is static between `register` calls. | `SkillRegistry.scala:90`             | Cache tool specs in a second `Ref`; invalidate on `register`.                                                          |
| [x]    | P12-022    | Suggestion | Performance       | `NotificationRouter.notifyChannel` uses `List.find` to locate a connector on every notification; should use a `Map[ConnectorType, ConnectorSkill]` accessor. | `NotificationRouter.scala:120`       | Expose `Map[ConnectorType, ConnectorSkill]` from `ConnectorManager`.                                                   |
| [x]    | P12-023    | Suggestion | Performance       | `loadPersonality` issues an uncached DB read on every `processMessage`; read twice per call (line 77 and inside `ensureSeeded`). | `AgentRunnerImpl.scala:407`          | Cache in `AgentRunnerState` or read once per `processMessage` and thread the value down.                               |
| [x]    | P12-024    | Suggestion | Observability     | `SkillRegistry.register` silently overwrites an existing skill with the same namespace; a misconfigured startup produces untraceable routing bugs. | `SkillRegistry.scala:85`             | Log a `ZIO.logWarning` when a collision is detected.                                                                   |
| [x]    | P12-025    | Suggestion | Test Coverage     | `notifyUser` fallback branch (`orElse(identities.headOption)`) — routing to a non-Telegram channel when no Telegram identity exists — is never exercised. | `NotificationRouter.scala:96`        | Add test: user with only a Slack identity; assert the Slack connector is invoked.                                      |
| [x]    | P12-026    | Suggestion | Test Coverage     | `notifyChannel` `catchAll` error-swallow branch is never exercised; no test injects a failing `ConnectorSkill`. | `NotificationRouter.scala:130`       | Add test: failing fake connector; assert result starts with "Error:" and the ZIO effect succeeds.                      |
| [x]    | P12-027    | Suggestion | Test Coverage     | `ensureSeeded` → `loadConversationHistory` non-empty branch (`seedHistory` call) is never exercised; test repos always start empty. | `AgentRunnerImpl.scala:271`          | Pre-populate the in-memory conversation repo; verify `CapturingFakeModelGateway.seedHistory` was called.              |
| [x]    | P12-028    | Suggestion | Test Coverage     | `getOrCreateConversation` cache-miss + DB-found path is never hit; the cache is always warm by the time a second call arrives. | `AgentRunnerImpl.scala:325`          | Construct a fresh `AgentRunnerImpl` against a repo that already contains a `Conversation`; verify it is reused.        |
| [x]    | P12-029    | Suggestion | Architecture      | Shell artifact capture for large outputs not implemented; spec §7.3 requires writing to `ArtifactRepository` above a configured size threshold. | `ShellSkill.scala`                   | Compare `stdout.length` against `jorlan.shell.captureThreshold`; write to `ArtifactRepository` and return the URI.    |
| [x]    | P12-030    | Suggestion | Architecture      | Shell event log entries (`ShellCommandInvoked`, `ShellCommandCompleted` with binary/args/exit/duration) not written; spec §7.4 requires shell-specific fields. | `ShellSkill.scala`                   | Write structured event log entries from `ShellSkill` instead of relying on the generic `SkillInvoked`/`SkillSucceeded`. |
| [x]    | P12-031    | Suggestion | Architecture      | `WorkspaceSettings.defaultScope` absent; all sessions share a flat workspace root with no per-session or per-user subdirectory; spec §6.1 requires scoping. | `configuration.scala`, `WorkspaceSkill.scala` | Add `defaultScope: WorkspaceScope` (`Session` \| `User`) and prepend the appropriate sub-path in `WorkspaceSkill`.    |

---

## Grouped Sections

### Security

**Capability gate absent in `SkillRegistry.invoke`** (P12-001) CONFIRMED BY 2 REVIEWERS

`SkillRegistryLive.invoke` validates JSON required-fields but never consults `CapabilityEvaluator` before dispatching. The `ToolDescriptor.requiredCapabilities` list is populated correctly by every skill (`notify.send`, `workspace.write`, `shell.execute`, etc.) but is never read at invocation time. Any session authenticated to the agent can invoke `shell.run`, `workspace.delete`, `identity.link`, or any other tool regardless of what capabilities have been granted to the actor.

```scala
// Fix: inject CapabilityEvaluator into SkillRegistryLive and add before skill dispatch:
for {
  cap  <- tool.requiredCapabilities
  _    <- evaluator.evaluate(CapabilityRequest(cap, ctx.actorId, ...))
            .flatMap {
              case AuthorizationResult.Granted(_) => ZIO.unit
              case AuthorizationResult.Denied(r)  => ZIO.fail(Json.Str(s"Error: capability ${cap.value} not granted: $r"))
            }
} yield ()
```

Note: The error must be `Json.Str(...)` (not a `JorlanError` propagation) so the ReAct loop can feed it back to the model as a tool result.

---

**`workspace.delete` wrong capability + no approval gate** (P12-002)

`workspace.delete` is declared with `requiredCapabilities = List(CapabilityName("workspace.write"))` and no approval gate. The spec classifies it as `RiskClass = Destructive` with an "Always" approval requirement. As a result, a `workspace.write` grant is sufficient to permanently delete workspace files with no approval prompt. Additionally, since P12-001 means capability checks are not enforced at all yet, this is compounded: the risk is inherited.

Fix requires two steps: (1) assign `CapabilityName("workspace.delete")` distinctly; (2) once P12-011 is resolved and `InvocationContext.approvalId` exists, require a non-empty `approvalId` or a live `ApprovalService.require(...)` call before executing deletion.

---

### Correctness

**`logSkillEvent` drops `payload` from the event log** (P12-003) CONFIRMED BY 3 REVIEWERS

The method signature at line 192 accepts `payload: String` which callers pass with the invocation args JSON (`argsJson` at line 152) and result JSON (`resultJson.toString` at line 154). However `payloadJson` on line 204 is hardcoded to `Some(Json.Obj("tool" -> Json.Str(toolName)))` — the `payload` argument is never referenced. Every `SkillInvoked` and `SkillSucceeded` event in the log records only the tool name. All diagnostic data (what the model sent, what the skill returned) is silently discarded.

```scala
// Fix: include payload in the stored object
payloadJson = Some(Json.Obj(
  "tool"    -> Json.Str(toolName),
  "payload" -> Json.Str(payload),
)),
```

---

### Concurrency / Resource Management

**Blocking filesystem I/O on the default ZIO thread pool** (P12-004) CONFIRMED BY 4 REVIEWERS

`WorkspaceSkill` wraps all filesystem calls — `Files.readAllBytes` (line 128), `Files.write` + `Files.createDirectories` (lines 143-148), `Files.walk` traversal (lines 162-176), and `Files.deleteIfExists` (line 186) — in `ZIO.attempt` rather than `ZIO.attemptBlocking`. `ZIO.attempt` runs on the default async fiber pool; any blocking POSIX call there pins a thread for its duration, degrading all other concurrent fibers sharing that pool. A `Files.walk` on a large workspace tree is particularly severe. `ShellSkill` correctly uses `zio-process` (non-blocking), making the inconsistency within Phase 12 itself apparent.

Fix: replace every `ZIO.attempt { Files.* }` with `ZIO.attemptBlocking { Files.* }` in `WorkspaceSkill.scala`. No other change is needed.

---

### Functional Purity

**`Instant.now()` bypasses ZIO Clock in two places** (P12-005) CONFIRMED BY 4 REVIEWERS

`ContactsSkill.identityLink` (line 183) and `AgentRunnerImpl.runCheckpoint` (line 368) both call `java.time.Instant.now()` directly inside ZIO effects. The project convention is `Clock.instant` (see `AgentRunnerImpl.logSessionEvent` at line 169, `getOrCreateConversation` at line 329). The inconsistency is especially confusing within `AgentRunnerImpl` itself, which uses the correct pattern six lines above and the direct call later.

```scala
// ContactsSkill.scala:183 — replace:
val now = Instant.now()
// with:
Clock.instant.flatMap { now =>
  val ci = ChannelIdentity(…, createdAt = now)
  repo.user.upsertChannelIdentity(ci).mapError(JorlanError(_))
}
```

The same pattern applies to `AgentRunnerImpl.runCheckpoint` at line 368.

---

**`safePath` uses `throw` inside `ZIO.attempt`** (P12-017)

`WorkspaceSkill.safePath` (line 114) deliberately throws `new SecurityException(…)` inside `ZIO.attempt` so the catch converts it to a failed `IO`. The pattern works but is roundabout — the project convention is `ZIO.fail` for typed errors.

```scala
private def safePath(relative: String): IO[JorlanError, Path] =
  ZIO.attempt(workspaceRoot.resolve(relative).normalize())
    .mapError(e => JorlanError(s"workspace: invalid path: ${e.getMessage}"))
    .flatMap { resolved =>
      if (!resolved.startsWith(workspaceRoot))
        ZIO.fail(JorlanError("workspace: path is outside workspace root"))
      else ZIO.succeed(resolved)
    }
```

---

### Architecture / Layer Discipline

**`InvocationContext` missing `workspaceId`, `approvalId`, `traceId`** (P12-011)

The spec §9 defines three additional fields on `InvocationContext` that are prerequisites for the approval gate (P12-002) and workspace scoping (P12-031). Without `approvalId`, the destructive-operation guard in `workspace.delete` cannot be enforced. Without `workspaceId`, `WorkspaceSkill` cannot scope paths per-session or per-user. Without `traceId`, distributed tracing across the ReAct loop is impossible.

**`workspace.snapshot` tool absent** (P12-010)

The WorkspaceSkill tool table in spec §6.2 lists `workspace.snapshot { tag? }` as a required tool. It was not implemented. Notably, `workspace.snapshot` writes a tar.gz artifact to `ArtifactRepository` — the only Phase 12 tool that exercises the artifact storage path.

**`NotificationRouter` constructs connector tool names by string convention** (P12-013)

`NotificationRouter.notifyChannel` (line 125) builds the connector's send-message tool name as `s"${connType.toString.toLowerCase}.send_message"`. This couples the router to an undocumented naming convention that every connector must follow. Any connector that names its tool differently (e.g. `email.send`, `slack.sendMessage`) will fail silently with an "unknown tool" error. The fix is to add `def sendMessageToolName: Option[String]` to `ConnectorSkill` and read from it directly.

**`SchedulerSkill.create_job` swallows `addTrigger` failure** (P12-012)

`SchedulerSkill.scala:136` calls `jobManager.addTrigger(…).ignore`. If the trigger fails (invalid cron expression, DB error), the `SchedulerJob` record is persisted but will never fire. The model receives a success response containing the new job ID. Combined with the lack of cron expression validation before job creation, a single malformed cron string can populate the scheduler DB with orphaned, non-triggering job records.

---

### Code Quality

**`getStr` helper duplicated across 4 skill files** (P12-007) CONFIRMED BY 2 REVIEWERS

`NotifySkill.scala:67`, `ContactsSkill.scala:97`, `WorkspaceSkill.scala:99`, and `ShellSkill.scala:60` each define an identical `private def getStr(args: Json, key: String): Option[String]`. Additionally `parseChannelType` is duplicated between `NotifySkill.scala:112` and `ContactsSkill.scala:106`. `ShellSkill` defines `getStrList` and `getInt` that are unavailable to the other skills. The recommended fix is a `private[service] object SkillArgs`:

```scala
private[service] object SkillArgs {
  def str(args: Json, key: String): Option[String] = …
  def strList(args: Json, key: String): List[String] = …
  def int(args: Json, key: String): Option[Int] = …
  def parseChannelType(s: String): Option[ChannelType] =
    ChannelType.values.find(_.toString.equalsIgnoreCase(s))
}
```

**`contactFind`/`contactsFind` dead indirection** (P12-008) CONFIRMED BY 3 REVIEWERS

`ContactsSkill` has a private method `contactFind` (line 109) implementing the full logic, and a second private method `contactsFind` (line 139) that does nothing but `contactFind(args)`. The router dispatches to `contactsFind`. This is a half-finished rename with no functional difference. Delete `contactsFind`, rename `contactFind` to `contactsFind`, update the dispatch at line 90.

---

### Performance

**ContactsSkill N+1 query + 200-row in-memory name filter** (P12-006) CONFIRMED BY 3 REVIEWERS

`contactsFind` issues `repo.user.search(UserSearch(pageSize = 200))` and then `.filter(_.displayName.toLowerCase.contains(name.toLowerCase))` in the JVM. Two problems interact:

1. `UserSearch` has no `nameSubstring` field, so the name predicate never reaches SQL. On deployments with more than 200 users, matched records beyond position 200 are silently excluded.
2. For each matched user, `getChannelIdentities(u.id)` is called inside `ZIO.foreach`, producing one SELECT per user — an N+1 pattern. With 10 matches: 11 round-trips.

Fix: add `nameContains: Option[String]` to `UserSearch` and push a `LIKE '%…%'` predicate to the repository. Combine with a JOIN or `ZIO.foreachPar` to address the identity fetch N+1.

---

### Documentation

**Stale ScalaDoc and misplaced ReAct overview** (P12-014, P12-015)

`AgentRunner.scala:22` still contains the sentence "The full ReAct planning loop (multi-step tool dispatch) is deferred to Phase 12." — Phase 12 is complete and this is now factually incorrect. Remove the sentence.

Separately, the detailed numbered overview of the 6 ReAct steps is attached to the ScalaDoc of `AgentRunnerState` (a private class) rather than `AgentRunnerImpl` (the public class). In generated API docs this overview appears under the wrong heading. Move the numbered steps to `AgentRunnerImpl`'s ScalaDoc; give `AgentRunnerState` a brief: `/** Mutable per-runner Ref bundle: seeded sessions, active conversations, cached agent IDs. */`

---

### Test Coverage

**High-priority missing tests** (P12-025 – P12-028)

| Missing Test | Gap |
|---|---|
| `notifyUser` with user having only a non-Telegram identity | `orElse(identities.headOption)` fallback at `NotificationRouter.scala:96` never exercised |
| `notifyChannel` with a failing `ConnectorSkill` | `catchAll` error-swallow branch at `NotificationRouter.scala:130` never exercised |
| `processMessage` with prior conversation history in repo | `AgentRunnerImpl.ensureSeeded` → `seedHistory` call path never exercised (all test repos start empty) |
| Fresh `AgentRunnerImpl` against a repo with an existing `Conversation` | Cache-miss + DB-found path at `AgentRunnerImpl.scala:325` never exercised |

---

## Cross-Cutting Patterns

**Blocking I/O on the wrong thread pool** was independently flagged by all four technical-depth agents (Functional Scala, Performance Oracle, Pattern Recognition, SRS/SDD Conformance). The root cause is a consistent copy-paste of `ZIO.attempt` for all `java.nio.file.Files` calls; the ZIO documentation and project conventions both require `ZIO.attemptBlocking` for these. The fix is mechanical and zero-risk. P12-004.

**`Instant.now()` direct call** was flagged by four agents independently (Functional Scala, Code Simplicity, Pattern Recognition, SRS/SDD Conformance), making it the most cross-confirmed pattern in this review. The project has documented this as a recurring Phase 9, 11, and now 12 issue. A `grep -r "Instant.now()\|new Date()"` pre-commit check or ScalaFix rule would prevent recurrence. P12-005.

**Duplicated per-skill boilerplate** (`getStr`, `parseChannelType`) was noted by Code Simplicity and Pattern Recognition agents (P12-007). As the skill count grows from 8 toward projected 15+, the duplicated surface will diverge further (already has: only `ShellSkill` has `getStrList` and `getInt`). The `SkillArgs` object should be created before Phase 13 adds more skills.

**Security surface not enforced** was identified from two complementary angles — Pattern Recognition found the missing capability gate, SRS/SDD Conformance identified the missing approval gate and wrong capability on `workspace.delete` (P12-001, P12-002). Both trace to the same root: the `CapabilityEvaluator` wiring was deferred from the skill layer. Since the spec explicitly describes the gate mechanism, this is a design-versus-implementation gap rather than a design omission.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count |
|------------|-------|
| Critical   | 3     |
| Warning    | 12    |
| Suggestion | 16    |
| **Total**  | **31** |

**Issues by area:**

| Area                   | Count |
|------------------------|-------|
| Security               | 2     |
| Architecture           | 5     |
| Correctness            | 1     |
| Functional Purity      | 2     |
| Concurrency            | 1     |
| Code Quality           | 5     |
| Performance            | 3     |
| Error Handling         | 1     |
| API Design             | 1     |
| Test Coverage          | 4     |
| Documentation          | 2     |
| Observability          | 1     |
| Infrastructure         | 1     |
| **Total**              | **31** |

**Agent contribution:**

| Agent                        | Unique Findings | Cross-Confirmed |
|------------------------------|-----------------|-----------------|
| Functional Scala Reviewer    | 3               | 7               |
| Code Simplicity Reviewer     | 2               | 8               |
| Performance Oracle           | 5               | 4               |
| Pattern Recognition Specialist| 5              | 6               |
| Test Coverage Tracker        | 4               | 0               |
| SRS/SDD Conformance Reviewer | 8               | 3               |
| ScalaDoc Auditor             | 2               | 0               |

**Phase 12 scope completion:**

| Item                                                              | Status |
|-------------------------------------------------------------------|--------|
| ReAct loop (`reactLoop` with `chatStep`, max-step guard)          | ✅     |
| `SkillRegistry` (Ref-based, `liveWith`, pre-registered skills)    | ✅     |
| `ToolSpec` in `model` module (no circular dep)                    | ✅     |
| `NotificationRouter` (bypasses `SkillRegistry`)                   | ✅     |
| `NotifySkill` (`notify.user`, `notify.channel`)                   | ✅     |
| `ContactsSkill` (`contacts.find`, `identity.*`)                   | ✅     |
| `WorkspaceSkill` (`workspace.read/write/search/delete`)           | ⚠️     |
| `workspace.snapshot` tool                                         | ❌     |
| Workspace scoping per-session or per-user                         | ❌     |
| `ShellSkill` (`shell.run`, allowlist, `zio-process`)              | ✅     |
| Shell artifact capture above size threshold                       | ❌     |
| Shell event log entries with shell-specific fields                | ❌     |
| `skills: [SkillInfo!]!` GQL query                                 | ⚠️     |
| `notifyUser` GQL admin mutation                                   | ❌     |
| `toolEvents` GQL subscription                                     | ❌     |
| Capability gate in `SkillRegistry.invoke`                         | ❌     |
| `AgentSettings(maxToolSteps)` as ZIO service                      | ✅     |
| `WorkspaceSettings` + `ShellSettings` in `JorlanConfig`           | ✅     |
| 24 admin capabilities seeded in `InitService`                     | ✅     |
| 577 tests passing, 85.03% statement coverage                      | ✅     |

---

## What Was Done Well

**Clean circular-dependency resolution**: The decision to have `NotificationRouter` wire `ConnectorManager` directly (rather than through `SkillRegistry`) is the correct architectural move and is documented in the ScalaDoc. Registering the four new skills at runtime in `startServices` (rather than in `liveSkillRegistryLayer`) correctly breaks the `MessageIngress → AgentRunner → SkillRegistry → NotifySkill → NotificationRouter → ConnectorManager` cycle without requiring any interface abstraction.

**`SkillRegistry.validateRequiredFields` pure-then-lift pattern**: The entire field-validation logic is written as a pure `for`/`Either` computation and lifted into `UIO` via a single `ZIO.succeed(result)`. This is the idiomatic ZIO pattern for separating pure validation from effects, and it is well-executed.

**`AgentRunnerState` as a companion factory**: Keeping mutable `Ref` construction inside `AgentRunnerState.make` rather than in the `AgentRunnerImpl` constructor keeps the ZLayer lean and avoids the `ZLayer.fromZIO { Ref.make… Ref.make… }` anti-pattern. It also makes the per-runner state easy to mock in tests.

**Error containment in `reactLoop`**: Both tool-call errors and loop exhaustion are funnelled through `errorRef` (a `Ref[Option[String]]`) rather than propagating as ZIO failures. This means `finaliseResponse` always runs and the session always receives a finished sentinel — even on model failure or tool error.

**`WorkspaceSkill.safePath` security posture**: The path normalization + prefix-check pattern is correct, explicit, does not reveal the workspace root in error messages, and is well-documented. This is the right template for any future filesystem-adjacent skill.
