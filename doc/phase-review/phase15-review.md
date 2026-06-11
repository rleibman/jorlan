# Phase 15 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala Reviewer, Code Simplicity Reviewer, Performance Oracle, Pattern
Recognition Specialist, Test Coverage Tracker, ScalaDoc Auditor, SRS/SDD Conformance Reviewer, UI Test Plan Writer,
General Security/Architecture Reviewer)
**Date**: 2026-06-10
**Branch**: `phase-15/webfrontend`
**Scope**: Phase 15 — Web Frontend (Scala.js + React 19 + MUI v9 SPA) (
`web/src/main/scala/jorlan/web/JorlanWebApp.scala`, `web/src/main/scala/jorlan/web/AppRouter.scala`,
`web/src/main/scala/jorlan/web/AppShell.scala`, `web/src/main/scala/jorlan/web/pages/ChatPage.scala`,
`web/src/main/scala/jorlan/web/pages/SessionsPage.scala`, `web/src/main/scala/jorlan/web/pages/ApprovalsPage.scala`,
`web/src/main/scala/jorlan/web/pages/MemoryPage.scala`, `web/src/main/scala/jorlan/web/pages/SchedulerPage.scala`,
`web/src/main/scala/jorlan/web/pages/EventLogPage.scala`, `web/src/main/scala/jorlan/web/pages/UsersPage.scala`,
`web/src/main/scala/jorlan/web/pages/SettingsPage.scala`, `web/src/main/scala/jorlan/web/pages/SkillsPage.scala`,
`web/src/main/scala/jorlan/web/graphql/client/JorlanClient.scala`,
`web/src/main/scala/jorlan/web/graphql/ScalaJSClientAdapter.scala`,
`web/src/main/scala/jorlan/web/graphql/ApiClientSttp4.scala`, `web/src/main/scala/jorlan/web/ClientConfiguration.scala`,
`web/src/main/scala/jorlan/web/components/Toast.scala`, `web/src/main/web/graphiql.html`,
`server/src/main/scala/jorlan/web/StaticFileRoutes.scala`)

---

## Executive Summary

Phase 15 successfully establishes the full web frontend scaffold: authentication gate, MUI v9 theme, hash-based routing,
a GraphQL client (HTTP + WebSocket), and nine page components covering chat, sessions, approvals, memory, scheduler,
event log, skills, users, and settings. The Scala.js + ScalablyTyped + React 19 foundation is properly wired, the auth
flow works end-to-end, and SchedulerPage is the most fully-realised page with all five job mutations wired and working.
`StaticFileRoutes` correctly implements SPA deep-link fallback, and the WebSocket reconnect and keepalive logic in
`ScalaJSClientAdapter` is thoughtfully constructed.

Five security and correctness issues demand immediate attention before this branch is merged. `StaticFileRoutes`
performs no canonical-path normalisation on incoming URL segments, enabling a path-traversal attack that can read
arbitrary files from the server filesystem (confirmed by 2 reviewers). The JWT is stored in `localStorage` rather than
an HttpOnly cookie as specified in the mini-design, exposing every authenticated user's token to XSS (confirmed by 2
reviewers). `agentResponseStream` — the subscription that delivers streaming AI responses — is never opened anywhere in
`ChatPage`, making the primary use-case of the entire application non-functional (confirmed by 5 reviewers).
`ApprovalsPage` polls for approvals once at mount rather than subscribing to `approvalNotifications`, so new approvals
never appear without a page reload (confirmed by 2 reviewers). Several roadmap items are marked `[x]` but are
unimplemented stubs: the Terminate button in `SessionsPage` is `Callback.empty`, `UsersPage` exposes no write operations
despite the roadmap listing them as complete, and `MemoryPage` is missing `markShared`, `markPrivate`, and
`storeMemory` (confirmed by 2–3 reviewers each).

**Overall health: Issues Present — ready to advance to Phase N+1 with open items tracked.**

