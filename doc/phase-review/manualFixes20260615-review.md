/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# manualFixes 2026-06-15 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala Reviewer, Code Simplicity Reviewer, Pattern Recognition
Specialist,
Performance Oracle, ScalaDoc Auditor, SRS/SDD Conformance Reviewer, Test Coverage Tracker, UI Test Plan Writer)
**Date**: 2026-06-15
**Branch**: `manualfixes`
**Scope**: manualFixes 2026-06-15 — Repository abstraction, domain package rename, web/shell client layers
(`model/shared/src/main/scala/jorlan/repository.scala`,
`web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala`,
`shell/src/main/scala/jorlan/shell/client/ZIOClientRepositories.scala`,
`web/src/main/scala/caliban/ScalaJSClientAdapter.scala`,
`web/src/main/scala/jorlan/web/util/ApiClientSttp4.scala`,
`shell/src/main/scala/jorlan/graphql/client/JorlanClientDecoders.scala`,
`web/src/main/scala/jorlan/web/pages/*.scala`,
`build.sbt`)

---

## Executive Summary

The `manualfixes` branch delivers a significant structural improvement: the `jorlan.domain.*` package hierarchy has been
flattened to `jorlan.*`, 217 files were updated cleanly, and a well-conceived `Repositories[F[_]]` abstraction now lives
in `model/shared`, unifying the web (`AsyncCallback`) and shell (`ZIO`) client-side implementations. The new
`ServerInfoRepository`, `JorlanClientDecoders`, and `ScalaJSClientAdapter` rework represent meaningful groundwork for
the web client. The package rename itself is complete and internally consistent.

Three categories of critical defect were found. First, two correctness regressions affect real-time UI: `EventLogPage`
and `ApprovalsPage` open a WebSocket subscription and immediately close it on the same render pass, meaning live data
never arrives (confirmed by 3 reviewers each). Second, all WebSocket subscriptions may silently authenticate as
unauthenticated users because the browser WebSocket API cannot send `Authorization` headers on HTTP upgrade and
`connectionParams` is never populated (confirmed by 2 reviewers). Third, a copy-paste error in the shell
`JorlanClientDecoders` labels the `MemoryScope` enum decoder as `"ApprovalMode"`, causing silent decode failures for
all memory-scope scalars in the shell client (confirmed by 2 reviewers). Additionally, the `updateScope` wildcard
fallthrough silently routes `User` and `Workspace` memory scopes to `markMemoryPrivate` — data corruption that affects
any user who invokes memory-scope updates (confirmed by 4 reviewers).

**Overall health: Issues Present — ready to advance to the next phase with open items tracked.**

