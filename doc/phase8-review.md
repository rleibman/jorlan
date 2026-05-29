/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# Phase 8 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala, Code Simplicity, Performance Oracle, Architecture & Patterns,
Test Coverage, SRS/SDD Conformance, Scala-Doc Auditor)
**Date**: 2026-05-29
**Branch**: `phase-8/agent-session-runtime`
**Scope**: Phase 8 — Agent Session Runtime & Model Gateway (`AgentRunner`, `AgentSessionManager`, `SessionHub`,
`OllamaModelGateway`, `ModelGateway` trait, `HumanApprovalNotifier`, `JorlanAPI` Phase 8 resolvers, shell
`CommandHandler` session commands, `SubscriptionClient`)

---

## Executive Summary

Phase 8 delivers the core agent execution loop — session lifecycle management, streaming model responses via
LangChain4j/Ollama, and real-time GraphQL subscriptions — and the architecture is structurally sound for its scope.
The ZStream-based streaming design, the SessionHub broadcast pattern, the capability-check integration, and the
LangChain4j abstraction layer over Ollama are all reasonable choices that align with the SDD.

However, **six defects are critical-severity**: the `listSessions` GraphQL resolver always returns an empty list; a
phantom Hub entry under the sentinel session ID 0 is created and never removed on every `createSession` call; the
`OllamaModelGateway` sessions map grows without bound (one `OllamaStreamingChatModel` with its own HTTP connection
pool per session, never pruned); a non-atomic `getOrCreate` pattern in both `SessionHub` and `OllamaModelGateway`
allows concurrent fibers to orphan resources and silently reset chat memory; and the `/new [model]` shell command
silently discards its model argument. These six issues affect production correctness and must be resolved before any
load test or multi-user deployment.

The warning tier reveals **twelve additional issues** that compromise auditability (missing event log entries for
model failures, session suspension/termination, and `AgentResponseCompleted` on error), correctness (inverted
`grantPermission` predicate, `AgentSessionManager` layer violation, `ensureDefaultAgent` full-table scan on hot
path), and observability (`forkDaemon` silently drops errors, finished sentinel indistinguishable from clean vs
failed completion). The test coverage gap is significant: the four most critical runtime paths — `createSession`,
`submitMessage`, `agentResponseStream`, and `AgentRunner` failure handling — have no automated coverage.

**Overall health: Issues Present — Advance to Phase 9 with critical items resolved.** The Phase 8 feature set is
functionally complete for the happy path. The six critical bugs must be fixed before multi-user or production
scenarios are attempted; the warning-tier audit and event-log gaps should be addressed in the Phase 9 iteration
alongside the missing integration test for end-to-end streaming.

