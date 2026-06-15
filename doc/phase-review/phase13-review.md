/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# Phase 13 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala Reviewer, Code Simplicity Reviewer, Performance Oracle, Pattern
Recognition Specialist, Test Coverage Tracker, SRS/SDD Conformance Reviewer, ScalaDoc Auditor, Security Reviewer, UI
Test Plan Writer)
**Date**: 2026-06-12
**Branch**: `phase-13/emailAndCalendar`
**Scope**: Phase 13 — Email/Calendar Skills (`email` module, `google-services` module, `GmailProvider.scala`,
`GoogleCalendarProvider.scala`, `GoogleDriveProvider.scala`, `EmailSkill.scala`, `GoogleCalendarSkill.scala`,
`GoogleDriveSkill.scala`, `OAuthRoutes.scala`, `OAuthCredentialEncryptor.scala`, `OAuthCredentialServiceImpl.scala`,
`QuillExternalCredentialRepository`, `V025__external_credentials.sql`, `JorlanAPI.scala` new resolvers,
`CommandHandler.scala` shell commands, `EnvironmentBuilder.scala`)

---

## Executive Summary

Phase 13 delivers a coherent set of email and calendar skills built on Google's native Java client libraries. The
generic `F[_]` provider abstraction (`EmailProvider`, `CalendarProvider`, `DriveProvider`) is a sound architectural
choice that cleanly separates the Google-specific implementation from the skill layer. The `OAuthCredentialEncryptor`
introduces AES/GCM encryption for stored credentials, V025 adds the `externalCredential` table with correct FK
constraints, and the 1047-test count demonstrates solid overall test discipline in the rest of the codebase. The ZIO
effect model is applied consistently throughout the new code, and `QuillExternalCredentialRepository` follows the
established Quill/Flyway pattern.

Several critical issues must be resolved before Phase 14 begins. The most severe is that the `invokeTool` GraphQL
mutation has no capability guard, meaning any authenticated user can invoke `email.send`, `calendar.deleteEvent`, or
`drive.downloadFile` on behalf of any actorId (confirmed by 2 reviewers). The OAuth start route trusts the `X-User-Id`
header from an unauthenticated caller, allowing an attacker to link another user's Google account (Security Reviewer).
The encryption key for stored credentials is derived from the same secret as the JWT signing key using a non-standard
SHA-256 construction, with no rotation path (confirmed by 2 reviewers). The `emailDelete` handler was copy-pasted from
`emailArchive` and logs `EventType.EmailMessageArchived` for a delete operation — a correctness bug confirmed by 3
reviewers. The `calendarId` parameter is incorrectly required in `calendar.listEvents`, breaking `/calendar today` and
`/calendar list` (SRS/SDD Conformance Reviewer). OAuthRoutes, OAuthCredentialServiceImpl, and all five new GraphQL
resolvers have zero test coverage (confirmed by 2 reviewers). The N+1 token refresh pattern in
`GmailProvider.listMessages` issues one full OAuth HTTP round-trip per message (confirmed by 3 reviewers).

**Overall health: Issues Present — ready to advance to Phase 14 with open items tracked.**

