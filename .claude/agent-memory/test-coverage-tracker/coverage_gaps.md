---
name: coverage-gaps
description: Known untested areas by package, updated 2026-06-29 (includes Sprint 1/2/3)
metadata:
  type: project
---

## Sprint 3 — Approvals, Discord, RSS (2026-06-29)

### ApprovalHub — WELL COVERED, minor gaps
- awaitDecision approved concurrently: COVERED
- awaitDecision denied concurrently: COVERED
- race safety (preDecision): COVERED
- timeout: COVERED
- single subscriber receives request: COVERED
- multiple subscribers: COVERED
- MISSING: subscriber queue cleanup after stream terminates (the `ensuring` block)
- MISSING: notifyNewRequest with zero subscribers (trivial no-op path)
- MISSING: multiple requests to same subscriber

### ToolEventHub — WELL COVERED, minor gaps
- subscriber receives event for its session: COVERED
- different session filtered out: COVERED
- publish with no subscriber is no-op: COVERED
- multiple subscribers same session: COVERED
- MISSING: subscriber cleanup after stream terminates
- MISSING: ToolResultEvent delivery (only ToolInvokedEvent used in filtering test)
- MISSING: multiple session isolation test

### HumanApprovalNotifierImpl — WELL COVERED, minor gaps
- logs ApprovalRequested event: COVERED
- second request succeeds: COVERED
- MISSING: resource field in EventLog not asserted (set to Some(request.id))
- MISSING: request with agentId set

### ApprovalServiceImpl (unit) — WELL COVERED, gaps remain
- recordDecision Approved: COVERED
- recordDecision Rejected: COVERED
- session branch with/without sessionId: COVERED
- PerInvocation → PendingApproval: COVERED
- Once → PendingApproval (no existing): COVERED
- Session → PendingApproval (no existing): COVERED
- recordDecision with Pending status fails: COVERED
- authorize Allowed (Persistent): COVERED
- authorize Denied: COVERED
- expireStaleRequests: COVERED
- MISSING: Cancelled/Expired status → EventType.ApprovalDenied branch
- MISSING: Once grant with existing approved request → Allowed
- MISSING: hub.notifyNewRequest call verified (wired but not asserted)

### ApprovalService integration (db/ApprovalServiceSpec) — WELL COVERED
- Persistent grant → Allowed + event: COVERED
- Denied grant → Denied + event: COVERED
- PerInvocation → PendingApproval with DB-assigned id: COVERED
- expireStaleRequests with stale: COVERED
- expireStaleRequests with none stale: COVERED
- MISSING: recordDecision at DB level (integration)
- MISSING: Once grant end-to-end approval flow

### DiscordConnectorSkill — WELL COVERED, gaps remain
- descriptor fields: COVERED
- send_message happy path: COVERED
- send_dm happy path: COVERED
- get_channel_info: COVERED
- get_history: COVERED
- unknown tool: COVERED
- send_message missing channelId: COVERED
- send_message missing content: COVERED
- send_dm missing userId: COVERED
- start/stop: COVERED
- event loop normalizes DM: COVERED
- event loop skips bots: COVERED
- event loop drops unlisted guilds: COVERED
- event loop drops unlisted users: COVERED
- mentionOnly config: COVERED
- MISSING: send_dm missing content field
- MISSING: sendToChannel/sendToDm apiClient failure path
- MISSING: get_channel_info missing channelId
- MISSING: get_history missing channelId

### DiscordMessageNormalizer — WELL COVERED, minor gaps
- DM → Private: COVERED
- guild → Group: COVERED
- bot dropped: COVERED
- content preserved: COVERED
- chatRef: COVERED
- channelUserId: COVERED
- receivedAt: COVERED
- MISSING: empty content string
- MISSING: isMention=true on DM (guildId=None)

### RssFeedParser — GOOD COVERAGE, edge case gaps
- RSS 2.0 parsing: COVERED
- Atom 1.0 parsing: COVERED
- limit: COVERED
- invalid XML: COVERED
- non-feed root element: COVERED
- MISSING: empty channel (no items) returns empty list
- MISSING: Atom entry with updated fallback (no published)
- MISSING: Atom entry with content (no summary)
- MISSING: Atom link rel="self" filtered vs rel="alternate" used

### RssFeedSkill — PARTIAL COVERAGE — rss.fetch ZERO TESTS
- rss.list_saved empty: COVERED
- rss.list_saved with feeds: COVERED
- rss.save_feed adds URL: COVERED
- rss.save_feed dedup: COVERED
- rss.remove_feed removes: COVERED
- rss.remove_feed no-op: COVERED
- unknown tool: COVERED
- MISSING: rss.fetch — ENTIRE TOOL UNTESTED (fetchFeed, fetchXml, HTTP error, parse, encode)
- MISSING: rss.save_feed missing url field
- MISSING: rss.remove_feed missing url field
- MISSING: args is not JSON object → ValidationError
- MISSING: limit clamping behavior (min 1, max 50)
- MISSING: totalFeeds field not asserted in save_feed response

