/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# Phase 8.5 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Code Simplicity, Functional Scala, Pattern Recognition, Performance Oracle, SRS/SDD
Conformance, Test Coverage, ScalaDoc Auditor, TUI Test Plan Writer)
**Date**: 2026-06-02
**Status**: Review Complete
**Branch**: `phase-8.5/manual-testing`
**Scope**: Phase 8.5 — Session Connection Redesign & Manual Testing Infrastructure
(`SubscriptionClient.scala`, `SessionHub.scala`, `AgentRunnerImpl.scala`, `AgentSessionManagerImpl.scala`,
`ConversationLogger.scala`, `VersionCheck.scala`, `CommandHandler.scala`, `JorlanShell.scala`,
`JorlanAPI.scala`, `InitRoutes.scala`, `JorlanScreen.scala`, `ShellState.scala`)

---

## Executive Summary

The Phase 8.5 session connection redesign is architecturally sound and represents a genuine improvement over the prior
per-message WebSocket handshake approach. The Hub→Queue streaming redesign matches the design document point-for-point;
`VersionCheck` is a textbook pure-functional module; `ConversationLogger`'s SiftingAppender pattern is good operational
design; and the `AgentSessionManager→SessionHub` coupling has been correctly reworked at the interface level. ZIO idioms
are used correctly throughout — `ZStream.unwrap` for subscription resolvers, `initDone` Promise-based server switchover,
and proper fiber lifecycle management in `handleNewSession` all reflect solid ZIO practice.

Three issues require attention before merging. First, **`AgentSessionManagerImpl` retains a dead `SessionHub` dependency
** that was supposed to be removed by the redesign — confirmed independently by three agents (Pattern Recognition,
SRS/SDD Conformance, and Code Simplicity). Second, **`ConversationLogger` wraps blocking Logback I/O in `ZIO.succeed`**
instead of `ZIO.attemptBlocking`, creating a latent thread-starvation risk on the ZIO fiber pool — flagged by both the
Functional Scala and Performance Oracle reviewers. Third, **`SessionHub.sliding` silently drops tokens under
backpressure** while the class-level ScalaDoc claims subscribers receive a complete copy of the token stream — a
documented contract violation confirmed by Pattern Recognition and the ScalaDoc Auditor.

**Overall health: Issues Present — ready to advance to Phase 9 with open items tracked.**