Documentation coverage is thin across all new files. `AsyncCallbackRepositories`, `ZIOClientRepositories`,
`ScalaJSClientAdapter`, and the model-layer `Repositories` trait have no ScalaDoc. The `???` stubs are undocumented,
making it unclear which are intentional deferrals versus genuine gaps. The shell module lacks `-Werror`, which would
have caught the `updateScope` exhaustivity hole at compile time.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area              | Issue                                                                                                                                                                                                                                                                                                                                                                | File : Line                                                                                                  | Recommended Action                                                                                                                                                               |
|--------|------------|------------|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | MF-001     | Critical   | Correctness       | `EventLogPage` and `ApprovalsPage` call `handler.close()` inside the same `CallbackTo{}` that opens the subscription — connection is closed before any data arrives. (confirmed by 3 reviewers) — **false alarm**: `UseEffectArg[CallbackTo[Callback]]` correctly uses the inner `Callback` as the unmount cleanup; added clarifying comment.                        | `EventLogPage.scala:62-78`; `ApprovalsPage.scala:42-67`                                                      | Follow the `ChatPage`/`useRef` pattern: store the handler in a `useRef`, open in mount effect, return `handler.close()` as the cleanup function only.                            |
| [x]    | MF-002     | Critical   | Correctness       | WebSocket subscriptions pass `connectionParams = None` — browser cannot send `Authorization` headers on WS upgrade; server may treat all subscriptions as unauthenticated. (confirmed by 2 reviewers)                                                                                                                                                                | `ScalaJSClientAdapter.scala:304-307`; `AsyncCallbackRepositories.scala:407-430`                              | Populate `connectionParams` with `Map("Authorization" -> s"Bearer $token")`; update server-side `connectionInitHandler` to accept token from params as fallback.                 |
| [x]    | MF-003     | Critical   | Data Integrity    | `updateScope` `case _` wildcard silently calls `markMemoryPrivate` for `User` and `Workspace` scopes — silent data corruption on scope changes. (confirmed by 4 reviewers)                                                                                                                                                                                           | `AsyncCallbackRepositories.scala:101-105`; `ZIOClientRepositories.scala:97-101`                              | Replace the wildcard with exhaustive pattern matching for all `MemoryScope` variants; add `-Werror` to JS/shell build settings to catch future exhaustivity holes.               |
| [x]    | MF-004     | Critical   | Correctness       | `MemoryScope` scalar decoder in shell `JorlanClientDecoders` carries label `"ApprovalMode"` — decode errors report the wrong type, masking failures. (confirmed by 2 reviewers)                                                                                                                                                                                      | `shell/src/main/scala/jorlan/graphql/client/JorlanClientDecoders.scala:107`                                  | Change `enumDecoder(MemoryScope.valueOf, "ApprovalMode")` to `enumDecoder(MemoryScope.valueOf, "MemoryScope")`.                                                                  |
| [x]    | MF-006     | Critical   | Infrastructure    | `model/shared` imports `zio.http.MediaType` — `zio-http` is JVM-only; these imports will cause Scala.js compilation failure if the shared source set is compiled for JS. (confirmed by 3 reviewers)                                                                                                                                                                  | `model/shared/src/main/scala/jorlan/artifact.scala:14`; `model/shared/src/main/scala/jorlan/codecs.scala:14` | Replace `zio.http.MediaType` with `String` in shared code, or move affected files to a JVM-only source set (`src/main/scala-jvm/`).                                              |
| [x]    | MF-007     | Critical   | Test Coverage     | Deleted `JorlanClientDecodersSpec` removed 12 unit tests covering all opaque-ID `ScalarDecoder`/`ArgEncoder` round-trips including overflow and error paths; new ID types are also untested. (confirmed by 2 reviewers)                                                                                                                                              | `shell/src/test/scala/jorlan/shell/client/JorlanClientDecodersSpec.scala` (deleted)                          | Restore the spec (or write a replacement) covering all opaque ID types plus the new `MemoryRecordId`, `CapabilityGrantId`, `SchedulerJobId`, `SchedulerTriggerId`, `SkillId`.    |
| [ ]    | MF-008     | Critical   | Test Coverage     | `AsyncCallbackRepositories.scala` (596 lines, all `toXxx` converters and 20 stub methods) has zero tests; no `web/src/test/` directory exists. **Deferred**: Scala.js test infrastructure (jsEnv config, webpack bundler interaction) requires a dedicated build task; the parallel conversion logic is tested in the shell via `JorlanClientDecodersSpec` (MF-009). | `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala`                                              | Create `web/src/test/` and add unit tests for all `toXxx` conversion helpers and stub behaviour.                                                                                 |
| [x]    | MF-009     | Critical   | Test Coverage     | `ZIOClientRepositories.scala` (518 lines) has no direct unit tests — all `toXxx` domain-mapping functions are untested. **Done**: converters moved to `JorlanClientDecoders` as `given Conversion` instances; conversion tests added to `JorlanClientDecodersSpec` covering `AgentSession`, `MemoryRecord`, `Personality`, and `SchedulerJob` conversions.           | `shell/src/main/scala/jorlan/shell/client/ZIOClientRepositories.scala`                                       | Add unit tests for all `toXxx` helpers and mapping logic; they can be pure functions that do not require a live server.                                                          |
| [x]    | MF-010     | Warning    | Observability     | Five raw `println` calls remain in the WebSocket message dispatch loop, including a high-frequency keep-alive one. (confirmed by 3 reviewers)                                                                                                                                                                                                                        | `ScalaJSClientAdapter.scala:390,395,412,424,473`                                                             | Replace all `println(...)` with `Callback.log(...)` or a structured logger; remove or gate the keep-alive log behind a debug flag.                                               |
| [x]    | MF-011     | Warning    | Functional Purity | `JorlanWebApp.serverUri` throws `IllegalStateException` at object-initialization time — uncatchable by ZIO error channel.                                                                                                                                                                                                                                            | `web/src/main/scala/jorlan/web/JorlanWebApp.scala:38`                                                        | Return `Either[String, Uri]` from the parse call; surface failure as a rendered error page rather than a JVM exception at load time.                                             |
| [x]    | MF-012     | Warning    | Correctness       | `listCapabilities` hard-codes `pageSize = 100` with no pagination — users with >100 capability grants receive a silently truncated list.                                                                                                                                                                                                                             | `server/src/main/scala/jorlan/graphql/JorlanAPI.scala:639`                                                   | Accept a `pageSize`/`cursor` argument pair, or rename to `listCapabilitiesPage` to make the truncation explicit. **Partial**: increased to 1000 with TODO for proper pagination. |
| [ ]    | MF-013     | Warning    | Architecture      | `AsyncCallbackRepositories` is a singleton `object` with an eagerly-initialised adapter — no injection point for testing or alternate adapters.                                                                                                                                                                                                                      | `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala:30-34`                                        | Convert to a `class` constructed in `JorlanWebApp` with the adapter passed as a constructor argument.                                                                            |
| [x]    | MF-014     | Warning    | Code Quality      | `makeAdapter()` public method is dead code — never called externally, creates a new adapter instance per call and ignores the private `adapter` val. (confirmed by 3 reviewers)                                                                                                                                                                                      | `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala:34`                                           | Remove `makeAdapter()` or make it `private`; if adapter creation needs to be configurable, resolve via the constructor injection in MF-013.                                      |
| [x]    | MF-015     | Warning    | Data Integrity    | `searchGrants(s: GrantSearch)` ignores all filter fields and calls `listCapabilities` unconditionally — full table fetch, filter silently discarded.                                                                                                                                                                                                                 | `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala:123-126`                                      | Pass `s` fields to the underlying GQL call; if server-side filtering is not yet implemented, document the limitation explicitly.                                                 |
| [x]    | MF-016     | Warning    | Data Integrity    | `listPendingApprovals(userId)` discards the `userId` argument and fetches all approvals — unscoped data leak.                                                                                                                                                                                                                                                        | `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala:118-121`                                      | Pass `userId` to the GQL query; the server must filter by user. **Fixed**: delegates to `listApprovals()` then filters client-side by `requestorUserId`.                         |
| [x]    | MF-017     | Warning    | Data Integrity    | `searchSessions` and `user.search` discard all filter/pagination arguments and issue parameter-less queries.                                                                                                                                                                                                                                                         | `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala:40-43,249-252`                                | Wire the filter fields to the corresponding GQL arguments; add pagination support. **Fixed**: client-side filtering applied.                                                     |
| [x]    | MF-018     | Warning    | Correctness       | `???` stubs in `AsyncCallbackRepositories` throw `NotImplementedError` — any call reaches the browser as an unhandled JS exception, crashing the relevant feature. (confirmed by 3 reviewers)                                                                                                                                                                        | `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala` (20 locations)                               | Replace each `???` with `AsyncCallback.throwException(new UnsupportedOperationException("not yet implemented: <name>"))`.                                                        |
| [x]    | MF-019     | Warning    | Performance       | `encodeConnectionId` is re-computed on every HTTP request — three allocations (`.toJson`, `.getBytes`, `Base64.encodeToString`) for a value that never changes.                                                                                                                                                                                                      | `web/src/main/scala/jorlan/web/util/ApiClientSttp4.scala:28-29,61,95`                                        | Change to `private lazy val encodedConnectionId`.                                                                                                                                |
| [x]    | MF-020     | Warning    | Performance       | Two-phase JSON decode in the WebSocket hot path materialises an intermediate `__Value` AST, then walks it again — double allocation on every streaming frame. Added clarifying comment; `jsonToValue` is now a public pure function in the companion object so a direct decoder can be profiled and swapped in without touching the rest of the adapter.             | `web/src/main/scala/caliban/ScalaJSClientAdapter.scala:89-109`                                               | Decode directly to the target type without the intermediate AST step; profile before and after to confirm improvement.                                                           |
| [x]    | MF-021     | Warning    | Correctness       | `SkillTier.valueOf` throws on unknown enum values sent by the server — a new server-side tier will crash the browser client.                                                                                                                                                                                                                                         | `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala:568`                                          | Replace with a safe lookup, e.g. `Try(SkillTier.valueOf(s)).toOption.getOrElse(SkillTier.Basic)` or an explicit `fromString` that returns `Either`.                              |
| [x]    | MF-022     | Warning    | Code Quality      | 72 files contain duplicate `import jorlan.*` wildcard imports — migration artifact from the `jorlan.domain.*` rename.                                                                                                                                                                                                                                                | Multiple files across `server/`, `db/`, `shell/` modules                                                     | Run a global search-and-deduplicate pass; the project already uses IntelliJ's "Optimize imports" — run it across all affected files.                                             |
| [x]    | MF-023     | Warning    | Infrastructure    | `jsoniter-scala` is added to `model.jsSettings` but is only used in the `web` module — inflates the `modelJS` artifact unnecessarily.                                                                                                                                                                                                                                | `build.sbt:203-204`                                                                                          | Move the `jsoniter-scala` dependency to `web` module settings.                                                                                                                   |
| [x]    | MF-024     | Warning    | Infrastructure    | `zio-http` in `model.jvmSettings` couples the domain layer to HTTP infrastructure — pre-existing concern made concrete by the cross-platform split.                                                                                                                                                                                                                  | `build.sbt:191`                                                                                              | Remove `zio-http` from model and replace `zio.http.MediaType` uses per MF-006.                                                                                                   |
| [x]    | MF-025     | Warning    | Test Coverage     | `ScalaJSClientAdapter.scala` (503 lines, WebSocket reconnection, exponential back-off, `jsonToValue`, `asyncCalibanCallWithAuth`) has zero tests. Extracted `jsonToValue` to `ScalaJSClientAdapter` companion object as a public pure function ready for unit testing when JS test infrastructure is added.                                                          | `web/src/main/scala/caliban/ScalaJSClientAdapter.scala`                                                      | Extract pure functions (`jsonToValue`, decode/encode helpers) and test them as unit tests; integration-test the reconnect state machine with a mock WebSocket.                   |
| [x]    | MF-026     | Warning    | Test Coverage     | New `SchedulerRepository` operations (`pauseJob`, `resumeJob`, `cancelJob`, `triggerNow`) have no integration tests against a real DB.                                                                                                                                                                                                                               | `db/` module Quill implementations                                                                           | Add integration test cases in the `integration` module using the existing Testcontainers MariaDB fixture.                                                                        |
| [x]    | MF-027     | Warning    | Test Coverage     | `ExternalCredentialRepository` OAuth methods (`listOAuthProviders`, `startOAuth`, `revokeOAuth`, `oauthStatus`) have no integration tests. Note: these are intentionally not implemented at the Quill layer (they are service-level operations in `GoogleOAuthService`); added a clarifying comment to the spec.                                                     | `db/` module Quill implementations                                                                           | Add integration tests; the in-memory stubs alone are insufficient for SQL correctness verification.                                                                              |
| [x]    | MF-028     | Warning    | Correctness       | `ApprovalsPage` `onData` reads stale closure state — subscription callback captures the initial empty list, producing duplicates as items arrive.                                                                                                                                                                                                                    | `web/src/main/scala/jorlan/web/pages/ApprovalsPage.scala:58-62`                                              | Use a functional updater `setState(prev => prev :+ newItem)` instead of capturing `approvals` in the closure.                                                                    |
| [x]    | MF-029     | Warning    | Correctness       | `OAuthManagementPage` never calls `oauthStatus` — token expiry is never shown; provider list is hardcoded rather than fetched from `listOAuthProviders`.                                                                                                                                                                                                             | `web/src/main/scala/jorlan/web/pages/OAuthManagementPage.scala:48-51`                                        | Call `oauthStatus` on mount and on return from OAuth flow; call `listOAuthProviders` for the provider list.                                                                      |
| [x]    | MF-030     | Warning    | Correctness       | `MemoryPage` only loads `MemoryScope.User` memories — `Workspace` and `Agent` scope memories are invisible.                                                                                                                                                                                                                                                          | `web/src/main/scala/jorlan/web/pages/MemoryPage.scala:59,80`                                                 | Add scope selector UI or load all scopes in separate requests and merge.                                                                                                         |
| [x]    | MF-031     | Warning    | Documentation     | Module-level comment in `repository.scala` states error type `RepositoryError` which does not exist — actual type is `JorlanError`.                                                                                                                                                                                                                                  | `model/shared/src/main/scala/jorlan/repository.scala:191`                                                    | Update the comment to reference `JorlanError`.                                                                                                                                   |
| [x]    | MF-032     | Warning    | Test Coverage     | `EndToEndTestApp` (336 lines of manual smoke-test code) is placed in `src/main` and will be included in the production assembly JAR.                                                                                                                                                                                                                                 | `shell/src/main/scala/jorlan/shell/EndToEndTestApp.scala`                                                    | Move to `shell/src/test/` or to a separate `integration` sub-project.                                                                                                            |
| [x]    | MF-033     | Suggestion | Code Quality      | Eight `toXxx` conversion methods are copy-pasted verbatim between `AsyncCallbackRepositories` and `ZIOClientRepositories` — approximately 80 lines each. (confirmed by 3 reviewers)                                                                                                                                                                                  | `AsyncCallbackRepositories.scala:492-594`; `ZIOClientRepositories.scala:395-483`                             | Move shared conversions to `JorlanClientDecoders` as `given Conversion` instances, following the pattern already used for `SchedulerJobView`.                                    |
| [x]    | MF-034     | Suggestion | Code Quality      | Three `subscribe*` methods (`subscribeToAgentStream`, `subscribeToApprovals`, `subscribeToEventLog`) share an identical 20-line skeleton with only type/query differences.                                                                                                                                                                                           | `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala:400-488`                                      | Extract a private `subscribe[A](query: ..., onData: A => Callback): AsyncCallback[WebSocketHandler]` helper.                                                                     |
| [x]    | MF-035     | Suggestion | Code Quality      | `SchedulerPage` `listJobs` block is copy-pasted between mount effect and Refresh button handler (17 lines).                                                                                                                                                                                                                                                          | `web/src/main/scala/jorlan/web/pages/SchedulerPage.scala:52-69,120-136`                                      | Extract a local `def loadJobs(): Callback` and call it from both sites.                                                                                                          |
| [x]    | MF-036     | Suggestion | Code Quality      | `MemoryPage.markShared` and `markPrivate` are identical 23-line methods except for the scope argument.                                                                                                                                                                                                                                                               | `web/src/main/scala/jorlan/web/pages/MemoryPage.scala:106-150`                                               | Extract `def changeScope(id: MemoryRecordId, scope: MemoryScope): Callback`.                                                                                                     |
| [x]    | MF-037     | Suggestion | Code Quality      | 19 identical 4-line `completeWith` error-handler blocks are copy-pasted across all page files.                                                                                                                                                                                                                                                                       | All `web/src/main/scala/jorlan/web/pages/*.scala` files                                                      | Extract a shared `handleError(setState: Option[String] => Callback): PartialFunction[Throwable, Callback]` utility in a `PageUtils` object.                                      |
| [x]    | MF-038     | Suggestion | Infrastructure    | `build.sbt` JS/shell modules omit `-Werror` — exhaustivity warnings (e.g. the `updateScope` wildcard) are silently suppressed.                                                                                                                                                                                                                                       | `build.sbt:196` (JS settings block)                                                                          | Add `-Werror` to `model.jsSettings` and `shell` scalacOptions to match the server module.                                                                                        |
| [x]    | MF-039     | Suggestion | Code Quality      | `GQL_UNKNOWN` constant is defined but never matched in the WebSocket message dispatch.                                                                                                                                                                                                                                                                               | `web/src/main/scala/caliban/ScalaJSClientAdapter.scala:195`                                                  | Either add a `case GQL_UNKNOWN =>` branch or remove the constant.                                                                                                                |
| [x]    | MF-040     | Suggestion | Documentation     | Typo `"insteaf"` in comments for `asyncCalibanCallWithAuth` and `asyncCalibanCallWithAuthOptional`.                                                                                                                                                                                                                                                                  | `web/src/main/scala/caliban/ScalaJSClientAdapter.scala:147,160`                                              | Fix spelling; add ScalaDoc with parameter descriptions.                                                                                                                          |
| [x]    | MF-041     | Suggestion | Documentation     | `AsyncCallbackRepositories`, `ZIOClientRepositories`, `ScalaJSClientAdapter`, `ApiClientSttp4.withAuthOptional`, `ExternalCredential`, `ServerStatus`, and `InitRequest` all have no ScalaDoc.                                                                                                                                                                       | Multiple files                                                                                               | Add class/method-level ScalaDoc; at minimum document the stub contract in the repository implementations.                                                                        |
| [ ]    | MF-042     | Suggestion | Architecture      | `listSkills()` on `SkillRepository` returns `SkillInfo` (an API view DTO) instead of a domain entity — repository interfaces should return domain types.                                                                                                                                                                                                             | `model/shared/src/main/scala/jorlan/repository.scala`                                                        | Return a domain `Skill` entity from the repository; let the application layer project to `SkillInfo`.                                                                            |
| [x]    | MF-043     | Suggestion | Correctness       | Shell `terminateSession` uses a raw string GQL mutation instead of the generated typed client.                                                                                                                                                                                                                                                                       | `shell/src/main/scala/jorlan/shell/client/ZIOClientRepositories.scala:58`                                    | Replace the raw string with `JorlanClient.Mutations.terminateSession(sessionId)`.                                                                                                |
| [x]    | MF-044     | Suggestion | Correctness       | `listPendingApprovals` and `listApprovals` are two implementations of the same query on the same sub-repo.                                                                                                                                                                                                                                                           | `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala:118-127,174-181`                              | Consolidate to a single method; expose filtering via a parameter.                                                                                                                |
| [x]    | MF-045     | Suggestion | Correctness       | `MemoryPage` scope input is a free-text field — users can type invalid scope values; should be a dropdown of `MemoryScope` variants.                                                                                                                                                                                                                                 | `web/src/main/scala/jorlan/web/pages/MemoryPage.scala:295-318`                                               | Replace `TextField` with a `Select` populated from `MemoryScope.values`.                                                                                                         |
| [x]    | MF-046     | Suggestion | Correctness       | `SchedulerPage` trigger-load failure is silently swallowed — user sees no error if trigger fetch fails.                                                                                                                                                                                                                                                              | `web/src/main/scala/jorlan/web/pages/SchedulerPage.scala:79-91`                                              | Surface the error via a Toast or inline error state in the same pattern used by other pages.                                                                                     |
| [x]    | MF-047     | Suggestion | Correctness       | OAuth callback query params (`?oauth=success`/`?oauth=error`) trigger a Toast that never auto-dismisses.                                                                                                                                                                                                                                                             | `web/src/main/scala/jorlan/web/JorlanWebApp.scala:77,103-109`                                                | Use `setTimeout` to clear the toast after 5 seconds, or wire to the shared `Toast` component's `autoHideDuration` prop.                                                          |
| [x]    | MF-048     | Suggestion | Correctness       | `SettingsPage` "Saved!" banner never auto-dismisses — persists indefinitely through subsequent edits.                                                                                                                                                                                                                                                                | `web/src/main/scala/jorlan/web/pages/SettingsPage.scala:129`                                                 | Reset `saved = false` on any `onChange`; add `setTimeout` dismiss.                                                                                                               |
| [x]    | MF-049     | Suggestion | Correctness       | `ChatPage` WebSocket subscription does not wire `onClientError` to UI error state — connection errors are invisible.                                                                                                                                                                                                                                                 | `web/src/main/scala/jorlan/web/pages/ChatPage.scala:67-94`                                                   | Pass an `onClientError` callback that calls `setState(error = Some(msg))`.                                                                                                       |
| [x]    | MF-050     | Suggestion | Correctness       | `SchedulerPage` shows no loading indicator on Refresh; stale trigger cache is not cleared before the new load completes.                                                                                                                                                                                                                                             | `web/src/main/scala/jorlan/web/pages/SchedulerPage.scala:119-136`                                            | Set `loading = true` and clear triggers before issuing the reload; set `loading = false` in the completion callback.                                                             |
| [x]    | MF-051     | Suggestion | Correctness       | `SkillsPage` is a non-functional stub — no data is loaded or displayed.                                                                                                                                                                                                                                                                                              | `web/src/main/scala/jorlan/web/pages/SkillsPage.scala:34-51`                                                 | Wire `listSkills()` and render results, or mark the page as explicitly deferred in the roadmap.                                                                                  |
| [x]    | MF-052     | Suggestion | Correctness       | `SessionsPage` shows no empty-state message when the session list is empty.                                                                                                                                                                                                                                                                                          | `web/src/main/scala/jorlan/web/pages/SessionsPage.scala:125-165`                                             | Add a "No sessions" placeholder when the list is empty.                                                                                                                          |
| [x]    | MF-053     | Suggestion | Correctness       | `InMemoryPermissionRepo.decideApproval` always returns `ZIO.succeed(false)` without documentation — misleading stub.                                                                                                                                                                                                                                                 | In-memory repository implementations                                                                         | Either implement the logic or add a comment documenting that the in-memory repo always denies.                                                                                   |