ScalaDoc coverage for the new modules is thin. `OAuthCredentialEncryptor`, `OAuthCredentialService`, all three provider
traits, and all three provider implementations lack class-level documentation. Several implementation choices (per-call
token refresh, null expiry sentinel, `CalendarEventId("")` as new-event sentinel) are undocumented in-code and will be
opaque to the next developer. The Phase 13 manual testing guide entries are also missing.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area                | Issue                                                                                                                                                                                             | File : Line                                                                                          | Recommended Action                                                                                                                                                |
|--------|------------|------------|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | P13-001    | Critical   | Security            | `invokeTool` GraphQL mutation has no `requireCapability` guard — any authenticated user can invoke `email.send`, `calendar.deleteEvent`, etc. (confirmed by 2 reviewers)                          | `JorlanAPI.scala:926-931`                                                                            | Add `requireCapability` check before dispatch; map each tool name to its required capability; fail with `Forbidden` if not held.                                  |
| [x]    | P13-002    | Critical   | Security            | OAuth start route trusts `X-User-Id` header from unauthenticated caller — attacker can link another user's Google account.                                                                        | `OAuthRoutes.scala:108-114`                                                                          | Require a valid session JWT; extract `userId` from the verified token rather than from a caller-supplied header.                                                  |
| [x]    | P13-003    | Critical   | Security            | Encryption key derived via SHA-256(salt‖secret) from the same secret used for JWT signing; rotation breaks all stored credentials. (confirmed by 2 reviewers)                                     | `OAuthCredentialEncryptor.scala:27-31`, `EnvironmentBuilder.scala:73`                                | Use a dedicated `JORLAN_CREDENTIAL_ENCRYPTION_KEY` env var; derive the key with HKDF or PBKDF2 rather than bare SHA-256.                                          |
| [x]    | P13-004    | Critical   | Correctness         | `emailDelete` was copy-pasted from `emailArchive` and logs `EventType.EmailMessageArchived` for a delete — wrong event type in the audit trail. (confirmed by 3 reviewers)                        | `EmailSkill.scala:284`                                                                               | Add `EventType.EmailMessageDeleted`; replace `EmailMessageArchived` in the delete path with the new variant.                                                      |
| [x]    | P13-005    | Critical   | Correctness         | `calendarId` is required in `calendar.listEvents` tool schema, breaking `/calendar today` and `/calendar list` shell commands that omit it. (SRS/SDD Conformance Reviewer)                        | `GoogleCalendarSkill.scala:57`, `CommandHandler.scala:661,681`                                       | Make `calendarId` optional with a default of `"primary"`; update shell commands to omit the field rather than hardcoding it.                                      |
| [x]    | P13-006    | Critical   | Security            | `email.send` and `calendar.write` seeded as `ApprovalMode.Persistent` instead of `PerInvocation` — per-operation approval gate removed.                                                           | `InitService.scala:230`                                                                              | Change both capability seeds to `ApprovalMode.PerInvocation` as specified.                                                                                        |
| [x]    | P13-007    | Critical   | Test Coverage       | `OAuthRoutes` has zero test coverage — `startRoute`, `callbackRoute`, `verifyStateJwt`, and `buildStateJwt` are entirely untested. (confirmed by 2 reviewers)                                     | `OAuthRoutes.scala`                                                                                  | Add `OAuthRoutesSpec` with unit tests for state JWT round-trip, callback happy path, and tampered-state rejection.                                                |
| [x]    | P13-008    | Critical   | Test Coverage       | `OAuthCredentialServiceImpl` has zero unit tests for `store`, `load`, `revoke`, `listProviders`, and `refreshAccessToken`. (confirmed by 2 reviewers)                                             | `OAuthCredentialServiceImpl.scala`                                                                   | Add `OAuthCredentialServiceSpec` with a fake repository; cover encrypt-on-store, decrypt-on-load, and revoke-clears-row paths.                                    |
| [x]    | P13-009    | Critical   | Test Coverage       | All five new GraphQL resolvers (`oauthStatus`, `listOAuthProviders`, `startOAuth`, `revokeOAuth`, `invokeTool`) have zero test coverage. (confirmed by 2 reviewers)                               | `JorlanAPI.scala:639-931`                                                                            | Add Caliban unit tests for each resolver; cover happy path and at least one authorization-failure path each.                                                      |
| [x]    | P13-010    | Critical   | API Design          | Web `JorlanClient` has no bindings for `startOAuth`, `revokeOAuth`, `invokeTool`, `oauthStatus`, or `listOAuthProviders` — Phase 13 APIs are unreachable from the web SPA.                        | `JorlanClient.scala`                                                                                 | Add GraphQL operation definitions and companion case classes for all five new operations; wire into `JorlanClient`.                                               |
| [x]    | P13-011    | Critical   | API Design          | `startOAuth` returns a server-relative path (`/api/oauth/start/google`) rather than a full Google authorization URL — shell and web callers receive an unusable value. (confirmed by 2 reviewers) | `JorlanAPI.scala:918`, `OAuthRoutes.scala`                                                           | Return the full `https://accounts.google.com/o/oauth2/auth?...` URL from `startOAuth`, or redirect via `OAuthRoutes`; document which form is intended.            |
| [x]    | P13-012    | Critical   | Performance         | N+1 token refreshes in `GmailProvider.listMessages` — one OAuth HTTP call per message; 10 messages = 10 unnecessary refreshes. (confirmed by 3 reviewers)                                         | `GmailProvider.scala:134-142`                                                                        | Hoist token acquisition outside the per-message loop; share the single refreshed credential for all messages in the batch.                                        |
| [x]    | P13-013    | Critical   | Performance         | `refreshAccessToken` is called unconditionally on every API operation with no expiry check — doubles latency for every email, calendar, and drive call. (confirmed by 3 reviewers)                | `OAuthCredentialServiceImpl.scala:57-67`, `GmailProvider.scala:51`                                   | Check `expiresAt` before refreshing; only call `callTokenRefresh` when the stored token is within a small margin of expiry (e.g., 60 seconds).                    |
| [x]    | P13-014    | Warning    | Security            | No nonce in state JWT; 30-minute replay window; consumed state tokens are not invalidated server-side — CSRF replay is possible.                                                                  | `OAuthRoutes.scala:64-70`                                                                            | Include a random nonce in the state JWT; store it in a short-lived DB/cache table; mark it consumed on first use.                                                 |
| [x]    | P13-015    | Warning    | Security            | Google error body logged verbatim and reflected to the caller — potential information disclosure of internal Google API error messages.                                                           | `OAuthRoutes.scala:160-170`                                                                          | Log the raw Google error at DEBUG; return a sanitized message to the caller.                                                                                      |
| [x]    | P13-016    | Warning    | Security            | `callTokenRefresh` POST body is not URL-encoded — `client_id`, `client_secret`, and `refresh_token` values with special characters will break token exchange or be injected.                      | `OAuthCredentialServiceImpl.scala:105-107`                                                           | Use `URLEncoder.encode` (or `java.net.URI`) for each field; join with `&`.                                                                                        |
| [x]    | P13-017    | Warning    | Security            | `expiresAt` is not updated after a successful token refresh — `expires_in` from the Google response is discarded; stale expiry drives unnecessary future refreshes.                               | `OAuthCredentialServiceImpl.scala:88-92`                                                             | Parse `expires_in` from the token response and compute `Instant.now().plusSeconds(expiresIn)`; store it in `ExternalCredential.expiresAt`.                        |
| [x]    | P13-018    | Warning    | Error Handling      | `callTokenRefresh` does not check HTTP status before parsing the body as `TokenResponse` — a 4xx/5xx error response is silently stored as a credential.                                           | `OAuthCredentialServiceImpl.scala:104-120`                                                           | Assert `response.statusCode == 200` before parsing; propagate an error on non-success; log the raw body at DEBUG.                                                 |
| [x]    | P13-019    | Warning    | Correctness         | `drive.downloadFile` stores an artifact record with a dangling URI — file bytes are downloaded but never written to any storage backend.                                                          | `GoogleDriveSkill.scala:145-172`                                                                     | Either write bytes to the configured storage backend before creating the artifact, or return raw bytes and omit the artifact record until storage is implemented. |
| [x]    | P13-020    | Warning    | Correctness         | `EmailSkill.buildDraft` silently ignores `signWithPgp` from tool arguments — always drafts with PGP disabled regardless of caller intent.                                                         | `EmailSkill.scala:186`                                                                               | Thread `signWithPgp` through to `PgpService`; when `PgpService.noOp` is in use, return a warning in the tool result rather than silently dropping the intent.     |
| [x]    | P13-021    | Warning    | Architecture        | `ImapSmtpProvider` is registered as a production provider but all methods throw `NotImplementedError` at runtime with no startup warning.                                                         | `ImapSmtpProvider.scala`                                                                             | Either implement or replace with an explicit `UnimplementedEmailProvider` that returns `ZIO.fail(NotImplemented(...))` with a clear user-facing error.            |
| [x]    | P13-022    | Warning    | Resource Management | New Google API client object (`GoogleCredentials`, `HttpCredentialsAdapter`, transport) built on every API call — no caching or pooling. (confirmed by 2 reviewers)                               | `GmailProvider.scala:40-47`, `GoogleCalendarProvider.scala:36-43`, `GoogleDriveProvider.scala:35-42` | Cache the client in a `Ref` keyed by `userId`; invalidate after token refresh; share the `HttpTransport` singleton.                                               |
| [x]    | P13-023    | Warning    | Resource Management | `GoogleNetHttpTransport.newTrustedTransport()` called at class construction time outside `ZIO.attemptBlocking` — blocks a ZIO fiber thread at startup.                                            | `GmailProvider.scala:37`, `GoogleCalendarProvider.scala:33`, `GoogleDriveProvider.scala:32`          | Move transport creation into `ZIO.attemptBlocking`; create once at layer initialization via `ZLayer.fromZIO`.                                                     |
| [x]    | P13-024    | Warning    | Functional Purity   | `SecureRandom.getInstanceStrong()` instantiated on every `encrypt()` call — potentially blocking and allocates a new CSPRNG on each credential write.                                             | `OAuthCredentialEncryptor.scala:36`                                                                  | Instantiate once at layer initialization and share via `Ref` or constructor parameter.                                                                            |
| [x]    | P13-025    | Warning    | Code Quality        | `logEvent` helper duplicated verbatim in `EmailSkill`, `GoogleCalendarSkill`, `GoogleDriveSkill`, and `ShellSkill` — four identical copies.                                                       | `EmailSkill.scala:151`, `GoogleCalendarSkill.scala:125`, `GoogleDriveSkill.scala:88`                 | Extract a `SkillEventLogger` trait with the shared `logEvent` method; mix it into all skill implementations.                                                      |
| [x]    | P13-026    | Warning    | Code Quality        | Google API transport/factory/`makeXxx`/`withXxx` pattern duplicated across all three providers — significant structural copy-paste.                                                               | `GmailProvider.scala:37-54`, `GoogleCalendarProvider.scala:33-50`, `GoogleDriveProvider.scala:32-49` | Extract an abstract `GoogleApiProvider[C]` base class parameterized on the client type; pull the credential/transport wiring into the base.                       |
| [x]    | P13-027    | Warning    | API Design          | `oauthStatus` resolver hardcodes `expiresAt = None` despite the value being stored in `ExternalCredential.expiresAt`.                                                                             | `JorlanAPI.scala:647`                                                                                | Map `ExternalCredential.expiresAt` to the GraphQL response field.                                                                                                 |
| [x]    | P13-028    | Warning    | API Design          | `startOAuth` mutation and `oauthStatus`/`listOAuthProviders` queries have no capability gate (undocumented omission, low but non-zero risk).                                                      | `JorlanAPI.scala:639-655,914-918`                                                                    | Add at minimum `requireAuthenticated`; document the decision to omit a capability check in a comment.                                                             |
| [x]    | P13-029    | Warning    | Correctness         | `verifyStateJwt` uses `Instant.now()` as a direct side effect inside a function that is not wrapped in ZIO — breaks time-based testability.                                                       | `OAuthRoutes.scala:86`                                                                               | Accept a `Clock` parameter or wrap in `ZIO.clockWith`; enables deterministic testing of expiry logic.                                                             |
| [x]    | P13-030    | Warning    | Architecture        | Anonymous `IOExternalCredentialRepository` adapter in `EnvironmentBuilder` is 20 lines of untestable, non-reusable glue code.                                                                     | `EnvironmentBuilder.scala:77-96`                                                                     | Extract to a named `IOExternalCredentialRepository` class in the `db` module; wire via `ZLayer`.                                                                  |
| [x]    | P13-031    | Warning    | API Design          | `startOAuth` accepts any provider name string; unknown providers produce an empty-scope URL silently rather than a `BadRequest` error.                                                            | `OAuthRoutes.scala:116`                                                                              | Validate the provider name against a known set (`Set("google")`); return `ZIO.fail(BadRequest(...))` for unknown values.                                          |
| [x]    | P13-032    | Warning    | Test Coverage       | `calendar.updateEvent` tool is never exercised in `GoogleCalendarSkillSpec`.                                                                                                                      | `GoogleCalendarSkillSpec.scala`                                                                      | Add a test covering the read-modify-write cycle; assert updated fields are persisted.                                                                             |
| [x]    | P13-033    | Warning    | Test Coverage       | `drive.listFiles` with a non-empty query parameter is untested.                                                                                                                                   | `GoogleDriveSkillSpec.scala`                                                                         | Add a test supplying a query string and assert the Google API client receives the correct filter.                                                                 |
| [x]    | P13-034    | Warning    | Test Coverage       | New Phase 13 email/calendar/drive skills are not registered in `SkillRegistry` during integration tests — their tool call paths are never exercised end-to-end.                                   | `Jorlan.scala:152-154`                                                                               | Register `EmailSkill`, `GoogleCalendarSkill`, and `GoogleDriveSkill` in the integration test `SkillRegistry` layer using fake providers.                          |
| [x]    | P13-035    | Warning    | Documentation       | `/oauth`, `/email`, and `/calendar` shell commands are not listed in `/help`. (UI Test Plan Writer)                                                                                               | `CommandHandler.scala:124`                                                                           | Add entries to the help text for all three command groups.                                                                                                        |
| [x]    | P13-036    | Warning    | API Design          | Web `JorlanWebApp.scala` does not handle `oauth=success` / `oauth=error` query parameters after the OAuth callback redirect — users return to a blank page.                                       | `JorlanWebApp.scala:85`                                                                              | Parse the query parameter on app load; display a success/error toast and redirect to the OAuth management page.                                                   |
| [x]    | P13-037    | Warning    | API Design          | No OAuth management page or route in the web frontend — users cannot see connection status or revoke access from the SPA.                                                                         | `AppRouter.scala`                                                                                    | Add `AppPage.OAuth` route and an `OAuthManagementPage` component that calls `oauthStatus`, `listOAuthProviders`, `startOAuth`, and `revokeOAuth`.                 |
| [x]    | P13-038    | Warning    | Documentation       | Roadmap items unchecked: `OAuthRoutesSpec`, `GmailProviderSpec`, `PgpServiceSpec`, test coverage ≥ 80%, `scalafmtAll`, Skills appendix, module dependency graph.                                  | `doc/development_roadmap.md`                                                                         | Complete each item or explicitly defer with a phase note; update roadmap checkboxes.                                                                              |
| [x]    | P13-039    | Suggestion | Security            | Empty-string Google credential defaults in `configuration.scala`; no startup validation that they are populated.                                                                                  | `configuration.scala:89-93`                                                                          | Add startup guard that fails with a descriptive error if `google.clientId` or `google.clientSecret` is empty.                                                     |
| [x]    | P13-040    | Suggestion | Security            | OAuth scopes stored in plaintext in the `externalCredential` table alongside the encrypted credential blob.                                                                                       | `V025__external_credentials.sql:7`                                                                   | Document explicitly whether this is intentional (scopes are not secret); add a comment to the migration.                                                          |
| [x]    | P13-041    | Suggestion | Performance         | `calendar.updateEvent` issues two Google API calls (read-modify-write) — doubles network latency for every update.                                                                                | `GoogleCalendarSkill.scala:255-280`                                                                  | Accept a full event patch payload from the caller when possible; use the Google Calendar patch API to send only changed fields.                                   |
| [x]    | P13-042    | Suggestion | Performance         | No in-process access token cache — every API call hits the DB and then Google's token endpoint.                                                                                                   | `OAuthCredentialServiceImpl.scala`                                                                   | Add a short-lived (5-minute) in-process cache keyed by `(userId, provider)` that is invalidated on revoke and after token refresh.                                |
| [x]    | P13-043    | Suggestion | Performance         | `Chunk.toMap` allocates an intermediate `Map` on every `decrypt` call for a two-field JSON object.                                                                                                | `OAuthCredentialEncryptor.scala:55`                                                                  | Parse the two fields directly with pattern matching on the JSON AST; avoid the intermediate `Map` allocation.                                                     |
| [x]    | P13-044    | Suggestion | Code Quality        | `emailReply` uses nested `Option` match; should use `ZIO.fromOption.orElseFail` for-comprehension pattern.                                                                                        | `EmailSkill.scala:288-316`                                                                           | Refactor to idiomatic ZIO option handling; reduces nesting by two levels.                                                                                         |
| [x]    | P13-045    | Suggestion | Code Quality        | `ImapSmtpProvider.pgp` is an unused constructor parameter.                                                                                                                                        | `ImapSmtpProvider.scala:32`                                                                          | Remove the parameter or use it in at least one method; add a TODO comment if intentional.                                                                         |
| [x]    | P13-046    | Suggestion | Code Quality        | `extractScopes` is a one-liner that duplicates `extractField(json, "scope")` — unnecessary abstraction.                                                                                           | `OAuthCredentialServiceImpl.scala:69-86`                                                             | Inline `extractField(json, "scope")` at the call site; remove `extractScopes`.                                                                                    |
| [x]    | P13-047    | Suggestion | Code Quality        | `GoogleDriveProvider.listFiles` uses a `null` sentinel for the optional query parameter — should use `Option`.                                                                                    | `GoogleDriveProvider.scala:62-67`                                                                    | Change the parameter to `Option[String]`; use `.orNull` only at the boundary where the Google client requires it.                                                 |
| [x]    | P13-048    | Suggestion | Correctness         | `ToolDescriptor.inputSchema` silently falls back to `Json.Obj()` on a malformed schema literal — 17 occurrences across the three new skill files with no log or error.                            | `EmailSkill.scala`, `GoogleCalendarSkill.scala`, `GoogleDriveSkill.scala`                            | Parse schema literals at startup; fail fast with a descriptive error if any literal is malformed.                                                                 |
| [x]    | P13-049    | Suggestion | Correctness         | `GmailProvider.buildRfc822` is missing a `From:` header — RFC 822 requires it; many SMTP servers will reject or mangle the message.                                                               | `GmailProvider.scala:183-189`                                                                        | Retrieve the authenticated user's email address (available from the Google People API or the credential record) and add a `From:` header.                         |
| [x]    | P13-050    | Suggestion | Correctness         | `CalendarEventId("")` used as a sentinel for new events with no documentation of the convention.                                                                                                  | `GoogleCalendarSkill.scala:231`                                                                      | Document the sentinel in a comment; consider a sealed ADT (`NewEvent                                                                                              | ExistingEvent(id)`) to make the intent type-safe.                                           |
| [x]    | P13-051    | Suggestion | Documentation       | `OAuthCredentialEncryptor` has no ScalaDoc describing the cipher (AES/GCM), key derivation, or IV strategy.                                                                                       | `OAuthCredentialEncryptor.scala:23`                                                                  | Add class-level ScalaDoc covering algorithm choices and the security rationale; note the key-reuse issue (P13-003) as a known limitation until fixed.             |
| [x]    | P13-052    | Suggestion | Documentation       | `OAuthCredentialService` trait is undocumented — the encrypt-on-store / decrypt-on-load contract and the live HTTP call in `refreshAccessToken` are not visible to callers.                       | `model/src/main/scala/jorlan/model/service/OAuthCredentialService.scala:18`                          | Add class-level ScalaDoc and `@param`/`@return` for each method.                                                                                                  |
| [x]    | P13-053    | Suggestion | Documentation       | `ImapSmtpProvider` ScalaDoc implies a working implementation; all methods are unimplemented stubs.                                                                                                | `ImapSmtpProvider.scala:23`                                                                          | Update ScalaDoc to state the class is a placeholder; reference the GitHub issue or roadmap item for the real implementation.                                      |
| [x]    | P13-054    | Suggestion | Documentation       | `PgpService.noOp` is undocumented as a null-object placeholder.                                                                                                                                   | `PgpService.scala:26`                                                                                | Add a ScalaDoc comment noting this is a no-op implementation; reference `P13-020`.                                                                                |
| [x]    | P13-055    | Suggestion | Documentation       | `EmailProvider`, `CalendarProvider`, and `DriveProvider` traits lack class-level ScalaDoc.                                                                                                        | `model/src/main/scala/jorlan/model/service/`                                                         | Add ScalaDoc for each trait explaining its role and the expected lifecycle of its methods.                                                                        |
| [x]    | P13-056    | Suggestion | Documentation       | `GmailProvider`, `GoogleCalendarProvider`, and `GoogleDriveProvider` lack class-level ScalaDoc explaining the per-call token refresh pattern.                                                     | `google-services/`                                                                                   | Add class-level ScalaDoc; note that token refresh is currently unconditional (P13-013) and link to the fix plan.                                                  |
| [x]    | P13-057    | Suggestion | Documentation       | `ExternalCredential.credentialData` has no ScalaDoc noting it is stored encrypted.                                                                                                                | `externalCredential.scala:22`                                                                        | Add `/** Stored encrypted via [[OAuthCredentialEncryptor]]. Never log or transmit in plaintext. */`.                                                              |
| [x]    | P13-058    | Suggestion | Documentation       | State JWT 1800-second constant is unnamed and undocumented in `OAuthRoutes`.                                                                                                                      | `OAuthRoutes.scala:66`                                                                               | Extract to a named constant `StateJwtTtlSeconds = 1800` with a comment explaining the 30-minute rationale.                                                        |
| [x]    | P13-059    | Suggestion | Documentation       | Manual testing guide not updated with Phase 13 OAuth flow, email, calendar, and drive test cases.                                                                                                 | `doc/manual-testing-guide.md`                                                                        | Add golden-path and edge-case test scenarios for OAuth link/unlink, email send/receive, calendar list/create, and drive list/download.                            |
| [x]    | P13-060    | Suggestion | Test Coverage       | `email.reply` missing `messageId` error path not tested.                                                                                                                                          | `EmailSkillSpec.scala`                                                                               | Add a test that invokes `email.reply` without a `messageId` and asserts a descriptive error is returned.                                                          |
| [x]    | P13-061    | Suggestion | Test Coverage       | `email.send` with `cc` and `bcc` fields not tested.                                                                                                                                               | `EmailSkillSpec.scala`                                                                               | Add a test covering all optional address fields; verify they appear in the constructed MIME message.                                                              |
| [x]    | P13-062    | Suggestion | Test Coverage       | `OAuthCredentialEncryptor.decrypt` error paths (tampered ciphertext, wrong key, truncated IV) not tested.                                                                                         | `OAuthCredentialEncryptorSpec.scala`                                                                 | Add negative-case tests; assert a meaningful error is returned rather than an exception propagating unchecked.                                                    |
| [x]    | P13-063    | Suggestion | Test Coverage       | `ExternalCredentialRepository.listByUser` with zero credentials not tested.                                                                                                                       | `ExternalCredentialRepositorySpec.scala`                                                             | Add a test that asserts an empty sequence is returned for a user with no linked providers.                                                                        |

