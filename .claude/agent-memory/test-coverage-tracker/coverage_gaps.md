---
name: coverage-gaps
description: Known untested areas by package as of 2026-05-27 (~75% aggregate scoverage)
metadata:
  type: project
---

## Phase 8 Agent Session Runtime — Coverage Gaps (added 2026-05-29)
See dedicated section below.

## Phase 6 GraphQL API — Coverage Gaps (added 2026-05-27)
See dedicated section below.

## Package Coverage Summary (2026-05-27)
- jorlan (model/domain): 34.72% — 265 statements
- jorlan.auth: 0% — 59 statements (JorlanAuthServer.scala entirely untested)
- jorlan.db: 37.90% — 248 statements
- jorlan.db.repository: 36.44% — 483 statements (QuillRepositories.scala)
- jorlan.domain: 25.23% — 214 statements
- jorlan.service: 100% — 45 statements

## Critical Untested Areas
1. `JorlanAuthServer` — entire auth layer at 0%: login, changePassword, createOAuthUser, linkOAuthToUser, userByPK, userByOAuthProvider, createUser (expected failure), sendEmail (noop), activateUser (noop)
2. `UserZIORepository.login` / `changePassword` — security-critical SQL logic never integration-tested
3. `RepositoryError.apply(Throwable)` — SQLTransient/SQLNonTransient branching untested

## High Priority Untested Areas
1. `jorlan.domain` enums JSON roundtrip — ApprovalMode, ApprovalStatus, SessionStatus, MessageRole, EventType, MemoryScope, SkillTier, SkillStatus, ConnectorType, JobStatus, TriggerType, ChannelType (non-fromProvider values) all lack codec tests
2. `MemorySearch` scoped by Workspace/Private/Agent — only User and Shared scopes tested
3. Search pagination (page > 0) — `page` parameter not exercised in any search test
4. `MemoryRepository.getById` — never called in tests
5. `AgentRepository` — session `getSession` only called once; `getById` for agent not tested standalone

## Medium Priority
1. `EventLogFilter` pagination (page > 0 in integration)
2. `SkillRepository` — `getConnector(id)` tested but standalone `getById(SkillId)` not tested in isolation
3. `quillUtil.scala` — Quill encodings for PublicKey, SemVer, URI, MediaType (exercised indirectly but coverage is low)
4. `configuration.scala` — AppConfig / DataSourceConfig parsing
5. `FlywayMigration.scala` — not directly tested (exercised implicitly by container startup)

## Phase 5 Capability Kernel — Unit Test Gaps (2026-05-27)

### RiskClassifierImpl — untested capabilities
- `permission.revoke` exact override (Privileged) — in exactOverrides but no test
- `filesystem.remove.*` prefix (Destructive) — only `filesystem.delete` tested
- `filesystem.list.*` prefix (ReadOnly) — untested
- `filesystem.*` fallback prefix (WorkspaceWrite) — untested
- `memory.delete.*` prefix (Destructive) — only `memory.forget` tested
- `memory.read.*` prefix (ReadOnly) — only `memory.search` tested
- `memory.*` fallback prefix (WorkspaceWrite) — untested
- `network.send.*` / `network.external.*` (ExternalEffect) — untested
- `network.*` fallback prefix (WorkspaceWrite) — untested
- `role.remove.*` (Privileged) — untested; `role.*` fallback (Privileged) untested
- `permission.*` prefix (Privileged) — `permission.grant` tested as exact override but not prefix fallback
- `capability.*` prefix (SecuritySensitive) fallback — untested (only `capability.grant` exact override tested)
- `skill.install`, `skill.approve` (ExternalEffect) — untested
- `skill.*` fallback (WorkspaceWrite) — untested
- `scheduler.*` prefix (WorkspaceWrite) — untested
- `agent.*` prefix (WorkspaceWrite) — untested
- `shell.sudo.*` prefix (SecuritySensitive) — `shell.sudo.execute` tested but `shell.sudo.anything` prefix fallback not
- Boundary: name exactly equal to prefix with no trailing dot (e.g. `"shell"` alone — no test)
- Boundary: empty string capability name — no test
- Boundary: name that is a strict prefix of a registered prefix (e.g. `"shel"`) — no test

### ApprovalPolicyEngineImpl — untested scenarios
- `Once` mode: existingApprovals present but all have `ApprovalStatus.Rejected` / `Pending` / `Expired` — currently the check is `.nonEmpty`, so a rejected approval incorrectly grants access; no test exposes this
- `Session` mode: request has `sessionId = None` — both sides of the match are untested with null session
- `Timed` mode: expiry instant exactly equal to `now` (boundary — `isAfter` is strict, so this falls to PendingApproval; no test for the boundary)
- `buildRequest` fields: `capability` in template uses `request.capability` not `grant.capability` — no test where they differ to prove the request field is copied correctly
- `buildRequest` fields: `scopeJson` comes from `grant.scopeJson` — not tested with a non-None scope
- `Denied` mode reason string content — test only checks `isInstanceOf`, not the reason text
- `DefaultDeny` reason string content — same issue