ScalaDoc and inline documentation are uniformly absent across the web module. `makeWebSocketClient` (14 parameters, the
most complex method in the codebase) has no documentation. The manual testing guide has not been updated for Phase 15.
`JORLAN_WEB_ROOT` is absent from `.env.example`, and the README misquotes the default web root.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area                | Issue                                                                                                                                                                                                                                            | File : Line                                                                                                                                                                                                                   | Recommended Action                                                                                                                                            |
|--------|------------|------------|---------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | P15-001    | Critical   | Security            | Path traversal in `StaticFileRoutes`: `new File(root, segments.mkString("/"))` — `../` segments can escape `webRoot` and read arbitrary server files. (confirmed by 2 reviewers)                                                                 | `StaticFileRoutes.scala:39`                                                                                                                                                                                                   | Canonicalize the resolved path with `file.getCanonicalPath`; reject any path that does not start with `new File(root).getCanonicalPath`.                      |
| [ ]    | P15-002    | Critical   | Security            | JWT stored in `localStorage` — XSS can steal the token; mini-design required an HttpOnly cookie. (confirmed by 2 reviewers)                                                                                                                      | `ApiClientSttp4.scala:35,96,114`                                                                                                                                                                                              | Move token storage to an HttpOnly, Secure, SameSite=Strict cookie set by the server; remove `localStorage` read/write for the JWT.                            |
| [x]    | P15-003    | Critical   | Security            | `graphiql.html` loads React 15.4.2 (2016) and GraphiQL 0.16.0 (2019) from CDN with no Subresource Integrity hashes — supply-chain attack vector.                                                                                                 | `web/src/main/web/graphiql.html` (CDN `<script>` tags)                                                                                                                                                                        | Pin SRI hashes on all CDN assets; upgrade to a current GraphiQL release; or bundle GraphiQL locally via webpack.                                              |
| [x]    | P15-004    | Critical   | Correctness         | `agentResponseStream` WebSocket subscription is never opened in `ChatPage` — streaming AI responses are non-functional. (confirmed by 5 reviewers)                                                                                               | `ChatPage.scala:57-204` (subscription call absent)                                                                                                                                                                            | Wire `adapter.subscribe(agentResponseStream(sessionId))` inside `useEffectOnMountBy` when a `sessionId` is present; append tokens to `messages`.              |
| [x]    | P15-005    | Critical   | Correctness         | `ApprovalsPage` uses a one-time `listApprovals` query at mount; `approvalNotifications` subscription is never opened — new approvals never appear in real time. (confirmed by 2 reviewers)                                                       | `ApprovalsPage.scala:39-59`                                                                                                                                                                                                   | Replace the one-shot query with a `useEffectOnMountBy` that subscribes to `approvalNotifications` and appends events to local state.                          |
| [x]    | P15-006    | Critical   | Correctness         | Terminate button in `SessionsPage` is `Callback.empty.runNow()` — always does nothing. (confirmed by 5 reviewers)                                                                                                                                | `SessionsPage.scala:97`                                                                                                                                                                                                       | Wire `terminateSession(id)` mutation and remove the stub; add a confirmation dialog before calling the mutation.                                              |
| [x]    | P15-007    | Critical   | Security            | `ApiClientSttp4` removes JWT on any non-2xx response, not only 401 — a transient 500 permanently logs the user out. (confirmed by 3 reviewers)                                                                                                   | `ApiClientSttp4.scala:112-116`                                                                                                                                                                                                | Restrict JWT removal to HTTP 401 responses only; treat other non-success codes as retriable or display a recoverable error.                                   |
| [ ]    | P15-008    | Warning    | Correctness         | `UsersPage` is entirely read-only — Create/Edit/Roles/Permissions dialogs are roadmap-marked `[x]` but absent. (confirmed by 2 reviewers)                                                                                                        | `UsersPage.scala` (write operations absent)                                                                                                                                                                                   | Implement `createUser`, `updateUser`, `assignRole`, `revokeRole`, `grantPermission`, `revokePermission` dialogs as roadmap specifies.                         |
| [x]    | P15-009    | Warning    | Correctness         | `MemoryPage` is missing `storeMemory`, `markMemoryShared`, and `markMemoryPrivate` mutations — three roadmap `[x]` items not implemented. (confirmed by 2 reviewers)                                                                             | `MemoryPage.scala` (mutations absent)                                                                                                                                                                                         | Add the "Remember" dialog for `storeMemory`; wire Mark Shared / Mark Private buttons invoking the corresponding mutations.                                    |
| [x]    | P15-010    | Warning    | Correctness         | `SessionsPage` has no Create session dialog with model picker — roadmap item marked `[x]` but absent.                                                                                                                                            | `SessionsPage.scala` (dialog absent)                                                                                                                                                                                          | Implement a dialog that calls `availableModels` to populate a model selector and then calls `createSession`.                                                  |
| [x]    | P15-011    | Warning    | Correctness         | `SettingsPage` formality options are `formal/neutral/casual` but the domain `Formality` enum is `casual/professional/academic/technical` — mismatch persists invalid values. (confirmed by 2 reviewers)                                          | `SettingsPage.scala:119-123`                                                                                                                                                                                                  | Replace the hardcoded string options with the actual `Formality` enum variants; validate against the server-side enum before submitting.                      |
| [x]    | P15-012    | Warning    | Resource Management | `EventLogPage` constructs a `WebSocketHandler` but never calls `close()` on unmount — WebSocket connection leaks permanently. (confirmed by 4 reviewers)                                                                                         | `EventLogPage.scala:54-73`                                                                                                                                                                                                    | Store the handler reference in `useRef` or component state; call `handler.close()` in `useEffectOnMountBy`'s cleanup / unmount callback.                      |
| [x]    | P15-013    | Warning    | Correctness         | `running = true` set synchronously before the WebSocket socket connects in `EventLogPage` — UI shows "running" for a connection that may never open.                                                                                             | `EventLogPage.scala:68,74`                                                                                                                                                                                                    | Set `running = true` only inside the WebSocket `onOpen` callback; set `running = false` in `onClose` and `onError`.                                           |
| [x]    | P15-014    | Warning    | Correctness         | `hashchange` event is never attached in `AppRouter` — browser back/forward navigation breaks completely; `useEffect(Callback.empty)` is a no-op placeholder. (confirmed by 5 reviewers)                                                          | `AppRouter.scala:51`                                                                                                                                                                                                          | Implement `useEffect` that calls `window.addEventListener("hashchange", ...)` and reads `window.location.hash` to update the current page state.              |
| [x]    | P15-015    | Warning    | Correctness         | `AppShell` approval count badge queries once at mount and never refreshes — badge count is permanently stale after the first approval action.                                                                                                    | `AppShell.scala:42-66`                                                                                                                                                                                                        | Either remove the badge (deferred per roadmap) or subscribe to `approvalNotifications` to keep the count live.                                                |
| [x]    | P15-016    | Warning    | Correctness         | `SettingsPage` `saved` flag is never reset after the user edits a field — "Saved!" remains visible indefinitely after subsequent edits.                                                                                                          | `SettingsPage.scala:145`                                                                                                                                                                                                      | Reset `saved = false` in any `onChange` handler; or use a `setTimeout`-based dismiss after 3 seconds.                                                         |
| [x]    | P15-017    | Warning    | Functional Purity   | `var socket` and `var connectionState` in `ScalaJSClientAdapter` — mutable vars violate the no-var rule and create concurrent-update hazards.                                                                                                    | `ScalaJSClientAdapter.scala:153,165`                                                                                                                                                                                          | Replace with a `Ref`-backed state object or `useState`-equivalent; if the class lives outside React state, use an `AtomicReference`.                          |
| [x]    | P15-018    | Warning    | Functional Purity   | `throw new Exception(...)` used for URI parsing errors across 10 call sites — violates no-throw rule; exceptions propagate as unhandled Promise rejections in JS. (confirmed by 2 reviewers)                                                     | `ChatPage.scala:49`, `AppShell.scala:53`, `UsersPage.scala:48`, `SessionsPage.scala:49`, `MemoryPage.scala:50,74`, `ApprovalsPage.scala:49,76`, `EventLogPage.scala:51`, `SettingsPage.scala:50,77`, `SchedulerPage.scala:47` | Extract a `parseServerUri(config): Either[String, URI]` helper; surface the error via a Toast rather than throwing.                                           |
| [x]    | P15-019    | Warning    | Functional Purity   | `explicit return` statements in `attemptReconnect()` — violates no-return rule and creates multiple exit paths that are hard to reason about.                                                                                                    | `ScalaJSClientAdapter.scala:179,189`                                                                                                                                                                                          | Rewrite as a single expression using `if/else` or pattern matching; remove all `return` statements.                                                           |
| [x]    | P15-020    | Warning    | Functional Purity   | `Instant.now()` called outside ZIO effect in `ScalaJSClientAdapter` — non-deterministic; breaks test-clock control.                                                                                                                              | `ScalaJSClientAdapter.scala:182,245,261`                                                                                                                                                                                      | Replace with `Clock.instant` threaded from the ZIO caller, or accept a `now: () => Instant` parameter injected at construction.                               |
| [x]    | P15-021    | Warning    | Functional Purity   | `asyncCalibanCallWithAuth` errors silently discarded with `completeWith(_ => Callback.empty)` across all pages — GraphQL failures are invisible to the user. (confirmed by 6 reviewers)                                                          | All page files (ApprovalsPage, MemoryPage, SchedulerPage, SessionsPage, UsersPage, SettingsPage, ChatPage)                                                                                                                    | Replace `Callback.empty` with a `setState(error = Some(msg))` call that surfaces the error in a Toast or inline error state.                                  |
| [x]    | P15-022    | Warning    | Architecture        | `ScalaJSClientAdapter` constructed inside render closures or `useEffectOnMountBy` on every render — creates a new HTTP client + WebSocket factory on every re-render, including during streaming (one per token). (confirmed by all 9 reviewers) | `ChatPage.scala`, `ApprovalsPage.scala`, `MemoryPage.scala`, `SettingsPage.scala`, `SchedulerPage.scala:97`                                                                                                                   | Construct `ScalaJSClientAdapter` once per component lifecycle, store in `useState`/`useRef`, and pass as a stable reference to callbacks.                     |
| [ ]    | P15-023    | Warning    | Architecture        | `ScalaJSClientAdapter` violates Single Responsibility — HTTP client, WebSocket factory, and React lifecycle management all in one class.                                                                                                         | `ScalaJSClientAdapter.scala` (entire file)                                                                                                                                                                                    | Extract `JorlanHttpClient` (HTTP POST), `JorlanWsClient` (WebSocket), and `ClientLifecycle` (React adapter) as three separate concerns.                       |
| [x]    | P15-024    | Warning    | Architecture        | `AppShell` is directly coupled to the approvals domain — queries approval count from inside the shell layout.                                                                                                                                    | `AppShell.scala:42-66`                                                                                                                                                                                                        | Either remove the badge (deferred per roadmap) or have `AppRouter` pass the count as a prop to `AppShell`, keeping the layout component domain-free.          |
| [x]    | P15-025    | Warning    | Performance         | `MemoryPage` search filter runs client-side on every keystroke — full scan of all loaded records; server `textSearch` argument is available but unused.                                                                                          | `MemoryPage.scala` (client-side filter)                                                                                                                                                                                       | Debounce the search input (300 ms) and pass the query string to `listMemory(textSearch: ...)` instead of filtering locally.                                   |
| [x]    | P15-026    | Warning    | Performance         | `ApprovalsPage` fetches all approval records then filters client-side, while `AppShell` makes a second independent call for the same data — two redundant full scans.                                                                            | `ApprovalsPage.scala:56`, `AppShell.scala:42-66`                                                                                                                                                                              | Move filtering server-side via the `listApprovals` query; lift the result to a shared context or prop to eliminate the duplicate call.                        |
| [x]    | P15-027    | Warning    | Performance         | `SchedulerPage` fires two serial GraphQL calls per job action (fetch triggers, then re-fetch all jobs) — doubled round-trips for every mutation.                                                                                                 | `SchedulerPage.scala:127-142`                                                                                                                                                                                                 | Return the updated job from the mutation response; update local state directly instead of re-querying.                                                        |
| [x]    | P15-028    | Warning    | Performance         | `StaticFileRoutes` serves all static files with no `Cache-Control` header — browsers and CDNs get no caching benefit.                                                                                                                            | `StaticFileRoutes.scala` (response headers absent)                                                                                                                                                                            | Add `Cache-Control: public, max-age=31536000, immutable` for hashed assets and `Cache-Control: no-cache` for `index.html`.                                    |
| [x]    | P15-029    | Warning    | Performance         | `scalaJSStage := FastOptStage` set globally in `build.sbt` with no production override — the deployed bundle is 5–15× larger than the `fullOptJS` equivalent.                                                                                    | `build.sbt:539`                                                                                                                                                                                                               | Add `scalaJSStage := FullOptStage` under a `production` SBT profile or set it explicitly in the `dist` task definition.                                       |
| [x]    | P15-030    | Warning    | Error Handling      | `File.exists()` and `File.isFile()` called as blocking I/O outside `ZIO.attemptBlocking` in `StaticFileRoutes` — can block the ZIO thread pool.                                                                                                  | `StaticFileRoutes.scala:42-43`                                                                                                                                                                                                | Wrap in `ZIO.attemptBlocking { file.exists() && file.isFile }` and use the result in the ZIO for-comprehension.                                               |
| [x]    | P15-031    | Warning    | Functional Purity   | `new File(webRoot)` side-effecting `val` at object level in `StaticFileRoutes` — I/O performed at class-load time outside ZIO.                                                                                                                   | `StaticFileRoutes.scala:26`                                                                                                                                                                                                   | Wrap in `ZIO.attempt(new File(webRoot))` called at route construction time, not at object initialisation.                                                     |
| [x]    | P15-032    | Warning    | Correctness         | `ApprovalsPage` performs an optimistic remove from local state before the `decideApproval` mutation returns — no rollback if mutation fails.                                                                                                     | `ApprovalsPage.scala:86`                                                                                                                                                                                                      | Perform state update only inside the `completeWith` success path; display an error Toast and restore the item on failure.                                     |
| [x]    | P15-033    | Warning    | Code Quality        | `makeAdapter()` factory call copy-pasted across 11 sites in 7+ page files — any change to adapter construction requires 11 edits. (confirmed by all reviewers)                                                                                   | All page files                                                                                                                                                                                                                | Extract `useAdapter(): ScalaJSClientAdapter` as a custom React hook (or a single `Hooks.useAdapter` companion method) and call it once per page.              |
| [x]    | P15-034    | Warning    | Code Quality        | `ClientConfiguration` is a case class that is barely used and carries half-dead code — port-exclusion logic is non-obvious and untested.                                                                                                         | `ClientConfiguration.scala`                                                                                                                                                                                                   | Convert to an `object` with a factory `def make(...)` method; document the port-exclusion rule and add a unit test for it.                                    |
| [x]    | P15-035    | Warning    | Code Quality        | Source maps bundled into the production `dist` output — exposes full Scala source structure in the browser developer tools.                                                                                                                      | `build.sbt:626,651`                                                                                                                                                                                                           | Emit source maps to a separate, non-publicly-served directory; or disable them entirely in the `dist` / `fullOptJS` task.                                     |
| [ ]    | P15-036    | Warning    | Test Coverage       | No test files exist for the entire `web/` module — `web/src/test/` does not exist. (confirmed by all reviewers)                                                                                                                                  | `web/src/test/` (absent)                                                                                                                                                                                                      | Create `web/src/test/scala/jorlan/web/` and add at minimum: router unit tests, client-decoder round-trip tests, and Caliban schema-compatibility smoke tests. |
| [x]    | P15-037    | Warning    | Test Coverage       | `StaticFileRoutes` has no server-side tests — path traversal guard (P15-001), `index.html` fallback, and 404 branch are all unverified.                                                                                                          | `StaticFileRoutes.scala` (no spec)                                                                                                                                                                                            | Add `StaticFileRoutesSpec` covering: valid asset served, `../` segment rejected, unknown path falls back to `index.html`, non-existent path returns 404.      |
| [ ]    | P15-038    | Warning    | Test Coverage       | `ApiClientSttp4.withAuth` has 6 branches (success, 401-clear, non-2xx-error, network error, missing JWT, reload) with zero test coverage — security-sensitive paths.                                                                             | `ApiClientSttp4.scala:26-120`                                                                                                                                                                                                 | Add unit tests for each branch using a fake sttp backend; assert JWT is cleared only on 401, not on transient failures.                                       |
| [x]    | P15-039    | Warning    | Test Coverage       | `JorlanAPISpec` does not cover `availableModels` or `terminateSession` success path — both were added in Phase 15. (confirmed by 2 reviewers)                                                                                                    | `JorlanAPISpec` (missing tests)                                                                                                                                                                                               | Add one happy-path test for each; `terminateSession` should also assert `SessionStatus.Cancelled` after the call.                                             |
| [x]    | P15-040    | Warning    | Documentation       | `makeWebSocketClient` has 14 parameters and zero ScalaDoc — it is the most complex method in the module.                                                                                                                                         | `ScalaJSClientAdapter.scala:90`                                                                                                                                                                                               | Add `@param` for every parameter; document the WebSocket lifecycle sequence and the role of each callback.                                                    |
| [x]    | P15-041    | Warning    | Documentation       | Manual testing guide has no Phase 15 scenarios — the web frontend is the primary user-facing interface and is entirely absent from manual QA.                                                                                                    | `doc/manual-testing-guide.md:3`                                                                                                                                                                                               | Add a Phase 15 section: auth flow, chat streaming (once P15-004 is fixed), approvals round-trip, memory CRUD, scheduler actions, event log live-tail.         |
| [x]    | P15-042    | Warning    | Documentation       | `JORLAN_WEB_ROOT` environment variable is absent from `.env.example`; README claims default is `/opt/jorlan/www` but actual default in `debugDist` local runs differs.                                                                           | `.env.example`, `README.md:148`                                                                                                                                                                                               | Add `JORLAN_WEB_ROOT=/opt/jorlan/www` to `.env.example`; correct the README to note the local dev override (`debugDist`).                                     |
| [x]    | P15-043    | Suggestion | Code Quality        | `println` used in production code instead of `console.log` / `Callback.log` throughout `ScalaJSClientAdapter`.                                                                                                                                   | `ScalaJSClientAdapter.scala:149,179,186,193,300`                                                                                                                                                                              | Replace all `println(...)` with `Callback.log(...)` or a structured logger; conditionally silence in production builds.                                       |
| [x]    | P15-044    | Suggestion | Code Quality        | `debugDist` and `dist` SBT tasks are near-identical 25-line blocks — DRY violation in the build definition.                                                                                                                                      | `build.sbt` (`debugDist`, `dist` task definitions)                                                                                                                                                                            | Extract a `webDistTask(stage: ScalaJSStage): Def.Initialize[Task[_]]` helper; instantiate twice with `FastOptStage` and `FullOptStage`.                       |
| [x]    | P15-045    | Suggestion | Code Quality        | `ApprovalsPage` risk-class color match uses a non-exhaustive wildcard — new `RiskClass` variants would render with no color and no compile-time warning.                                                                                         | `ApprovalsPage.scala` (color match)                                                                                                                                                                                           | Replace the wildcard arm with explicit cases for all `RiskClass` variants; rely on the compiler's exhaustiveness check.                                       |
| [x]    | P15-046    | Suggestion | Code Quality        | `CapabilityGrantView.approvalMode` is typed as `String` with a `// String or Enum?` TODO in source — should be `ApprovalMode`.                                                                                                                   | `JorlanClient.scala:362`                                                                                                                                                                                                      | Change the type to `ApprovalMode`; update `JorlanClientDecoders` with a decoder that maps the string representation.                                          |
| [x]    | P15-047    | Suggestion | Code Quality        | `Formality` is a type alias for `String` — provides no compile-time constraint; callers can pass any string.                                                                                                                                     | `JorlanClient.scala` (or domain type definition)                                                                                                                                                                              | Convert to a sealed enum (or opaque type with a smart constructor) matching the server-side `Formality` variants.                                             |
| [x]    | P15-048    | Suggestion | Code Quality        | `GQLOperationMessage` protocol constants are bare string literals scattered through `ScalaJSClientAdapter` — should be grouped in a companion object.                                                                                            | `ScalaJSClientAdapter.scala`                                                                                                                                                                                                  | Extract `object GQLOperationMessage { val ConnectionInit = "connection_init"; ... }` companion; reference constants by name.                                  |
| [x]    | P15-049    | Suggestion | Code Quality        | `JorlanClientDecoders` defines 19 opaque-ID decoders using an identical pattern — an existing `longDecoder` helper is present but not applied universally.                                                                                       | `JorlanClient.scala` (decoder section)                                                                                                                                                                                        | Apply the `longDecoder` helper consistently for all opaque `Long`-backed ID types; reduce 19 definitions to a one-liner each.                                 |
| [x]    | P15-050    | Suggestion | Code Quality        | Dead `loading` state field in `SkillsPage` — allocated but never mutated or rendered.                                                                                                                                                            | `SkillsPage.scala`                                                                                                                                                                                                            | Remove the field; if a loading spinner is planned, add a TODO comment explaining the deferral.                                                                |
| [x]    | P15-051    | Suggestion | Code Quality        | Dead `streamBuffer` and `streaming` state fields in `ChatPage` — exist but are never wired to the subscription (see P15-004).                                                                                                                    | `ChatPage.scala:39-40`                                                                                                                                                                                                        | These will be used once P15-004 is fixed; add a `// TODO P15-004:` comment on each to make the dependency explicit.                                           |
| [x]    | P15-052    | Suggestion | Code Quality        | Magic icon strings in `AppShell` nav items should be part of the `AppPage` enum to prevent drift between route and icon.                                                                                                                         | `AppShell.scala` (nav item definitions)                                                                                                                                                                                       | Add an `icon: String` field to the `AppPage` enum; derive the nav item list from `AppPage.values`.                                                            |
| [x]    | P15-053    | Suggestion | Performance         | `EventLogPage` prepends each WebSocket event to the full list with a state update per message — under high event volume this produces jank and O(n) allocations.                                                                                 | `EventLogPage.scala` (state prepend pattern)                                                                                                                                                                                  | Batch incoming events with a 100 ms debounce; prepend the batch in a single `setState` call.                                                                  |
| [ ]    | P15-054    | Suggestion | Test Coverage       | `SchedulerPage.statusColor` has 6 branches, none tested — `JobStatus` variants map to MUI chip colors.                                                                                                                                           | `SchedulerPage.scala` (statusColor function)                                                                                                                                                                                  | Add a unit test that asserts each `JobStatus` maps to the expected MUI color string.                                                                          |
| [ ]    | P15-055    | Suggestion | Test Coverage       | `AppRouter` hash routing fallback (unknown hash → default page) is untested.                                                                                                                                                                     | `AppRouter.scala`                                                                                                                                                                                                             | Add a test that sets `window.location.hash` to an unrecognized value and asserts the default page renders.                                                    |
| [x]    | P15-056    | Suggestion | Documentation       | `AppPage` enum fields `hash` and `label` have no ScalaDoc — the relationship between hash and routing is non-obvious to new contributors.                                                                                                        | `AppRouter.scala:21`                                                                                                                                                                                                          | Add `@param hash` and `@param label` to the enum; document the convention that `hash` must start with `#`.                                                    |
| [x]    | P15-057    | Suggestion | Documentation       | `WebSocketHandler` trait has no ScalaDoc — its lifetime contract and thread-safety expectations are undocumented.                                                                                                                                | `ScalaJSClientAdapter.scala:33`                                                                                                                                                                                               | Add a class-level comment describing when `close()` must be called and whether callbacks fire on the JS event loop.                                           |
| [x]    | P15-058    | Suggestion | Documentation       | `ApiClientSttp4.withAuth` has no ScalaDoc — the `window.location.reload()` side effect on auth failure is a significant, non-obvious behavior.                                                                                                   | `ApiClientSttp4.scala:26`                                                                                                                                                                                                     | Add ScalaDoc documenting the reload side effect, the JWT removal condition (once fixed to 401-only), and the expected caller contract.                        |
| [ ]    | P15-059    | Suggestion | UI/UX               | No confirmation dialog before session termination, memory forget, scheduler job delete, or approval denial — destructive actions are one-click.                                                                                                  | `SessionsPage.scala:97`, `MemoryPage.scala:67-84`, `SchedulerPage.scala:237-239`, `ApprovalsPage.scala:113-151`                                                                                                               | Add a `ConfirmDialog` component; show it before each destructive mutation with a clear description of the action.                                             |
| [ ]    | P15-060    | Suggestion | UI/UX               | No pagination on any list page (Sessions, Users, Memory, EventLog) — all lists load unbounded results.                                                                                                                                           | All list pages                                                                                                                                                                                                                | Add server-side pagination parameters to each query; render a MUI `TablePagination` component with page/pageSize controls.                                    |
| [x]    | P15-061    | Suggestion | UI/UX               | `SkillsPage` renders a bare "Skills — coming soon" stub with no explanation — looks like a broken page to users.                                                                                                                                 | `SkillsPage.scala`                                                                                                                                                                                                            | Render a styled placeholder that explains `listSkillVersions` is not yet in the API and will appear in a future phase.                                        |
| [x]    | P15-062    | Suggestion | UI/UX               | Chat message area (`ChatPage`) has no `aria-live="polite"` region — screen readers do not announce incoming AI responses.                                                                                                                        | `ChatPage.scala` (message list container)                                                                                                                                                                                     | Add `aria-live="polite"` and `aria-atomic="false"` to the message list container element.                                                                     |