---

## Grouped Sections

### Security

**`invokeTool` missing capability guard** (P13-001) — CONFIRMED BY 2 REVIEWERS

The `invokeTool` GraphQL mutation (`JorlanAPI.scala:926-931`) dispatches to `email.send`, `calendar.deleteEvent`,
`drive.downloadFile`, and other destructive operations without any `requireCapability` check. Any authenticated user can
invoke any registered tool on behalf of any `actorId`. This is a complete bypass of the capability model for the most
flexible and powerful new operation in Phase 13. The fix requires mapping each tool name prefix to its corresponding
capability (e.g., `"email.*"` → `"email.use"`, `"calendar.write.*"` → `"calendar.write"`) and inserting a
`requireCapability` guard before dispatching.

**Unauthenticated `X-User-Id` trust on OAuth start** (P13-002)

`OAuthRoutes.startRoute` (`OAuthRoutes.scala:108-114`) reads the target `userId` from the `X-User-Id` HTTP header
without verifying a session token. An unauthenticated attacker can craft a request with an arbitrary `X-User-Id` header,
triggering an OAuth flow that links the victim's Google account to the attacker's user record. The fix is to require a
valid JWT, extract `userId` from the token claims, and discard the header.

**Key reuse and non-standard derivation** (P13-003) — CONFIRMED BY 2 REVIEWERS