### CapabilityEvaluatorImpl — integration test gaps (no unit tests at all)
- Happy path: explicit deny grant present → returns ExplicitDeny
- Happy path: direct user permission present → returns ResourcePermissionAllows
- Happy path: role permission present (no direct) → returns RolePermissionAllows
- Happy path: non-denied grant present (no direct/role permission) → returns CapabilityGrantAllows
- Happy path: nothing matches → returns DefaultDeny
- Multiple grants including a Denied one — ordering check (Denied wins regardless of other grants)
- `splitCapability` for no-dot name — resource = full name, action = "use"
- `splitCapability` for multi-dot name — only first dot is the split point

### ApprovalServiceImpl — integration test gaps (no unit tests at all)
- Full `authorize` pipeline for all seven EvaluationResult paths
- PendingApproval result: persisted template has id replaced by saved record id
- Allowed result: CapabilityAllowed event is written to event log
- Denied result: CapabilityDenied event is written to event log
- `loadExistingApprovals` short-circuits to Nil for all non-Once/Session evaluation results (no DB call)
- `recordDecision` delegates to permissionService.recordApprovalDecision
- `expireStaleRequests` sums expired counts across multiple records
- `expireStaleRequests` with empty result set → returns 0

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

### GraphQL serverPersonality query: NOT TESTED
- No test in JorlanAPISpec or GraphQLApiSpec calls `{ serverPersonality { ... } }`
- Both specs use a fakePersonality stub, bypassing the real service

### GraphQL updatePersonality mutation: NOT TESTED
- No test calls `mutation { updatePersonality(...) }`
- The admin capability check (`admin.personality.update`) is NOT tested for deny/allow paths
- The mutation is entirely absent from JorlanAPISpec and GraphQLApiSpec

### OllamaModelGateway system-prompt rebuild: NOT DIRECTLY TESTABLE
- Entire file is `$COVERAGE-OFF$` / `$COVERAGE-ON$`; requires live Ollama
- The `getOrCreate` session rebuild when systemPrompt changes IS the key logic but cannot be unit-tested
- No integration test covers this behavior

### AgentRunnerImpl personality integration: COVERED ONLY BY PROXY
- AgentRunnerSpec uses fakePersonality returning `Personality.default`; personality is fetched on every processMessage call
- The fact that `buildSystemPrompt` output is passed to `modelGateway.streamedResponse` is structurally exercised but never verified by asserting on systemPrompt content

### Shell /personality command (CommandHandler.showPersonality): NOT TESTED
- `ShellCommand.Personality` case is present in CommandHandler.handle dispatch
- CommandHandlerSpec has zero tests for `ShellCommand.Personality`
- The GQL query path, JSON parsing, and both success/failure branches are untested

### Shell /commands listing includes /personality: IMPLICITLY COVERED
- The showCommands test checks `text.contains("/personality")` indirectly via the commands listing

### JorlanShell status bar server name (initialisePostLogin): NOT TESTED
- `initialisePostLogin` fetches serverName via `InitClient.checkStatus`, formats it into status/mode bars
- JorlanShellSpec only tests `fmtDelay` and `resolveCredentials` filtering — zero test for `initialisePostLogin`

### V018 migration and PersonalityKey constant: NOT COVERED BY INTEGRATION TESTS
- V018__personality.sql seeds the default personality row — no integration test queries `PersonalityKey`
- GraphQLApiSpec uses `fakePersonality` stub, so V018 row is never read through the real service
- `ServerSettingsRepository.PersonalityKey` constant is used only in PersonalityServiceImpl; covered by unit tests only

### StreamedChatSpec (Phase 8.4 AI CI): WELL COVERED
- 5 tests covering LangChainConfig default values and field overrides
- `streamedChat` bridge itself is excluded ($COVERAGE-OFF$) — reasonable as it needs live Ollama

## Phase 8 Agent Session Runtime Coverage Gaps (2026-05-29)

### OllamaModelGateway
- Entire file excluded with $COVERAGE-OFF$ / $COVERAGE-ON$ — appropriate because it requires a live Ollama process.

### FakeModelGateway
- `chunkDelay` branch (tap + ZIO.sleep) — never exercised; only the no-delay path is tested indirectly via AgentRunnerSpec.
- `availableModels` — never directly asserted.

### SessionHub
- `publish` to a non-existent session (no hub created) — the None branch is untested; only the Some branch is exercised.
- `subscribe` stream integration via `SessionHub.subscribe()` method directly — tests use `hub.getOrCreate` + `innerHub.subscribe` directly, bypassing `SessionHub.subscribe`.

