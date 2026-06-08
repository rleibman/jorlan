/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law. All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders. If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

# Phase 11 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Performance Oracle, Code Simplicity Reviewer, Functional Scala Reviewer, Pattern Recognition Specialist, ScalaDoc Auditor, Test Coverage Tracker, SRS/SDD Conformance Reviewer, UI Test Plan)
**Date**: 2026-06-07
**Branch**: `phase-11/telegram`
**Scope**: Phase 11 — Telegram Connector (`connector-api/src/main/scala/jorlan/connector/MessageIngress.scala`, `connector-api/src/main/scala/jorlan/connector/Skill.scala`, `connector-api/src/main/scala/jorlan/connector/ingress.scala`, `server/src/main/scala/jorlan/service/MessageIngressImpl.scala`, `server/src/main/scala/jorlan/service/ConnectorManager.scala`, `server/src/main/scala/jorlan/service/MessageIngressSpec.scala`, `server/src/main/scala/jorlan/EnvironmentBuilder.scala`, `server/src/main/scala/jorlan/Jorlan.scala`, `telegram/src/main/scala/jorlan/connector/telegram/TelegramApiClient.scala`, `telegram/src/main/scala/jorlan/connector/telegram/TelegramConnectorSkill.scala`, `server/src/main/resources/sql/V024__session_chat_ref.sql`, `integration/src/main/scala/jorlan/telegram/TelegramManualTest.scala`)

---

## Executive Summary

Phase 11 successfully delivers the foundational connector seam: the `Skill` / `ConnectorSkill` trait hierarchy, the reusable `MessageIngress` pipeline with identity resolution and capability gating, and a working Telegram connector (`TelegramConnectorSkill`) with long-polling, message normalization, and three egress tools. The new `connector-api` module cleanly separates the connector contract from server internals, V024 adds the `chatRef` column required for durable session binding, and the test suite is extended with `TelegramConnectorSkillSpec` and `MessageIngressSpec`. The overall ZIO idiom is consistent and the new module structure is a sound foundation for Phase 12 connectors.

Four issues require attention before Phase 12 begins. The reply path (AgentRunner subscribe → telegram.send_message) that is checked off as complete in the roadmap is entirely absent from the implementation — `agentRunner` is injected but never called anywhere in `TelegramConnectorSkill` (confirmed by 4 reviewers). The `resolveOrCreateSession` method silently discards the DB index added by V024 because the Quill filter runs in Scala after a capped 100-row page scan, meaning users with more than 100 sessions will silently receive duplicate sessions (confirmed by 5 reviewers). Unsafe `.get` on `Option` inside `invoke` can throw `NoSuchElementException` at runtime for any malformed egress call (confirmed by 3 reviewers). `UnrecognizedIdentityPolicy` is fully implemented in the domain model but never consulted in `MessageIngressImpl`, and the roadmap checkbox for it is nonetheless marked complete (confirmed by 4 reviewers).

**Overall health: Critical Issues — not ready to advance to Phase 12.**