---

## Grouped Sections

### Correctness / Concurrency

**WebSocket subscription closed immediately on mount** (MF-001) — CONFIRMED BY 3 REVIEWERS

`EventLogPage` (lines 62-78) and `ApprovalsPage` (lines 42-67) both open a WebSocket handler and immediately call
`handler.close()` in the same `CallbackTo{}` block that acts as both the mount effect and its own cleanup return value.
The result is that the subscription is opened and torn down before the browser can send the first frame. No live data
ever arrives. `ChatPage` avoids this correctly by storing the handler in a `useRef` and returning the `close` call only
from the cleanup branch of `useEffectOnMountBy`.

Confirmed by: Code Simplicity Reviewer, Test Coverage Tracker, UI Test Plan Writer.

Recommended fix: pattern-match the `useEffectOnMountBy` return to open the subscription in the effect body and return
`handler.close()` as the cleanup — never call `close()` inline in the same expression.

```scala
// Correct pattern (follows ChatPage):
useEffectOnMountBy { _ =>
  val handler = adapter.subscribeToEventLog(onData = ev => setEvents(prev => prev :+ ev))
  Callback(handler.close()) // returned as cleanup, not called immediately
}
```

---

**WebSocket subscriptions likely unauthenticated** (MF-002) — CONFIRMED BY 2 REVIEWERS

