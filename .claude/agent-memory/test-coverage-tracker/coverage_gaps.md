---
name: coverage-gaps
description: Known untested areas by package as of 2026-06-02
metadata:
  type: project
---

## Phase 9 Memory System — Coverage Gaps (added 2026-06-03)
See dedicated section below.

## Phase 8.5 Session Connection Redesign — Coverage Gaps (added 2026-06-02)
See dedicated section below.

## Phase 9 Memory System — Coverage Gaps (2026-06-03)

### MemoryAccessPolicyImpl — PARTIALLY COVERED
- User scope: own user visible, other user invisible: COVERED
- Private scope: wrong user invisible: COVERED
- Shared scope: visible to all: NOT TESTED (no unit test; the `MemoryScope.Shared` branch in `filter` is `true` unconditionally but never directly asserted)
- Workspace scope: visible to all: NOT TESTED (no unit test at all for `MemoryScope.Workspace`)
- Private scope: own user + wrong agentId invisible: NOT TESTED (only wrong userId is tested, not wrong agentId with correct userId)
- Private scope: records with `agentId = None` should be invisible to any agent: NOT TESTED
- User scope: records with `userId = None` should be invisible to any requesting user: NOT TESTED

### MemoryClassifierImpl — WELL COVERED
- PII keywords → Private: COVERED ("password", "secret", "private key")
- Sharing keywords → Shared: COVERED ("everyone", "share with all", "public")
- Neutral → User: COVERED
- MISSING: Case-insensitivity boundary ("PASSWORD" in uppercase): relies on `lower.contains` but no test verifies a full-uppercase PII keyword
- MISSING: Simultaneous PII + sharing keywords — which wins (PII check runs first, but no test confirms precedence)
- MISSING: Empty string → defaults to User: NOT TESTED

