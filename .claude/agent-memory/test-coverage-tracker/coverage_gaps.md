---
name: coverage-gaps
description: Known untested areas by package, updated 2026-06-12 (includes Phase 13)
metadata:
  type: project
---

## Phase 13 Email/Calendar Skills — Coverage Gaps (2026-06-12)

### OAuthRoutes — ZERO UNIT TESTS (Critical)
- `GET /api/oauth/start/:provider` — missing X-User-Id header → 400 error: NOT TESTED
- `GET /api/oauth/start/:provider` — happy path building authUrl + stateJwt: NOT TESTED
- `GET /api/oauth/callback/google` — missing `code` param → error redirect: NOT TESTED
- `GET /api/oauth/callback/google` — missing `state` param → error redirect: NOT TESTED
- `GET /api/oauth/callback/google` — expired state JWT → error redirect: NOT TESTED
- `GET /api/oauth/callback/google` — invalid HMAC signature → error redirect: NOT TESTED
- `GET /api/oauth/callback/google` — token exchange HTTP error → error redirect: NOT TESTED
- `GET /api/oauth/callback/google` — success path → stores credential + redirects: NOT TESTED
- `verifyStateJwt` private helper — invalid format (no dot): NOT TESTED
- `verifyStateJwt` — expired token: NOT TESTED
- `buildStateJwt` round-trip: NOT TESTED
- NOTE: The JWT format is custom (base64url(payload).HmacSHA256), which has security implications; the signature verification deserves targeted tests

### OAuthCredentialServiceImpl — ZERO UNIT TESTS (Critical)
- `store` round-trip (encrypt + persist to repo): NOT TESTED
- `load` decrypts and returns parsed JSON: NOT TESTED
- `load` when no credential exists → None: NOT TESTED
- `load` with corrupt ciphertext in repo → decrypt failure propagated: NOT TESTED
- `revoke` delegates to repo.delete: NOT TESTED
- `listProviders` delegates to repo.listByUser: NOT TESTED
- `refreshAccessToken` — no credential found → fail: NOT TESTED
- `refreshAccessToken` — no `refresh_token` field in stored JSON → fail: NOT TESTED
- `refreshAccessToken` — HTTP call succeeds → stores updated token: NOT TESTED
- `refreshAccessToken` — HTTP call fails → IO.fail: NOT TESTED
- `mergeAccessToken` replaces old access_token: NOT TESTED
- `extractExpiresAt` from `expiry_date` field: NOT TESTED
- `extractScopes` from `scope` field: NOT TESTED
- NOTE: All 5 callers in production (GmailProvider, GoogleCalendarProvider, GoogleDriveProvider, JorlanAPI, OAuthRoutes) call `refreshAccessToken`; this path is only stubbed in tests

### GraphQL OAuth & invokeTool resolvers — ZERO TEST COVERAGE (High)
- `oauthStatus` query — connected=true when credential exists: NOT TESTED in any spec
- `oauthStatus` query — connected=false when no credential: NOT TESTED
- `oauthStatus` query — unauthenticated → fails: NOT TESTED
- `listOAuthProviders` query — returns list from service: NOT TESTED
- `listOAuthProviders` query — unauthenticated → fails: NOT TESTED
- `startOAuth` mutation — returns redirect URL prefix: NOT TESTED
- `startOAuth` mutation — unauthenticated → fails: NOT TESTED
- `revokeOAuth` mutation — calls OAuthCredentialService.revoke: NOT TESTED
- `revokeOAuth` mutation — unauthenticated → fails: NOT TESTED
- `invokeTool` mutation — dispatches to SkillRegistry: NOT TESTED
- `invokeTool` mutation — unknown tool → JorlanError: NOT TESTED
- `invokeTool` mutation — unauthenticated → fails: NOT TESTED

### GmailProvider / GoogleCalendarProvider / GoogleDriveProvider — ZERO UNIT TESTS (High)
- All three real providers use `withGmail`/`withCalendar`/`withDrive` pattern: NONE TESTED
- Token refresh called on every provider method: not tested at any level (real HTTP calls)
- `messageToEmail` conversion (header extraction, body decoding, attachment parsing): NOT TESTED
- `extractTextBody` multipart parsing: NOT TESTED
- `toInstant` for EventDateTime with date-only (all-day event) vs datetime: NOT TESTED
- `buildRfc822` message construction (to, cc, bcc, replyTo): NOT TESTED
- NOTE: These require live Google API; acceptable, but no wiremock-style HTTP mock layer exists