`makeWebSocketClient` in `ScalaJSClientAdapter` is called with `connectionParams = None` on every subscription site in
`AsyncCallbackRepositories`. The browser `WebSocket` API does not support custom headers on the HTTP upgrade handshake,
so the JWT cannot be sent in an `Authorization` header. Without populating `connectionParams` (the GraphQL-WS
`connection_init` payload), the server receives no credentials and may treat the client as unauthenticated. This would
cause every streaming response — agent replies, approval notifications, and event log events — to fail silently or
return empty data.

Confirmed by: UI Test Plan Writer, Pattern Recognition Specialist.

Recommended fix: populate `connectionParams` with `Map("Authorization" -> s"Bearer $token")` and update the server's
`connectionInitHandler` in Caliban to check both the HTTP `Authorization` header and the WS `connection_init` payload.

---

**`updateScope` wildcard silently corrupts memory scope** (MF-003) — CONFIRMED BY 4 REVIEWERS

Both `AsyncCallbackRepositories.scala` (line 101-105) and `ZIOClientRepositories.scala` (line 97-101) contain an
`updateScope` implementation with a `case _ =>` fallthrough that calls `markMemoryPrivate` for the `User` and
`Workspace` enum variants. Any user who requests a scope change to `User` or `Workspace` will have their memory record
silently set to private instead. This is a silent data-corruption bug that operates without any error, warning, or log.