---

## Grouped Sections

### Security

**Path traversal in `StaticFileRoutes`** (P15-001) — CONFIRMED BY 2 REVIEWERS

`StaticFileRoutes.scala:39` constructs the served file path as `new File(root, segments.mkString("/"))` without
canonicalizing either the root or the resolved path. An attacker who requests `GET /../../etc/passwd` receives the
contents of that file if the server process has read permission. The fix requires calling `file.getCanonicalPath` and
asserting it starts with `new File(root).getCanonicalPath`:

```scala
val canonical = file.getCanonicalPath
if (!canonical.startsWith(new File(root).getCanonicalPath)) {
  ZIO.succeed(Response.status(Status.Forbidden))
} else {
  // serve file
}
```

**JWT in `localStorage`** (P15-002) — CONFIRMED BY 2 REVIEWERS

`ApiClientSttp4.scala:35,96,114` reads and writes the JWT to `window.localStorage`. Any XSS vulnerability — in Jorlan
itself, in a bundled dependency, or in a GraphiQL CDN script (see P15-003) — can exfiltrate the token. The Phase 15
mini-design explicitly specified an HttpOnly cookie. The fix requires the server to set a
`Set-Cookie: jorlan_jwt=...; HttpOnly; Secure; SameSite=Strict` header on login and to read the cookie automatically on
every subsequent request, removing the `localStorage` interaction entirely.