ScalaDoc coverage is sparse throughout the new code. `TelegramApiClient` trait methods, `Skill.descriptor` / `Skill.invoke`, `SkillDescriptor` fields, `ConnectorManager.empty`, and both factory methods (`TelegramApiClientLive.make`, `TelegramConnectorSkill.make`) are undocumented. The mini-design document contains several factual errors that should be corrected: wrong module paths (§3/§4.2), wrong migration number (§8 says V023, actual is V024), and a reply-path design (§4.2) that was not implemented.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area              | Issue                                                                                                                                                                              | File : Line                                                                          | Recommended Action                                                                                                                                                  |
|--------|------------|------------|-------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | P11-001    | Critical   | Correctness       | Reply path (AgentRunner.subscribe → telegram.send_message) roadmap item marked [x] but never implemented — `agentRunner` injected but never called. (confirmed by 4 reviewers)    | `TelegramConnectorSkill.scala:74,206,209`                                            | Either implement the subscribe-and-forward loop in `TelegramConnectorSkill.start`, or uncheck the roadmap item and document as a Phase 12 `NotificationRouter` seam. |
| [x]    | P11-002    | Critical   | Data Integrity    | `resolveOrCreateSession` filters chatRef in Scala after fetching up to 100 rows — V024 DB index never used; users with >100 sessions silently get duplicate sessions. (confirmed by 5 reviewers) | `MessageIngressImpl.scala:91–113`                                           | Add `chatRef: Option[String]` to `AgentSessionSearch`; push filter to Quill; use `pageSize = 1`.                                                                   |
| [x]    | P11-003    | Critical   | Correctness       | `invoke` calls `.get` on `Option` (unsafe) — `Json.Obj().asObject.get` throws `NoSuchElementException` for any malformed egress invocation. (confirmed by 3 reviewers)             | `TelegramConnectorSkill.scala:124`                                                   | Replace `.get` with `.getOrElse(JsonObject.empty)` or pattern match; propagate a `JorlanError` for missing fields.                                                  |
| [x]    | P11-004    | Critical   | Correctness       | `UnrecognizedIdentityPolicy` stored in `TelegramConfig` but never consulted — both `Reject` and `Quarantine` branches are dead; unknown users are always silently dropped. (confirmed by 4 reviewers) | `MessageIngressImpl.scala:51–56`, `ingress.scala:57`                    | Read `connector.unrecognizedPolicy` from config in `MessageIngressImpl.receive`; implement `Reject` (drop + event log) and `Quarantine` (persist or log) branches.  |
| [x]    | P11-005    | Critical   | Correctness       | `Base64.getDecoder.decode` called outside ZIO in `invoke` — can throw `IllegalArgumentException` for invalid base64 without entering the ZIO error channel. (confirmed by 2 reviewers) | `TelegramConnectorSkill.scala:135,142`                                  | Wrap in `ZIO.attempt(Base64.getDecoder.decode(...)).mapError(...)`.                                                                                                 |
| [x]    | P11-006    | Critical   | Correctness       | `invoke` silently falls back to `""` for required fields `chatId` and `text` — malformed calls produce Telegram API errors instead of clear application errors. (confirmed by 2 reviewers) | `TelegramConnectorSkill.scala:127–128,132–133,139–141`                  | Add explicit required-field validation; return `ZIO.fail(JorlanError.UserError("chatId is required"))` when field is absent.                                        |
| [x]    | P11-007    | Critical   | Resource Management | `ConnectorManager.stopAll` is never called on server shutdown — Telegram polling fibers leak permanently on SIGTERM. (confirmed by 2 reviewers)                                | `Jorlan.scala:98,114`                                                                | Register `ConnectorManager.stopAll` as a shutdown action in the ZIO `Scope` / `ZIO.addFinalizer`, mirroring the `TriggerEngine` shutdown pattern.                   |
| [x]    | P11-008    | Warning    | Architecture      | `TelegramConnectorSkill.scala` imports `jorlan.service.AgentRunner` — telegram module depends on a server-layer type, violating module boundary. (confirmed by 2 reviewers)        | `TelegramConnectorSkill.scala:16,74`                                                 | Define `AgentRunner` interface in `connector-api` (or `model`); have the server implement it; inject via the trait only.                                             |
| [x]    | P11-009    | Warning    | Correctness       | `EvaluationResult` wildcard match in `MessageIngressImpl` — new variants would silently dispatch without checking `ApprovalMode`. (confirmed by 2 reviewers)                      | `MessageIngressImpl.scala:72–83`                                                     | Replace wildcard with exhaustive match; add a `CapabilityGrantAllows` arm that inspects `approvalMode` before dispatching.                                          |
| [x]    | P11-010    | Warning    | Functional Purity | `Instant.now()` called inside pure `normalizeMessage` — bypasses ZIO Clock, breaks determinism in tests. (confirmed by 5 reviewers)                                               | `TelegramConnectorSkill.scala:52`                                                    | Pass `receivedAt: Instant` as a parameter from the ZIO caller using `Clock.instant`.                                                                                |
| [x]    | P11-011    | Warning    | Correctness       | `TelegramConfig.useWebhook` is parsed and stored but never honored — class-level ScalaDoc and @param doc claim webhook support that does not exist.                               | `TelegramApiClient.scala:34–35`                                                      | Either implement the webhook path or remove `useWebhook` from `TelegramConfig` and add a `// TODO Phase N:` comment documenting the deferral.                       |
| [x]    | P11-012    | Warning    | Concurrency       | `pollLoop` dispatches Telegram updates sequentially, including full LLM round-trips — with 10 pending updates the last message waits 9× LLM latency.                              | `TelegramConnectorSkill.scala:166`                                                   | Replace `ZIO.foreach` with `ZIO.foreachParDiscard`.                                                                                                                 |
| [x]    | P11-013    | Warning    | Concurrency       | `ConnectorManager.startAll` starts connectors sequentially — delays startup proportional to number of connectors.                                                                 | `ConnectorManager.scala:36`                                                          | Replace `ZIO.foreach` with `ZIO.foreachParDiscard`.                                                                                                                 |
| [x]    | P11-014    | Warning    | Performance       | `ZLayer.succeed(client)` allocated on every `getUpdates` / `sendMessage` HTTP call in a tight loop.                                                                               | `TelegramApiClient.scala:93`                                                         | Replace with `.provideEnvironment(ZEnvironment(client))` to avoid per-call object allocation.                                                                       |
| [x]    | P11-015    | Warning    | Correctness       | `startAll` duplicated in both branches of `Jorlan.run` (`Jorlan.scala:98,114`) — if one branch is taken, connectors start; the other branch may not.                              | `Jorlan.scala:98,114`                                                                | Extract startup into a single `startServices` helper called once before branching, or merge the two branches.                                                       |
| [x]    | P11-016    | Warning    | Observability     | `logInboundEvent` called with `sessionId = None` even after session is successfully resolved — event log entry is missing the session reference required for traceability.        | `MessageIngressImpl.scala:80–81`                                                     | Pass the resolved `sessionId` when writing the inbound event; update the log call after the session is resolved.                                                    |
| [x]    | P11-017    | Warning    | Error Handling    | `ZIO.fail(new Exception(...))` used inside a `JorlanError` error channel in `sendMessage`, `sendPhoto`, `sendDocument` — mixes `Throwable` into a typed error channel.            | `TelegramApiClient.scala:136,162,186`                                                | Replace `ZIO.fail(new Exception(...))` with `ZIO.fail(JorlanError.ExternalServiceError(...))` or appropriate typed error.                                           |
| [x]    | P11-018    | Warning    | Code Quality      | `startAll` / `stopAll` declared as `IO[JorlanError, Unit]` but bodies call `.ignore` — return type should be `UIO[Unit]`.                                                         | `ConnectorManager.scala:25,28`                                                       | Change return type to `UIO[Unit]`; remove the now-redundant `.ignore` calls.                                                                                        |
| [x]    | P11-019    | Warning    | Architecture      | `FakeTelegramApiClient` lives in main production source tree instead of test scope.                                                                                               | `TelegramApiClient.scala:206–250`                                                    | Move to `telegram/src/test/scala/...` or `server/src/test/scala/...`.                                                                                               |
| [x]    | P11-020    | Warning    | Concurrency       | `pollLoop` uses naked Scala recursion instead of `ZIO.tailRecM` or `ZStream.unfoldZIO` — risks stack overflow under stack-based runtimes or on very long-running bots.            | `TelegramConnectorSkill.scala` (pollLoop)                                            | Rewrite using `ZStream.unfoldZIO` or `ZIO.tailRecM` for explicit tail-call safety.                                                                                  |
| [x]    | P11-021    | Warning    | Test Coverage     | `userByChannelIdentity` has zero integration coverage — the identity resolution path core to `MessageIngressImpl` is untested at the DB layer.                                    | `RepositorySpec.scala`                                                               | Add integration test for `userByChannelIdentity` covering: match by channel type + id, no match, unverified identity.                                               |
| [x]    | P11-022    | Warning    | Test Coverage     | `ConnectorManager` has zero unit tests — `startAll`, `stopAll`, error propagation, and the `empty` singleton are all uncovered.                                                   | `ConnectorManager.scala`                                                             | Add a `ConnectorManagerSpec` with at least: start/stop lifecycle, start failure propagation, and empty manager no-op.                                               |
| [x]    | P11-023    | Warning    | Test Coverage     | `resolveOrCreateSession` session-reuse invariant is untested — no test sends two messages with the same `chatRef` and asserts the same session is returned.                       | `MessageIngressSpec.scala`                                                           | Add a test: call `receive` twice with identical `chatRef`; assert the session count is 1 and both dispatches use the same `AgentSessionId`.                          |
| [x]    | P11-024    | Warning    | Test Coverage     | `ExplicitDeny` capability path is untested in `MessageIngressSpec` — only `DefaultDeny` is exercised.                                                                             | `MessageIngressSpec.scala`                                                           | Add a test where the capability evaluator returns `ExplicitDeny` and assert the message is dropped with an event log entry.                                          |
| [x]    | P11-025    | Warning    | Test Coverage     | `TelegramConnectorSkillSpec` only covers `send_message` — `send_photo` and `send_file` egress tools are untested.                                                                 | `TelegramConnectorSkillSpec.scala:92–101`                                            | Add test cases for `send_photo` (valid base64, invalid base64) and `send_file` (valid, invalid base64, missing filename).                                            |
| [x]    | P11-026    | Warning    | Test Coverage     | Event log writes in `MessageIngressImpl` are never asserted in any test — audit trail correctness is unverifiable.                                                                | `MessageIngressSpec.scala`                                                           | Inject a recording `EventLogRepository`; assert the expected event type and fields after each `receive` call.                                                       |
| [x]    | P11-027    | Warning    | Test Coverage     | `chatRef` column persistence is not integration-tested — `RepositorySpec` creates sessions without `chatRef`.                                                                     | `RepositorySpec.scala`                                                               | Add a test that creates a session with `chatRef`, reads it back, and asserts the field round-trips correctly.                                                        |
| [x]    | P11-028    | Warning    | Test Coverage     | `TelegramApiClientLive` error paths (Telegram `ok: false` response, JSON parse failure, base64 decode failure) are not covered.                                                   | `TelegramApiClient.scala`                                                            | Add unit tests with a fake HTTP layer returning `{"ok":false}`, malformed JSON, and invalid base64.                                                                 |
| [x]    | P11-029    | Warning    | Documentation     | Mini-design §4.2 describes a reply path (AgentRunner.subscribe → telegram.send_message) that does not exist in the implementation — misleading for future contributors.           | `doc/mini-designs/phase11-telegram-connector.md:103–104`                             | Update §4.2 to document the seam as deferred to Phase 12 `NotificationRouter`; remove the pseudocode that implies it is implemented.                                |
| [x]    | P11-030    | Warning    | Documentation     | Mini-design §3 and §4.2 state wrong module path (`model/src/.../service/`) — actual files are in `connector-api/src/.../connector/`.                                              | `doc/mini-designs/phase11-telegram-connector.md:43,83`                               | Update the module paths in §3 and §4.2 to reflect the actual `connector-api` module location.                                                                       |
| [x]    | P11-031    | Warning    | Documentation     | Mini-design §8 states migration number V023 — actual migration created is V024.                                                                                                   | `doc/mini-designs/phase11-telegram-connector.md:185`                                 | Correct §8 to reference V024 and note that V023 was used by a prior phase.                                                                                          |
| [x]    | P11-032    | Warning    | Documentation     | `MessageIngressImpl` class-level ScalaDoc claims `UnrecognizedIdentityPolicy` is applied — implementation ignores it entirely.                                                    | `MessageIngressImpl.scala:23`                                                        | Update the class doc to state the policy is parsed but not yet enforced; reference P11-004 as the tracking item.                                                    |
| [x]    | P11-033    | Suggestion | Code Quality      | Dead `agentRunner` constructor parameter — injected but never referenced, creating confusion about intent.                                                                        | `TelegramConnectorSkill.scala:74,206`                                                | Remove the parameter until the reply path (P11-001) is implemented; add a `// TODO P11-001:` comment at the removal site.                                           |
| [x]    | P11-034    | Suggestion | Code Quality      | `sendPhoto` and `sendDocument` share identical multipart body assembly (14 lines each) — duplicated verbatim.                                                                     | `TelegramApiClient.scala:141–188`                                                    | Extract a private `buildMultipartBody(chatId, data, filename, caption): Array[Byte]` helper.                                                                        |
| [x]    | P11-035    | Suggestion | Code Quality      | Success/error response check is repeated 3× across `sendMessage`, `sendPhoto`, `sendDocument`.                                                                                   | `TelegramApiClient.scala:136,162,186`                                                | Extract a private `checkSuccess(response: Response): IO[JorlanError, Unit]` helper.                                                                                 |
| [x]    | P11-036    | Suggestion | Code Quality      | Six identical decode-and-fallback calls for `ToolDescriptor` JSON schemas — verbose and error-prone if any schema changes.                                                        | `TelegramConnectorSkill.scala:87–116`                                                | Extract the schema strings as private `val`s at class / companion level.                                                                                            |
| [x]    | P11-037    | Suggestion | Code Quality      | `filterUpdates` uses verbose intermediate `val`s and `maxOption` on a mapped list where `maxByOption` is cleaner.                                                                 | `TelegramConnectorSkill.scala:176,179–194`                                           | Replace `updates.map(_.updateId).maxOption` with `updates.maxByOption(_.updateId).map(_.updateId)`; simplify intermediate bindings.                                 |
| [x]    | P11-038    | Suggestion | Code Quality      | `knownUserRepo` and `unknownUserRepo` fakes in `MessageIngressSpec` duplicate 7 of 8 methods — test boilerplate adds maintenance surface.                                         | `MessageIngressSpec.scala:40–99`                                                     | Extract a single `stubUserRepo(result: Option[User])` helper that returns the result and stubs all other methods as `ZIO.die`.                                      |
| [x]    | P11-039    | Suggestion | Code Quality      | Three test bodies in `MessageIngressSpec` repeat the same 5-line ingress setup preamble.                                                                                          | `MessageIngressSpec.scala:159–196`                                                   | Extract a `withIngress(config)(test)` helper or use a shared `ZLayer` in the test spec.                                                                             |
| [x]    | P11-040    | Suggestion | Code Quality      | `liveConnectorManagerLayer` uses a nested `match` inside `ZIO.foreach` — hard to read; idiomatic ZIO would use `ZIO.fromEither + foldZIO`.                                        | `EnvironmentBuilder.scala:87–99`                                                     | Rewrite using `ZIO.fromEither(parse(...)).foldZIO(err => ..., cfg => ...)`.                                                                                         |
| [x]    | P11-041    | Suggestion | Performance       | Multipart body built via three sequential `Array[Byte] ++` — a 5 MB photo allocates 10+ MB of transient heap.                                                                    | `TelegramApiClient.scala:152,176`                                                    | Replace with `ByteArrayOutputStream` and `write()` calls; avoids intermediate copies.                                                                               |
| [x]    | P11-042    | Suggestion | Code Quality      | Hardcoded `"ZioBoundary"` multipart boundary string is not RFC 2046-safe and is duplicated in two methods.                                                                        | `TelegramApiClient.scala:146,172`                                                    | Extract as a private constant; optionally generate a random boundary per request.                                                                                   |
| [x]    | P11-043    | Suggestion | Functional Purity | Unused `err` binding in first `catchAll` arm — `err` is bound but never referenced.                                                                                              | `TelegramApiClient.scala:116–119`                                                    | Replace `err =>` with `_ =>`.                                                                                                                                       |
| [x]    | P11-044    | Suggestion | Correctness       | `sys.env` reads at object initialization time in `TelegramManualTest` — environment variables set after class loading are invisible.                                              | `TelegramManualTest.scala:37–38`                                                     | Read `sys.env` inside the ZIO effect, not at object construction.                                                                                                   |
| [x]    | P11-045    | Suggestion | Error Handling    | `mapError(e => new RuntimeException(e.getMessage, null))` in `TelegramManualTest` loses the original cause.                                                                       | `TelegramManualTest.scala:89`                                                        | Replace `null` with the original exception: `new RuntimeException(e.getMessage, e)`.                                                                                |
| [x]    | P11-046    | Suggestion | Documentation     | `TelegramApiClient` trait methods (`getUpdates`, `sendMessage`, `sendPhoto`, `sendDocument`) lack `@param` / `@return` ScalaDoc.                                                  | `TelegramApiClient.scala:54–76`                                                      | Add method-level ScalaDoc for each trait method; document side-effect semantics and error conditions.                                                               |
| [x]    | P11-047    | Suggestion | Documentation     | `Skill.descriptor` and `Skill.invoke` lack method-level ScalaDoc; `SkillDescriptor` case class fields lack `@param`.                                                              | `Skill.scala:26,28–33,56–60`                                                         | Add ScalaDoc for both methods and all `SkillDescriptor` fields; document the contract for implementors.                                                             |
| [x]    | P11-048    | Suggestion | Documentation     | `ConnectorManager.empty` is undocumented — callers cannot tell when a no-op manager is appropriate.                                                                               | `ConnectorManager.scala:60`                                                          | Add a ScalaDoc comment explaining the intended use (testing, no-connector configuration).                                                                            |
| [x]    | P11-049    | Suggestion | Documentation     | `TelegramApiClientLive.make` and `TelegramConnectorSkill.make` factory methods are undocumented.                                                                                  | `TelegramApiClient.scala`, `TelegramConnectorSkill.scala`                            | Add `@param` / `@return` ScalaDoc to both factory methods.                                                                                                          |
| [x]    | P11-050    | Suggestion | Documentation     | CLAUDE.md module table omits the new `connector-api` module.                                                                                                                      | `CLAUDE.md`                                                                          | Add a row for `connector-api` describing its role as the shared connector/skill contract module.                                                                    |
| [x]    | P11-051    | Suggestion | Test Coverage     | `TelegramMessageNormalizer` unknown chat type fallback path is untested.                                                                                                           | `TelegramMessageNormalizerSpec.scala`                                                | Add a test with an unrecognized `chat.type` string and assert the fallback `ChatKind` is applied.                                                                   |
| [x]    | P11-052    | Suggestion | Test Coverage     | `filterUpdates` allowlist filtering is never exercised — both `allowedChatIds` and `allowedUserIds` are `Set.empty` in all current tests.                                         | `TelegramConnectorSkillSpec.scala`                                                   | Add tests with non-empty allowlists asserting that non-matching updates are dropped.                                                                                 |
| [x]    | P11-053    | Suggestion | Test Coverage     | `pollLoop` offset advancement is only implicitly verified — no test directly asserts the next poll uses `offset = lastUpdateId + 1`.                                               | `TelegramConnectorSkillSpec.scala`                                                   | Instrument the fake `TelegramApiClient` to record received offset arguments; assert the value after first batch delivery.                                            |
| [x]    | P11-054    | Suggestion | Correctness       | Stale `ZStream` import in `TelegramConnectorSkill.scala:22` — unused since `pollLoop` uses recursion rather than `ZStream`.                                                       | `TelegramConnectorSkill.scala:22`                                                    | Remove the unused import; if P11-020 (ZStream refactor) is adopted the import will be needed again.                                                                 |
| [x]    | P11-055    | Suggestion | Test Coverage     | `TelegramConnectorSkillSpec` uses `withLiveClock` with fixed wall-clock sleeps — racy on slow CI where timing is non-deterministic.                                               | `TelegramConnectorSkillSpec.scala`                                                   | Replace sleep-based synchronization with a `Promise`-based handshake: have the fake client complete a `Promise` on first call, and `await` in the test.             |