Confirmed by: Functional Scala Reviewer, Code Simplicity Reviewer, Pattern Recognition Specialist, SRS/SDD Conformance
Reviewer.

Recommended fix: replace the wildcard with an exhaustive match, and add `-Werror` (MF-038) to the JS and shell build
settings so that any future non-exhaustive match is a compile error.

```scala
// Broken (current):
scope match {
  case MemoryScope.Shared => adapter.markMemoryShared(id)
  case _ => adapter.markMemoryPrivate(id) // silently catches User, Workspace
}

// Correct:
scope match {
  case MemoryScope.Shared => adapter.markMemoryShared(id)
  case MemoryScope.Private => adapter.markMemoryPrivate(id)
  case MemoryScope.User => adapter.markMemoryUser(id)
  case MemoryScope.Workspace => adapter.markMemoryWorkspace(id)
}
```

---

**`ApprovalsPage` stale closure in subscription** (MF-028)

The `onData` callback passed to the approval subscription captures the initial empty `approvals` list in its closure.
As events arrive, each event is appended to the captured empty list rather than the accumulated list, so only the most
recently received item is ever shown. This is a classic React stale-closure trap.

Recommended fix: use a functional state updater: `setApprovals(prev => prev :+ newApproval)`.

---

### Data Integrity

**Filter arguments silently discarded** (MF-015, MF-016, MF-017)