### EmailSkill — GOOD COVERAGE, two gaps
- email.list: COVERED
- email.list with maxResults: COVERED
- email.read: COVERED
- email.read unknown id: COVERED
- email.read missing messageId: COVERED
- email.send happy path: COVERED
- email.send missing fields: COVERED
- email.draft: COVERED
- email.archive: COVERED
- email.delete: COVERED
- email.reply: COVERED
- email.search: COVERED
- email.search missing query: COVERED
- unknown tool: COVERED
- MISSING: email.send with cc/bcc optional fields populated — args include "cc" and "bcc" arrays; EmailSkill extracts via SkillArgs.strList but no test passes cc/bcc values and verifies them on the sent draft
- MISSING: email.reply missing messageId (no "messageId" arg) — the reply impl calls email.read first; a missing messageId should fail but no test covers this path directly (email.read missing messageId test covers the sub-call, but not through email.reply)

### GoogleCalendarSkill — GOOD COVERAGE, one gap
- calendar.listCalendars: COVERED
- calendar.listEvents: COVERED
- calendar.listEvents missing calendarId: COVERED
- calendar.getEvent: COVERED
- calendar.getEvent unknown event: COVERED
- calendar.createEvent: COVERED
- calendar.createEvent missing fields: COVERED
- calendar.deleteEvent: COVERED
- unknown tool: COVERED
- MISSING: calendar.updateEvent — `calendar.updateEvent` is a registered tool in the invoke dispatch (line 120 of GoogleCalendarSkill.scala) but has NO test in GoogleCalendarSkillSpec. The FakeCalendarProvider in the spec correctly implements updateEvent, but the tool is never called.

### GoogleDriveSkill — GOOD COVERAGE, one gap
- drive.listFiles: COVERED
- drive.listFiles with folderId: COVERED
- drive.listFiles with maxResults: COVERED
- drive.readFile: COVERED
- drive.readFile unknown file: COVERED
- drive.readFile missing fileId: COVERED
- drive.downloadFile: COVERED
- drive.downloadFile missing fileId: COVERED
- unknown tool: COVERED
- MISSING: drive.listFiles with `query` parameter — FakeDriveProvider.listFiles accepts a `query: Option[String]` and filters by filename, but no test passes a `query` argument to verify name-based search filtering

### ExternalCredentialRepository — WELL COVERED
- upsert + find round-trip: COVERED
- upsert updates existing (idempotent): COVERED
- delete removes credential: COVERED
- listByUser returns all credentials for user: COVERED
- find returns None for non-existent: COVERED
- MISSING: find for non-existent provider returns None even when user has other credentials: edge case not explicitly tested (only tested when user has NO credentials at all)
- MISSING: listByUser when user has no credentials → empty list: not directly tested (only tested when user has 2 credentials)

### OAuthCredentialEncryptor — WELL COVERED
- encrypt + decrypt round-trip: COVERED
- encrypt produces different ciphertext (random IV): COVERED
- decrypt with wrong key fails: COVERED
- encrypt + decrypt with JSON object payload: COVERED
- MISSING: decrypt with malformed base64 IV → exception caught → Left(JorlanError): NOT TESTED
- MISSING: decrypt with non-JSON object passed as encrypted payload → Chunk.empty path: NOT TESTED

### New Skills not registered in SkillRegistry.live (Observation)
- EmailSkill, GoogleCalendarSkill, GoogleDriveSkill are registered dynamically in Jorlan.scala startup (lines 152-154) AFTER SkillRegistry.liveSecure is constructed in EnvironmentBuilder
- This means `SkillRegistry.allTools` and `skills` query in tests that use the standard app layer do NOT include these three skills
- No test verifies that the `skills` GraphQL query includes email/calendar/drive tool descriptors after startup registration

## Phase 12 Built-in Skills — Coverage Gaps (2026-06-10)

### SkillRegistry — WELL COVERED, minor gaps remain
- MISSING: validateRequiredFields — `inputSchema` is not a `Json.Obj` → falls to `Right(Nil)` (no required fields assumed); never tested
- MISSING: validateRequiredFields — `required` key absent from schema fields → `orElse(Some(Nil))` → empty required list; never tested
- MISSING: invoke unknown tool name within a known namespace (tool not in descriptor.tools); validateRequiredFields returns `Left("unknown tool '...'")` without calling skill.invoke; not tested
- MISSING: re-registering the same namespace replaces the previous skill (Map semantics)