---

## Grouped Sections

### Correctness / Unimplemented Spec Deliverables

**Reply path not implemented — roadmap checkbox incorrectly marked complete** (P11-001) — CONFIRMED BY 4 REVIEWERS

The mini-design §4.2 specifies that `MessageIngressImpl` subscribes to the agent's session stream via `AgentRunner.subscribe` and forwards the completed response to `telegram.send_message` for the originating `chatRef`. The roadmap item "NotificationRouter outbound" is checked `[x]`. In the actual implementation, `agentRunner` is injected as a constructor parameter of `TelegramConnectorSkill` but is never referenced anywhere in the class body (lines 74, 206). The `invoke` egress tools are implemented, but there is no code path that reads agent replies and sends them back to the Telegram user.

This is the highest-visibility gap for end-to-end usability: messages arrive and trigger agent dispatch, but replies never reach the user. The roadmap checkbox must be unchecked until the path is implemented, or the design decision to defer the reply path to the Phase 12 `NotificationRouter` must be documented explicitly.

**`UnrecognizedIdentityPolicy` entirely ignored** (P11-004) — CONFIRMED BY 4 REVIEWERS

`TelegramConfig.unrecognizedPolicy` is parsed from JSON and stored, but `MessageIngressImpl.receive` never reads it. Both `Reject` and `Quarantine` cases in the `UnrecognizedIdentityPolicy` enum produce the same runtime behavior: the message is silently dropped. The SRS/SDD Conformance Reviewer also flagged that the roadmap item for "unrecognized policy" is checked despite this being a no-op.

