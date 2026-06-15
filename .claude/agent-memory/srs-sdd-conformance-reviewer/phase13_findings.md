---
name: phase13-findings
description: Phase 13 Email/Calendar/Drive Skills conformance review findings (2026-06-12)
metadata:
  type: project
---

Phase 13 Email/Calendar/Drive Skills review — 2026-06-12.

**Why:** To confirm implementation matches doc/mini-designs/phase13-email-calendar.md and doc/development_roadmap.md Phase 13 section.

## Critical Issues

- **P13-001**: calendarId marked required in GoogleCalendarSkill tool schema but roadmap specifies calendarId? (optional). Shell /calendar today and /calendar list commands call calendar.listEvents WITHOUT calendarId, which errors at runtime (Major).
- **P13-002**: startOAuth GraphQL mutation returns "/api/oauth/start/<provider>" (a path) not the full Google auth URL. Shell /oauth connect command displays this path to the user, who cannot open it as a Google auth URL without the server performing the redirect. The spec intended the mutation to return the real Google OAuth URL.
- **P13-003**: GmailProvider, GoogleCalendarProvider, and GoogleDriveProvider call refreshAccessToken on EVERY API call, which issues a real HTTP POST to Google's token endpoint each time, even when the stored token is valid. Mini-design says "check expiresAt, call refreshAccessToken if within 60s of expiry." This causes O(N+1) network calls for listMessages (N = message count).
- **P13-004**: OAuthCredentialServiceImpl.refreshAccessToken calls the Google token endpoint but only saves the new access_token via mergeAccessToken, discarding the new expires_in from the response. So the stored expiresAt is never updated after the first refresh.
- **P13-005**: InitService.seedAdminGrants uses ApprovalMode.Persistent for ALL capabilities including email.send and calendar.write. Roadmap/mini-design specifies email.send and calendar.write as PerInvocation.

## Minor Issues

- **P13-006**: OAuthCredentialEncryptor.deriveKey uses SHA-256(info||secret) which is not proper HKDF-SHA256. The spec says HKDF-SHA256. Not an immediate attack surface but cryptographically weaker.
- **P13-007**: oauthStatus GraphQL resolver hardcodes expiresAt = None despite the ExternalCredential table storing expires_at in plaintext. The design says the OAuthStatus type exposes expiresAt.
- **P13-008**: email.delete tool logs EventType.EmailMessageArchived (not a distinct EmailMessageDeleted). No EmailMessageDeleted event type exists in the event enum, so this is a semantic mismatch in the event log.
- **P13-009**: showCommands (shell /help) does not list any of the new /oauth, /email, or /calendar commands. They are implemented but invisible to users via /help.

## Missing Tests (per roadmap unchecked items)

- OAuthRoutesSpec: state JWT; CSRF rejection; redirect URL — not implemented
- GmailProviderSpec: via FakeGmailProvider; token refresh before expiry — not implemented
- PgpServiceSpec — not implemented (BouncyCastle deferred, stub in place)
- Overall test coverage >= 80% for Phase 13 code — not verified

## Correct Aspects

- V025 migration correct: external_credentials table, FK to user(id), unique key on (user_id, provider)
- OAuthCredentialEncryptor uses AES-256-GCM with random IV — correct cipher mode
- GoogleCalendarSkill, GoogleDriveSkill, EmailSkill: event log writes per invocation
- SkillRegistry wiring: email, calendar, drive skills registered in Jorlan.registerBuiltInSkills
- Capability seeding: email.read, email.write, email.send, calendar.read, calendar.write, drive.read all seeded
- OAuthRoutes: HMAC-SHA256 CSRF state JWT with 30-min TTL — correct
- Callback route correctly unauthenticated (not behind bearerSessionProvider)
- Shell commands /oauth, /email, /calendar all parsed and dispatched correctly
- Domain types (EmailMessage, CalendarEntry, DriveFile, ExternalCredential) match spec
- Provider traits generic on F[_] as required
- GmailProvider, GoogleCalendarProvider, GoogleDriveProvider use Google API Java client (matches updated roadmap)
- ImapSmtpProvider correctly stubbed with ZIO.fail("not yet implemented")

**How to apply:** Flag these issues in the Phase 13 review report.