Scaladoc quality improved significantly in Phase 8: all seven agents confirmed no documentation drift in any Phase 8
file, and targeted improvements were applied directly to `ModelGateway.scala`, `SessionHub.scala`,
`AgentRunner.scala`, `AgentSessionManager.scala`, `HumanApprovalNotifier.scala`, `ShellState.scala`,
`SubscriptionClient.scala`, and the domain files `agent.scala` and `event.scala`.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area                   | Issue                                                                                                                                                                                                     | File : Line                                                                                                                      | Recommended Action                                                                                                                                                                                               |
|--------|------------|------------|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | P8-001     | Critical   | Correctness            | `listSessions` resolver always returns empty list — calls `getSession(AgentSessionId.empty)`; `AgentSessionId.empty` is sentinel 0 which always resolves to None. PaginationInput silently ignored. (confirmed by 4 reviewers) | `JorlanAPI.scala:~231`                                                                                                           | Add `listSessions(userId, page, pageSize)` to `AgentSessionManager` trait and impl (delegate to `agentRepo.searchSessions`). Add `userId: Option[UserId]` filter to `AgentSessionSearch`.                       |
| [x]    | P8-002     | Critical   | Resource Management    | `createSession` leaks phantom Hub entry under sentinel ID 0 — `sessionHub.getOrCreate(AgentSessionId.empty).unit` fires before session is persisted; entry keyed to ID 0 is never removed. (confirmed by 4 reviewers) | `AgentSessionManager.scala:95`                                                                                                   | Delete line 95. The call on line 107 (after `saved.id` is known) is the correct and sufficient one.                                                                                                              |
| [x]    | P8-003     | Critical   | Resource Management    | `OllamaModelGateway` sessions map never pruned — one `OllamaStreamingChatModel` (with its own HTTP connection pool) and one `InMemoryChatMemoryStore` (up to 1,000 messages) allocated per session, never released. `terminateSession` removes from `SessionHub` but not this map. (confirmed by 2 reviewers) | `OllamaModelGateway.scala:28, 64`                                                                                                | Add `invalidateSession(id: AgentSessionId): UIO[Unit]` to `ModelGateway` trait; implement as `sessions.update(_ - id)`. Call from `AgentSessionManagerImpl.terminateSession`.                                   |
| [x]    | P8-004     | Critical   | Performance            | One `OllamaStreamingChatModel` (and HTTP pool) instantiated per session — the model is stateless; all per-session state lives in `InMemoryChatMemoryStore`. N sessions create N HTTP connection pools.    | `OllamaModelGateway.scala:44-65`                                                                                                 | Build `OllamaStreamingChatModel` once in `OllamaModelGateway.live`; share across all sessions. Only `StreamAssistant` (carrying `ChatMemory`) is per-session.                                                   |
| [x]    | P8-005     | Critical   | Concurrency            | Non-atomic `getOrCreate` in `SessionHub` and `OllamaModelGateway` — `Ref.get / match / Ref.update` is not atomic; two concurrent fibers can both see `None`, both allocate, and one is orphaned. In `SessionHub`: subscriber latches to orphaned hub and receives no messages. In `OllamaModelGateway`: chat memory is silently reset. (confirmed by 3 reviewers) | `SessionHub.scala:24-29`, `OllamaModelGateway.scala:33-64`                                                                      | Replace with `Ref.modify` (allocate `Hub` eagerly; discard on race-winner path). Same pattern in `OllamaModelGateway`.                                                                                          |
| [x]    | P8-006     | Critical   | Correctness            | `/new [model]` shell command silently discards model argument — `ShellCommand.NewSession` is zero-arity; parser matches `case "new" :: _` producing `NewSession` with no model. `/new llama3` and `/new` are indistinguishable. | `ShellCommand.scala:52`, `CommandHandler.scala:40`                                                                               | Change to `case NewSession(model: Option[String])`. Update parser: `case "new" :: model :: _ => NewSession(Some(model))`, `case "new" :: Nil => NewSession(None)`. Update handler dispatch accordingly.          |
| [x]    | P8-007     | Warning    | Observability          | `ModelCallFailed` event never written — on Ollama error, log shows `ModelCallStarted` then `ModelCallCompleted`; misleading audit trail. (confirmed by 2 reviewers)                                       | `OllamaModelGateway.scala`                                                                                                       | Add `.tapError(e => eventLog.log(...ModelCallFailed...).ignore)` inside `streamedResponse`; guard `ModelCallCompleted` to only fire on success.                                                                  |
| [x]    | P8-008     | Warning    | Observability          | `AgentResponseCompleted` not written on model failure — for-comprehension short-circuits; `AgentResponseCompleted` write is skipped. Hub receives finished sentinel (`.ensuring`) but event log has no record. (confirmed by 2 reviewers) | `AgentRunner.scala`                                                                                                              | Move `AgentResponseCompleted` write into an `.ensuring` block so it fires on both success and failure.                                                                                                           |
| [x]    | P8-009     | Warning    | Observability          | `suspendSession` and `terminateSession` write no event log entries — only `createSession` is audited; all subsequent session state transitions leave no audit trail.                                       | `AgentSessionManager.scala`                                                                                                      | Write events (reuse `AgentCompleted`/`AgentFailed` or add `SessionSuspended`/`SessionTerminated`) in `updateStatus`.                                                                                             |
| [x]    | P8-010     | Warning    | Correctness            | `grantPermission` validation predicate is inverted — `ZIO.unless(input.userId.isDefined != input.roleId.isDefined)` fails when both provided (correct) but also passes when neither provided (wrong).     | `JorlanAPI.scala:~281`                                                                                                           | Change to `ZIO.when(input.userId.isDefined == input.roleId.isDefined)(ZIO.fail(...))`.                                                                                                                           |
| [ ]    | P8-011     | Warning    | Architecture           | `AgentSessionManager` bypasses `AgentService`, calls `AgentZIORepository` directly — scaladoc says "Delegates persistence to AgentService" but implementation imports from `jorlan.db.repository`; layer violation misses AgentService event logging. (confirmed by 2 reviewers) | `AgentSessionManager.scala`                                                                                                      | Defer to Phase 9: affects business logic integration across multiple layers.                                                                                                                                         |
| [x]    | P8-012     | Warning    | Performance            | `ensureDefaultAgent` issues full table scan on every `createSession` — calls `agentRepo.search(AgentSearch())` (page 1 = 20 agents), then in-memory `.find`; default agent on page 2+ not found → duplicate creation. (confirmed by 2 reviewers) | `AgentSessionManager.scala:63-86`                                                                                                | Seeded default agent via Flyway migration V016; eliminated hot-path lookup.                                                                                                     |
| [x]    | P8-013     | Warning    | Architecture           | `SessionHub` exposed directly in `JorlanApiEnv` — infrastructure type in GraphQL layer lets any resolver bypass `AgentRunner`'s event logging.                                                            | `JorlanAPI.scala:33`                                                                                                             | Added `subscribeToSession(id)` to `AgentRunner`; removed `SessionHub` from `JorlanApiEnv`.                                                                                                                          |
| [x]    | P8-014     | Warning    | Architecture           | `AgentRunner` and `AgentSessionManager` traits defined in `server/` instead of `model/` — project convention places service traits in `model/`; `ModelGateway` correctly follows this; these traits have no server-only dependencies. | `server/src/main/scala/jorlan/service/AgentRunner.scala`, `server/src/main/scala/jorlan/service/AgentSessionManager.scala`       | Move traits to `model/` module; keep impls in `server/`.                                                                                                                                                         |
| [x]    | P8-015     | Warning    | Architecture           | `HumanApprovalNotifier` is a plain class, not a ZIO service — no trait, no companion accessors; untestable in isolation; cannot swap impl for Phase 11 without changing call sites.                       | `server/src/main/scala/jorlan/service/HumanApprovalNotifier.scala`                                                               | Extract `trait HumanApprovalNotifier` with `notifyApprovalRequired`; move to `model/`; rename current class to `HumanApprovalNotifierImpl`.                                                                      |
| [x]    | P8-016     | Warning    | Error Handling         | `submitMessage` uses `forkDaemon` with no error surface — errors from `processMessage` are silently dropped; failed agent runs have zero visibility. (confirmed by 2 reviewers)                           | `JorlanAPI.scala:~322`                                                                                                           | Add `.tapError(e => ZIO.logError(...))` before `forkDaemon`. Emit error sentinel via `SessionHub` for subscribers.                                                                                               |
| [x]    | P8-017     | Warning    | Error Handling         | `.ensuring` in `AgentRunner` publishes finished sentinel on both success and failure — subscribers cannot distinguish clean completion from model failure; both receive `finished=true` with empty content. | `AgentRunner.scala:~74`                                                                                                          | Use `.onExit` to distinguish success vs. failure; surface error description in sentinel or add `isError: Boolean` to `ResponseChunk`.                                                                             |
| [x]    | P8-018     | Warning    | Code Quality           | `EventLog(...)` constructor duplicated 6 times across 4 files — `id = EventLogId.empty`, `agentId = None`, `payloadJson = None` always constant; copy-paste risk when `EventLog` schema evolves.         | `AgentRunner.scala`, `OllamaModelGateway.scala`, `AgentSessionManager.scala`, `HumanApprovalNotifier.scala`                      | Add `EventLog.entry(eventType, actorId, sessionId, resource, now)` helper to the companion object.                                                                                                               |
| [x]    | P8-019     | Warning    | Resource Management    | `SubscriptionClient` creates new `HttpClientZioBackend` per subscription — same P7-001 pattern; new thread pool and SSL context per subscription. (confirmed by 2 reviewers)                              | `shell/src/main/scala/jorlan/shell/client/SubscriptionClient.scala`                                                              | Provided single `HttpClientZioBackend` via `ZLayer.scoped`; shared across all subscriptions.                                                                                         |
| [x]    | P8-020     | Warning    | Code Quality           | `handleNewSession` uses manual JSON traversal instead of a decoder — improved with helper function and explicit error — five-step `Option`-chained traversal; silent `None` on any mismatch gives generic "Could not parse" with no diagnostic. (confirmed by 2 reviewers) | `CommandHandler.scala:155-161`                                                                                                   | Define `case class CreateSessionResult(id: Long) derives JsonDecoder` and use `.fromJson`.                                                                                                                        |
| [x]    | P8-021     | Warning    | Code Quality           | Magic hyperparameters duplicated between `OllamaModelGateway` and `ai/util.scala` — `temperature(1.1)`, `topK(40)`, `topP(0.9)`, `maxMessages(1000)` hardcoded and duplicated.                           | `OllamaModelGateway.scala:43, 52-54`                                                                                             | Exposed as fields on `LangChainConfig` with sensible defaults.                                                                                                                            |
| [x]    | P8-022     | Warning    | Architecture           | `ensureDefaultAgent` is a SRP violation — session manager provisioning agent records; should be in `AgentService` or a startup seeding step.                                                              | `AgentSessionManager.scala:63-86`                                                                                                | Seeded default agent via Flyway migration V016; eliminated `ensureDefaultAgent` call from hot path.                                                                                                    |
| [x]    | P8-023     | Warning    | Resource Management    | `Queue.unbounded` in `SubscriptionClient` — unbounded in-memory buffer if TUI rendering is slow during fast token streams. (confirmed by 2 reviewers)                                                    | `SubscriptionClient.scala:77`                                                                                                    | Replace with `Queue.bounded(1024)`.                                                                                                                                                                              |
| [x]    | P8-024     | Suggestion | Code Quality           | `AgentRunnerImpl`/`AgentSessionManagerImpl` companion naming inconsistency — resolved by P8-014: traits now in model/ with their own companions; impls use XxxImpl naming consistently — `live` defined in `object AgentRunnerImpl` instead of `object AgentRunner`; inconsistent with all other services.            | `AgentRunner.scala`, `AgentSessionManager.scala`                                                                                 | Move `val live` to the trait's companion object.                                                                                                                                                                 |
| [x]    | P8-025     | Suggestion | Code Quality           | `AgentSessionSearch` missing `userId` filter field — cannot filter sessions by user; all queries require full scans or application-side filtering.                                                        | `model/src/main/scala/jorlan/repository.scala`                                                                                   | Add `userId: Option[UserId] = None` to `AgentSessionSearch`; apply in `QuillRepositories.searchSessions`.                                                                                                        |
| [x]    | P8-026     | Suggestion | Code Quality           | Verbose `ZLayer.fromFunction` lambda in `AgentSessionManager.scala:150-157`.                                                                                                                              | `AgentSessionManager.scala:150-157`                                                                                              | Use constructor-reference style: `ZLayer.fromFunction(new AgentSessionManagerImpl(_, _, _))`.                                                                                                                    |
| [x]    | P8-027     | Suggestion | Code Quality           | Dead wildcard arm in `setTrace` match.                                                                                                                                                                    | `CommandHandler.scala:191-197`                                                                                                   | Annotate as `// unreachable` or remove.                                                                                                                                                                          |
| [x]    | P8-028     | Suggestion | Code Quality           | `import scala.math.BigDecimal.javaBigDecimal2bigDecimal` is cryptic — removed (P8-020 refactor eliminated the need).                                                                                                                                      | `CommandHandler.scala:17`                                                                                                        | Add explanatory comment, or eliminate via decoder approach (see P8-020).                                                                                                                                         |
| [x]    | P8-029     | Suggestion | Code Quality           | `SubscriptionClient` wrapper class names `ResponseData`/`NextPayload` are unclear in context.                                                                                                             | `SubscriptionClient.scala`                                                                                                       | Rename `ResponseData` to `AgentResponseData`.                                                                                                                                                                    |
| [x]    | P8-030     | Suggestion | Code Quality           | `OllamaModelGateway.availableModels` hardcodes `contextWindow = 4096` — actual value depends on the loaded model.                                                                                        | `OllamaModelGateway.scala:~128`                                                                                                  | Add comment: `// placeholder — actual context window depends on the loaded model`.                                                                                                                                |
| [x]    | P8-031     | Suggestion | Error Handling         | `handleMessage` subscription errors silently discarded — `.mapError(err => ()).ignore` drops subscription error; user sees nothing.                                                                       | `CommandHandler.scala:83`                                                                                                        | Surface the error string in the TUI as `MessageKind.Error`.                                                                                                                                                      |
| [x]    | P8-032     | Suggestion | Test Coverage          | `AgentRunner` with failing `ModelGateway` not tested — finished sentinel must still be published to Hub on failure.                                                                                       | `AgentRunnerSpec.scala` (missing)                                                                                                | Add test: stub `ModelGateway` to fail; assert `ResponseChunk(finished = true)` published; assert `AgentResponseCompleted` written to event log.                                                                  |
| [ ]    | P8-033     | Suggestion | Test Coverage          | `AgentSessionManager.updateStatus` with unknown session ID not tested — should return `JorlanError`.                                                                                                      | `AgentSessionManagerSpec.scala`                                                                                                  | Add test: call `updateStatus` with non-existent `AgentSessionId`; assert typed error returned.                                                                                                                   |
| [ ]    | P8-034     | Suggestion | Test Coverage          | GraphQL `createSession` and `submitMessage` mutations absent from `JorlanAPISpec`.                                                                                                                        | `JorlanAPISpec.scala`                                                                                                            | Add unit tests for both mutations using `FakeAgentSessionManager` and `FakeAgentRunner`; cover capability-check failure path.                                                                                    |
| [ ]    | P8-035     | Suggestion | Test Coverage          | GraphQL `agentResponseStream` subscription end-to-end test missing.                                                                                                                                       | `JorlanAPISpec.scala`                                                                                                            | Add Caliban subscription test: publish chunks via `FakeAgentRunner`; assert chunks arrive in order; assert `finished` sentinel received.                                                                         |
| [ ]    | P8-036     | Suggestion | Test Coverage          | `HumanApprovalNotifier` has 0% coverage.                                                                                                                                                                  | `HumanApprovalNotifier.scala`                                                                                                    | Add unit tests after trait extraction (P8-015); test `notifyApprovalRequired` with both approval and rejection paths.                                                                                            |
| [ ]    | P8-037     | Suggestion | Test Coverage          | `SessionHub.publish` to non-existent session (no-op path) not tested; `subscribe` method never called directly in tests.                                                                                  | `SessionHubSpec.scala`                                                                                                           | Add tests for no-op publish and for `subscribe` receiving chunks in insertion order.                                                                                                                             |
| [ ]    | P8-038     | Suggestion | Test Coverage          | `AgentSessionManager.terminateSession` does not verify hub removal.                                                                                                                                       | `AgentSessionManagerSpec.scala`                                                                                                  | Add test: create session, terminate it, assert `SessionHub` entry absent.                                                                                                                                        |
| [ ]    | P8-039     | Suggestion | Test Coverage          | `CommandHandler.handleMessage` with active session (happy path and GQL failure) untested; `handleNewSession` all three result branches untested.                                                          | `CommandHandlerSpec.scala`                                                                                                       | Add tests using `FakeSubscriptionClient`; cover session-not-found, successful stream, and mid-stream error branches.                                                                                             |
| [ ]    | P8-040     | Suggestion | Test Coverage          | `FakeModelGateway` `chunkDelay` branch not exercised in any test.                                                                                                                                         | `FakeModelGateway.scala`                                                                                                         | Add one test that configures `chunkDelay` and asserts chunks are interleaved with a `TestClock` advance.                                                                                                         |
| [ ]    | P8-041     | Suggestion | Test Coverage          | `ShellState` has 0% coverage — trivial pure functions.                                                                                                                                                    | `ShellState.scala`                                                                                                               | Add 3 pure unit tests covering each method; no ZIO runtime required.                                                                                                                                             |
| [ ]    | P8-042     | Suggestion | Test Coverage          | Integration test missing: full round-trip using `FakeModelGateway`, asserting each chunk arrives in order. Explicitly marked unchecked in phase roadmap.                                                  | Integration test module                                                                                                          | Implement `AgentRoundTripSpec` in the `integration` module using Testcontainers MariaDB and `FakeModelGateway`; assert chunk order and event log entries.                                                        |