**`resolveOrCreateSession` in-memory chatRef scan — correctness and data integrity** (P11-002) — CONFIRMED BY 5 REVIEWERS

`MessageIngressImpl.resolveOrCreateSession` calls `searchSessions` with no `chatRef` filter, then uses `.find(_.chatRef.contains(...))` in Scala to locate the matching session. The V024 migration adds `idx_agent_session_chat_ref` precisely for this query, but the index is never used because the filter never reaches the DB. The method also has a secondary correctness gap noted by the Pattern Recognition Specialist: there is no `channelType` scoping, meaning a Telegram `chatRef` of `"12345"` could match a Slack session with the same ID, binding the wrong session.

For users with exactly 100 or fewer sessions the current code happens to work. For users exceeding that page cap, a duplicate session is created on every message after the cap, fragmenting conversation history.

```scala
// Recommended fix: extend AgentSessionSearch
case class AgentSessionSearch(
  ...
  chatRef: Option[String] = None,
  channelType: Option[ChannelType] = None,
)

// In resolveOrCreateSession:
searchSessions(AgentSessionSearch(
  userId = Some(userId),
  chatRef = Some(msg.chatRef),
  channelType = Some(msg.channelType),
  pageSize = 1,
))
```

**Unsafe `.get` and missing required-field validation in `invoke`** (P11-003, P11-006) — CONFIRMED BY 3 REVIEWERS

