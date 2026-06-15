---
name: phase13-audit
description: Audit results for Email/Calendar/Drive Skills phase; encryption doc gap, delete/archive event type bug, stub warning, testing guide stale
metadata:
  type: project
---

## Phase 13 audit (Email/Calendar/Drive Skills)

### Confirmed good
- `OAuthRoutes` object has a thorough class-level ScalaDoc describing both routes and the state JWT format — good model for security-sensitive objects.
- `OAuthCredentialServiceImpl.make` companion method has ScalaDoc.
- All five new opaque type IDs (`ExternalCredentialId`, `EmailMessageId`, `CalendarId`, `CalendarEventId`, `DriveFileId`) have ScalaDoc comments.
- `ExternalCredentialRepository` trait has a class-level ScalaDoc.
- Three skill classes (`EmailSkill`, `GoogleCalendarSkill`, `GoogleDriveSkill`) each have a class-level ScalaDoc listing their tools.
- `ImapSmtpProvider` has a class-level ScalaDoc.

### Issues found
- **Critical bug (not doc)**: `EmailSkill.emailDelete` (line 284) logs `EventType.EmailMessageArchived` instead of a delete event type — the `EmailMessageDeleted` case does not exist; the EventType enum has only `EmailMessageArchived`. This is a semantic mislabeling, not a compilation error.
- `OAuthCredentialEncryptor` class has no ScalaDoc explaining: algorithm (AES-256-GCM), key derivation scheme (SHA-256 of `"jorlan-external-credentials" + secret), IV generation (12-byte random via SecureRandom.getInstanceStrong), or ciphertext storage format (JSON with `iv`/`ciphertext` Base64 fields).
- `OAuthCredentialService` trait has no class-level ScalaDoc. Critical that it documents: `store` encrypts before persisting, `load` decrypts on return, `refreshAccessToken` calls the provider token endpoint.
- `EmailProvider[F[_]]` trait has no class-level ScalaDoc (what F[_] represents in context).
- `CalendarProvider[F[_]]` and `DriveProvider[F[_]]` — same gap.
- `PgpService` trait has no class-level ScalaDoc; the `noOp` instance in the companion is undocumented (always returns false/unsigned — callers should know this is a stub).
- `GmailProvider`, `GoogleCalendarProvider`, `GoogleDriveProvider` — no class-level ScalaDoc.
- `ImapSmtpProvider` class ScalaDoc mentions `emil` library but all methods are `ZIO.fail(JorlanError("... not yet implemented"))` — stub nature not clearly flagged in the class doc.
- `EmailSkill.buildDraft` silently ignores `signWithPgp = true` from args — always hardcodes `signWithPgp = false`. The tool input schema advertises this param, but the impl ignores it.
- Manual testing guide last updated 2026-06-09 (Phase 12); no Phase 13 OAuth/email/calendar rows added.

### Opaque ID notes
- `EmailMessageId` has no `empty` value (unlike other IDs) — intentional; provider-assigned strings don't have a sentinel.
- String-based IDs (`CalendarId`, `CalendarEventId`, `DriveFileId`) correctly lack a numeric `empty` value.

### V-migrations
- V025 and V026 used for external_credentials table (to verify against actual migration files).