---

## Grouped Sections

### Correctness / Concurrency

**C1 — `listSessions` always returns empty list** (P8-001) [CONFIRMED BY 4 REVIEWERS]

`AgentSessionManagerImpl.listSessions` calls `getSession(AgentSessionId.empty)`. `AgentSessionId.empty` evaluates to
the sentinel value 0, which has no persisted session record; the repository returns `None`, which is mapped to an
empty list. The `PaginationInput` argument is never forwarded to the repository. This means the entire
`listSessions` GraphQL query is silently broken: it will always return `[]` regardless of how many sessions exist.

Fix: add `listSessions(userId: Option[UserId], page: Int, pageSize: Int): Task[List[AgentSession]]` to the
`AgentSessionManager` trait and implement it as a direct delegation to `agentRepo.searchSessions`. Add `userId:
Option[UserId] = None` to `AgentSessionSearch` and apply it as a query filter.

**C2 — Phantom Hub entry under sentinel ID 0** (P8-002) [CONFIRMED BY 4 REVIEWERS]

`AgentSessionManagerImpl.createSession` calls `sessionHub.getOrCreate(AgentSessionId.empty).unit` at line 95 before
the session record is persisted. The session is then saved and assigned a real auto-increment ID (e.g., 42). The
`getOrCreate` call at line 107 uses `saved.id` correctly. The call at line 95 creates a `Hub` entry permanently
keyed to ID 0 that is never referenced again and never removed. Over the lifetime of the server, every
`createSession` call adds another orphaned `Hub` entry to the internal map.