`TelegramConnectorSkill.invoke` calls `.asObject.get` on the parsed `JsonObject` result, which throws `NoSuchElementException` at runtime if the input is not a valid JSON object (any tool call arriving with a scalar or array body will crash the fiber). Additionally, the `chatId` and `text` fields extracted from the object fall back silently to `""` when absent, producing Telegram API errors with no indication of what went wrong at the application level.

**`Base64.getDecoder.decode` outside ZIO** (P11-005) — CONFIRMED BY 2 REVIEWERS

`invoke` calls `java.util.Base64.getDecoder.decode(...)` directly. This method throws `IllegalArgumentException` for any input that is not valid base64. Because the call is not wrapped in `ZIO.attempt`, the exception escapes the ZIO error channel and propagates as a defect, bypassing any error logging or caller error handling.

```scala
// Fix:
ZIO.attempt(Base64.getDecoder.decode(photoB64))
  .mapError(e => JorlanError.UserError(s"Invalid base64 for photo: ${e.getMessage}"))
```

---

### Resource Management

**Telegram polling fibers leak on shutdown** (P11-007) — CONFIRMED BY 2 REVIEWERS

`ConnectorManager.startAll` is called from `Jorlan.run`, forking each connector's polling loop as a fiber. `ConnectorManager.stopAll` exists and calls `connector.stop()` for each registered connector, which interrupts the polling fiber. However, `stopAll` is never registered as a shutdown action anywhere in `Jorlan.scala` — neither via `ZIO.addFinalizer` nor via a `Scope`-based lifecycle. On SIGTERM, the process exits with all polling fibers still running, and — since `start()` called twice orphans the first fiber (UI Test Plan TC-029) — repeated restarts can compound the leak.