Documentation quality is inconsistent: `SessionHub.scala` carries actively misleading ScalaDoc describing the old
single-queue design; `session-connection-redesign.md` contains stale status text and a broken table row; and
`AgentSessionManagerImpl.scala` has no class-level documentation at all. Test coverage for new code is weak —
`ConversationLogger` has zero direct tests, and `CommandHandler`'s new active-session paths have approximately 30%
coverage of new code paths, with a misleading passing test that actually exercises only the failure branch.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area              | Issue                                                                                                                                                                                                      | File : Line                                                 | Recommended Action                                                                                                                         |
|--------|------------|------------|-------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | P85-001    | Critical   | Functional Purity | `ConversationLogger` wraps blocking Logback I/O in `ZIO.succeed` instead of `ZIO.attemptBlocking`, risking fiber pool starvation. (confirmed by 2 reviewers)                                               | `ConversationLogger.scala:36-53`                            | Replace with `ZIO.attemptBlocking { withMdc(...) { logger.info(...) } }.ignoreLogged`.                                                     |
| [x]    | P85-002    | Critical   | Architecture      | `AgentSessionManagerImpl` injects `SessionHub` in constructor but never references it — dead dependency from the redesign. (confirmed by 3 reviewers)                                                      | `AgentSessionManagerImpl.scala:20,133-141`                  | Remove `sessionHub` constructor param and from `URLayer`.                                                                                  |
| [x]    | P85-003    | Critical   | Correctness       | `SessionHub.sliding` silently drops tokens under backpressure; class ScalaDoc claims "complete copy of token stream" — contract violation. (confirmed by 2 reviewers)                                      | `SessionHub.scala:48`                                       | Use `Queue.unbounded` for lossless delivery, or `Queue.bounded` with documented, deliberate backpressure semantics and corrected ScalaDoc. |
| [x]    | P85-004    | Critical   | Performance       | `appendToLastMessage` calls `Vector.init` per streaming token — O(n) in message count; causes visible UI lag at 500+ messages. (confirmed by 1 reviewer)                                                   | `JorlanScreen.scala:175`                                    | Hold in-progress response in `Ref[Option[StringBuilder]]`; flush to `messages` Vector only on response completion.                         |
| [x]    | P85-005    | Critical   | Performance       | `AgentRunnerImpl` accumulates tokens via `tokensRef.update(_ :+ chunk)` — creates a new `Vector` per token (2000 tokens = 2000 allocations). (confirmed by 1 reviewer)                                     | `AgentRunnerImpl.scala:40,65`                               | Replace `Ref[Vector[String]]` with `Ref[StringBuilder]`.                                                                                   |
| [x]    | P85-006    | Warning    | Concurrency       | `SessionHub.subscribe` reads `subs` Ref twice (get after update) — race window between count read and log statement. (confirmed by 3 reviewers)                                                            | `SessionHub.scala:53-55`                                    | Use `Ref.modify` or `Ref.updateAndGet` to capture count atomically.                                                                        |
| [x]    | P85-007    | Warning    | Concurrency       | Subscription-setup race window: stale `Right(None)` sentinel in queue can cause second response to render invisibly if user types before drain completes.                                                  | `CommandHandler.scala:70-85`                                | Drain queue contents before subscribing new response, or tag sentinel with request ID.                                                     |
| [x]    | P85-008    | Warning    | Correctness       | `createSession` resolver has per-resolver `tapError` duplicating the `logErrors` OverallWrapper — every failure logs twice. (confirmed by 2 reviewers)                                                     | `JorlanAPI.scala:~423`                                      | Remove per-resolver `tapError`; rely on the `logErrors` wrapper exclusively.                                                               |
| [x]    | P85-009    | Warning    | Performance       | `SessionHub.publish` uses sequential `ZIO.foreachDiscard` across subscribers — one slow subscriber delays all others.                                                                                      | `SessionHub.scala:78`                                       | Replace with `ZIO.foreachParDiscard` for parallel fan-out.                                                                                 |
| [x]    | P85-010    | Warning    | Observability     | `ConversationLogger.withMdc` copies the full MDC map (`MDC.getCopyOfContextMap`) on every log call — HashMap allocation per call and thread-local unsafeness on ZIO fiber pool. (confirmed by 2 reviewers) | `ConversationLogger.scala:56-59`                            | Save/restore only the `sessionId` key, or migrate to `ZIO.logAnnotate` for fiber-safe context propagation.                                 |
| [x]    | P85-011    | Warning    | Correctness       | `adminCapabilities` typed as `List[String]` instead of `List[CapabilityName]` — typos grant nothing silently.                                                                                              | `InitService.scala:186-198`                                 | Change type to `List[CapabilityName]` so the compiler rejects unknown names.                                                               |
| [x]    | P85-012    | Warning    | API Design        | `JorlanClient.Argument("value", value, "ModelId")` argument name `"value"` may not match server schema field name after input-type unwrapping.                                                             | `JorlanClient.scala:~516`                                   | Verify against `jorlan.gql` schema; rename to match the actual schema argument name.                                                       |
| [x]    | P85-013    | Warning    | Error Handling    | `GraphQLClient.run` uses `e.getMessage` on `CalibanClientError` which can return null under `-Yexplicit-nulls`.                                                                                            | `GraphQLClient.scala:~113`                                  | Use `Option(e.getMessage).getOrElse(e.getClass.getName)`.                                                                                  |
| [x]    | P85-014    | Warning    | Test Coverage     | `ConversationLogger` has zero direct tests — the `logUserMessage(actorId=None)` fallback, `logAgentResponse(isError=true)` path, and MDC restore are never verified.                                       | `ConversationLogger.scala` (no spec)                        | Create `ConversationLoggerSpec` with at least 6 tests covering all method paths and MDC restore.                                           |
| [x]    | P85-015    | Warning    | Test Coverage     | `CommandHandler.handleMessage` active-session drain arms (`Left(err)`, `Right(None)`, `Right(Some(chunk))`) are never exercised in tests.                                                                  | `CommandHandlerSpec.scala`                                  | Extend `fakeGQL` to support `run`; add tests for each drain arm with a live `LiveSession`.                                                 |
| [x]    | P85-016    | Warning    | Test Coverage     | `CommandHandlerSpec."/new creates a new session"` passes because an error message is non-empty — misleading green that never exercises the success path.                                                   | `CommandHandlerSpec.scala`                                  | Fix fake or assert `MessageKind.Error` explicitly; add separate test for success path with working fake.                                   |
| [x]    | P85-017    | Warning    | Test Coverage     | `InitRoutes` new behaviors (localhost token bypass, `initDone` Promise completion) have no test coverage.                                                                                                  | `InitRoutesSpec.scala`                                      | Add tests with a loopback `InetAddress` and `Promise.succeed`/`Promise.await` assertions.                                                  |
| [x]    | P85-018    | Warning    | Documentation     | `SessionHub.scala` class-level ScalaDoc describes the old single-queue, unbounded, no-drop design — actively misleads readers about actual contract. (confirmed by 2 reviewers)                            | `SessionHub.scala`                                          | Rewrite class comment to describe the sliding-bounded queue design and document the drop-under-backpressure contract.                      |
| [x]    | P85-019    | Warning    | Documentation     | `session-connection-redesign.md` has stale "pending implementation" status text, wrong `subscribe` return type (`ZStream[...]` vs `UIO[ZStream[...]]`), and a broken table row at line 170.                | `session-connection-redesign.md`                            | Update status to "Implemented in Phase 8.5", correct the return-type signature, and fix the broken table row.                              |
| [ ]    | P85-020    | Suggestion | Code Quality      | WS frame handler in `SubscriptionClient` is 6+ nesting levels deep — cyclomatic complexity ~8.                                                                                                             | `SubscriptionClient.scala:130-200`                          | Extract `handleCompleteFrame(json: String): UIO[Unit]` helper to reduce nesting to ~3.                                                     |
| [x]    | P85-021    | Suggestion | Code Quality      | Live-session subscription setup duplicated ~15–25 lines between `loadOrCreateSession` and `handleNewSession`.                                                                                              | `JorlanShell.scala:169-215`, `CommandHandler.scala:156-190` | Extract `setupLiveSession(sessionId, queue, fiber, ...)` to `object LiveSession` or a shared helper.                                       |
| [x]    | P85-022    | Suggestion | Code Quality      | `AgentRunnerImpl.processMessage` has 5+ nesting levels from two nested `flatMap` blocks introducing two `Ref` values.                                                                                      | `AgentRunnerImpl.scala:39-106`                              | Replace with a top-level `for`-comprehension binding both refs before the effectful body.                                                  |
| [x]    | P85-023    | Suggestion | Code Quality      | `setPersonalityField` calls `field.toLowerCase` 5 times and duplicates list-splitting logic twice.                                                                                                         | `CommandHandler.scala:213-257`                              | Extract `val f = field.toLowerCase` and `val splitList = ...` as local vals.                                                               |
| [x]    | P85-024    | Suggestion | Code Quality      | `ZIO.when(response.errors.nonEmpty)(ZIO.foreach(...))` should be `ZIO.foreachDiscard` — unnecessary guard.                                                                                                 | `JorlanAPI.scala:37-78`                                     | Remove the `ZIO.when` wrapper; use `ZIO.foreachDiscard(response.errors)(...)`.                                                             |
| [x]    | P85-025    | Suggestion | Observability     | `SessionHub.subscribe` logs subscriber add/remove at INFO level — too noisy for production.                                                                                                                | `SessionHub.scala:53-55`                                    | Change to DEBUG level.                                                                                                                     |
| [x]    | P85-026    | Suggestion | Functional Purity | `VersionCheck.parse` null guards are unnecessary under `-Yexplicit-nulls`; pattern match uses verbose `.flatMap { case ... if ... => Some(...); case _ => None }`.                                         | `VersionCheck.scala:33-38`                                  | Replace with `.collect { case ... if ... => ... }`.                                                                                        |
| [x]    | P85-027    | Suggestion | Functional Purity | `sys.exit(0)` called inside `ZIO.succeed` — should be wrapped in `ZIO.attempt(sys.exit(0)).orDie`.                                                                                                         | `JorlanShell.scala` (ensuring block)                        | Use `ZIO.attempt(sys.exit(0)).orDie`.                                                                                                      |
| [x]    | P85-028    | Suggestion | Error Handling    | `initialisePostLogin` broadens error type to `Throwable` for version check; `new RuntimeException(msg)` for a user-facing message overloads the error channel.                                             | `JorlanShell.scala`                                         | Introduce `final case class VersionIncompatibleError(msg: String) extends RuntimeException(msg)`.                                          |
| [x]    | P85-029    | Suggestion | Observability     | `logRequests` logs full query body including user message content — sensitive data in debug logs.                                                                                                          | `JorlanAPI.scala`                                           | Truncate request body to 120 characters before logging.                                                                                    |
| [ ]    | P85-030    | Suggestion | Performance       | `loadOrCreateSession` in-memory `find` over first-page sessions may miss active session if user has many sessions.                                                                                         | `JorlanShell.scala:205-215`                                 | Add `status: Option[SessionStatus]` filter to the server query.                                                                            |
| [x]    | P85-031    | Suggestion | Test Coverage     | `SessionHub` multi-subscriber cleanup test only asserts `assertCompletes` — internal Ref state not inspected after disconnect.                                                                             | `SessionHubSpec.scala`                                      | Add assertion that subscriber count in `subs` Ref returns to expected value after unsubscribe.                                             |
| [x]    | P85-032    | Suggestion | Test Coverage     | `SessionHub` sliding queue overflow semantics never tested — >1024 chunks never published.                                                                                                                 | `SessionHubSpec.scala`                                      | Add test publishing 1025+ chunks to a bounded subscriber queue and asserting first chunk was dropped.                                      |
| [x]    | P85-033    | Suggestion | Test Coverage     | `InitService.seedAdminGrants` — grants written but never queried/asserted in tests.                                                                                                                        | `InitServiceSpec.scala`                                     | Add assertion that all 12 expected capability grants exist after `seedAdminGrants`.                                                        |
| [x]    | P85-034    | Suggestion | Test Coverage     | `AgentRunner.subscribeToSession` with `actorId=None` path never exercised in tests.                                                                                                                        | `AgentRunnerSpec.scala`                                     | Add test case with `actorId = None`.                                                                                                       |
| [ ]    | P85-035    | Suggestion | Test Coverage     | `SubscriptionClient` WS message decoders (`DataPayload`, `isError` field) have no unit tests.                                                                                                              | `SubscriptionClient.scala` (no spec)                        | Add `SubscriptionClientSpec` with decoder unit tests for each message type and the `isError` field.                                        |
| [x]    | P85-036    | Suggestion | Documentation     | `AgentSessionManagerImpl.scala` has no class-level ScalaDoc and no `@param` docs on constructor or `live` ZLayer.                                                                                          | `AgentSessionManagerImpl.scala`                             | Add class-level doc describing lifecycle, constructor params, and `live` ZLayer composition.                                               |
| [x]    | P85-037    | Suggestion | Documentation     | `ConversationLogger.logUserMessage` and `logAgentResponse` have no ScalaDoc despite being the primary public API.                                                                                          | `ConversationLogger.scala`                                  | Add ScalaDoc with `@param` for `actorId` (noting `None` → "unknown") and `isError` (noting `logger.error` path).                           |
| [x]    | P85-038    | Suggestion | Documentation     | `VersionCheck.check` missing `@param clientBuildTime` explanation for the `>=` semantics.                                                                                                                  | `VersionCheck.scala`                                        | Add `@param clientBuildTime` doc noting that a newer client build time is acceptable.                                                      |
| [x]    | P85-039    | Suggestion | Code Quality      | `handleMessage` drain recursive `def` needs a comment explaining why ZIO trampolining prevents stack overflow for long responses.                                                                          | `CommandHandler.scala:60-86`                                | Add a single-line comment: `// ZIO evaluates this tail-recursively via trampolining — no stack risk at LLM token rates`.                   |
| [x]    | P85-040    | Suggestion | Concurrency       | `loadOrCreateSession` subscription forked before `setLiveSession`; correct because queue is already in scope, but ordering rationale is non-obvious.                                                       | `JorlanShell.scala:~200`                                    | Add a comment explaining that fork-before-set is safe because the queue buffer captures early tokens.                                      |