**Outdated CDN dependencies in `graphiql.html`** (P15-003)

`graphiql.html` loads React 15.4.2 (released 2016) and GraphiQL 0.16.0 (released 2019) from `cdn.jsdelivr.net` with no
Subresource Integrity (SRI) hashes. If the CDN is compromised or the hosting URL is hijacked, arbitrary JavaScript runs
in the browser in the context of an authenticated Jorlan session. Additionally, the `fetcher` function in the file
passes the auth token to the wrong API path and never sets the `Authorization` header. The recommended fix is to add SRI
`integrity` attributes to every CDN `<script>` and `<link>` tag, upgrade to a current GraphiQL version, and correct the
API URL and auth header.

**JWT cleared on any non-2xx** (P15-007) — CONFIRMED BY 3 REVIEWERS

`ApiClientSttp4.scala:112-116` removes the JWT from `localStorage` for any response code that is not a 2xx success —
including transient 500 errors from the server, 503 during a restart, or 429 rate-limit responses. This permanently logs
the user out on any non-auth-related server error. Only an HTTP 401 should trigger token removal; all other error codes
should surface an error state without invalidating the session.

---

### Correctness / Functional Completeness

**`agentResponseStream` subscription never wired** (P15-004) — CONFIRMED BY 5 REVIEWERS