### AgentRunner
- ModelGateway failure path — when `streamedResponse` emits an error, the `.ensuring` block must still publish the finished sentinel; this is not tested.
- `actorId = None` path — only tested with `Some(userId)`.
- `AgentResponseCompleted` event written AFTER model failure (vs. success) — no test.

### AgentSessionManager
- `suspendSession` does NOT remove the hub (only `terminateSession` does); no test verifies hub is still accessible after suspend.
- `terminateSession` hub removal — no test verifies the hub is gone from the Ref after terminate.
- `ensureDefaultAgent` when multiple agents exist (only one named "Jorlan Interactive") — tested only when store is empty or already has the default.
- `updateStatus` when session not found — `ZIO.fromOption(_).orElseFail(JorlanError(...))` error path never tested (both suspendSession and terminateSession).
- `createSession` with modelId = None — only `Some(ModelId("test-model"))` path is tested in most tests.

### HumanApprovalNotifier
- Zero tests. Single method `notifyApprovalRequired` writes an `ApprovalRequested` event — entirely untested.

### GraphQL mutations createSession / submitMessage / agentResponseStream
- `createSession` mutation — NOT tested in JorlanAPISpec.
- `submitMessage` mutation — NOT tested in JorlanAPISpec.
- `agentResponseStream` subscription — NOT tested in JorlanAPISpec.

### Shell CommandHandler (Phase 8 behaviors)
- `handleMessage` with active session (Some(sessionId)) — both the success path (GQL ok → subscription stream) and the GQL failure path (`Submit failed`) are untested. Tests only check the no-session branch.
- `handleNewSession` GQL success path — tested to check the "Phase 8" stub, but the real implementation (parsing session JSON, calling `setSessionId`) is not exercised in tests.
- `handleNewSession` parse failure path (`Could not parse session response`) — untested.
- `showModelInfo` with active session — untested (only "No active session" path is tested).

### ShellState
- Zero tests. All three methods (`getSessionId`, `setSessionId`, `clearSessionId`) are untested.

### SubscriptionClient
- Zero tests. Requires WebSocket infrastructure; excluded from unit testing is reasonable, but no integration test covers it either.

## Phase 6 GraphQL API Coverage Gaps

### Queries — coverage
- `users` — COVERED (test: "users query includes the seeded server user")
- `user(id)` happy path — COVERED (test: "user(value) query returns a specific user")
- `user(id)` NOT FOUND path — NOT TESTED (None result path)
- `roles(userId)` — COVERED indirectly (test: "assignRole and roles(value) round-trip")
- `role(id)` happy path — COVERED (test: "role(value) returns a specific role by id")
- `role(id)` NOT FOUND path — NOT TESTED
- `permissions(userId)` — COVERED (test: "grantPermission and permissions(value) round-trip")
- `permissions(userId)` empty / no permissions — covered only via revokePermission test

### Mutations — coverage
- `createUser` — COVERED
- `updateUser` — NOT TESTED (no test whatsoever)
- `createRole` — COVERED
- `assignRole` — COVERED
- `revokeRole` — NOT TESTED (no test whatsoever)
- `grantPermission` — COVERED
- `revokePermission` — COVERED

### Subscriptions
- `approvalNotifications` — NOT TESTED (stub ZStream.empty; low priority until Phase 8)
- `eventLogTail` — NOT TESTED (stub ZStream.empty; low priority until Phase 8)

### Schema validation
- Schema render sanity check — COVERED (test: "schema renders with expected types")

### getRole (repository + service)
- `PermissionRepository.getRole` — COVERED via role(id) GraphQL test and indirectly
- `PermissionServiceImpl.getRole` — COVERED via role(id) GraphQL test
- `getRole` NOT FOUND branch — NOT TESTED (only happy path tested)

### Untested error paths (GraphQL layer)
- `user(id)` for non-existent ID — None is returned but no test asserts this
- `role(id)` for non-existent ID — None is returned but no test asserts this
- `createUser` with duplicate email — no constraint violation test
- `revokePermission(id)` for non-existent id — delete returns 0 but not asserted

### JorlanRoutes.scala
- HTTP route bindings: NOT TESTED via HTTP (all GraphQL tests bypass HTTP, use interpreter directly)
- WebSocket subscription route: NOT TESTED
- GraphiQL route: NOT TESTED

### SchemaGen.scala
- `main()` method: NOT TESTED (trivially calls render; low priority)

### Jorlan.scala (server wiring)
- Server startup + GraphQL route integration: NOT TESTED via HTTP end-to-end

## Patterns
- No error-path tests anywhere in integration layer (constraint violations, duplicate keys, etc.)
- `ChannelType` values Shell, Telegram, Slack, Email, WhatsApp, Sms, GraphQL never appear in `fromProvider` tests (they return None — currently untested)
- `OrchestratorIdentity` domain type has no tests whatsoever