---

## Grouped Sections

### Correctness / Concurrency

**Dead `SessionHub` dependency in `AgentSessionManagerImpl`** (P85-002) — CONFIRMED BY 3 REVIEWERS

`AgentSessionManagerImpl` accepts `sessionHub: SessionHub` as a constructor parameter (line 20) and includes it in the
`URLayer` definition (lines 133–141), but no method body in the class references `sessionHub`. The redesign explicitly
removed the `getOrCreate`/`remove` calls that formerly justified this dependency, but the constructor parameter was not
cleaned up.

This creates a false coupling in the dependency graph, forces callers to provide a `SessionHub` layer unnecessarily, and
misleads readers into thinking the session manager still coordinates directly with the hub. The Pattern Recognition,
SRS/SDD Conformance, and Code Simplicity agents all flagged this independently.

Recommended fix: remove `sessionHub` from the constructor parameter list, the class body, and the `URLayer` definition
entirely.

---

**`SessionHub.sliding` silent token drops** (P85-003) — CONFIRMED BY 2 REVIEWERS

`SessionHub.subscribe` creates a `Queue.sliding(1024)` per subscriber. When a subscriber's queue is full (e.g., a slow
consumer or one that disconnects ungracefully), the queue silently drops the oldest tokens. The class-level ScalaDoc
currently states subscribers receive "a complete copy of the token stream," which is false under any backpressure
scenario.