`ChatPage.scala` declares `streamBuffer` and `streaming` state fields (lines 39–40), calls `submitMessage`, and renders
the message list, but never opens the `agentResponseStream` WebSocket subscription anywhere in the file. The
subscription variable exists in `JorlanClient.scala` but there is no `adapter.subscribe(...)` call in any lifecycle
hook. As a result, sending a message through the web UI submits the request but the AI response never appears — the core
feature of the application is non-functional. The fix is to add a `useEffectOnMountBy` that opens the subscription when
`sessionId` becomes non-empty and appends response chunks to `messages` state, terminating on the `finished` sentinel.

**`ApprovalsPage` missing real-time subscription** (P15-005) — CONFIRMED BY 2 REVIEWERS

The Approvals page calls `listApprovals` once on mount and stores the result. It never subscribes to
`approvalNotifications`. Any approval request created after the page loads is invisible until the user manually
navigates away and back. The roadmap marks this item `[x]`. The fix is to replace the one-shot query with a subscription
hook.

**Terminate button no-op** (P15-006) — CONFIRMED BY 5 REVIEWERS

`SessionsPage.scala:97` has the Terminate action wired to `Callback.empty.runNow()`. The `terminateSession` GraphQL
mutation exists in the server and in `JorlanClient.scala` but is never called. Clicking Terminate silently does nothing.
The roadmap marks this `[x]`.