### NotificationRouter — WELL COVERED, two gaps
- MISSING: notifyUser fallback to first available channel when no Telegram identity exists (orElse(identities.headOption)); only the no-identity path and the Telegram path are tested; the "non-Telegram first-in-list fallback" branch is not
- MISSING: notifyChannel — connector.invoke fails with JorlanError → catchAll returns Json.Str("Error: ..."); no test injects a connector that throws

### ContactsSkill — WELL COVERED, minor gaps
- MISSING: contacts.find — args that are not a Json.Obj; getStr returns None → "name is required" error path, but only Json.Obj() is tested, not a non-object root

### WorkspaceSkill — WELL COVERED, minor gaps
- MISSING: workspaceSearch — prefix resolves to a path that is NOT a directory; the `!Files.isDirectory(p)` branch returns `Option(p.getParent).getOrElse(workspaceRoot)` → not covered
- MISSING: workspaceWrite — Files.createDirectories or Files.write throws a non-security IOException

### ShellSkill — WELL COVERED, minor gaps
- MISSING: getStrList — args with a `args` key that contains non-string array elements (Json.Num inside the array)
- MISSING: Command fails to launch (binary doesn't exist despite being in allowlist)

### AgentRunnerImpl — PARTIALLY COVERED (most coverage via AgentRunnerSpec + AgentRunnerReActSpec)
- MISSING: resolveAgentId — session exists in repo → session.agentId cached; only AgentId.empty (no session) path is exercised
- MISSING: loadConversationHistory — session found, messages loaded → modelGateway.seedHistory called; the actual history-loading path (not-seeded + non-empty messages) is NOT tested
- MISSING: getOrCreateConversation — active conversation found in DB (not in cache) → reuses it
- MISSING: buildMemoryContext error path — memoryService.query failing → catchAll returns ""; no failing MemoryService wired
- MISSING: logSkillEvent — SkillInvoked and SkillSucceeded event types written during ReAct tool call; event log contents not asserted

## Phase 11 Telegram Connector — Coverage Gaps (2026-06-07)

### MessageIngressImpl — PARTIALLY COVERED
- MISSING: ExplicitDeny branch — only DefaultDeny tested
- MISSING: Quarantine policy — `UnrecognizedIdentityPolicy.Quarantine` defined but never tested
- MISSING: session reuse by chatRef — no test sends two messages to same chatRef
- MISSING: event log written on dispatch — not asserted

### ConnectorManager — NO UNIT TESTS
- `ConnectorManagerImpl.startAll` / `stopAll` — no tests

### TelegramConnectorSkill — MOSTLY COVERED, gaps remain
- MISSING: send_photo invoke, send_file invoke
- MISSING: filterUpdates with non-empty allowedChatIds/allowedUserIds
- MISSING: pollLoop offset advancement

### DB: userByChannelIdentity — NOT INTEGRATION-TESTED (Critical path)
### DB: chatRef column on AgentSession — NOT INTEGRATION-TESTED

## Phase 10 Durable Scheduler — Coverage Gaps (2026-06-04)

### TriggerEngine — advanceTriggers NEVER TESTED
- OneShot/Cron/Interval/Event trigger advancement: NOT TESTED
- MissedRunPolicy: NOT IMPLEMENTED OR TESTED

### GraphQL Scheduler API — ZERO unit or integration tests
- `jobs`, `job`, `triggers`, `listApprovals`, `listCapabilities` queries: NOT TESTED
- All scheduler mutations: NOT TESTED

## Phase 9 Memory System — Coverage Gaps (2026-06-03)
### MemoryAccessPolicyImpl — PARTIALLY COVERED
- Workspace scope, Shared scope direct assertion: NOT TESTED
### MemoryServiceImpl — PARTIALLY COVERED
- checkpoint with empty messages list: NOT TESTED
- query with textSearch parameter: only via MemorySkillSpec
### AgentRunnerImpl (memory integration) — MISSING
- buildMemoryContext with real sessions: NOT TESTED
- buildMemoryContext error path: NOT TESTED

## Critical Untested Areas (carry-forward)
1. `JorlanAuthServer` — entire auth layer: login, changePassword, createOAuthUser, etc.
2. `OAuthRoutes` — zero tests; JWT state logic has security implications
3. `OAuthCredentialServiceImpl` — zero unit tests; token refresh is production hot path
4. `UserZIORepository.login` / `changePassword` — security-critical SQL logic never integration-tested