`EnvironmentBuilder.scala:73` derives the credential encryption key from `JORLAN_AUTH_SECRET_KEY` — the same value used
for JWT signing — via `SHA-256(salt‖secret)`. This violates key separation: compromise of one key compromises both.
Standard practice is HKDF with a domain-separation label, or a dedicated `JORLAN_CREDENTIAL_ENCRYPTION_KEY` env var with
PBKDF2 derivation. Additionally, key rotation is impossible without re-encrypting all stored credentials, which has no
tooling. This should be resolved before any production deployment.

**`email.send` / `calendar.write` incorrect approval mode** (P13-006)

`InitService.scala:230` seeds both capabilities with `ApprovalMode.Persistent`. The spec requires `PerInvocation` so
that every invocation of a send or write operation requires explicit user approval. With `Persistent`, the first
approval grants unlimited future invocations without further prompts, silently removing the intended safety gate for
destructive email and calendar operations.

**CSRF replay window** (P13-014)

The state JWT in the OAuth flow carries no nonce and is not invalidated on first use. An attacker who intercepts a state
JWT (e.g., via referrer header or log exfiltration) can replay it within the 30-minute validity window and complete the
OAuth flow as the victim. Server-side nonce storage with single-use semantics is the standard mitigation.

---

### Correctness