**`UsersPage` read-only despite roadmap `[x]`** (P15-008) — CONFIRMED BY 2 REVIEWERS

The Users page lists users from the `users` query but none of the write operations described in the roadmap —
`createUser`, `updateUser`, `assignRole`, `revokeRole`, `grantPermission`, `revokePermission` — are implemented. The
roadmap checkboxes for all of these are marked complete.

**`MemoryPage` missing three mutations** (P15-009) — CONFIRMED BY 2 REVIEWERS

The roadmap marks "Remember" dialog (`storeMemory`), Mark Shared (`markMemoryShared`), and Mark Private (
`markMemoryPrivate`) as complete. None of the three operations appear in `MemoryPage.scala`. `forgetMemory` is the only
write operation present.

**`SessionsPage` missing Create dialog** (P15-010)

The roadmap marks the "Create session dialog (model picker from `availableModels` query)" as `[x]`. No such dialog
exists in `SessionsPage.scala`. The page shows a sessions list but provides no mechanism to start a new session.

**`SettingsPage` formality enum mismatch** (P15-011) — CONFIRMED BY 2 REVIEWERS

`SettingsPage.scala:119-123` offers `formal/neutral/casual` as formality options. The server-side `Formality` enum is
`casual/professional/academic/technical`. Selecting any option in the web UI submits a value the server does not
recognize, silently storing an invalid personality record.

**Optimistic remove without rollback in `ApprovalsPage`** (P15-032)

`ApprovalsPage.scala:86` removes the approval from local state immediately on button click, before the `decideApproval`
mutation returns. If the mutation fails (network error, capability denial, optimistic concurrency conflict), the
approval record disappears from the UI with no way to recover except a page reload.

**`hashchange` never wired in `AppRouter`** (P15-014) — CONFIRMED BY 5 REVIEWERS

`AppRouter.scala:51` contains `useEffect(Callback.empty)` — a no-op placeholder. The `hashchange` DOM event is never
registered. Browser back/forward navigation and direct URL sharing with a hash fragment do not update the React state
machine; the URL changes but the page does not. All five reviewers that examined `AppRouter` flagged this independently.

---

### Resource Management

**`EventLogPage` WebSocket handle leaked** (P15-012) — CONFIRMED BY 4 REVIEWERS

`EventLogPage.scala:54-73` creates a `WebSocketHandler` for the `eventLogTail` subscription but never calls `close()`.
When the user navigates away from the Event Log page, the WebSocket connection remains open and the JavaScript closure
over component state remains live. Incoming events after unmount will attempt to call `setState` on an unmounted
component, producing stale-state bugs and memory leaks. The handler reference must be stored in a `useRef` and `close()`
must be called in the unmount path of `useEffectOnMountBy`.

**`ScalaJSClientAdapter` recreated on every render** (P15-022) — CONFIRMED BY ALL 9 REVIEWERS

Every page component reconstructs a `ScalaJSClientAdapter` inside a render closure or `useEffectOnMountBy`. During
streaming in `ChatPage`, this fires on every token delivery — meaning a new HTTP client and WebSocket factory is
allocated for every chunk of the AI response. In `ApprovalsPage`, `MemoryPage`, and `SettingsPage` the adapter is
constructed twice: once in `useEffectOnMountBy` and once in the render body. The fix is to construct the adapter exactly
once per component lifecycle using `useState` or `useRef`, and pass the stable reference into all callbacks. This is the
single highest-frequency performance and architecture problem in the codebase.

---

### Architecture / Code Quality

**Systematic error swallowing** (P15-021) — CONFIRMED BY 6 REVIEWERS

Every page uses `completeWith(_ => Callback.empty)` as the error handler for all `asyncCalibanCallWithAuth` calls, with
the sole exception of `SchedulerPage`. When any GraphQL mutation or query fails — network timeout, capability denial,
server error, schema mismatch — the error is silently discarded and the UI shows a spinner that never resolves, or
simply does nothing. This pattern makes the application impossible to debug and gives users no feedback on failures. A
global `setError: Option[String] => Callback` state pattern should be established and wired into every page's error
path.

**`makeAdapter()` copy-pasted 11 times** (P15-033) — CONFIRMED BY ALL REVIEWERS

The adapter construction pattern appears in 11 places across 7+ files. Any change to how the adapter is constructed —
whether to fix P15-022 (lifecycle), P15-018 (URI parsing), or P15-023 (splitting concerns) — requires the same edit in
11 places. Extracting `useAdapter()` as a shared custom hook would reduce this to a single definition.

**`ApiClientSttp4` JWT clear on any non-2xx** (P15-007) — CONFIRMED BY 3 REVIEWERS