Fix: delete line 95. The call at line 107 is sufficient.

**C3 — Non-atomic `getOrCreate` pattern** (P8-005) [CONFIRMED BY 3 REVIEWERS]

Both `SessionHub` (lines 24-29) and `OllamaModelGateway` (lines 33-64) implement `getOrCreate` as a
read-then-update: `Ref.get` to check existence, then `Ref.update` to insert if absent. This is not atomic under
ZIO's cooperative concurrency model. Two fibers can both execute `Ref.get`, both observe `None`, both allocate a
new `Hub` (or `StreamAssistant`), and then race on `Ref.update`. The loser's allocated resource is orphaned. For
`SessionHub`, the loser's subscribers are connected to a hub that will never receive publications. For
`OllamaModelGateway`, the loser's `InMemoryChatMemoryStore` (carrying all prior context) is discarded; the session
effectively loses its memory silently.

Fix: use `Ref.modify` in both locations. For `SessionHub`:

```scala
def getOrCreate(id: AgentSessionId): UIO[Hub[ResponseChunk]] =
  Hub.unbounded[ResponseChunk].flatMap { newHub =>
    hubs.modify { map =>
      map.get(id) match {
        case Some(existing) => (existing, map)
        case None           => (newHub, map + (id -> newHub))
      }
    }
  }
```