Register `connectorManager.stopAll` as a finalizer in the same `Scope` that calls `startAll`:

```scala
ZIO.acquireRelease(connectorManager.startAll)(_ => connectorManager.stopAll)
```

**`startAll` duplicated in both `Jorlan.run` branches** (P11-015)

Both the interactive and non-interactive startup branches in `Jorlan.scala` call `connectorManager.startAll` independently. This duplication means any change to the startup sequence (adding a finalizer, logging, ordering) must be applied in two places. Extract into a single `startServices` effect called before the branch point.

---

### Architecture / Layer Discipline

**Telegram module imports server-layer `AgentRunner`** (P11-008) — CONFIRMED BY 2 REVIEWERS

`TelegramConnectorSkill.scala` imports `jorlan.service.AgentRunner`, which is defined in the `server` module. The `telegram` module is intended to be a separate plugin that the server loads — it should depend only on `connector-api` (and `model`), not on server internals. This creates a circular/upward dependency and prevents the `telegram` module from being packaged independently.

The fix is to move the `AgentRunner` interface (or a `MessageDispatcher` subset of it) into `connector-api`, and have the server's `AgentRunnerImpl` implement it. The `TelegramConnectorSkill` then depends only on the interface.

**`FakeTelegramApiClient` in production source tree** (P11-019)

The fake implementation used for tests is compiled into the main production artifact. Move it to `telegram/src/test/scala/` or tag it with `@testOnly`.

---

### Observability / Audit Trail

**Event log session reference missing** (P11-016)

`MessageIngressImpl.logInboundEvent` is called with `sessionId = None` even when the session has been successfully resolved earlier in the same for-comprehension. The event log entry for every inbound Telegram message therefore lacks the session reference, making it impossible to correlate an audit log entry with a conversation thread.

The fix is straightforward: capture the resolved `sessionId` before the dispatch call and pass it to `logInboundEvent`.

---

### Functional Purity

**`Instant.now()` in `normalizeMessage`** (P11-010) — CONFIRMED BY 5 REVIEWERS

`TelegramConnectorSkill.normalizeMessage` (the `TelegramMessageNormalizer` function) calls `java.time.Instant.now()` directly. This is a side effect inside what should be a pure transformation function. It also bypasses ZIO's `Clock` service, making the normalizer non-deterministic in tests even when `TestClock` is provided. The fix is to thread `receivedAt: Instant` as a parameter, obtained once from `Clock.instant` in the ZIO caller before the normalizer is invoked.

---

### Test Coverage

**Critical gaps — must add before Phase 12**

