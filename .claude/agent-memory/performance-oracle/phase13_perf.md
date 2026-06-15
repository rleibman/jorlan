---
name: phase13_perf
description: Phase 13 Email/Calendar/Drive performance findings — N+1 token refresh, blocking transport init, new-client-per-call, SecureRandom.getInstanceStrong, no access-token caching
metadata:
  type: project
---

## Phase 13 Performance Findings (Email/Calendar/Drive Skills)

### Critical

- **P13-001**: `GmailProvider.listMessages` — N+1 pattern. One call to list message refs, then one `withGmail` per ref (each fetches a token + creates a Gmail client). For 50 messages = 51 token-refresh round trips + 51 client allocations. File: GmailProvider.scala:134-142. Fix: use Gmail batch API or `batchGet`.
- **P13-002**: `refreshAccessToken` always calls `callTokenRefresh` unconditionally — no expiry check before hitting the Google token endpoint. Every Google API call triggers a full HTTP round trip to `oauth2.googleapis.com/token`. OAuthCredentialServiceImpl.scala:57-67.

### Warning

- **P13-003**: New `Gmail`/`Calendar`/`Drive` client built on every call (`makeGmail`, `makeCalendar`, `makeDrive`). These constructors wrap a reusable `transport` + `jsonFactory` but build a fresh `HttpCredentialsAdapter` and `*.Builder.build()` on every invocation. Under concurrent agents this multiplies allocation pressure. Each provider file, the `with*` helper.
- **P13-004**: `GoogleNetHttpTransport.newTrustedTransport()` called at class construction time on the ZIO fiber thread (not wrapped in `ZIO.attemptBlocking`). Transport init loads TrustManager / SSLContext which can block. GmailProvider.scala:37, GoogleCalendarProvider.scala:33, GoogleDriveProvider.scala:32.
- **P13-005**: `SecureRandom.getInstanceStrong` called on every `encrypt()` invocation. `getInstanceStrong` may block on entropy collection (platform-dependent, notably slow on Linux without hardware RNG). OAuthCredentialEncryptor.scala:36. Fix: instantiate once and share with synchronization, or use `new SecureRandom()`.
- **P13-006**: `updateEvent` in `GoogleCalendarSkill` issues a `getEvent` API call followed immediately by `updateEvent` — two round trips to Google Calendar API for a single user-visible operation. GoogleCalendarSkill.scala:258-279. Fix: let caller supply the full updated entry directly.

### Info / Suggestion

- **P13-007**: `Chunk.toMap` in `OAuthCredentialEncryptor.decrypt` creates an intermediate `Map[String, Json]` from `Chunk[(String, Json)]` on every decryption. Negligible in isolation but called on every `refreshAccessToken`. Consider direct field lookup with `collectFirst`. OAuthCredentialEncryptor.scala:55.
- **P13-008**: `OAuthCredentialServiceImpl.refreshAccessToken` does: DB read → decrypt → HTTP token refresh → encrypt → DB write (5 sequential operations). No short-circuit if the stored access_token is still valid (no expiry comparison). A cached `Ref[Map[(UserId,provider), (token, expiresAt)]]` in the service would reduce DB + HTTP calls by ~90% for normal usage.
- **P13-009**: `external_credentials` table has `UNIQUE KEY uq_user_provider(user_id, provider)` which doubles as a lookup index — this is correct and adequate for current access patterns.