The newly allocated `Hub` is discarded if a concurrent fiber already inserted one; the winner's `Hub` is returned
to both callers.

**C4 — `/new [model]` silently drops model argument** (P8-006)

`ShellCommand.NewSession` is defined as a zero-arity case object (or case class with no fields). The command parser
matches `case "new" :: _` and produces `NewSession` regardless of what follows. There is no way for the user or the
handler to recover the intended model name. The `CommandHandler` cannot forward it to `AgentSessionManager.createSession`.

Fix: change `NewSession` to `case class NewSession(model: Option[String])`. Update the parser:

```scala
case "new" :: model :: _ => Right(NewSession(Some(model)))
case "new" :: Nil        => Right(NewSession(None))
```

Update the handler to pass `model` to the session creation request.

---

### Resource Management

**R1 — OllamaModelGateway sessions map never pruned** (P8-003) [CONFIRMED BY 2 REVIEWERS]

The `Ref[Map[AgentSessionId, StreamAssistant]]` in `OllamaModelGateway` grows without bound. Each value holds an
`InMemoryChatMemoryStore` capped at 1,000 messages (potentially several MB per session) and an
`OllamaStreamingChatModel` with its own `OkHttpClient` connection pool. Sessions that are terminated via
`AgentSessionManager.terminateSession` have their `SessionHub` entry removed but are never removed from this map.
In a long-running server with moderate session churn, this constitutes a memory and socket leak proportional to total
historical session count.

Fix: add `invalidateSession(id: AgentSessionId): UIO[Unit]` to the `ModelGateway` trait and implement it as
`sessions.update(_ - id)`. Call it from `AgentSessionManagerImpl.terminateSession` alongside the existing
`sessionHub.remove(id)` call.

**R2 — One HTTP connection pool per session in OllamaModelGateway** (P8-004)

`OllamaModelGateway` constructs a new `OllamaStreamingChatModel` instance inside `sessions.update(... case None =>
buildModel(...))`. The model object is stateless — all per-session conversational state is stored in the
`InMemoryChatMemoryStore` that is wrapped in `StreamAssistant`. Building a new model instance per session creates N
parallel `OkHttpClient` instances (one per `OllamaStreamingChatModel`), each with its own connection pool, thread
pool, and DNS cache. For 100 concurrent sessions this means 100 HTTP clients pointed at the same Ollama endpoint.

Fix: extract model construction to `OllamaModelGateway.live`. Pass the single shared model instance into the
`OllamaModelGatewayImpl` constructor. Only `StreamAssistant(memory = new InMemoryChatMemoryStore(...))` needs to be
allocated per session.

---

### Observability / Audit Trail

**O1 — `ModelCallFailed` event never written** (P8-007) [CONFIRMED BY 2 REVIEWERS]

`EventType.ModelCallFailed` exists in the domain and in the generated GraphQL client schema. On any Ollama error, the
event log records `ModelCallStarted` followed by `ModelCallCompleted`. An operator reviewing the event log after a
failed model call cannot distinguish success from failure by log inspection alone.