| Missing Test (ID) | What Is Undetectable Without It |
|---|---|
| `userByChannelIdentity` integration (P11-021) | Identity resolution bugs at the DB layer — the core path of the entire ingress pipeline |
| `ConnectorManager` unit tests (P11-022) | Start/stop lifecycle, error propagation, fiber management |
| `resolveOrCreateSession` reuse invariant (P11-023) | Duplicate session creation for users with > 100 sessions |
| `ExplicitDeny` capability path (P11-024) | Explicit deny never tested; only default deny is verified |
| `send_photo` and `send_file` egress tools (P11-025) | Two of three declared egress tools have no test coverage |
| Event log assertions (P11-026) | Audit trail completeness is entirely unverified |
| `chatRef` column persistence (P11-027) | V024 migration correctness unverifiable from tests |
| `TelegramApiClientLive` error paths (P11-028) | HTTP error, `ok:false`, JSON parse failure all undetected |

**Test quality issues**

`TelegramConnectorSkillSpec` uses `TestAspect.withLiveClock` with fixed sleep durations (P11-055). On heavily-loaded CI agents, wall-clock sleeps are unreliable — a slow machine may not complete the poll loop within the sleep window. Replace with `Promise`-based synchronization: the fake `TelegramApiClient` fulfills a `Promise` on its first `getUpdates` call; the test `await`s the promise instead of sleeping.

`filterUpdates` allowlist paths are never exercised (P11-052). All current tests pass `Set.empty` for both `allowedChatIds` and `allowedUserIds`, meaning the filtering logic could be entirely removed without any test failing.

---

### Code Quality

**Duplicated multipart body assembly** (P11-034, P11-035)

`sendPhoto` and `sendDocument` in `TelegramApiClient` share 14 nearly-identical lines building a multipart/form-data body. The success/error check pattern is also repeated three times. Extract a `buildMultipartBody` private helper and a `checkSuccess` private helper; this also enables fixing the `ByteArrayOutputStream` allocation issue (P11-041) in a single place.

**Verbose test setup** (P11-038, P11-039)

`MessageIngressSpec` contains two `UserZIORepository` anonymous implementations (`knownUserRepo` and `unknownUserRepo`) that differ only in the return value of `userByChannelIdentity` — 7 of 8 methods are identical stubs. Three test bodies also repeat a 5-line setup preamble. Extracting `stubUserRepo(result)` and a shared `withIngress` helper would reduce the spec size by ~40 lines and make it easier to add new test cases for P11-023 and P11-024.

**`liveConnectorManagerLayer` nested match** (P11-040)

`EnvironmentBuilder.liveConnectorManagerLayer` contains a `match` nested inside `ZIO.foreach`. The idiomatic rewrite uses `ZIO.fromEither(parse(json)).foldZIO(...)`, which makes the error path explicit and avoids the deep nesting.

---

## Open Design Questions

**OD-1: Reply egress path ownership.** The mini-design §4.2 places the reply path inside `MessageIngressImpl` (subscribe to session stream → call `telegram.send_message`). An alternative is to put this in the Phase 12 `NotificationRouter`, which would have a registry of `ConnectorSkill`s and route outbound messages by channel type. The pattern Recognition Specialist and SRS/SDD Conformance Reviewer both flagged this ambiguity. A decision is needed before starting Phase 12 work on the notification path. The current implementation makes no choice — neither path exists — so Phase 12 starts from a clean slate.

**OD-2: `UnrecognizedIdentityPolicy` enforcement scope.** The policy is currently parsed from `TelegramConfig` (connector-specific) but would need to be enforced inside `MessageIngressImpl` (connector-agnostic). The SRS/SDD Conformance Reviewer notes that it is ambiguous whether policy should be a per-connector configuration or a global ingress configuration. If it is connector-specific, `MessageIngress` needs to accept the policy as a parameter (breaking the connector-agnostic contract). If it is global, `TelegramConfig.unrecognizedPolicy` should be removed. This should be resolved when implementing P11-004.

---

## Cross-Cutting Patterns

**The reply path gap** is the single largest correctness omission in Phase 11 and was independently flagged by the Performance Oracle, Functional Scala Reviewer, Pattern Recognition Specialist, and SRS/SDD Conformance Reviewer (P11-001). The root cause is that `TelegramConnectorSkill` was built with `agentRunner` as a constructor parameter intended for the reply path, but the implementation of the subscribe-and-forward loop was never added. The roadmap checkbox being marked complete suggests the gap was not caught in self-review. This pattern — injecting a dependency for a feature that was not yet implemented — should be caught by requiring that every injected dependency has at least one call site.

**`Instant.now()` impurity** was flagged by all five content-reviewing agents (Performance Oracle, Code Simplicity, Functional Scala, Pattern Recognition, SRS/SDD Conformance) making it the most-confirmed finding in the phase review (P11-010). The root cause is that the `normalizeMessage` function was designed as a pure transformation but was given an implicitly-side-effecting timestamp. The project convention (established in Phase 8 with `Clock.instant`) was not followed.

**Unimplemented features stored as active configuration** appears across three findings (P11-001 reply path, P11-004 `UnrecognizedIdentityPolicy`, P11-011 `useWebhook`) and echoes the Phase 10 pattern with `MissedRunPolicy`. In all three cases, a feature is represented in the type system and is parsed/stored, but the code that would consult it does not exist. The safer convention — adopted in Phase 10 for some items — is to add a `// TODO PhaseN:` comment and either reject the config value with a `NotImplemented` error or gate the parsing behind a feature flag, so callers are not misled by apparently working configuration.