This is a documented contract violation. Pattern Recognition flagged it as a critical design issue; the ScalaDoc Auditor
flagged the class comment as actively misleading. The impact is that a slow shell client may silently lose LLM response
tokens, producing truncated output with no error indication to the user.

Recommended fix: choose one of two approaches and document it clearly. If lossless delivery is required: change to
`Queue.unbounded`. If bounded/dropping is intentional: keep `Queue.sliding` but update the ScalaDoc to state explicitly
that slow subscribers will lose tokens under backpressure, and consider exposing this as a configurable parameter.

---

**Subscription-setup race window: stale sentinel** (P85-007)

In `CommandHandler.handleMessage`, a `Right(None)` sentinel marks the end of a response stream. If the user sends a
second message before the first response is fully drained, a stale sentinel from the prior response may be consumed
immediately by the drain loop, causing the second response to render as invisible. The Pattern Recognition agent
identified this as a timing-dependent correctness bug.

Recommended fix: drain any existing queue contents before forking the subscription for a new response, or tag each
sentinel with a request nonce and reject sentinels that do not match the current request.

---

**`SessionHub.subscribe` non-atomic count read** (P85-006) — CONFIRMED BY 3 REVIEWERS

`SessionHub.subscribe` calls `subs.update(...)` then `subs.get` in two separate Ref operations to log the subscriber
count. Between the two operations another fiber may add or remove a subscriber, making the logged count inaccurate.
Flagged by Functional Scala, Pattern Recognition, and Performance Oracle.

Recommended fix:

```scala
// Replace:
_
<- subs.update(_ + (sessionId -> queue))
count
<- subs.get.map(_.size)

// With:
count
<- subs.updateAndGet(_ + (sessionId -> queue)).map(_.size)
```

---

### Functional Purity / Resource Management

**`ConversationLogger` blocking I/O on ZIO fiber** (P85-001) — CONFIRMED BY 2 REVIEWERS

`ConversationLogger.logUserMessage` and `logAgentResponse` call `withMdc { logger.info(...) }` (and `logger.error`)
inside `ZIO.succeed`. Logback file appenders perform blocking I/O (disk writes, file rotation locks) and can throw
`IOException`. Wrapping blocking, throwing code in `ZIO.succeed` violates ZIO's contract that `succeed` is pure and
non-blocking, and risks stalling the ZIO fiber scheduler. The Functional Scala and Performance Oracle agents both
flagged this.

Recommended fix:

```scala
def logUserMessage(sessionId: SessionId, actorId: Option[ActorId], content: String): UIO[Unit] =
  ZIO.attemptBlocking {
    withMdc(sessionId) {
      logger.info(s"[USER:${actorId.fold("unknown")(_.value)}] $content")
    }
  }.ignoreLogged
```