Fix: add `.tapError(e => eventLog.log(EventLog.entry(ModelCallFailed, ...)).ignore)` inside `streamedResponse`.
Guard the existing `ModelCallCompleted` write so it only fires on success (e.g., wrap in `.tapBoth`).

**O2 — `AgentResponseCompleted` skipped on model failure** (P8-008) [CONFIRMED BY 2 REVIEWERS]

The for-comprehension in `AgentRunner` short-circuits when `ModelGateway.streamedResponse` fails. The
`AgentResponseCompleted` write that follows is never reached. The `SessionHub` publish of the finished sentinel is
guarded by `.ensuring` and does execute — so clients receive a `finished=true` chunk — but the event log entry is
absent. The audit trail has a `AgentResponseStarted` with no corresponding `AgentResponseCompleted`.

Fix: move the `AgentResponseCompleted` log write into an `.ensuring` block so it fires unconditionally:

```scala
agentRunner
  .processMessage(...)
  .ensuring(eventLog.log(EventLog.entry(AgentResponseCompleted, ...)).ignore)
```

**O3 — Session state transitions not audited** (P8-009)

`AgentSessionManager.suspendSession` and `terminateSession` update the DB record but write no event log entries.
Only `createSession` produces an audit record. An operator cannot reconstruct the session lifecycle from the event
log alone.

Fix: call `eventLog.log(...)` within `updateStatus` for each terminal or suspended state transition. Add
`SessionSuspended` and `SessionTerminated` to `EventType` if they do not already exist, or reuse `AgentCompleted` /
`AgentFailed` with a note in `payloadJson`.

---

### Correctness

**X1 — `grantPermission` predicate is inverted** (P8-010)

```scala
ZIO.unless(input.userId.isDefined != input.roleId.isDefined)
```

This condition is equivalent to `ZIO.when(input.userId.isDefined == input.roleId.isDefined)`. The intent is to
reject calls where both or neither of `userId` / `roleId` are provided (i.e., exactly one must be set). The current
code rejects the case where exactly one is set — the valid case — and passes through the invalid case where neither
is set.

Fix:

```scala
ZIO.when(input.userId.isDefined == input.roleId.isDefined)(
  ZIO.fail(JorlanError("Exactly one of userId or roleId must be provided"))
)
```

---

### Architecture / Layer Discipline

**A1 — `AgentSessionManager` bypasses `AgentService`** (P8-011) [CONFIRMED BY 2 REVIEWERS]

The scaladoc on `AgentSessionManagerImpl` states that it "Delegates persistence to AgentService." The
implementation imports `jorlan.db.repository.AgentZIORepository` and calls it directly. This skips any business
logic and event logging that `AgentService` applies. It also violates the project's architectural rule that the
domain layer must not depend on DB specifics.

Fix: replace the `AgentZIORepository` constructor parameter with `AgentService`. Update all call sites to use
`AgentService` methods.

**A2 — `AgentRunner` and `AgentSessionManager` traits in `server/` instead of `model/`** (P8-014)

The project convention (confirmed by `ModelGateway`) is that service traits belong in the `model` module. These
traits have no server-specific imports. Keeping them in `server/` means the `model` module cannot reference them,
and any future module that needs to depend on these abstractions must take a dependency on `server/`.

Fix: move `trait AgentRunner` and `trait AgentSessionManager` to
`model/src/main/scala/jorlan/service/`. Keep the `Impl` classes in `server/`.

**A3 — `HumanApprovalNotifier` is a plain class with no service trait** (P8-015)

No trait exists for `HumanApprovalNotifier`. There are no companion-object ZIO accessors. This makes it impossible
to inject a test double without modifying call sites, and impossible to swap the implementation (e.g., for a Phase
11 webhook-based notifier) without rewriting callers.

Fix: extract `trait HumanApprovalNotifier { def notifyApprovalRequired(...): Task[Unit] }`. Move to `model/`.
Rename the current class to `HumanApprovalNotifierImpl`. Add a `live` ZLayer to the companion object.

**A4 — `ensureDefaultAgent` is a SRP violation on the hot path** (P8-022)

`AgentSessionManagerImpl.createSession` calls `ensureDefaultAgent`, which queries the agent table and potentially
inserts a default agent record, on every session creation. The session manager should not be responsible for
provisioning agent records. This logic executes on the hot path for every user who creates a session.

Fix: move agent seeding to a Flyway data migration (`V__seed_default_agent.sql`) or a ZIO startup hook that runs
once at server boot. Remove `ensureDefaultAgent` from `createSession`.

---

### Error Handling

**E1 — `submitMessage` drops errors silently** (P8-016) [CONFIRMED BY 2 REVIEWERS]

`AgentRunner.processMessage` is invoked via `.forkDaemon`. No error handler is attached before the fork. Any
failure — model unavailable, capability check failure, DB error — is silently discarded. From the caller's
perspective, `submitMessage` always succeeds (returns the session ID). The user receives no indication that their
message was not processed.

Fix: attach `.tapError(e => ZIO.logError(s"processMessage failed: ${e.getMessage}"))` before `forkDaemon`. Also
publish an error sentinel chunk to `SessionHub` so that active subscribers see a meaningful error message rather
than a hanging stream:

```scala
agentRunner.processMessage(...)
  .tapError(e =>
    sessionHub.publish(sessionId, ResponseChunk(content = e.getMessage, finished = true, isError = true))
      *> ZIO.logError(s"Agent run failed for session $sessionId: $e")
  )
  .forkDaemon
```