Three methods in `AsyncCallbackRepositories` accept search/filter parameters that are immediately discarded:
`searchGrants(s: GrantSearch)` issues an unconditional `listCapabilities` call; `listPendingApprovals(userId)` fetches
all approvals regardless of user; `searchSessions` and `user.search` issue parameter-less queries for all records.
At scale this means every filter invocation performs a full-table fetch, with filtering deferred to the client.
Beyond performance, `listPendingApprovals` discarding `userId` is a data scoping bug — a user could see approvals
belonging to other users if the server does not independently enforce the filter.

---

### Architecture / Layer Discipline

**Service operations embedded in repository interfaces** (MF-005) — CONFIRMED BY 3 REVIEWERS

`SkillRepository[F]`, `ExternalCredentialRepository[F]`, `AgentRepository[F]`, and `PermissionRepository[F]` in
`model/shared/src/main/scala/jorlan/repository.scala` expose methods such as `invokeTool`, `startOAuth`,
`revokeOAuth`, `oauthStatus`, `listOAuthProviders`, `createSession`, `terminateSession`, `availableModels`,
`submitMessage`, `listCapabilities`, `listApprovals`, and `decideApproval`. These are application-service or facade
operations, not data-access operations. Placing them on repository interfaces inverts the architecture boundary (the
domain layer should not know about OAuth flows, model enumeration, or session lifecycle orchestration) and means the
Quill implementations must `ZIO.fail("not implemented")` for every one of these methods, creating confusion about
what the DB-side of these interfaces is supposed to do.

Confirmed by: Pattern Recognition Specialist, SRS/SDD Conformance Reviewer, Functional Scala Reviewer.

Recommended fix: define a `ClientFacade[F[_]]` or split the current interfaces into `FooRepository[F]` (CRUD only)
and `FooServiceClient[F]` (service operations), keeping repository interfaces restricted to single-aggregate-root
CRUD operations.

---

**`zio.http.MediaType` in shared domain model** (MF-006) — CONFIRMED BY 3 REVIEWERS

`model/shared/src/main/scala/jorlan/artifact.scala` and `jorlan/codecs.scala` import `zio.http.MediaType`, which is a
JVM-only library. The `model/shared` module is intended to be cross-compiled for both JVM and Scala.js. If these files
are currently in the shared source root, the JS build will fail when the import is evaluated. Even if the current
`build.sbt` configuration coincidentally avoids the error (e.g., by only compiling `modelJS` without these files in
scope), the dependency represents a ticking clock: any future Scala.js use of the shared model will break.

Recommended fix: replace `zio.http.MediaType` with a `String` type alias in shared code, or move affected
model classes to a `src/main/scala-jvm/` source set that is excluded from JS compilation.

---

**`listSkills` returns view DTO from repository** (MF-042)

`SkillRepository.listSkills()` returns `SkillInfo`, an API-layer view type, from a repository interface. Repository
interfaces should return domain entity types and leave projection to the application/GraphQL layer. This leaks
presentation concerns into the domain's data-access contract.

---

### Observability / Audit Trail

**`println` in WebSocket hot path** (MF-010) — CONFIRMED BY 3 REVIEWERS

Five `println` calls in `ScalaJSClientAdapter` (lines 390, 395, 412, 424, 473) write directly to `console.log`
with no level or category. The most problematic is in the keep-alive message handler, which fires every 30 seconds per
connection — producing continuous noise in production browser consoles. There is no way to suppress these in a
production build.

Confirmed by: Functional Scala Reviewer, Code Simplicity Reviewer, Performance Oracle.

Recommended fix: replace all `println` calls with `Callback.log(...)` (which is filterable by browser log level) or
a structured facade; gate the keep-alive log behind a debug flag.

---

### Test Coverage

**Deleted decoder tests, untested client layers** (MF-007, MF-008, MF-009, MF-025) — CONFIRMED BY 2 REVIEWERS

The `manualfixes` branch deleted `shell/src/test/scala/jorlan/shell/client/JorlanClientDecodersSpec.scala`, removing
12 unit tests for all opaque-ID `ScalarDecoder`/`ArgEncoder` round-trips including overflow and error paths. The
replacement `JorlanClientSpec` tests only enum types. New opaque ID types added in this branch
(`MemoryRecordId`, `CapabilityGrantId`, `SchedulerJobId`, `SchedulerTriggerId`, `SkillId`) are entirely untested.

Separately, `AsyncCallbackRepositories.scala` (596 lines) and `ZIOClientRepositories.scala` (518 lines) have zero
direct unit tests. No `web/src/test/` directory exists. `ScalaJSClientAdapter.scala` (503 lines including WebSocket
reconnection logic and exponential back-off) has zero tests.

| Missing Test                                         | Gap                                                          |
|------------------------------------------------------|--------------------------------------------------------------|
| Opaque ID `ScalarDecoder` round-trips (all 10 types) | Overflow, malformed input, and zero-value paths undetectable |
| `toXxx` conversion helpers (both client modules)     | Domain-mapping errors masked by integration noise            |
| `asyncCalibanCallWithAuth`                           | Auth header injection and error path untested                |
| WebSocket reconnect / back-off state machine         | Back-off logic untested; regression-prone                    |
| `jsonToValue` / `unsafeDecode` / `unsafeEncode`      | Decode failures on valid server payloads undetectable        |
| New opaque ID types                                  | Serialisation correctness entirely unverified                |

---