Apply the same pattern to `logAgentResponse`.

---

**`ConversationLogger` MDC full-map copy overhead** (P85-010) — CONFIRMED BY 2 REVIEWERS

`withMdc` calls `MDC.getCopyOfContextMap()` which allocates a new `HashMap` on every invocation — once per token in a
streaming response. MDC context maps are also stored in Java thread-locals, which may not be visible across ZIO fiber
hops on a fixed thread pool. The Performance Oracle and Functional Scala agents both identified this.

Recommended fix: save and restore only the `sessionId` key rather than copying the entire map:

```scala
private def withMdc[A](sessionId: SessionId)(block: => A): A = {
  val prev = MDC.get("sessionId")
  MDC.put("sessionId", sessionId.value)
  try block finally {
    if (prev == null) MDC.remove("sessionId") else MDC.put("sessionId", prev)
  }
}
```

Longer term, consider replacing MDC-based routing with `ZIO.logAnnotate` and a custom ZIO log appender to avoid
thread-local semantics entirely.

---

**`sys.exit(0)` in `ZIO.succeed`** (P85-027)

`JorlanShell.scala` calls `sys.exit(0)` inside a `ZIO.succeed` block, which violates ZIO's effect model — `sys.exit`
throws a `SecurityException` or terminates the JVM and should be wrapped in `ZIO.attempt`. This was carried over from a
prior phase and was noted by the Functional Scala reviewer.

Recommended fix: `ZIO.attempt(sys.exit(0)).orDie`.

---

### Performance

**`appendToLastMessage` O(n) per streaming token** (P85-004)

In `JorlanScreen.scala`, `appendToLastMessage` appends to the last message by calling `Vector.init` to copy all but the
last element, then appending the modified last element. With 500 accumulated messages and a 1000-token response, this
produces 500,000 element-copies and 1,000 `String` concatenation allocations. At streaming token rates (~20–50
tokens/second), this becomes visible UI lag.

Recommended fix: introduce `inProgressResponse: Ref[Option[StringBuilder]]`. When a streaming response begins,
accumulate tokens into the `StringBuilder`. On `Right(None)` sentinel, convert to `String`, append to `messages`, and
clear the ref. `appendToLastMessage` then becomes an `O(1)` `StringBuilder.append`.

---

**`AgentRunnerImpl` Vector-per-token accumulation** (P85-005)

`AgentRunnerImpl` accumulates streamed tokens using `tokensRef.update(_ :+ chunk)`, which creates a new `Vector[String]`
on every token. At 2000 tokens, this allocates 2000 intermediate Vectors — significant GC pressure during long
responses.

Recommended fix: replace `Ref[Vector[String]]` with `Ref[StringBuilder]`:

```scala
for {
  sb <- Ref.make(new StringBuilder)
  _ <- tokenStream.foreach(chunk => sb.update(_.append(chunk)))
  result <- sb.get.map(_.toString)
} yield result
```

---

**`SessionHub.publish` sequential fan-out** (P85-009)

`SessionHub.publish` iterates subscribers with `ZIO.foreachDiscard`, which is sequential. One slow subscriber (e.g., one
whose queue `offer` blocks on a full sliding queue before dropping) delays token delivery to all other subscribers.
While single-subscriber scenarios are the common case today, the fix is a one-word change.

Recommended fix: `ZIO.foreachParDiscard(subscribers)(_.queue.offer(token))`.

---

### Test Coverage

**`ConversationLogger` — zero direct tests** (P85-014)

`ConversationLogger` is a new module introduced in this phase with two public methods and three distinct code paths,
none of which are covered by any test. The test coverage agent identified this as a P1 gap.

| Missing Test                                     | Gap                                                          |
|--------------------------------------------------|--------------------------------------------------------------|
| `logUserMessage(actorId = None)`                 | "unknown" fallback for actor name never asserted             |
| `logAgentResponse(isError = true)`               | `logger.error` code path unreachable in tests                |
| `withMdc` MDC restore                            | MDC state after exception in block never verified            |
| `logUserMessage` + `logAgentResponse` sequencing | Cross-session routing via SiftingAppender never tested       |
| Concurrent calls from multiple sessions          | Thread-safety of MDC save/restore unverified                 |
| File output location                             | Log file created in expected per-session path never asserted |

Recommended action: create `ConversationLoggerSpec` covering all six scenarios above.

---

**`CommandHandler` active-session paths untested** (P85-015, P85-016)

All `CommandHandlerSpec` tests provide a `ShellState` with no `LiveSession`, so every test exercises only the `None`
branch of `handleMessage`. The three drain arms (`Left(err)`, `Right(None)`, `Right(Some(chunk))`) are never reached.
Additionally, the `"/new creates a new session"` test assertion `msgs.nonEmpty` is satisfied by the error message
produced when the fake GQL client fails — the test passes green while never touching the success path.