**E2 — Finished sentinel indistinguishable from error** (P8-017)

The `.ensuring` block in `AgentRunner` publishes `ResponseChunk(content = "", finished = true)` on both clean
completion and model failure. Subscribers receive the same sentinel regardless of whether the run succeeded or
failed. Combined with P8-016 (errors dropped silently), a client that receives `finished = true` with empty content
cannot know whether to display "Done" or "Error".

Fix: use `.onExit` to distinguish `Exit.Success` from `Exit.Failure`; pass `isError: Boolean` in the sentinel
chunk (requires adding the field to `ResponseChunk`).

---

### Code Quality

**Q1 — `EventLog(...)` constructor duplicated 6 times** (P8-018)

The `EventLog` case class instantiation is repeated verbatim across `AgentRunner.scala`,
`OllamaModelGateway.scala`, `AgentSessionManager.scala`, and `HumanApprovalNotifier.scala`, always with `id =
EventLogId.empty`, `agentId = None`, and `payloadJson = None`. Any schema change to `EventLog` requires editing all
six call sites simultaneously.

Fix: add a smart constructor to the companion object:

```scala
object EventLog {
  def entry(
    eventType: EventType,
    actorId:   UserId,
    sessionId: Option[AgentSessionId],
    resource:  Option[String],
    now:       Instant,
  ): EventLog =
    EventLog(
      id          = EventLogId.empty,
      eventType   = eventType,
      actorId     = actorId,
      sessionId   = sessionId,
      resource    = resource,
      agentId     = None,
      payloadJson = None,
      createdAt   = now,
    )
}
```

**Q2 — Manual JSON traversal in `handleNewSession`** (P8-020) [CONFIRMED BY 2 REVIEWERS]

The session ID is extracted from the GraphQL response via a five-step `Option`-chained traversal of raw
`circe.Json` (or equivalent). Any change to the response shape — an added wrapper, a renamed field — silently
produces `None` and falls through to the generic "Could not parse session ID" error with no diagnostic information.

Fix: define a typed decoder:

```scala
case class CreateSessionPayload(createSession: CreateSessionResult) derives JsonDecoder
case class CreateSessionResult(id: Long) derives JsonDecoder
```

Decode with `.fromJson[CreateSessionPayload]` and surface the `Left` message in the TUI.

---

### Test Coverage

**T1 — Core runtime paths have no test coverage** [P8-032, P8-033, P8-034, P8-035]

The four highest-value test gaps in Phase 8 are:

1. `AgentRunner` with failing `ModelGateway`: stub `ModelGateway` to return `ZIO.fail(...)`; assert that the
   `finished = true` sentinel is still published to `SessionHub`; assert that `AgentResponseCompleted` is written to
   the event log (after P8-008 fix).

2. `AgentSessionManager.updateStatus` with unknown ID: assert `JorlanError` is returned rather than a silent
   no-op or DB exception.

3. GraphQL `createSession` and `submitMessage` mutations: add to `JorlanAPISpec` using `FakeAgentSessionManager`
   and `FakeAgentRunner`; cover the capability-check failure path for both.

4. GraphQL `agentResponseStream` subscription: add a Caliban subscription test using the `>+>` layer pattern
   (established in Phase 6/7); publish chunks via `FakeAgentRunner`; assert all chunks arrive in insertion order
   and that the `finished = true` sentinel terminates the stream.

**T2 — Supporting infrastructure gaps** [P8-036 through P8-041]

- `HumanApprovalNotifier` (0% coverage): test after trait extraction (P8-015).
- `SessionHub.publish` no-op path and `subscribe` method: add dedicated `SessionHubSpec` tests.
- `AgentSessionManager.terminateSession`: verify `SessionHub` entry is removed post-termination.
- `CommandHandler.handleMessage` / `handleNewSession`: add tests using a `FakeSubscriptionClient`.
- `FakeModelGateway.chunkDelay` branch: one `TestClock`-based test.
- `ShellState` (0% coverage): three trivial pure unit tests.

**T3 — Missing integration test** (P8-042)

The phase roadmap explicitly marks this item unchecked:

> `[ ] Integration test: full round-trip using FakeModelGateway, asserting each chunk arrives in order`

This test does not yet exist. It should be implemented in the `integration` module as
`AgentRoundTripSpec`, using Testcontainers MariaDB, a real `AgentSessionManager`, and `FakeModelGateway`.
Assertions: session created, message submitted, all chunks arrive via `SessionHub` subscription in insertion order,
`AgentResponseCompleted` event log entry written.

---

## Cross-Cutting Patterns

**Resource lifecycle mismanagement** is the defining cross-cutting pattern of Phase 8 and was independently
identified by four separate agents. The `getOrCreate` atomicity issue (P8-005) in `SessionHub` and
`OllamaModelGateway`, the unpruned sessions map (P8-003), the per-session HTTP pool (P8-004), and the phantom Hub
entry (P8-002) are all manifestations of the same root cause: resource allocation without a matching lifecycle
management plan. The Phase 7 fix for `HttpClientZioBackend` per-request creation (P7-001) established the right
pattern (`ZLayer.scoped`); Phase 8 requires applying that same discipline to `SessionHub` entries and
`OllamaStreamingChatModel` instances.