### MemoryServiceImpl — PARTIALLY COVERED
- store and query happy path: COVERED
- forget happy path: COVERED
- markShared happy path: COVERED
- markPrivate happy path: COVERED
- markShared non-existent id → failure: COVERED
- markPrivate non-existent id → failure: COVERED
- checkpoint with non-SessionEnd trigger → no-op: COVERED
- checkpoint with SessionEnd trigger → stores records: COVERED (two forms)
- MISSING: checkpoint with empty messages list: tested in CheckpointSummarizerSpec but NOT in MemoryServiceSpec (the `summarizer.summarize(Nil,...)` early-return path; MemoryServiceSpec uses NoOpSummarizer which always returns Nil regardless)
- MISSING: checkpoint writes classifier-assigned scope (not the summarizer's default `User` scope): tested indirectly only; no assertion that a PII bullet gets stored as `Private`
- MISSING: query with textSearch parameter: MemoryServiceSpec calls `svc.query(scope, userId, agentId)` (no text filter) — the text-search branch in `InMemoryMemoryRepo.search` and `QuillMemoryRepository.search` is exercised only by MemorySkillSpec (via `search("prefers Scala", ...)`)
- MISSING: store returns record with assigned id (id != MemoryRecordId.empty after insert): the unit test stores but does not assert the returned record's id
- MISSING: query isolation — two different userId records stored; query for one user must not return other user's records: not directly tested in MemoryServiceSpec (only "other user sees nothing" for Private scope)

### CheckpointSummarizerImpl — PARTIALLY COVERED
- Empty messages → Nil: COVERED
- FakeModelGateway bullet response → records: COVERED
- MISSING: LLM returns blank/whitespace response → Nil: not tested (the `summary.isBlank` branch)
- MISSING: LLM returns non-bullet text (no "- " prefix) → Nil: not tested
- MISSING: LLM stream error → IO failure propagated: not tested (FakeModelGateway.failingLayer not used here)
- MISSING: recordKey is "episodic.checkpoint" on all produced records: test asserts `forall(_.recordKey == "episodic.checkpoint")` — COVERED, but userId/agentId fields on produced records not verified

### MemorySkill — MOSTLY COVERED
- remember + search: COVERED
- forget: COVERED
- markShared: COVERED
- markPrivate: COVERED
- search empty: COVERED
- MISSING: markShared/markPrivate on non-existent id → propagated error: not tested in MemorySkillSpec

### AgentRunnerImpl (memory integration) — MISSING
- buildMemoryContext with no sessions → empty string: NOT TESTED (NoOpMemoryService always returns Nil so no context is injected; behavior with real sessions unknown)
- buildMemoryContext with sessions + records → context injected into systemPrompt: NOT TESTED (systemPrompt content never asserted)
- buildMemoryContext with sessions but empty records → empty string: NOT TESTED
- buildMemoryContext error path (`catchAll(_ => "")` swallows errors silently): NOT TESTED — a failing MemoryService is never wired in for this path
- checkpoint triggered at SessionEnd via AgentRunner: NOT TESTED (AgentRunner does not explicitly call checkpoint in the visible code paths reviewed)

### NoOpMemoryService — FIDELITY ISSUE
- `markShared` returns `ZIO.fail(JorlanError("not implemented"))` instead of succeeding
- `markPrivate` returns `ZIO.fail(JorlanError("not implemented"))` instead of succeeding
- These will cause AgentRunnerSpec tests to fail if any code path in AgentRunner ever calls markShared/markPrivate (currently none does, but it's a divergence from real behavior)
- `store` returns the record unchanged (no id assignment), diverging from real behavior which assigns an auto-incremented id: tests that store via NoOp and then query by id will get wrong results

### GraphQL Memory API — NOT INTEGRATION-TESTED
- `listMemory` query: NO integration test (GraphQLApiSpec has no memory queries/mutations)
- `storeMemory` mutation: NO integration test
- `forgetMemory` mutation: NO integration test
- `markMemoryShared` mutation: NO integration test
- `markMemoryPrivate` mutation: NO integration test
- Capability enforcement (`memory.read` / `memory.write`): NO test for denied access paths
- `agentId` fallback to `AgentId.empty` when no sessions exist: NO test

### QuillMemoryRepository — PARTIALLY COVERED (via SortingAndSortingSpec)
- sort by id desc: COVERED
- sort by recordKey asc/desc: COVERED
- sort by createdAt asc/desc: COVERED
- sort by updatedAt asc/desc: COVERED (likely, given pattern)
- MISSING: `purgeExpired` with and without expired records: NOT integration-tested
- MISSING: `getById` for existing and non-existent record: NOT integration-tested
- MISSING: `textSearch` filter (post-query in-memory filter in QuillMemoryRepository.search): NOT integration-tested
- MISSING: `delete` by id: NOT integration-tested
- MISSING: upsert with existing id (conflict update path): NOT integration-tested

### Shell memory commands (CommandHandlerSpec) — MOSTLY COVERED
- /memory list with records: COVERED
- /memory list empty: COVERED
- /memory list GQL failure: COVERED
- /memory search matching: COVERED
- /memory search empty: COVERED
- /memory forget success: COVERED
- /memory forget GQL failure: COVERED
- /memory remember success (record returned): COVERED
- /memory remember server returns None: COVERED
- /memory remember GQL failure: COVERED
- MISSING: /memory list with explicit scope argument (e.g. "Private"): `ShellCommand.MemoryList(Some("Private"))` path not tested
- MISSING: /memory search GQL failure path: NOT TESTED

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
- showPersonality: NOW TESTED in Phase 9 CommandHandlerSpec (3 tests for Personality, 3 for PersonalitySet)
- setPersonalityField: NOW TESTED in Phase 9 CommandHandlerSpec
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

## Phase 10 Durable Scheduler — Coverage Gaps (2026-06-04)

### TriggerEngine — advanceTriggers NEVER TESTED
- All 6 TriggerEngineSpec tests exercise the tick→claim→execute→succeed/fail path but NOT the advanceTriggers path
- OneShot trigger → disabled after firing: NOT TESTED
- Cron trigger → job rescheduled at next cron slot: NOT TESTED
- Interval trigger → job rescheduled at now + duration: NOT TESTED
- Event trigger → ZIO.unit (no-op): NOT TESTED
- Invalid cron expression in advanceTriggers → IO.fail propagated (then .orDie): NOT TESTED
- Invalid ISO duration in advanceTriggers → IO.fail propagated (then .orDie): NOT TESTED
- Cron with no next occurrence (cronExpr.next returns None) → ZIO.unit: NOT TESTED
- Disabled trigger (enabled = false) skipped in advanceTriggers: NOT TESTED

### TriggerEngine — MissedRunPolicy NEVER TESTED (not even implemented)
- MissedRunPolicy is stored on SchedulerJob but TriggerEngine.tick/getPendingJobs does NOT implement RunOnce/RunAllMissed logic
- All three policies (Skip, RunOnce, RunAllMissed) are in the domain model but only Skip is effectively active
- No test for RunOnce or RunAllMissed behavior

### TriggerEngine — event log assertions missing
- All TriggerEngineSpec tests wire InMemoryEventLogRepo but never assert on its contents
- SchedulerJobStarted event written for each tick: NOT ASSERTED
- SchedulerJobCompleted event written on success: NOT ASSERTED
- SchedulerJobFailed event written on failure: NOT ASSERTED

### JobManagerSpec — shared state contamination risk
- ZIOSpec[JobManager] uses a shared bootstrap layer with InMemorySchedulerRepo
- Tests run in parallel by default (no @@ sequential); listJobs >= 2 test is fragile since prior tests add jobs
- cancelJob idempotency (cancel already-Cancelled job) NOT TESTED — the `unless(job.status == JobStatus.Cancelled)` guard is untested
- listJobs(Some(agentId)) filter NOT TESTED — only listJobs(None) tested
- pauseJob on Running job: NOT TESTED
- resumeJob on Cancelled job: NOT TESTED (resumes to Pending which may be surprising)
- triggerNow on Succeeded/Failed job: NOT TESTED
- addTrigger with non-existent jobId: NOT TESTED (in-memory repo accepts it; real DB would FK-fail)

### SchedulerRepositorySpec — expireLeases NOT integration-tested
- expireLeases with stale running jobs → reset to Pending: NOT TESTED (only in-memory unit test via TriggerEngineSpec)
- expireLeases with no stale jobs → returns 0: NOT TESTED
- listJobs(Some(agentId)) filter at DB level: NOT TESTED
- getPendingJobs with future scheduledAt → NOT returned: only past-scheduled jobs tested; future exclusion is confirmed but the non-returned case not explicitly asserted
- deleteJob cascade behavior (triggers deleted?): NOT TESTED

### GraphQL Scheduler API — ZERO unit or integration tests
- `jobs` query: NOT TESTED in JorlanAPISpec or GraphQLApiSpec
- `job` query: NOT TESTED
- `triggers` query: NOT TESTED
- `listApprovals` query: NOT TESTED
- `listCapabilities` query: NOT TESTED (capability check is absent — no requireCapability on this query)
- `createJob` mutation: NOT TESTED
- `addTrigger` mutation: NOT TESTED
- `pauseJob` mutation: NOT TESTED
- `resumeJob` mutation: NOT TESTED
- `cancelJob` mutation: NOT TESTED
- `triggerNow` mutation: NOT TESTED
- `deleteJob` mutation: NOT TESTED
- `decideApproval` mutation: NOT TESTED
- `terminateSession` mutation: NOT TESTED
- `scheduler.manage` capability denial for all above: NOT TESTED
- `createJob` logs SchedulerJobQueued event: NOT TESTED
- `cancelJob` logs SchedulerJobCancelled event: NOT TESTED
- `decideApproval` has NO requireCapability guard — any authenticated user can decide approvals: NOT TESTED as a security property

### Shell Command error paths for Phase 10 commands — MISSING
- /capabilities GQL failure path (Left): NOT TESTED (fakeGQL always fails in non-overriding tests, but explicit GQL error for /capabilities: NOT TESTED)
- /agents list GQL failure path: NOT TESTED
- /agents stop with false result (session not found): NOT TESTED
- /agents stop GQL failure path: NOT TESTED
- /approvals approve with false result: NOT TESTED
- /approvals deny with false result: NOT TESTED
- /approvals approve GQL failure path: NOT TESTED
- /approvals deny GQL failure path: NOT TESTED

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