| Missing Test                                                     | Gap                                                     |
|------------------------------------------------------------------|---------------------------------------------------------|
| `handleMessage` with `LiveSession` present, `Right(Some(chunk))` | Chunk rendering in TUI                                  |
| `handleMessage` with `LiveSession`, `Right(None)` sentinel       | Response completion behavior                            |
| `handleMessage` with `LiveSession`, `Left(err)`                  | Error rendering in TUI                                  |
| `handleNewSession` success path                                  | Session creation, fiber fork, subscription start        |
| `showPersonality` / `setPersonalityField` commands               | No `ShellCommand.Personality` or `PersonalitySet` tests |

Recommended action: extend the fake GQL to support `run`; inject a `LiveSession` with a real `Queue.bounded(16)` in
active-session tests; assert `MessageKind.Error` explicitly in the `/new` failure test.

---

**`InitRoutes` new behaviors untested** (P85-017)

Two new behaviors added in Phase 8.5 — the localhost token bypass (`isLoopbackAddress` check) and the `initDone` Promise
completion path — have no test coverage. The existing tests always pass `None` for the Promise and `0.0.0.0` for the
remote address.

| Missing Test                         | Gap                                                    |
|--------------------------------------|--------------------------------------------------------|
| `isLoopbackAddress = true`           | Localhost bypass never verified                        |
| `initDone` Promise `.succeed` path   | Server-ready signal never tested                       |
| `initDone` Promise `.await` blocking | Race between init completion and next request untested |

---

**Additional high-priority coverage gaps**

| Gap ID  | File                           | Missing Scenario                                                |
|---------|--------------------------------|-----------------------------------------------------------------|
| P85-031 | `SessionHubSpec`               | Ref state after subscriber disconnect                           |
| P85-032 | `SessionHubSpec`               | >1024 chunk overflow — first chunk dropped                      |
| P85-033 | `InitServiceSpec`              | All 12 capability grants asserted after `seedAdminGrants`       |
| P85-034 | `AgentRunnerSpec`              | `subscribeToSession` with `actorId = None`                      |
| P85-035 | (new) `SubscriptionClientSpec` | WS decoder unit tests for each message type and `isError` field |

---

### Architecture / Layer Discipline

**Session lifecycle duplication** (P85-021)

`JorlanShell.loadOrCreateSession` (lines 169–215) and `CommandHandler.handleNewSession` (lines 156–190) each
independently implement the same ~15–25 line sequence: create queue, fork subscription fiber, call `setLiveSession`,
update status bar. This duplication was flagged by Code Simplicity and Pattern Recognition. Any change to session
startup behavior must be made in two places.

Recommended fix: extract `setupLiveSession(sessionId: SessionId, queue: Queue[...], ...) : UIO[LiveSession]` into
`object LiveSession` or a shared `SessionSupport` helper, and have both callers delegate to it.

---

**`JorlanClient.Formality` typed as `String`** (P85-012)

The client-side `Formality` argument is typed as `String` rather than mirroring the server-side enum, meaning typos or
schema drift are silently ignored rather than caught at compile time. This was flagged by Pattern Recognition.

Recommended fix: generate or manually define a client-side `Formality` enum that mirrors the server schema, and use it
in the `JorlanClient` argument.

---

### Documentation

**`SessionHub.scala` class comment describes wrong design** (P85-018) — CONFIRMED BY 2 REVIEWERS

The existing class-level ScalaDoc for `SessionHub` describes a single unbounded queue with no-drop semantics. The actual
implementation uses `Queue.sliding(1024)` per subscriber, which actively drops tokens under backpressure. A developer
reading the ScalaDoc would build false expectations about delivery guarantees. Both the ScalaDoc Auditor (rated 5/10)
and Pattern Recognition flagged this.

Recommended fix: replace the class comment with an accurate description: per-subscriber bounded sliding queue;
drop-oldest under backpressure; each subscriber is isolated so a slow subscriber does not affect others.

---

**`session-connection-redesign.md` stale content** (P85-019)

The design document contains three stale items: the Status field reads "pending implementation" despite the feature
being complete; the `subscribe` method return type is shown as `ZStream[...]` when the actual signature is
`UIO[ZStream[...]]`; and there is a broken table row at line 170. This was flagged by the ScalaDoc Auditor.

Recommended fix: update status to "Implemented in Phase 8.5 — 2026-06-02", correct the return-type signature, and fix
the broken table row.

---

**`AgentSessionManagerImpl` entirely undocumented** (P85-036)

`AgentSessionManagerImpl` has no class-level ScalaDoc, no `@param` documentation on constructor parameters, and no
documentation on `terminateSession` side effects or the `live` ZLayer composition. The ScalaDoc Auditor rated this
3/10 — the lowest score among all reviewed files.

---

### Code Quality

**`SubscriptionClient` deep nesting** (P85-020)

The WS frame handler in `SubscriptionClient` reaches 6+ nesting levels handling fragment accumulation, JSON parsing, and
protocol dispatch in a single block. Cyclomatic complexity is approximately 8. The Code Simplicity reviewer identified
this as the highest-impact simplification opportunity in the new code.

Recommended fix: extract `handleCompleteFrame(json: String): UIO[Unit]` as a named helper, reducing the outer handler
to ~3 levels of nesting.

---

**`logErrors` double-logging** (P85-008) — CONFIRMED BY 2 REVIEWERS