## Sprint 2 — Declarative Skills (2026-06-26)

### ManifestValidatorSpec — GOOD COVERAGE, minor gaps
- valid http_api: COVERED
- valid prompt_template: COVERED
- invalid JSON: COVERED
- name with uppercase: COVERED
- name with spaces: COVERED
- name with underscore/digits: COVERED
- empty version: COVERED
- empty description: COVERED
- empty tools: COVERED
- tool without prefix: COVERED
- unsupported HTTP method: COVERED
- empty URL: COVERED
- empty systemPrompt: COVERED
- MISSING: empty userPromptTemplate in PromptTemplate fails
- MISSING: empty tool.description fails
- MISSING: non-object inputSchema fails
- MISSING: multiple simultaneous errors accumulation

### SkillLifecycleServiceSpec — GOOD COVERAGE, gaps remain
- createDraft creates Draft: COVERED
- createDraft invalid manifest fails: COVERED
- advance Draft → Validated: COVERED
- advance Validated → PermissionReviewed: COVERED
- advance full pipeline to AwaitingApproval: COVERED
- approve from AwaitingApproval → Active: COVERED
- approve from non-AwaitingApproval fails: COVERED
- reject returns to Draft with note: COVERED
- advance from AwaitingApproval returns error: COVERED
- MISSING: advance — version not found → JorlanError
- MISSING: approve/reject — version not found → JorlanError
- MISSING: reject from non-AwaitingApproval status fails
- MISSING: createDraft for second version of same existing skill (existing headOption → Some branch)
- MISSING: approve — skill registered in SkillRegistry after approval (not asserted)

### HttpApiExecutor — ZERO DIRECT TESTS (Critical)
- URL template substitution: NOT TESTED
- HTTP method dispatch (GET/POST/PUT/DELETE/PATCH): NOT TESTED
- JSON response parsing: NOT TESTED
- plain text response (non-JSON body): NOT TESTED
- HTTP 4xx/5xx response → JorlanError: NOT TESTED
- invalid URL → JorlanError: NOT TESTED
- body template substitution: NOT TESTED

### PromptTemplateExecutor — ZERO DIRECT TESTS (Critical)
- template substitution for systemPrompt: NOT TESTED
- template substitution for userPromptTemplate: NOT TESTED
- FinalAnswer path → Json.Str: NOT TESTED
- ToolCallRequested path → JorlanError: NOT TESTED

### DeclarativeSkill — ZERO DIRECT TESTS (Critical)
- unknown tool returns JorlanError: NOT TESTED
- routes to HttpApiExecutor for HttpApi executor: NOT TESTED
- routes to PromptTemplateExecutor for PromptTemplate executor: NOT TESTED

### SkillAuthoringSkill — ZERO TESTS (Critical)
- skill_authoring.propose happy path (4-advance auto-pipeline): NOT TESTED
- skill_authoring.propose manifest field missing: NOT TESTED
- skill_authoring.propose invalid JSON manifest string: NOT TESTED
- skill_authoring.propose with validation errors stops early: NOT TESTED
- unknown tool: NOT TESTED

### GraphQL toolEvents subscription — ZERO TESTS (High)
- toolEvents subscription receives ToolInvokedEvent: NOT TESTED
- toolEvents subscription receives ToolResultEvent: NOT TESTED
- toolEvents subscription session-isolation: NOT TESTED

### InMemorySkillRepo connector stubs — LANDMINE
- getConnector, searchConnectors, upsertConnector throw ??? — will fail at runtime
  for any test that exercises connector paths via InMemoryRepositories

### approvalNotifications GQL subscription — PARTIAL (High)
- schema contains approvalNotifications: COVERED
- subscription yields empty stream without published requests: COVERED
- MISSING: subscription receives published request end-to-end at GQL layer

## Phase 13 Email/Calendar Skills — Coverage Gaps (2026-06-12)
[carry-forward — see prior entries; gaps remain as documented]

## Phase 12 Built-in Skills — Coverage Gaps (2026-06-10)
[carry-forward — see prior entries; gaps remain as documented]

## Phase 11 Telegram Connector — Coverage Gaps (2026-06-07)
[carry-forward — see prior entries; gaps remain as documented]

## Phase 10 Durable Scheduler — Coverage Gaps (2026-06-04)
[carry-forward — see prior entries; gaps remain as documented]

## Critical Untested Areas (carry-forward)
1. JorlanAuthServer — entire auth layer: login, changePassword, createOAuthUser, etc.
2. OAuthRoutes — zero tests; JWT state logic has security implications
3. OAuthCredentialServiceImpl — zero unit tests; token refresh is production hot path
4. UserZIORepository.login / changePassword — security-critical SQL logic never integration-tested
