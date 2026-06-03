---
name: coverage-gaps
description: Known untested areas by package as of 2026-06-02
metadata:
  type: project
---

## Phase 8.5 Session Connection Redesign — Coverage Gaps (added 2026-06-02)
See dedicated section below.

## Phase 8.3 Server Personality / Phase 8.4 AI CI Testing Coverage Gaps (2026-06-01)

### PersonalityService — WELL COVERED (15 tests in PersonalityServiceSpec.scala)
- `buildSystemPrompt` all 4 Formality values: COVERED (Casual, Professional, Academic, Technical)
- Language hint omitted for single 'en': COVERED
- Language hint included for multi-language: COVERED
- Expertise hint included/omitted: COVERED
- Free-form prompt at end: COVERED
- get() returns default when no key stored: COVERED
- update() persists to repo and refreshes cache: COVERED
- `PersonalityServiceImpl.live` layer INIT FROM PRE-POPULATED STORE: NOT TESTED (always starts from empty InMemoryServerSettingsRepo — the code path `case Some(json) => json.as[Personality]` is never exercised in unit tests)

### GraphQL serverPersonality query / updatePersonality mutation: COVERED in JorlanAPISpec (Phase 8.5)
- serverPersonality query returns default: COVERED
- serverPersonality fails when unauthenticated: COVERED
- serverPersonality fails when capability denied: COVERED
- updatePersonality succeeds: COVERED
- updatePersonality fails (denyAll): COVERED
- updatePersonality fails (unauthenticated): COVERED

### OllamaModelGateway system-prompt rebuild: NOT DIRECTLY TESTABLE
- Entire file is `$COVERAGE-OFF$` / `$COVERAGE-ON$`; requires live Ollama

### AgentRunnerImpl personality integration: COVERED ONLY BY PROXY
- AgentRunnerSpec uses fakePersonality returning `Personality.default`; personality is fetched on every processMessage call
- The fact that `buildSystemPrompt` output is passed to `modelGateway.streamedResponse` is structurally exercised but never verified by asserting on systemPrompt content

### Shell /personality command (CommandHandler.showPersonality): NOT TESTED
- `ShellCommand.Personality` case is present in CommandHandler.handle dispatch but CommandHandlerSpec has zero tests for it
- `ShellCommand.PersonalitySet(field, v)` also not tested

### JorlanShell status bar server name (initialisePostLogin): NOT TESTED
- `initialisePostLogin` fetches serverName via `InitClient.checkStatus`, formats it into status/mode bars
- JorlanShellSpec only tests `fmtDelay` and `resolveCredentials` filtering — zero test for `initialisePostLogin`

### V018 migration and PersonalityKey constant: NOT COVERED BY INTEGRATION TESTS
- V018__personality.sql seeds the default personality row — no integration test queries `PersonalityKey`

## Phase 8.5 Session Connection Redesign — Coverage Gaps (2026-06-02)

### ConversationLogger — NO TESTS
- `ConversationLogger.logUserMessage` is called inside `AgentRunnerImpl.processMessage`; the AgentRunnerSpec exercises it indirectly (via processMessage) but never asserts on its behavior
- `ConversationLogger.logAgentResponse` — called in the `.ensuring` block of processMessage; isError=true path only exercised when ModelGateway fails, but no test verifies the log output
- `ConversationLogger.withMdc` — internal method; the MDC set/restore pattern is never verified (no assertion that MDC is clean after the effect)
- There are NO direct tests for ConversationLogger as a unit

### SessionHub — REDESIGNED (Hub→per-connection Queue), tests UPDATED
- Subscribe before publish (buffered delivery): COVERED
- Multiple subscribers per session each receive all chunks: COVERED
- Independent sessions: COVERED
- Publish to non-existent session: COVERED (was a gap, now tested)
- Subscriber cleanup on interruption: COVERED
- Ordering: COVERED
- Stream continues across messages (long-lived WS model): COVERED
- subscribe() returns `UIO[ZStream]` (not `ZStream` directly) — the new signature means callers must await the UIO before collecting; tests correctly use this form
- **STILL MISSING**: subscriber count after remove (no test checks that `subs` Ref is empty after all subscribers disconnect)
- **STILL MISSING**: sliding queue overflow behavior (queue is bounded/sliding(1024)); no test publishes >1024 chunks to verify oldest are dropped rather than blocking

### AgentRunnerImpl — IMPROVED
- processMessage publishes all chunks then finished sentinel: COVERED
- processMessage writes UserMessageReceived and AgentResponseCompleted events: COVERED
- processMessage with ModelGateway failure publishes finished+isError sentinel: COVERED
- AgentResponseCompleted written even on failure: COVERED
- `getOrCreate` call at top of processMessage (ensures queue exists after restart): exercised indirectly — NOT directly tested in isolation
- ConversationLogger calls in processMessage: not asserted (see ConversationLogger above)
- `actorId = None` path: NOT TESTED (only Some(userId) used)