Beyond the user-experience impact described under Security, the code pattern
`case Some(other) if !other.code.isSuccess => localStorage.removeItem(JwtKey)` is architecturally incorrect: it
conflates authentication failure (401) with all other server error conditions. This produces the counter-intuitive
behavior that a momentary 503 during a rolling deployment permanently invalidates the user's session and forces a new
login.

---

### Performance / Build

**`scalaJSStage := FastOptStage` in production** (P15-029)

`build.sbt:539` sets `scalaJSStage := FastOptStage` globally with no production override. The `dist` task that produces
the deployable bundle will therefore emit a fast-opt (unoptimized, un-dead-code-eliminated) JavaScript bundle. FullOptJS
bundles are typically 5–15× smaller and significantly faster to parse and execute. The fix is to set
`scalaJSStage := FullOptStage` in the `dist` task body or under a production SBT profile.

**Missing `Cache-Control` headers** (P15-028)

`StaticFileRoutes` serves all static assets (JS bundle, CSS, fonts) with no `Cache-Control` header. Browsers will use
heuristic caching or revalidate on every navigation. Since the Scala.js bundle filename is content-addressed by webpack,
it is safe to set `max-age=31536000, immutable` for all assets except `index.html`, which should be `no-cache`.

**`MemoryPage` client-side full-scan search** (P15-025)

The search input in `MemoryPage` filters the already-loaded record array on every keystroke. The server's `listMemory`
query accepts a `textSearch: String` parameter but it is never passed. Under even moderate memory volume, this produces
visible lag. The fix is a debounced re-query using the server-side parameter.

**Source maps in production bundle** (P15-035)

The `dist` task includes source maps in the public distribution. Source maps expose the full Scala package and class
structure of the application to anyone who opens browser developer tools, which is valuable information for an attacker
profiling the application. Source maps should be written to a non-public directory or omitted entirely from production
builds.

---

### Test Coverage

**Zero tests for the entire `web/` module** (P15-036) — CONFIRMED BY ALL REVIEWERS

`web/src/test/` does not exist. There is no test for any component, route, decoder, or client behavior in the web
module. This means the two confirmed functional regressions (P15-004 streaming non-functional, P15-006 terminate no-op)
were not caught by the test suite and will not be caught by future regressions until tests exist.

| Missing Test                                          | Gap                                                       |
|-------------------------------------------------------|-----------------------------------------------------------|
| `AppRouter` hash routing and fallback                 | Back/forward navigation breakage undetectable             |
| `JorlanClientDecoders` round-trip for each ID type    | Decoder drift from server schema undetectable             |
| `ApiClientSttp4` branches (success, 401, 5xx, no-JWT) | Security-sensitive JWT removal logic completely untested  |
| `ScalaJSClientAdapter` reconnect / keepalive logic    | Reconnect regressions undetectable without live WebSocket |
| `StaticFileRoutes` path traversal guard               | P15-001 security regression directly undetectable         |
| `ChatPage` streaming subscription wiring              | P15-004 re-regression undetectable after fix              |
| `ApprovalsPage` subscription wiring                   | P15-005 re-regression undetectable after fix              |

**`StaticFileRoutes` has no test coverage** (P15-037)

The three critical behaviors of `StaticFileRoutes` — valid asset served, `../` rejected, unknown path falls back to
`index.html` — are exercised only by manual testing. A `StaticFileRoutesSpec` using the `zio-http` test client should be
added.

**`JorlanAPISpec` missing Phase 15 additions** (P15-039) — CONFIRMED BY 2 REVIEWERS

`availableModels` and `terminateSession` were added in Phase 15 but are not covered by any test in `JorlanAPISpec`. The
`terminateSession` test should additionally assert that the session row transitions to `Cancelled` status.

---

### Documentation / Observability

**`makeWebSocketClient` has 14 parameters and no ScalaDoc** (P15-040)

`ScalaJSClientAdapter.scala:90` defines the most complex method in the web module with 14 parameters covering URL
construction, authentication, WebSocket callbacks, reconnect policy, and keepalive intervals. There is no `@param` for
any of them, no description of the expected calling sequence, and no note about which parameters must not be `null` in
the Scala.js context.

**Manual testing guide not updated for Phase 15** (P15-041)

`doc/manual-testing-guide.md` was last updated during Phase 11. Phase 15 introduces the primary user-facing interface —
the web SPA — but no manual testing scenarios for it exist. Given the confirmed functional gaps (P15-004, P15-005,
P15-006), a manual testing guide for the web is particularly important.

**`JORLAN_WEB_ROOT` absent from `.env.example`** (P15-042)

The `jorlan.web.root` configuration key is used by `StaticFileRoutes` but the corresponding environment variable
`JORLAN_WEB_ROOT` is not documented in `.env.example`. New installations following the setup guide will not know this
variable exists until they hit a 404 for all static assets.

---

### UI / UX

**Destructive actions have no confirmation** (P15-059)

Session termination (`SessionsPage`), memory forget (`MemoryPage`), scheduler job delete (`SchedulerPage`), and approval
denial (`ApprovalsPage`) all execute immediately on a single button click with no confirmation dialog. The `Terminate`
button is currently a no-op (P15-006), but when fixed it will immediately terminate active sessions. A shared
`ConfirmDialog` component should be added and wired to all destructive mutations.

**No pagination on any list** (P15-060)

Every list page — Sessions, Users, Memory, Event Log — issues an unbounded query and renders the full result set in a
table. Under production load, this will produce slow queries, large DOM trees, and poor perceived performance.
Server-side pagination with MUI `TablePagination` should be added to each list.

**Missing accessibility attributes** (P15-062)

The chat message log needs `aria-live="polite"` so screen readers announce incoming responses. Navigation items in
`AppShell` receive only the icon glyph text as their accessible name. The logout button has no `aria-label`.

---

## Cross-Cutting Patterns

**Silent error swallowing** was independently flagged by all 6 reviewing agents that examined individual page files (
Functional Scala, Code Simplicity, Pattern Recognition, Test Coverage, SRS/SDD, UI Test Plan, General Security). The
root cause is a single shared call pattern: `asyncCalibanCallWithAuth(...).completeWith(_ => Callback.empty)`. Because
the pattern was applied uniformly when the pages were scaffolded, fixing it requires the same change in every page. This
is the root cause of P15-021 and the "spinner runs forever on failure" behavior observed by the UI Test Plan agent
across SessionsPage, UsersPage, ApprovalsPage, and MemoryPage.