**Audit trail incompleteness** was flagged by three agents from different angles. The SRS/SDD conformance agent
noted that the SRS requires a complete event log for session lifecycle and model invocations. The functional Scala
reviewer noted that `AgentResponseCompleted` is skipped on the error path. The pattern recognition agent noted that
`suspendSession` and `terminateSession` write no events at all. These three findings together mean that the event
log — described in the architecture as an "append-only audit trail" — cannot reconstruct session history for any
session that was suspended, terminated, or experienced a model error.

**Architecture / layer discipline** violations cluster around the `server/` module. `AgentSessionManager` bypasses
`AgentService` (P8-011); `AgentRunner` and `AgentSessionManager` traits live in `server/` instead of `model/`
(P8-014); `SessionHub` leaks into `JorlanApiEnv` (P8-013); `HumanApprovalNotifier` has no trait (P8-015). All four
represent the same erosion of the layered architecture that the SDD prescribes. The Phase 8 sprint should include a
dedicated layer-cleanup pass.

**Silent error disposal** recurs across the resolver layer and shell layer. `submitMessage` swallows
`processMessage` failures (P8-016), the finished sentinel is ambiguous on error (P8-017), and `handleMessage` in
the shell discards subscription errors (P8-031). Each of these was flagged independently by at least two agents.
The pattern suggests a systemic under-investment in the error-reporting path: the happy path is well-covered, but
every error branch exits silently.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count  |
|------------|--------|
| Critical   | 6      |
| Warning    | 17     |
| Suggestion | 19     |
| **Total**  | **42** |

**Issues by area:**

| Area                   | Count |
|------------------------|-------|
| Test Coverage          | 11    |
| Architecture / Design  | 7     |
| Code Quality           | 7     |
| Resource Management    | 5     |
| Observability / Audit  | 3     |
| Correctness            | 4     |
| Error Handling         | 3     |
| Performance            | 2     |
| **Total**              | **42** |

**Agent contribution:**

| Agent                        | Unique Findings | Cross-Confirmed |
|------------------------------|-----------------|-----------------|
| Functional Scala Reviewer    | 12              | 5               |
| SRS/SDD Conformance Reviewer | 10              | 4               |
| Pattern Recognition          | 11              | 5               |
| Test Coverage Tracker        | 12              | 3               |
| Performance Oracle           | 8               | 3               |
| Code Simplicity Reviewer     | 7               | 4               |
| Scala-Doc Auditor            | 0 (no drift)    | —               |

**Phase 8 scope completion:**

| Item                                                                       | Status |
|----------------------------------------------------------------------------|--------|
| `AgentSessionManager` — create, suspend, terminate, list sessions          | ⚠️     |
| `SessionHub` — broadcast streaming response chunks to subscribers          | ⚠️     |
| `ModelGateway` trait + `OllamaModelGateway` implementation                 | ⚠️     |
| `AgentRunner` — stream model response, write event log, publish to Hub     | ⚠️     |
| GraphQL `createSession`, `submitMessage`, `agentResponseStream` resolvers  | ⚠️     |
| Shell `/new`, `/message`, session subscription commands                    | ⚠️     |
| `HumanApprovalNotifier` stub                                               | ⚠️     |
| Capability-check integration in session creation and message submission    | ✅     |
| LangChain4j / Ollama abstraction via `ModelGateway` trait                  | ✅     |
| ZStream-based streaming from model to GraphQL subscription                 | ✅     |
| `FakeModelGateway` for unit testing                                        | ✅     |
| Scaladoc — all Phase 8 files fully documented, no drift detected           | ✅     |
| Integration test: full round-trip with chunk-order assertion               | ❌     |

---

## What Was Done Well

**Streaming architecture**: The choice to use `ZStream` end-to-end — from `ModelGateway.streamedResponse` through
`AgentRunner` to `SessionHub` and finally the Caliban `ZStream`-backed subscription — is the correct design for
real-time token delivery. The `.ensuring` sentinel publication guarantees subscribers always receive a termination
signal, preventing hung clients.

**`ModelGateway` trait abstraction**: Placing the `ModelGateway` trait in `model/` with the `OllamaModelGateway`
implementation in `server/` correctly follows the project's layering rules. The `FakeModelGateway` test double is a
direct benefit of this separation and is already used in `AgentRunnerSpec`.

**Capability-check integration**: Both `createSession` and `submitMessage` correctly gate on capability checks
before proceeding. This enforces the deny-by-default capability model at the service entry points as specified by
the SRS.

**Event log instrumentation**: Phase 8 adds `ModelCallStarted`, `ModelCallCompleted`, `AgentResponseStarted`, and
`AgentResponseCompleted` events. The pattern is correct and the infrastructure is in place; the gaps identified in
this report are completeness issues, not structural ones.

**Scaladoc quality**: The Scala-Doc Auditor found no documentation drift anywhere in Phase 8. Targeted
documentation improvements were applied to 10 source files. Every public API in `ModelGateway`, `SessionHub`,
`AgentRunner`, `AgentSessionManager`, and the shell client now has complete `@param` and `@return` coverage.

**`SessionHub` broadcast design**: Using `Hub[ResponseChunk]` per session provides natural fan-out to multiple
subscribers (e.g., multiple browser tabs) with zero additional code. The `ZLayer.scoped` wrapping of the `Hub` map
ensures cleanup on server shutdown.

**`InMemoryChatMemoryStore` isolation**: Storing chat memory per session in `StreamAssistant` rather than in the
model instance is the correct factoring; it enables the P8-004 fix (shared model instance) without architectural
changes.