`createSession` in `JorlanAPI.scala` has a per-resolver `tapError` that logs failures. The file also uses a `logErrors`
`OverallWrapper` that catches all resolver failures. Every `createSession` failure is logged twice — once by the
per-resolver tap and once by the wrapper. The Functional Scala and Pattern Recognition agents both noted this.

Recommended fix: remove the per-resolver `tapError` from `createSession` and rely exclusively on the `logErrors` wrapper
for consistent single-point error logging.

---

## Cross-Cutting Patterns

**Dead dependency proliferation from incomplete refactor** was independently flagged by three agents (Pattern
Recognition, SRS/SDD Conformance, Code Simplicity). The root cause is that the `AgentSessionManager→SessionHub` coupling
was removed at the service-layer interface level during the redesign, but the constructor wiring in
`AgentSessionManagerImpl` was not cleaned up. This pattern — removing a logical dependency while leaving the structural
wiring — is easy to miss in Scala because the ZIO layer type system does not warn about unused services. Finding IDs:
P85-002. Mitigation: add a compile-time or test-time assertion that `AgentSessionManagerImpl.live` does not require
`SessionHub` in its environment.

**Blocking I/O and thread-local misuse on the ZIO fiber pool** was flagged by two agents (Functional Scala, Performance
Oracle) from complementary angles. The root cause is that `ConversationLogger` was modeled as a straightforward SLF4J
wrapper without accounting for ZIO's cooperative scheduling model. `ZIO.succeed` around blocking code,
`MDC.getCopyOfContextMap` with thread-local semantics, and full-map copy overhead on every token are three
manifestations of the same underlying mismatch. Finding IDs: P85-001, P85-010. These should be resolved together in a
single pass.

**Silent delivery degradation under load** appears in two independent places: `SessionHub.sliding` dropping tokens with
a falsely documented no-drop contract (P85-003), and `appendToLastMessage`'s O(n) copy causing visible UI lag during
long sessions (P85-004). Both are silent failures — no error is raised, no warning is logged, behavior simply degrades
as the session grows. The common fix pattern is to make the degradation visible: either prevent it (bounded queue →
unbounded, or `StringBuilder` accumulation), or instrument it (log drops, measure append time).

**Non-atomic Ref read-after-update** appears in `SessionHub.subscribe` (P85-006) and was flagged by three agents. The
pattern of `ref.update(...) *> ref.get` to capture the post-update value is a recurring ZIO anti-pattern when atomic
semantics are required. The correct idiom is `ref.updateAndGet(...)` or `ref.modify(...)`. This pattern was also noted
as a risk in `OllamaModelGateway.getOrCreate` in a prior review — it may be worth a codebase-wide grep for `\.update\(`
followed by `\.get` within the same for-comprehension.

**Test fakes that never reach the success path** (P85-015, P85-016) represent a broader test infrastructure concern. Two
separate `CommandHandlerSpec` tests pass because the fake GQL client always fails, and the test assertions do not
distinguish success from failure message kinds. This creates misleading green tests that provide false confidence. The
fix requires extending fakes to support the happy path, not just the error path — a pattern worth enforcing in all
future specs.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count  |
|------------|--------|
| Critical   | 5      |
| Warning    | 14     |
| Suggestion | 21     |
| **Total**  | **40** |

**Issues by area:**

| Area              | Count  |
|-------------------|--------|
| Test Coverage     | 9      |
| Code Quality      | 7      |
| Documentation     | 5      |
| Functional Purity | 3      |
| Performance       | 4      |
| Concurrency       | 3      |
| Correctness       | 2      |
| Architecture      | 2      |
| Observability     | 2      |
| Error Handling    | 2      |
| API Design        | 1      |
| **Total**         | **40** |

**Agent contribution:**

| Agent                | Unique Findings | Cross-Confirmed With                           |
|----------------------|-----------------|------------------------------------------------|
| Code Simplicity      | 4               | Pattern Recognition, Functional Scala, SRS/SDD |
| Functional Scala     | 6               | Performance Oracle, Pattern Recognition        |
| Pattern Recognition  | 5               | Code Simplicity, SRS/SDD, Functional Scala     |
| Performance Oracle   | 6               | Functional Scala, Pattern Recognition          |
| SRS/SDD Conformance  | 3               | Pattern Recognition, Code Simplicity           |
| Test Coverage        | 9               | (primary contributor for coverage gaps)        |
| ScalaDoc Auditor     | 4               | Pattern Recognition                            |
| TUI Test Plan Writer | 3               | (primary contributor for manual test plan)     |

**Phase 8.5 scope completion:**

| Deliverable                                            | Status                                                         |
|--------------------------------------------------------|----------------------------------------------------------------|
| Session connection redesign (one WS per session)       | ✅                                                              |
| `ConversationLogger` rewritten with `ZIO.log`          | ✅ Rewritten (fiber-safe, no MDC, no blocking I/O)              |
| `VersionCheck` client/server compatibility gate        | ✅                                                              |
| Shell TUI improvements (command handling, status bar)  | ✅                                                              |
| Manual testing infrastructure (`manual-testing-guide`) | ✅                                                              |
| Dead `SessionHub` dependency removal                   | ✅ Removed from `AgentSessionManagerImpl`                       |
| `ConversationLoggerSpec` (new test file)               | ✅ Created with 7 tests using `ZTestLogger`                     |
| `CommandHandlerSpec` active-session coverage           | ✅ 3 new tests; misleading `/new` test fixed                    |
| All 40 review items addressed                          | ✅ 37 fixed; 3 suggestions deferred (P85-020, P85-030, P85-035) |