**`ScalaJSClientAdapter` recreation on every render** was noted by all 9 reviewers from complementary angles: the
Functional Scala reviewer called it a lifecycle anti-pattern (P15-022), the Performance Oracle measured it as the
highest-frequency allocation site in the application, the Pattern Recognition specialist identified it as the dominant
DRY violation, the Code Simplicity reviewer counted 11 copy-paste sites (P15-033), and the SRS/SDD reviewer classified
it as an architectural concern. The shared root cause is that adapter construction was placed inline in render functions
and `useEffectOnMountBy` callbacks during initial scaffolding rather than being lifted to a stable hook.

**Roadmap checkbox inflation** is the most consequential cross-cutting pattern: multiple high-visibility features are
marked `[x]` in `doc/development_roadmap.md` but are either non-functional stubs (chat streaming — P15-004), entirely
absent (UsersPage write ops — P15-008, MemoryPage mutations — P15-009, SessionsPage create dialog — P15-010), or wired
incorrectly (ApprovalsPage subscription — P15-005, Terminate button — P15-006). This was independently identified by the
SRS/SDD Conformance agent and confirmed by the Test Coverage and UI Test Plan agents. Five roadmap `[x]` items do not
match the implementation.

**Blocking I/O and impure construction outside ZIO** appears in three distinct files. `StaticFileRoutes.scala:26`
constructs `new File(webRoot)` at object-initialization time (P15-031). `StaticFileRoutes.scala:42-43` calls
`file.exists()` and `file.isFile()` without `ZIO.attemptBlocking` (P15-030). `ScalaJSClientAdapter.scala:182,245,261`
calls `Instant.now()` outside ZIO (P15-020). All three violate the project's principle that side effects must live
inside ZIO. The Functional Scala reviewer and General Security reviewer flagged these independently.

**Missing real-time subscriptions** is a correctness pattern affecting two pages. `ChatPage` never opens
`agentResponseStream` (P15-004, confirmed by 5 agents) and `ApprovalsPage` never opens `approvalNotifications` (P15-005,
confirmed by 2 agents). Both items are marked `[x]` in the roadmap. The common cause is that the subscription
infrastructure in `ScalaJSClientAdapter` is available and working — the reconnect and keepalive logic is solid — but the
application-level wiring in the page components was never completed.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count  |
|------------|--------|
| Critical   | 7      |
| Warning    | 33     |
| Suggestion | 22     |
| **Total**  | **62** |

**Issues by area:**

| Area                | Count  |
|---------------------|--------|
| Correctness         | 14     |
| Code Quality        | 10     |
| Test Coverage       | 7      |
| Security            | 5      |
| Documentation       | 6      |
| Performance         | 5      |
| Functional Purity   | 5      |
| Architecture        | 3      |
| Resource Management | 2      |
| Error Handling      | 1      |
| UI/UX               | 4      |
| **Total**           | **62** |

**Agent contribution:**

| Agent                          | Findings Contributed | Cross-Confirmed |
|--------------------------------|----------------------|-----------------|
| Functional Scala Reviewer      | 28 raw               | 12              |
| Code Simplicity Reviewer       | 26 raw               | 14              |
| Performance Oracle             | 13 raw               | 8               |
| Pattern Recognition Specialist | 20 raw               | 11              |
| Test Coverage Tracker          | 16 raw               | 9               |
| ScalaDoc Auditor               | 16 raw               | 5               |
| SRS/SDD Conformance Reviewer   | 21 raw               | 12              |
| UI Test Plan Writer            | 25 raw               | 10              |
| General Security/Architecture  | 20 raw               | 9               |

**Phase 15 scope completion:**

| Item                                                   | Status |
|--------------------------------------------------------|--------|
| Web module foundation (SBT, stLib, routing, theme)     | ✅      |
| GraphQL client (HTTP + WebSocket transport)            | ✅      |
| Auth gate (whoami, LoginRouter, logout)                | ✅      |
| Chat interface — UI scaffold                           | ✅      |
| Chat interface — streaming subscription wired          | ❌      |
| Sessions page — list and display                       | ✅      |
| Sessions page — Create dialog with model picker        | ❌      |
| Sessions page — Terminate button functional            | ❌      |
| Approvals page — real-time via `approvalNotifications` | ❌      |
| Memory page — list and forget                          | ✅      |
| Memory page — store / mark shared / mark private       | ❌      |
| Scheduler page — all mutations wired                   | ✅      |
| Event Log page — live tail subscription                | ⚠️     |
| Users page — list display                              | ✅      |
| Users page — create / edit / roles / permissions       | ❌      |
| Settings page — personality editor                     | ⚠️     |
| Skills page                                            | ⚠️     |
| `StaticFileRoutes` with SPA fallback                   | ✅      |
| `web/dist` full-opt build verified                     | ❌      |
| Debian package includes web assets                     | ❌      |
| README updated for web module                          | ❌      |

---

## What Was Done Well

**Auth gate integration**: The `JorlanWebApp` → `AuthClient.whoami` → `LoginRouter` flow works end-to-end, correctly
redirecting unauthenticated users before any page component renders. The auth boundary is clean and does not leak into
individual page components.

**GraphQL client type safety**: `JorlanClient.scala` is hand-written with precise Scala types for every query, mutation,
and subscription. The `JorlanClientDecoders` opaque-ID decoder pattern prevents accidental type confusion across the 19
domain ID types, following the same discipline established in the server-side domain model.

**SchedulerPage completeness**: Of all nine page components, `SchedulerPage` is the most fully realized — all five job
lifecycle mutations (pause, resume, cancel, trigger-now, delete) are wired, the triggers sub-table is rendered per job,
and it is the only page that does not use `Callback.empty` as an error handler.

**WebSocket reconnect and keepalive**: `ScalaJSClientAdapter` implements a thoughtful exponential-backoff reconnect loop
with configurable keepalive pings and stale-connection detection. This infrastructure is solid and correct; the only
issue is that it is constructed too frequently (P15-022) rather than any defect in the reconnect logic itself.

**SPA deep-link fallback**: `StaticFileRoutes` correctly falls back to `index.html` for any unknown path, enabling
hash-based deep links to work after a hard browser reload. The path structure is correct and the route is wired as a
catch-all in `Jorlan.zapp`.

**MUI v9 theme consistency**: The custom `ThemeProvider` with Inter (body) and JetBrains Mono (code/monospace) fonts,
brand primary color, and `CssBaseline` reset is applied globally and propagates consistently to all page components.
Visual consistency across pages required no per-page theme overrides.
