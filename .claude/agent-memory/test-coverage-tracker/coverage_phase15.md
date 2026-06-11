---
name: coverage-phase15
description: Phase 15 Web Frontend coverage gaps — web/ module and StaticFileRoutes, identified 2026-06-10
metadata:
  type: project
---

## Phase 15 Web Frontend — Coverage Gaps (2026-06-10)

### Zero test files exist for the `web` module
There is no `web/src/test/` directory. The entire Scala.js module is untested.

### JorlanClientDecoders — NO TESTS
- `longDecoder` happy path (valid __NumberValue): NOT TESTED
- `longDecoder` fractional/non-exact BigDecimal → DecodingError: NOT TESTED
- `longDecoder` non-number type → "Expected number" error: NOT TESTED
- `enumDecoder` valid string: NOT TESTED
- `enumDecoder` invalid/unknown string → DecodingError: NOT TESTED
- `modelIdDecoder` with __StringValue: NOT TESTED
- `modelIdDecoder` with non-string → DecodingError: NOT TESTED
- All ArgEncoder instances (round-trip identity): NOT TESTED

### StaticFileRoutes — NO TESTS (server module)
- Existing file served with correct content: NOT TESTED
- Non-existent path falls back to index.html: NOT TESTED
- Empty path returns index.html: NOT TESTED
- Directory request (non-file) falls back to index.html: NOT TESTED

### ScalaJSClientAdapter — NO UNIT TESTS
- `requestToJson` happy path: NOT TESTED
- GQLOperationMessage serialisation/deserialisation: NOT TESTED
- `asyncCalibanCallWithAuth` Left/Right paths: NOT TESTED
- `makeWebSocketClient` all GQL message type handlers: NOT TESTED
- Reconnect backoff logic (`calculateBackoffDelay`): NOT TESTED
- `close()` with CONNECTING state (retry loop): NOT TESTED
- `close()` with OPEN state (sends GQLStop + GQLConnectionTerminate): NOT TESTED
- Max reconnect time exceeded path: NOT TESTED

### ApiClientSttp4 — NO UNIT TESTS
- `withAuth` token-not-set path → onAuthError: NOT TESTED
- `withAuth` token-expired 401 path → refresh flow: NOT TESTED
- `withAuth` refresh success path → new token stored: NOT TESTED
- `withAuth` refresh failure (non-2xx) → onAuthError: NOT TESTED
- `withAuth` refresh missing Authorization header → onAuthError: NOT TESTED
- `withAuth` non-2xx non-401 path → removes token: NOT TESTED
- `withAuth` success path: NOT TESTED

### AppRouter — NO TESTS
- `currentPage` with known hash: NOT TESTED
- `currentPage` with unknown hash → defaults to Chat: NOT TESTED
- Navigation via `navigate` updates state and window.location.hash: NOT TESTED
- All 9 page match arms: NOT TESTED

### Page Components — ENTIRELY UNTESTED
- ChatPage: sendMessage with empty input (early return), sendMessage with no sessionId (no-op), createSession None branch, role color mapping all 4 branches, Enter vs Shift+Enter key handling
- SessionsPage: Terminate button is wired to `Callback.empty` — mutation never called; this is a functional bug
- ApprovalsPage: uses one-time listApprovals query, NOT approvalNotifications subscription as roadmap states; decide() error path not handled
- MemoryPage: search filter client-side logic (`filtered`), forget() error path not handled; markMemoryShared/markMemoryPrivate/storeMemory/Remember dialog absent despite roadmap checkbox
- SchedulerPage: `statusColor` all 6 JobStatus branches, `jobAction` error path (Failure case), toggleExpand already-loaded triggers (cached branch), loadTriggers cache-hit short-circuit
- EventLogPage: expand/collapse toggle, 200-row cap, payloadJson undefined path
- SettingsPage: `update()` called when personality is None (no-op)
- SkillsPage: stub page — no GraphQL call, correct by design

### AppShell — NO TESTS
- Badge count > 0 branch for Approvals nav item: NOT TESTED
- Badge count = 0 branch: NOT TESTED
- Logout button callback: NOT TESTED
- Approval count query error path (completeWith ignores): NOT TESTED

### JorlanClient — NO TESTS
- SelectionBuilder field wiring (field names, arg encoder routing): NOT TESTED
- All `view` selections map tuples correctly: NOT TESTED
- Optional field selections (OptionOf): NOT TESTED

### New server-side GraphQL endpoints (Phase 15 additions) — PARTIALLY TESTED
- `availableModels` query: NOT TESTED in JorlanAPISpec
- `listCapabilities` query: NOT TESTED in JorlanAPISpec
- `decideApproval` mutation: TESTED (capability denial + success + rejected=false)
- `terminateSession` mutation: TESTED (capability denial only; success path NOT TESTED)

### Implementation Gaps vs Roadmap (functional bugs, not just test gaps)
- SessionsPage Terminate button: onClick always `Callback.empty` — `terminateSession` mutation never invoked
- ApprovalsPage uses one-time query, not `approvalNotifications` subscription (roadmap says "live approval list via subscription")
- ChatPage streamBuffer is never populated: `agentResponseStream` WebSocket subscription is never set up (no `makeWebSocketClient` call); streaming state exists but is cosmetic only
- UsersPage: no create/edit dialogs, no roles/permissions UI, no mutations — roadmap items marked [x] are not implemented
- MemoryPage: no markShared/markPrivate buttons, no storeMemory dialog — roadmap items marked [x] are not implemented
- SessionsPage: no create-session dialog, no model picker — roadmap items marked [x] are not implemented

**Why:** These are carry-forward issues from the Phase 15 review.
**How to apply:** When reviewing Phase 15 or working on Phase 16+, these are the highest-priority items to fix or reopen.