---

## What Was Done Well

**`VersionCheck` is textbook pure functional code.** The module is entirely effect-free until the boundary, has a clean
ADT for version compatibility results, is tested with ~95% coverage including edge cases, and uses `parse` + `check`
separation that makes the logic easy to reason about. This is the pattern all new utility modules should follow.

**Hub-to-Queue streaming redesign matches the design document exactly.** The `SessionHub` approach — one queue per
subscriber, isolated from other subscribers, with the subscription returning `UIO[ZStream[...]]` as a two-step
acquisition — is architecturally correct and eliminates the per-message WebSocket handshake overhead. The SRS/SDD
Conformance agent rated this section as fully conformant.

**`AgentSessionManagerImpl.live` uses `ZLayer.fromZIO` with `Ref.make` cleanly.** The layer construction correctly uses
the ZIO service pattern and avoids the anti-pattern of mutable state outside the ZIO runtime. This approach should be
continued for all stateful service layers.

**`handleNewSession` properly tears down the existing fiber before creating a new session.** Fiber lifecycle management
is correct: interrupt the prior subscription fiber before forking the new one, ensuring no ghost subscriptions
accumulate across repeated `/new` commands. The five-step flow matches the design document's specified sequence.

**`initDone` Promise-based server switchover is correct ZIO design.** Using a `Promise[Nothing, Unit]` to signal that
initialization is complete, and blocking new connections on `Promise.await`, is the correct ZIO pattern for phased
startup. This avoids polling, avoids race conditions, and composes cleanly with ZIO's fiber model.

**`ConversationLogger`'s SiftingAppender approach is good operational design.** Routing per-session conversation logs to
separate files via Logback's `SiftingAppender` is a well-established pattern for multi-tenant observability. The
conceptual design is correct — only the ZIO integration details need fixing.

**`GraphQLClient.run` using Caliban `SelectionBuilder` eliminates raw JSON parsing bugs.** The type-safe selection
builder approach means schema mismatches are caught at compile time rather than at runtime as
`json.hcursor.downField(...)` failures. This should be the pattern for all new GraphQL client calls.

---

## Items to Address Before Advancing to Phase 9

### Must Fix (Critical) — All Resolved ✅

- [x] P85-001: `ConversationLogger` rewritten with `ZIO.log` / `ZIOAspect.annotated` (fiber-safe, no blocking I/O)
- [x] P85-002: Dead `SessionHub` dependency removed from `AgentSessionManagerImpl`
- [x] P85-003: `SessionHub` changed to `Queue.bounded(1024)`; ScalaDoc rewritten to document bounded semantics
- [x] P85-004: `appendToLastMessage` fixed — `inProgressMessage` in `ScreenState` eliminates `Vector.init` per token
- [x] P85-005: `AgentRunnerImpl` token accumulation changed from `Ref[Vector[String]]` to `Ref[StringBuilder]`

### Should Fix (Warning) — All Resolved ✅

- [x] P85-006: `SessionHub.subscribe` count read made atomic with `Ref.updateAndGet`
- [x] P85-007: Stale sentinel race resolved — `tokenQueue.takeAll` drains before each `submitMessage`
- [x] P85-008: Duplicate `tapError` removed from `createSession`; `logErrors` wrapper is sole error logger
- [x] P85-009: `SessionHub.publish` now uses `ZIO.foreachParDiscard` for parallel fan-out
- [x] P85-010: `ConversationLogger` rewritten — no MDC copy; fiber context carries annotations
- [x] P85-011: `adminCapabilities` typed as `List[CapabilityName]`
- [x] P85-012: Verified — `createSession` already uses `"modelId"`, not `"value"` (no change needed)
- [x] P85-013: `e.getMessage` null-guarded in `GraphQLClient.run`
- [x] P85-014: `ConversationLoggerSpec` created with 7 tests using `ZTestLogger`
- [x] P85-015: `CommandHandlerSpec` extended — 3 new active-session drain tests
- [x] P85-016: Misleading `/new` test replaced with explicit error-path and success-path tests
- [x] P85-017: `InitRoutesSpec` — localhost bypass and `initDone` Promise tests added
- [x] P85-018: `SessionHub` class-level ScalaDoc rewritten to describe bounded queue and actual contract
- [x] P85-019: `session-connection-redesign.md` — status updated, `subscribe` return type corrected, broken table row
  fixed

---

## Manual Test Plan

The comprehensive manual test plan (smoke tests, version check, session lifecycle, reconnect, error display, and
cross-cutting checks) has been merged into [`doc/manual-testing-guide.md`](../manual-testing-guide.md), which is the
canonical location for all manual testing scenarios.

The guide was updated as part of this review to add:

- **Section B3** — Version check scenarios (C1–C8)
- **Section C** — Connection heartbeat & reconnect (K1–K6)
- **Section D** — Subscription fiber lifecycle (L1–L6)
- **Section E** — Error display and message kinds
- **Section F** — Cross-cutting verification checklist

---

*End of Phase 8.5 Review — 2026-06-02*