### AgentSessionManager — UPDATED tests
- createSession Active status: COVERED
- createSession logs SessionCreated event: COVERED
- getSession happy path: COVERED
- getSession unknown id: COVERED
- suspendSession → Paused: COVERED
- terminateSession → Completed: COVERED
- createSession reuses default agent: COVERED
- suspendSession with unknown id → JorlanError: COVERED
- listSessions: COVERED
- **STILL MISSING**: terminateSession hub removal not verified (hub entries not checked after terminate)
- **STILL MISSING**: suspendSession does not remove hub — no test verifies hub entries preserved after suspend

### JorlanAPI (GraphQL layer) — IMPROVED
- createSession mutation: COVERED (was gap, now tested)
- createSession with explicit modelId: COVERED
- submitMessage mutation: COVERED (was gap, now tested)
- listSessions: COVERED
- agentResponseStream subscription schema check: COVERED
- approvalNotifications empty stream: COVERED
- **STILL MISSING**: `agentResponseStream` subscription actual stream content — only schema is checked; no test verifies tokens flow through the subscription
- **STILL MISSING**: `submitMessage` with non-existent sessionId — error path
- **STILL MISSING**: `logErrors` wrapper — no test triggers a GraphQL execution error and verifies the error message is rewritten from "Effect failure" to the underlying cause message
- **STILL MISSING**: `logRequests` wrapper — no test verifies request logging fires

### InitRoutes — UPDATED
- New `tokenStore` parameter added to `SetupModeApp.make` — all tests updated with noopTokenStore stub
- Localhost token bypass (`isLocalhost && r.token.isEmpty → use stored token`): NOT TESTED
  - Tests use `ZIO.scoped(routes.run(...))` which does not simulate a real IP address; the `req.remoteAddress` check always fails in test context
- `initDone` Promise completion on success: NOT TESTED
  - All tests pass `initDone = None`; no test wires up a real Promise and verifies it's satisfied

### InitService — IMPROVED
- New `seedAdminGrants` method seeds 12 capability grants after admin user creation: exercised in the "successful init" test but NO assertion verifies the grants were actually written to the permRepo — only the settings flag is checked
- The `adminCapabilities` list content (12 entries): no test enumerates or asserts on specific capability names

### JorlanShell — NEW `loadOrCreateSession` logic, UNTESTED
- `loadOrCreateSession` — lists sessions, resumes Active one or creates new: NO TESTS
  - applySession (resume path): NOT TESTED
  - createNew (no active session path): NOT TESTED
  - listSessions error/Left path → falls through to createNew: NOT TESTED
  - server returns None from listSessions: NOT TESTED
- `shutdownCleanly` — interrupts subscription fiber before sys.exit: NOT TESTED (and untestable without live screen)
- `ensuring(sys.exit(0))` pattern: NOT TESTED

### CommandHandler — SIGNIFICANTLY REDESIGNED, PARTIALLY TESTED
- handleMessage with active LiveSession (new path): NOT TESTED
  - tokenQueue drain happy path: NOT TESTED
  - tokenQueue drain error (Left): NOT TESTED
  - tokenQueue drain finished sentinel (Right(None)): NOT TESTED
- handleNewSession now uses `JorlanClient.Mutations.createSession` (typed SelectionBuilder), tears down existing session, forks long-lived WS fiber: NOT TESTED for the success branch
  - GQL failure branch (Left): NOT TESTED for new path (old stub returns `run not implemented in fake`)
  - `Right(None)` branch ("Server returned no session"): NOT TESTED
  - Existing session teardown (subscriptionFiber.interrupt): NOT TESTED
- showPersonality: NOT TESTED (CommandHandlerSpec has no test for ShellCommand.Personality)
- setPersonalityField: NOT TESTED (CommandHandlerSpec has no test for ShellCommand.PersonalitySet)
- **fakeGQL.run always returns `ZIO.fail("run not implemented in fake")`** — this means ALL tests exercising handleNewSession, handleMessage with active session, showPersonality, and setPersonalityField via the standard testLayer will silently fail the GQL call; tests use `runCmd(ShellCommand.NewSession(None))` which hits `fakeGQL.run` and fails → the `Left(err)` branch is exercised, not the success branch