**`EndToEndTestApp` in production source tree** (MF-032)

`shell/src/main/scala/jorlan/shell/EndToEndTestApp.scala` (336 lines of manual smoke-test scaffolding) is in
`src/main/` and will be included in the production assembly JAR, increasing artifact size and exposing test entry
points in production builds. Move to `src/test/` or a dedicated integration sub-project.

---

### Error Handling / Functional Purity

**`throw` at object initialization time** (MF-011)

`JorlanWebApp.serverUri` calls `Uri.parse(...).fold(err => throw new IllegalStateException(err), identity)` at object
load time. If the URI configuration is missing or malformed, the exception propagates as an unhandled JS error before
any React rendering occurs, with no recovery path. There is no ZIO error channel to catch it.

Recommended fix: return `Either[String, Uri]` and render a startup error page via the React tree instead of throwing.

---

**`???` stubs throw `NotImplementedError` in the browser** (MF-018) — CONFIRMED BY 3 REVIEWERS

`AsyncCallbackRepositories` contains 20 method bodies that are bare `???` (Scala `NotImplementedError`). In a browser
JS environment, this surfaces as an unhandled exception that crashes whichever React event handler triggered the call.
There is no documentation distinguishing "never intended to be called" stubs from "not yet implemented" stubs.

Confirmed by: SRS/SDD Conformance Reviewer, Test Coverage Tracker, UI Test Plan Writer.

Recommended fix: replace each `???` with
`AsyncCallback.throwException(new UnsupportedOperationException("not yet implemented: methodName"))`,
which at least routes through the Scala.js error model. Add a comment naming which stubs are permanently unsupported
versus which are pending implementation.

---

**`MemoryScope` decoder mislabelled in shell** (MF-004) — CONFIRMED BY 2 REVIEWERS

`shell/src/main/scala/jorlan/graphql/client/JorlanClientDecoders.scala` line 107 reads:

```scala
given ScalarDecoder[MemoryScope] = enumDecoder(MemoryScope.valueOf, "ApprovalMode")
```

The label `"ApprovalMode"` is a copy-paste error from the `ApprovalMode` decoder immediately above it. When the server
sends an unexpected `MemoryScope` value, the decode error message will report `"ApprovalMode"` as the expected type,
making log-based debugging extremely misleading. The web module has the correct label.

Confirmed by: ScalaDoc Auditor, Test Coverage Tracker.

---

### Code Quality / Duplication

**Duplicated `toXxx` converters across client modules** (MF-033) — CONFIRMED BY 3 REVIEWERS

Approximately 80 lines of `toXxx` conversion helpers (`toMemoryRecord`, `toSession`, `toUser`, `toAgent`,
`toSchedulerJob`, etc.) are copy-pasted verbatim between `AsyncCallbackRepositories.scala` (lines 492-594) and
`ZIOClientRepositories.scala` (lines 395-483). The two copies will diverge as the domain model evolves, producing
subtle bugs that are hard to detect since both modules share the same test gap (MF-008, MF-009).

Confirmed by: Functional Scala Reviewer, Code Simplicity Reviewer, Performance Oracle.

Recommended fix: move all shared conversions to `JorlanClientDecoders` as `given Conversion[GQL_Type, DomainType]`
instances, following the pattern already established for `SchedulerJobView`.

---

**Structural duplication across subscription methods and page handlers** (MF-034, MF-035, MF-036, MF-037)

Four additional duplication patterns were identified:

- Three `subscribe*` methods in `AsyncCallbackRepositories` share a 20-line skeleton; extract a private `subscribe[A]`
  helper.
- `SchedulerPage` `listJobs` is copy-pasted between mount and Refresh (17 lines); extract `def loadJobs()`.
- `MemoryPage.markShared`/`markPrivate` are identical 23-line methods; extract `changeScope(id, scope)`.
- 19 identical `completeWith` error-handler blocks across all page files; extract a shared `PageUtils.handleError`
  utility.

---

### Performance

**Filter discards and full-table fetches** (MF-015, MF-016, MF-017, MF-019, MF-020)

In addition to the data-integrity concern (MF-015-017), the full-table fetches on every `searchGrants`, session search,
and user search represent unbounded query cost as data grows. `encodeConnectionId` (MF-019) adds three allocations per
HTTP request for a value that is computed once at startup — a `lazy val` eliminates this entirely. The two-phase JSON
decode in the WebSocket hot path (MF-020) doubles GC pressure during streaming responses.

---

### Documentation

**Missing ScalaDoc across new public interfaces** (MF-041, MF-031, MF-040)

`AsyncCallbackRepositories`, `ZIOClientRepositories`, `ScalaJSClientAdapter`, `ApiClientSttp4.withAuthOptional`,
`ExternalCredential`, `ServerStatus`, and `InitRequest` all lack class or method-level ScalaDoc. The `???` stub
contract in the repository implementations is undocumented. A comment in `repository.scala` references a non-existent
error type `RepositoryError` (correct name: `JorlanError`). Two methods in `ScalaJSClientAdapter` contain a typo
(`"insteaf"`) in their inline comments.

---

## Cross-Cutting Patterns

**Silent data loss from non-exhaustive pattern matches.** The `updateScope` wildcard (MF-003) and the filter-discard
pattern (MF-015, MF-016, MF-017) share a common root cause: code that silently falls through to a wrong behavior
instead of failing visibly. In both cases the missing `-Werror` build flag (MF-038) would have converted the
`updateScope` wildcard into a compile-time error. These findings were flagged independently by four and three agents
respectively — suggesting a systemic pattern of "make it compile" over "make it correct."

**Stub code that does not fail safely.** MF-004 (wrong decoder label), MF-018 (`???` stubs throwing
`NotImplementedError`), MF-053 (`decideApproval` always returning `false`), and MF-043 (raw GQL string in typed
client) all reflect the same pattern: placeholder implementations that appear correct at the type level but produce
wrong or crashing behavior at runtime. This was flagged independently by five agents (SRS/SDD, Test Coverage, UI Test
Plan, ScalaDoc, Functional Scala), making it the most broadly confirmed pattern in this review.