**`emailDelete` wrong event type** (P13-004) — CONFIRMED BY 3 REVIEWERS

`EmailSkill.emailDelete` (`EmailSkill.scala:284`) was copy-pasted from `emailArchive`. As a result, it logs
`EventType.EmailMessageArchived` to the audit trail when a delete operation is performed. There is no
`EmailMessageDeleted` variant in the `EventType` enum. Audit queries for deleted emails will return zero results;
archive queries will return inflated counts. Add `EventType.EmailMessageDeleted` and use it in the delete path.

**`calendarId` incorrectly required** (P13-005)

The `calendar.listEvents` tool descriptor (`GoogleCalendarSkill.scala:57`) marks `calendarId` as required. The shell
commands `/calendar today` and `/calendar list` (`CommandHandler.scala:661,681`) do not supply this field. At runtime,
these commands fail with a missing-parameter validation error. The field should be optional with an implicit default of
`"primary"` (the Google Calendar API's convention for the primary calendar).

**`drive.downloadFile` dangling artifact URI** (P13-019)

`GoogleDriveSkill.downloadFile` (`GoogleDriveSkill.scala:145-172`) downloads file bytes from Google Drive and then
stores an artifact record in the database with a URI. However, the downloaded bytes are never written to any storage
backend; the URI points to a location where no file exists. Any downstream attempt to fetch the artifact by URI will
fail silently. Either write the bytes to storage before creating the artifact, or omit the artifact record until storage
integration is implemented.

**`EmailSkill.buildDraft` silently ignores `signWithPgp`** (P13-020)

Callers who pass `signWithPgp=true` in tool arguments receive a draft that is silently unsigned. The `PgpService.noOp`
layer is in use but the intent is dropped with no feedback. At minimum, the tool result should include a warning field
noting that PGP signing is not available in the current configuration.

**`GmailProvider.buildRfc822` missing `From:` header** (P13-049)

RFC 822 requires a `From:` header; many SMTP relays reject or mangle messages without it. The constructed MIME message
omits this field. The authenticated user's email address can be retrieved from the Google People API or from the stored
credential; it should be included in the `From:` header before the message is base64-encoded and submitted.

---

### Performance

**N+1 token refresh in `GmailProvider.listMessages`** (P13-012) — CONFIRMED BY 3 REVIEWERS

`GmailProvider.listMessages` (`GmailProvider.scala:134-142`) fetches each message body in a loop. Each iteration calls
`withGmail`, which calls `refreshAccessToken` unconditionally. For a 10-message list operation, this produces 10
sequential OAuth HTTP calls before any message content is processed. The fix is to hoist the credential acquisition
above the loop: acquire once, pass the access token into the loop body.

**Unconditional token refresh on every API call** (P13-013) — CONFIRMED BY 3 REVIEWERS

`OAuthCredentialServiceImpl.refreshAccessToken` (`OAuthCredentialServiceImpl.scala:57-67`) calls Google's token endpoint
unconditionally, even when the stored access token has several hours of remaining validity. Combined with
`GmailProvider.withGmail` calling `refreshAccessToken` before every API operation (`GmailProvider.scala:51`), every
email/calendar/drive call incurs a minimum of two network round-trips before doing any real work. The fix is to compare
`Instant.now()` against the stored `expiresAt` and only refresh when within a configurable threshold (e.g., 60 seconds
of expiry).

**New Google API client built on every call** (P13-022) — CONFIRMED BY 2 REVIEWERS

`GmailProvider`, `GoogleCalendarProvider`, and `GoogleDriveProvider` each construct a new `GoogleCredentials`,
`HttpCredentialsAdapter`, and service client object on every API invocation. These objects are heavyweight and allocated
in the JVM heap. A `Ref[Map[UserId, GoogleApiClient]]` (invalidated on token refresh) would eliminate the per-call
allocation while maintaining correctness.

**`GoogleNetHttpTransport.newTrustedTransport()` outside ZIO** (P13-023)

Transport construction is blocking I/O (TrustStore loading). Calling it at class-construction time blocks the ZIO thread
pool during application startup. Move it into `ZIO.attemptBlocking` and create the transport once via `ZLayer.fromZIO`,
sharing a single `HttpTransport` instance across all provider instances.

---

### Resource Management

**`SecureRandom.getInstanceStrong()` per encrypt call** (P13-024)

`OAuthCredentialEncryptor.encrypt` (`OAuthCredentialEncryptor.scala:36`) calls `SecureRandom.getInstanceStrong()` on
every invocation. This method may block waiting for the OS entropy pool (on Linux with `/dev/random`), and the
allocation of a fresh CSPRNG instance per call is unnecessary overhead. Initialize one `SecureRandom` instance at layer
construction and share it via a constructor parameter or `Ref`.

---

### API Design

**Phase 13 APIs unreachable from web SPA** (P13-010, P13-036, P13-037)

Three related gaps leave the Phase 13 functionality entirely inaccessible from the web frontend:

1. `JorlanClient` has no operation definitions for any of the five new GraphQL operations (P13-010).
2. `JorlanWebApp.scala` does not process the `oauth=success`/`oauth=error` query parameters injected by the OAuth
   callback redirect (P13-036).
3. There is no `AppPage.OAuth` route or `OAuthManagementPage` component (P13-037).

These three items should be addressed together as a single web frontend task.

**`startOAuth` returns unusable relative path** (P13-011) — CONFIRMED BY 2 REVIEWERS

`JorlanAPI.scala:918` returns `/api/oauth/start/google` from the `startOAuth` mutation. This is a server-relative path
to Jorlan's own redirect endpoint, not a Google authorization URL. The shell client displays this path to the user, who
cannot use it to complete an OAuth flow without additional browser navigation. The mutation should return either the
full Google authorization URL, or the full absolute URL of Jorlan's redirect endpoint including scheme and hostname from
`ServerUrl`.

---

### Code Quality

**`logEvent` duplicated across all skill implementations** (P13-025)

`logEvent` is copy-pasted verbatim in `EmailSkill`, `GoogleCalendarSkill`, `GoogleDriveSkill`, and `ShellSkill`. Any
change to the event logging pattern (e.g., adding a correlation ID) must be replicated in all four files. Extract a
`SkillEventLogger` trait that provides `logEvent` as a protected method; mix it into all skill implementations.

**Google provider structural duplication** (P13-026)

The transport creation, `GoogleCredentials` construction, `HttpCredentialsAdapter` wrapping, and `withXxx` helper
pattern are structurally identical across all three providers. An abstract `GoogleApiProvider[C]` base class
parameterized on the client type would eliminate the duplication and centralize the token-refresh and client-creation
logic — the primary source of the N+1 and per-call allocation bugs (P13-012, P13-013, P13-022).

**`ImapSmtpProvider.pgp` unused parameter** (P13-045)

`ImapSmtpProvider` accepts a `pgp: PgpService` constructor parameter that is never referenced in the method bodies (all
of which are stubs). Remove the parameter or use it once PGP integration is implemented.

---

### Error Handling

**`callTokenRefresh` does not validate HTTP status** (P13-018)

`OAuthCredentialServiceImpl.callTokenRefresh` (`OAuthCredentialServiceImpl.scala:104-120`) reads the response body and
attempts to parse it as a `TokenResponse` without first checking the HTTP status code. A 400 Bad Request or 401
Unauthorized response from Google (e.g., invalid `refresh_token`) will be silently stored as a credential update,
replacing the valid credential with malformed data. The next API call will then fail with a confusing error unrelated to
the root cause. Assert `statusCode == 200` before parsing.

---

### Test Coverage

**Zero coverage on OAuth infrastructure** (P13-007, P13-008, P13-009)

The three most critical test gaps in Phase 13 cover code paths that are both security-sensitive and complex:

| Missing Test                                    | Gap                                                                |
|-------------------------------------------------|--------------------------------------------------------------------|
| `OAuthRoutes` state JWT round-trip              | Token tamper, replay, and expiry not detectable                    |
| `OAuthRoutes` callback with valid state JWT     | Happy path OAuth completion entirely unverified                    |
| `OAuthRoutes` callback with tampered state      | Security property (P13-002, P13-014) unenforceable without tests   |
| `OAuthCredentialServiceImpl.store` + `load`     | Encrypt-on-store / decrypt-on-load round-trip unverified           |
| `OAuthCredentialServiceImpl.refreshAccessToken` | Token refresh correctness (expiresAt update, P13-017) unverifiable |
| `oauthStatus` resolver                          | `expiresAt` hardcode bug (P13-027) undetectable                    |
| `listOAuthProviders` resolver                   | Provider enumeration unverified                                    |
| `startOAuth` resolver                           | Returns relative path bug (P13-011) undetectable                   |
| `revokeOAuth` resolver                          | Credential deletion unverified                                     |
| `invokeTool` resolver                           | Missing capability guard (P13-001) would pass undetected           |

**Skill integration coverage gap** (P13-034)

`EmailSkill`, `GoogleCalendarSkill`, and `GoogleDriveSkill` are not registered in `SkillRegistry` during integration
tests (`Jorlan.scala:152-154`). The full tool-call dispatch path — from GraphQL `invokeTool` through
`SkillRegistry.invoke` to the skill implementation — is never exercised in the test suite. Register the three skills
with fake provider implementations in the integration test layer.

---

### Documentation

**Security-critical code without documentation** (P13-051, P13-052, P13-057, P13-058)

`OAuthCredentialEncryptor`, `OAuthCredentialService`, `ExternalCredential.credentialData`, and the state JWT TTL
constant are all undocumented. These are the security-sensitive components of Phase 13; an engineer reading the code
must understand the algorithm choices, key derivation, and lifetime constraints to reason about the security properties.
Add ScalaDoc to all four before the next developer touches this code.

**Provider and trait documentation gaps** (P13-053, P13-054, P13-055, P13-056)

`ImapSmtpProvider`, `PgpService.noOp`, and all three provider traits lack class-level ScalaDoc. In particular,
`ImapSmtpProvider` actively misleads: its ScalaDoc implies a working implementation while all methods are stubs. Update
these docs to accurately reflect the current state and note the Phase 13 known limitations.

---

## Cross-Cutting Patterns

**Unconditional token refresh as a root cause** was independently flagged by the Security Reviewer, Performance Oracle,
and Code Simplicity Reviewer from three complementary angles. The Performance Oracle identified the N+1 pattern in
`listMessages` (P13-012) and the per-call refresh overhead (P13-013). The Security Reviewer noted that every API call
triggers a network round-trip to Google that exposes the credential store unnecessarily (P13-008 — token stored without
expiry). The Code Simplicity Reviewer identified the structural duplication in `withGmail`/`withCalendar`/`withDrive`
that makes this pattern hard to fix in one place (P13-026). All three issues trace to the same root: there is no
expiry-aware token caching layer, and the `withXxx` helpers are duplicated rather than centralized.

**Missing capability guards on new operations** is the most security-critical cross-cutting pattern, independently
flagged by the Functional Scala Reviewer and Security Reviewer. `invokeTool` has no guard at all (P13-001);
`startOAuth`, `oauthStatus`, and `listOAuthProviders` have no gate (P13-028); and `email.send`/`calendar.write` are
seeded with the wrong approval mode (P13-006). The pattern from Phase 10 (P10-001 through P10-009) repeats: new code
paths are added without applying the established `requireCapability` infrastructure. This suggests a need for a
checklist item in the phase template confirming capability guards on all new mutations.

**Copy-paste as the primary bug vector** accounts for three distinct correctness issues confirmed across multiple
reviewers. `emailDelete` copied from `emailArchive` produces the wrong event type (P13-004, confirmed by 3 reviewers).
The `withGmail`/`withCalendar`/`withDrive` duplication produces the N+1 bug in `listMessages` and the per-call refresh
in all providers (P13-012, P13-013, P13-022, confirmed by 3 reviewers). The `logEvent` duplication will cause the same
bug in all four skill implementations when the logging contract changes (P13-025). The root fix is P13-026 (abstract
base class) and P13-025 (`SkillEventLogger` trait).

**Zero test coverage on the OAuth flow** was independently identified by the Test Coverage Tracker and Security
Reviewer. `OAuthRoutes`, `OAuthCredentialServiceImpl`, and all five new GraphQL resolvers have no tests (P13-007,
P13-008, P13-009). This is the highest-risk untested surface in Phase 13: state JWT validation, credential encryption,
token refresh, and authorization checks are all exercised only in production. The consequence is that the security
bugs (P13-002, P13-003, P13-014, P13-018) are currently undetectable by the test suite.

**Web frontend not connected to Phase 13** is a pattern noted by the UI Test Plan Writer across P13-010, P13-036, and
P13-037. None of the five new GraphQL operations are reachable from the SPA; the OAuth callback redirect has no handler;
and there is no OAuth management UI. These three items form a cohesive work unit and should be addressed together in an
early Phase 14 task.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count  |
|------------|--------|
| Critical   | 13     |
| Warning    | 25     |
| Suggestion | 25     |
| **Total**  | **63** |

**Issues by area:**

| Area                | Count  |
|---------------------|--------|
| Security            | 8      |
| Test Coverage       | 11     |
| Correctness         | 9      |
| Code Quality        | 5      |
| Documentation       | 10     |
| API Design          | 7      |
| Performance         | 5      |
| Resource Management | 2      |
| Error Handling      | 1      |
| Architecture        | 2      |
| Functional Purity   | 1      |
| Documentation       | 2      |
| **Total**           | **63** |

**Agent contribution:**

| Agent                     | Unique Findings | Cross-Confirmed |
|---------------------------|-----------------|-----------------|
| Security Reviewer         | 10              | 6               |
| Performance Oracle        | 8               | 5               |
| Test Coverage Tracker     | 10              | 6               |
| Functional Scala Reviewer | 15              | 7               |
| Code Simplicity Reviewer  | 11              | 6               |
| ScalaDoc Auditor          | 11              | 3               |
| SRS/SDD Conformance Rev.  | 5               | 4               |
| Pattern Recognition Spec. | 3               | 8               |
| UI Test Plan Writer       | 11              | 4               |

**Phase 13 scope completion:**

| Item                                                      | Status |
|-----------------------------------------------------------|--------|
| `email` SBT module with `EmailSkill` and `EmailProvider`  | ✅      |
| `google-services` module with Gmail, Calendar, Drive      | ✅      |
| `GmailProvider` implementation                            | ✅      |
| `GoogleCalendarProvider` implementation                   | ✅      |
| `GoogleDriveProvider` implementation                      | ✅      |
| `OAuthCredentialEncryptor` (AES/GCM)                      | ✅      |
| `OAuthCredentialServiceImpl` with token refresh           | ✅      |
| `QuillExternalCredentialRepository`                       | ✅      |
| V025 Flyway migration                                     | ✅      |
| `OAuthRoutes` (start + callback)                          | ✅      |
| GraphQL resolvers (oauthStatus, listOAuthProviders, etc.) | ✅      |
| Shell commands (/oauth, /email, /calendar)                | ✅      |
| 1047 total tests passing                                  | ✅      |
| `OAuthRoutesSpec` test coverage                           | ❌      |
| `GmailProviderSpec` / `GoogleCalendarProviderSpec`        | ❌      |
| `PgpServiceSpec`                                          | ❌      |
| GraphQL resolver test coverage                            | ❌      |
| Web frontend OAuth management page                        | ❌      |
| Web `JorlanClient` Phase 13 bindings                      | ❌      |
| `startOAuth` returns full authorization URL               | ⚠️     |
| `email.send` / `calendar.write` approval mode             | ⚠️     |
| Manual testing guide Phase 13 entries                     | ❌      |
| Roadmap checkboxes complete                               | ⚠️     |

---

## What Was Done Well

**Generic `F[_]` provider abstraction**: The `EmailProvider`, `CalendarProvider`, and `DriveProvider` traits correctly
separate the Google-specific implementation from the skill layer. This means a future IMAP/CalDAV implementation can be
dropped in without touching `EmailSkill` or `GoogleCalendarSkill`. The pattern should be maintained for all future
external-service integrations.

**AES/GCM credential encryption with per-credential IV**: The decision to encrypt each credential blob independently
with a random IV (`OAuthCredentialEncryptor`) rather than using a fixed IV is correct and prevents IV-reuse attacks. The
algorithm choice (AES/GCM, 256-bit key, 12-byte IV) is industry-standard. The key derivation and key-reuse issues (
P13-003) are fixable without changing the encryption algorithm.

**V025 migration follows established Flyway conventions**: The migration correctly defines the `externalCredential`
table with a composite unique constraint on `(userId, provider)`, appropriate FK references, and a `updatedAt` column
managed via `ON UPDATE CURRENT_TIMESTAMP`. This follows the pattern established in prior migrations and ensures
idempotent upsert semantics in `QuillExternalCredentialRepository`.

**1047 tests and ZIO effect discipline**: The Phase 13 additions do not regress the existing test count, and all new
domain code consistently uses ZIO effects for I/O, database access, and error handling. The use of `ZIO.attemptBlocking`
for Java library calls (in the non-construction code paths) and `ZIO.fromEither` for validation is idiomatic and correct
throughout the skill implementations.