### VersionCheck — WELL COVERED (new file + new spec)
- Both semver identical: COVERED
- Client patch newer: COVERED
- Client patch equal (different buildTime): COVERED
- Client patch older: COVERED
- Minor mismatch: COVERED
- Major mismatch: COVERED
- Error message non-empty: COVERED
- Non-semver client newer buildTime: COVERED
- Non-semver client equal buildTime: COVERED
- Non-semver client older: COVERED
- Semver vs hash fallback: COVERED
- Hash vs semver fallback: COVERED
- **MISSING**: `VersionCheck.check` with an empty string for either version
- **MISSING**: Version string with extra components (e.g. "1.2.3.4" — parse returns None → falls to buildTime path)

### ShellState — IMPROVED (was entirely untested, now MOSTLY COVERED)
- getLiveSession returns None initially: COVERED
- setLiveSession makes session available: COVERED
- getSessionId returns None: COVERED
- getSessionId after setLiveSession: COVERED
- clearLiveSession: COVERED
- **MISSING**: clearLiveSession when already None (idempotency): NOT TESTED
- **MISSING**: setLiveSession replaces existing (interrupt old fiber): NOT TESTED

### SubscriptionClient — STILL ZERO UNIT TESTS
- Protocol now uses subscriptions-transport-ws "start"/"data" frames (not newer graphql-ws "subscribe"/"next")
- `isError` field added to `ChunkData` decoder — not unit-tested
- No test for WS reconnection or authentication header injection

## Phase 8 Agent Session Runtime Coverage Gaps (2026-05-29) — partially superseded by Phase 8.5

### OllamaModelGateway
- Entire file excluded with $COVERAGE-OFF$ / $COVERAGE-ON$ — appropriate because it requires a live Ollama process.

### HumanApprovalNotifier
- Zero tests. Single method `notifyApprovalRequired` writes an `ApprovalRequested` event — entirely untested.

## Phase 8 Agent Session Runtime Coverage Gaps (2026-05-29) — RESOLVED

### SessionHub
- `publish` to a non-existent session: NOW COVERED (test "publish to non-existent session is a no-op")
- Multiple subscribers per session: NOW COVERED

### AgentRunner
- ModelGateway failure path + finished sentinel: NOW COVERED
- AgentResponseCompleted on model failure: NOW COVERED

### AgentSessionManager
- suspendSession unknown ID: NOW COVERED
- listSessions: NOW COVERED

### GraphQL mutations createSession / submitMessage
- createSession: NOW COVERED in JorlanAPISpec

## Phase 6 GraphQL API Coverage Gaps (still open as of 2026-06-02)

### Mutations — coverage
- `revokePermission` — signature changed to `PermissionId => ...` (no wrapper input type); tests pass `value:` arg — still COVERED

### Subscriptions
- `approvalNotifications` — stub ZStream.empty; COVERED (empty stream asserted)
- `eventLogTail` — stub ZStream.empty; NOT TESTED (schema only)
- `agentResponseStream` — schema field present but actual stream content NOT TESTED

### JorlanRoutes.scala
- HTTP route bindings: NOT TESTED via HTTP (all GraphQL tests bypass HTTP, use interpreter directly)
- WebSocket subscription route: NOT TESTED
- GraphiQL route: NOT TESTED

### Jorlan.scala (server wiring)
- Server startup + GraphQL route integration: NOT TESTED via HTTP end-to-end

## Package Coverage Summary (stale, from 2026-05-27)
- jorlan (model/domain): 34.72% — 265 statements
- jorlan.auth: 0% — 59 statements (JorlanAuthServer.scala entirely untested)
- jorlan.db: 37.90% — 248 statements
- jorlan.db.repository: 36.44% — 483 statements (QuillRepositories.scala)
- jorlan.domain: 25.23% — 214 statements
- jorlan.service: 100% — 45 statements

## Critical Untested Areas (carry-forward from Phase 6)
1. `JorlanAuthServer` — entire auth layer at 0%: login, changePassword, createOAuthUser, linkOAuthToUser, userByPK, userByOAuthProvider, createUser (expected failure), sendEmail (noop), activateUser (noop)
2. `UserZIORepository.login` / `changePassword` — security-critical SQL logic never integration-tested
3. `RepositoryError.apply(Throwable)` — SQLTransient/SQLNonTransient branching untested

## Patterns
- No error-path tests anywhere in integration layer (constraint violations, duplicate keys, etc.)
- `fakeGQL.run` in CommandHandlerSpec always fails — all `JorlanClient.run(...)` paths in CommandHandler implicitly exercise only error branches in tests
- ConversationLogger is systematically skipped: no direct unit tests and no assertion on log output in indirect tests
- InitRoutes new behaviors (localhost bypass, initDone Promise) are untested because test helpers don't simulate real IP addresses