**`resolveOrCreateSession` has three independent problems** (P11-002): the in-memory filter that defeats the V024 index (Performance Oracle, 5 confirmations); the missing `channelType` scoping that enables cross-channel session collision (Pattern Recognition Specialist); and the lack of a session-reuse integration test (Test Coverage Tracker). Each finding was raised independently and from a different angle, but they all describe the same function. This function is the state management core of the entire ingress pipeline and deserves careful attention.

**Test infrastructure gaps** are concentrated in three areas that collectively reduce confidence in the most important new code: `userByChannelIdentity` (identity resolution, P11-021), `resolveOrCreateSession` reuse (session management, P11-023), and the event log (audit trail, P11-026). All three were flagged by the Test Coverage Tracker and corroborated by the SRS/SDD Conformance Reviewer. These three gaps mean the happy-path integration flow has no test coverage at its most critical decision points.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count |
|------------|-------|
| Critical   | 7     |
| Warning    | 26    |
| Suggestion | 22    |
| **Total**  | **55** |

**Issues by area:**

| Area                | Count |
|---------------------|-------|
| Correctness         | 10    |
| Test Coverage       | 13    |
| Code Quality        | 8     |
| Documentation       | 7     |
| Architecture        | 3     |
| Concurrency         | 3     |
| Resource Management | 2     |
| Observability       | 2     |
| Performance         | 2     |
| Functional Purity   | 1     |
| Error Handling      | 2     |
| Data Integrity      | 1     |
| Package Coherence   | 1     |
| **Total**           | **55** |

**Agent contribution:**

| Agent                        | Unique Findings | Cross-Confirmed |
|------------------------------|-----------------|-----------------|
| Performance Oracle           | 6               | 5               |
| Code Simplicity Reviewer     | 12              | 8               |
| Functional Scala Reviewer    | 13              | 10              |
| Pattern Recognition Spec.    | 13              | 11              |
| ScalaDoc Auditor             | 11              | 3               |
| Test Coverage Tracker        | 12              | 6               |
| SRS/SDD Conformance Rev.     | 11              | 9               |
| UI Test Plan                 | 72 test cases   | (cross-validation) |

**Phase 11 scope completion:**

| Item                                                        | Status |
|-------------------------------------------------------------|--------|
| `connector-api` module with `Skill` / `ConnectorSkill` traits | ✅   |
| `SkillRecord` rename (pre-work)                             | ✅     |
| `InboundMessage`, `ChatKind`, `UnrecognizedIdentityPolicy` types | ✅ |
| `MessageIngress` trait + `MessageIngressImpl`               | ✅     |
| `TelegramApiClient` trait + `TelegramApiClientLive`         | ✅     |
| `TelegramMessageNormalizer`                                 | ✅     |
| `TelegramConnectorSkill` (start/stop lifecycle + egress invoke) | ✅ |
| `ConnectorManager` (startAll / stopAll)                     | ✅     |
| V024 migration (`chatRef` column + index)                   | ✅     |
| `EnvironmentBuilder` wiring for connector pipeline          | ✅     |
| `TelegramConnectorSkillSpec` and `MessageIngressSpec`       | ✅     |
| Reply path (AgentRunner.subscribe → telegram.send_message)  | ❌     |
| `UnrecognizedIdentityPolicy` enforcement                    | ❌     |
| `resolveOrCreateSession` correct DB-layer chatRef filter    | ❌     |
| `stopAll` registered as shutdown finalizer                  | ❌     |
| `userByChannelIdentity` integration test coverage           | ❌     |

---

## What Was Done Well

**`connector-api` module boundary**: The decision to place `Skill`, `ConnectorSkill`, `MessageIngress`, and the ingress domain types in a dedicated `connector-api` module — separate from both `model` and `server` — is a sound architectural choice. It creates a clear seam that Phase 12 connectors (Slack, email) can implement without depending on server internals. The trait hierarchy (`Skill` → `ConnectorSkill`) cleanly separates stateless tool invocation from stateful connector lifecycle.

**`TelegramApiClient` trait with injectable fake**: Hiding the live Telegram HTTP client behind a trait and providing a `FakeTelegramApiClient` (even in the wrong location currently) means tests never need a live bot token. This follows the project's established pattern for external service gateways and is the correct approach for CI. Once the fake is moved to the test scope (P11-019) this will be entirely clean.

**V024 migration design**: Adding `chatRef VARCHAR(128)` with `idx_agent_session_chat_ref` to the `agentSession` table is the right schema change for durable Telegram session binding. The index exists and would perform well — the only gap is that the application code does not yet use it (P11-002). The migration itself is well-structured.

**Identity resolution pipeline structure**: The layered pipeline in `MessageIngressImpl` — identity resolution → capability gate → session resolve → dispatch → event log — is a clean and extensible design. Each step is clearly separable and the sequence matches the SRS specification. Once the implementation gaps (P11-002, P11-004, P11-016) are addressed, this will be a solid foundation for all future connectors.