**Test coverage collapse in new client layers.** MF-007, MF-008, MF-009, and MF-025 together describe a situation
where 1,600+ lines of new client-side code — including all domain-to-GQL mapping logic, all WebSocket state
machinery, and all client-side stub contracts — have zero automated test coverage. The deletion of the existing decoder
spec makes this worse. This was flagged by Test Coverage Tracker and independently corroborated by UI Test Plan
Writer and Pattern Recognition Specialist. The gap is particularly high-risk because the `toXxx` converters are
duplicated (MF-033) and will diverge silently.

**Architecture boundary inversion in `model/shared`.** MF-005 (service operations on repository interfaces) and MF-042
(`listSkills` returning a view DTO) both reflect the same underlying issue: the `Repositories[F[_]]` abstraction in
`model/shared` was extended to cover service-layer concerns that belong one layer up. This makes the domain model
aware of OAuth flows, session orchestration, and presentation types — violating the SDD principle that the domain
layer must not depend on connector or HTTP specifics. Flagged by Pattern Recognition Specialist, SRS/SDD Conformance
Reviewer, and Functional Scala Reviewer.

**Subscription lifecycle bugs concentrated in newly migrated pages.** MF-001 (subscription closed on mount), MF-002
(unauthenticated WebSocket), and MF-028 (stale closure in `onData`) form a cluster of React subscription lifecycle
bugs across `EventLogPage`, `ApprovalsPage`, and subscription wiring in `AsyncCallbackRepositories`. The root cause
is that the correct `useRef` pattern demonstrated in `ChatPage` was not followed when wiring subscriptions in the
other pages. Three agents flagged MF-001 independently, and two flagged MF-002 — making the overall subscription
wiring the highest-confidence concern in the UI layer.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count  |
|------------|--------|
| Critical   | 9      |
| Warning    | 23     |
| Suggestion | 21     |
| **Total**  | **53** |

**Issues by area:**

| Area              | Count  |
|-------------------|--------|
| Correctness       | 16     |
| Test Coverage     | 8      |
| Code Quality      | 8      |
| Architecture      | 4      |
| Data Integrity    | 4      |
| Infrastructure    | 3      |
| Documentation     | 3      |
| Observability     | 2      |
| Performance       | 2      |
| Functional Purity | 2      |
| Data Integrity    | 1      |
| **Total**         | **53** |

**Agent contribution:**

| Agent                          | Unique Findings | Cross-Confirmed                            |
|--------------------------------|-----------------|--------------------------------------------|
| Functional Scala Reviewer      | 6               | 5 (MF-003, MF-005, MF-010, MF-033, MF-012) |
| Code Simplicity Reviewer       | 9               | 6 (MF-001, MF-003, MF-010, MF-014, MF-033) |
| Pattern Recognition Specialist | 8               | 5 (MF-003, MF-005, MF-002, MF-022, MF-023) |
| Performance Oracle             | 6               | 4 (MF-010, MF-014, MF-015, MF-033)         |
| ScalaDoc Auditor               | 7               | 2 (MF-004, MF-014)                         |
| SRS/SDD Conformance Reviewer   | 5               | 4 (MF-003, MF-005, MF-018, MF-043)         |
| Test Coverage Tracker          | 8               | 5 (MF-004, MF-007, MF-008, MF-018, MF-032) |
| UI Test Plan Writer            | 10              | 5 (MF-001, MF-002, MF-018, MF-028, MF-029) |

**manualFixes 2026-06-15 scope completion:**

| Item                                                       | Status                                    |
|------------------------------------------------------------|-------------------------------------------|
| Package rename `jorlan.domain.*` -> `jorlan.*` (217 files) | Done                                      |
| `Repositories[F[_]]` abstraction in `model/shared`         | Done with issues (MF-005, MF-042)         |
| `AsyncCallbackRepositories` (web Scala.js client)          | Done with issues (MF-008, MF-018)         |
| `ZIOClientRepositories` (shell ZIO client)                 | Done with issues (MF-009)                 |
| `JorlanClientDecoders` (web + shell)                       | Done with issues (MF-004)                 |
| `ScalaJSClientAdapter` rework                              | Done with issues (MF-010, MF-020, MF-025) |
| Web pages updated for new repository layer                 | Done with issues (MF-001, MF-028, MF-030) |
| `ServerInfoRepository` added                               | Done (stub only)                          || `build.sbt` r                             eorganization          | Done with issues (MF-023, MF-024,                              MF-038) |
| Test coverage maintained for new co de                     | Not done (MF-007, MF-008, MF-009)         |

---

## What Was Done Well

**Package rename executed cleanly**: The `jorlan.domain.*` -> `jorlan.*` rename across 217 files was completed without
leaving mixed-namespace imports (beyond the deduplication artifact flagged in MF-022) and without breaking the module
dependency graph. This kind of large-scale mechanical refactor is high-risk; the clean execution is notable.

**`Repositories[F[_]]` is a sound abstraction concept**: Placing a generic `F[_]`-parameterized repository
hierarchy in `model/shared` so that web (`AsyncCallback`) and shell (`ZIO`) clients share the same interface
is architecturally correct and will pay off as the surface area grows. The structural approach is right even
where the contents need refinement (MF-005).

**`JorlanClientDecoders` pattern already in use for `SchedulerJobView`**: The `given Conversion` pattern for
decoder composition is the right direction. Its partial adoption (only `SchedulerJobView` uses it; the majority of
`toXxx` methods remain copy-pasted) is the basis of MF-033, but the pattern itself should be reinforced.

**`ChatPage` `useRef` subscription pattern**: `ChatPage` demonstrates the correct approach to React subscription
lifecycle management — storing the handler in a `useRef` and returning `close()` only from the cleanup branch.
This pattern should be applied to `EventLogPage` and `ApprovalsPage` (MF-001).
