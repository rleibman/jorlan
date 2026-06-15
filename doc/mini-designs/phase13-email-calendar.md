# Phase 13 Mini-Design: Email, Calendar, and Google Drive Skills

Version: 0.2  
Date: 2026-06-11

---

## Goal

Agents can read email and calendar events; can send/modify with explicit per-invocation approval. Google Drive is
read-only. OAuth2 credentials are stored per-user, encrypted at rest. IMAP/SMTP uses the `emil` library via
`zio-interop-cats`. Gmail, Calendar, and Drive use Google's REST APIs directly via zio-http.

---

## Scope

- **`email` module**: provider-independent `EmailSkill` + `ImapSmtpProvider` (via emil) + `PgpService`
- **`google-services` module**: `GmailProvider`, `GoogleCalendarSkill`, `GoogleDriveSkill`, `OAuthCredentialService`
- **DB migration V025**: `external_credentials` table
- **OAuth HTTP routes** (in `server`): `/api/oauth/start`, `/api/oauth/callback/:provider`
- **GraphQL** (in `server`): credential status, revoke, oauth-start
- **Shell commands** (in `server`): `/oauth`, `/email`, `/calendar`
- **Fake providers** for CI: no live credentials in any test

---

## New SBT Modules

### `email`

Purpose: IMAP/SMTP email skill using the `emil` library. Provider-independent — `GmailProvider` lives in
`google-services` and implements the same `EmailProvider` trait.

Dependencies:
```
model
connector-api
db
"com.github.eikek"  %% "emil-common"   % "0.19.0"
"com.github.eikek"  %% "emil-javamail" % "0.19.0"
"dev.zio"           %% "zio-interop-cats" % "23.1.0.5"
```

Also needs (transitively via emil-javamail):
```
"org.eclipse.angus" % "angus-mail" % "2.0.3"    // jakarta.mail implementation
```

Test only:
```
"org.bouncycastle" % "bcpg-jdk18on" % "1.79"    // PGP test key generation
```

### `google-services`

Purpose: Gmail + Google Calendar + Google Drive skills, all sharing one OAuth flow and credential store.

Dependencies:
```
model
connector-api
db
// No extra libraries — uses zio-http (already in model/server transitive) for REST calls
```

---

## Module Dependency Graph

```
model
  ← db
  ← ai
  ← connector-api
      ← email           (ImapSmtpProvider, EmailSkill, PgpService)
      ← google-services (GmailProvider, CalendarSkill, DriveSkill, OAuthCredentialService)
      ← telegram
  ← db ← server ← (ai, email, google-services, telegram, analytics)
  ← model ← shell
  ← model, server ← integration
```

---

## Domain Types (model module)

These live in `model` so both `email` and `google-services` can reference them without a circular dependency.

**File: `model/src/main/scala/jorlan/domain/email.scala`**

```scala
opaque type EmailMessageId = String
object EmailMessageId:
  def apply(v: String): EmailMessageId = v
  extension (id: EmailMessageId) def value: String = id

case class EmailMessage(
  id:                 EmailMessageId,
  threadId:           String,
  from:               String,
  to:                 List[String],
  cc:                 List[String],
  bcc:                List[String],
  subject:            String,
  body:               String,           // plain-text; HTML stripped by provider
  bodyHtml:           Option[String],
  date:               Instant,
  attachments:        List[EmailAttachment],
  labels:             List[String],
  pgpSigned:          Boolean,
  pgpSignatureValid:  Option[Boolean],
)

case class EmailAttachment(
  name:         String,
  mimeType:     String,
  sizeBytes:    Long,
  attachmentId: String,   // provider-specific handle for lazy fetch
)

case class EmailDraft(
  to:                 List[String],
  cc:                 List[String],
  bcc:                List[String],
  subject:            String,
  body:               String,
  replyToMessageId:   Option[String],
  signWithPgp:        Boolean = false,
)
```

**File: `model/src/main/scala/jorlan/domain/calendar.scala`**

```scala
opaque type CalendarId      = String
opaque type CalendarEventId = String

case class CalendarEntry(       // "CalendarEntry" to avoid confusion with EventLog
  id:          CalendarEventId,
  calendarId:  CalendarId,
  summary:     String,
  description: Option[String],
  location:    Option[String],
  start:       Instant,
  end:         Instant,
  allDay:      Boolean,
  attendees:   List[CalendarAttendee],
  organizer:   Option[String],
  status:      CalendarEventStatus,
)

case class CalendarAttendee(
  email:          String,
  displayName:    Option[String],
  responseStatus: AttendeeResponse,
)

enum CalendarEventStatus { case Confirmed, Tentative, Cancelled }
enum AttendeeResponse    { case Accepted, Declined, Tentative, NeedsAction }

case class UserCalendar(
  id:        CalendarId,
  summary:   String,
  isPrimary: Boolean,
  timeZone:  String,
)
```

**File: `model/src/main/scala/jorlan/domain/drive.scala`**

```scala
opaque type DriveFileId = String

case class DriveFile(
  id:          DriveFileId,
  name:        String,
  mimeType:    String,
  sizeBytes:   Option[Long],
  modifiedAt:  Instant,
  parents:     List[String],
  webViewLink: Option[String],
)
```

**File: `model/src/main/scala/jorlan/domain/externalCredential.scala`**

```scala
opaque type ExternalCredentialId = Long
object ExternalCredentialId:
  val empty: ExternalCredentialId = 0L
  def apply(v: Long): ExternalCredentialId = v

case class ExternalCredential(
  id:             ExternalCredentialId,
  userId:         UserId,
  provider:       String,
  credentialData: Json,        // AES-256-GCM encrypted blob: { iv, ciphertext }
  expiresAt:      Option[Instant],
  scopes:         Option[String],
  createdAt:      Instant,
  updatedAt:      Instant,
)
```

**Provider traits (model/src/main/scala/jorlan/service/)**

```scala
// email provider — implemented by ImapSmtpProvider (email module) and GmailProvider (google-services)
trait EmailProvider {
  def listMessages(userId: UserId, maxResults: Int, query: Option[String]): IO[JorlanError, List[EmailMessage]]
  def getMessage(userId: UserId, messageId: EmailMessageId): IO[JorlanError, EmailMessage]
  def sendDraft(userId: UserId, draft: EmailDraft): IO[JorlanError, EmailMessageId]
  def createDraft(userId: UserId, draft: EmailDraft): IO[JorlanError, String]
  def archiveMessage(userId: UserId, messageId: EmailMessageId): IO[JorlanError, Unit]
  def deleteMessage(userId: UserId, messageId: EmailMessageId): IO[JorlanError, Unit]
}

// OAuthCredentialService — trait in model, impl in google-services
trait OAuthCredentialService {
  def store(userId: UserId, provider: String, plainJson: Json): IO[JorlanError, Unit]
  def load(userId: UserId, provider: String): IO[JorlanError, Option[Json]]
  def revoke(userId: UserId, provider: String): IO[JorlanError, Unit]
  def listProviders(userId: UserId): IO[JorlanError, List[String]]
  def refreshAccessToken(userId: UserId, provider: String): IO[JorlanError, String]
}

// ExternalCredentialRepository — trait in model, impl in db
trait ExternalCredentialRepository[F[_]] {
  def upsert(userId: UserId, provider: String, encryptedData: Json, expiresAt: Option[Instant], scopes: Option[String]): F[Unit]
  def find(userId: UserId, provider: String): F[Option[ExternalCredential]]
  def delete(userId: UserId, provider: String): F[Unit]
  def listByUser(userId: UserId): F[List[ExternalCredential]]
}
```

**New EventType variants**

```
EmailMessageRead, EmailMessageSent, EmailDraftCreated, EmailMessageArchived,
CalendarEventRead, CalendarEventCreated, CalendarEventUpdated, CalendarEventDeleted,
DriveFileRead, DriveFileListed
```

---

## Database (db module)

### Migration V025: `external_credentials`

```sql
CREATE TABLE external_credentials (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT       NOT NULL,
  provider        VARCHAR(64)  NOT NULL,
  credential_data JSON         NOT NULL,     -- encrypted { iv: base64, ciphertext: base64 }
  expires_at      DATETIME(3)  NULL,         -- NULL = password creds (no expiry)
  scopes          TEXT         NULL,         -- space-separated OAuth scopes
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_user_provider (user_id, provider),
  CONSTRAINT fk_extcred_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
```

Provider values: `"google"` (covers Gmail + Calendar + Drive via shared OAuth flow), `"imap"`, `"smtp"`.

### `QuillExternalCredentialRepository` (db module)

Standard Quill implementation of `ExternalCredentialRepository[RepositoryTask]`.

---

## `email` Module

### `OAuthCredentialEncryptor`

Lives in `email` module (also used by `google-services` via `model` trait boundary — actually, encryptor is in
`server` since it needs `JORLAN_AUTH_SECRET_KEY` from config). See below.

> **Note**: `OAuthCredentialEncryptor` is a utility class, not a service trait. It lives in `server` and is
> injected into `OAuthCredentialServiceImpl` (google-services) and into the IMAP credential loader in `server`.
> Both modules get their credentials via the injected `OAuthCredentialService`.

### `PgpService`

**File: `email/src/main/scala/jorlan/email/PgpService.scala`**

```scala
trait PgpService {
  def verifySignature(body: String, senderEmail: String): IO[JorlanError, Boolean]
  def signMessage(body: String, userId: UserId): IO[JorlanError, String]
}
```

`PgpServiceImpl`: uses Bouncy Castle (`bcpg-jdk18on`). Key paths stored in `server_settings` under
`pgp.keys.<userId>` as encrypted JSON (via `OAuthCredentialEncryptor`). If no key configured,
`signMessage` returns the original body + appends `"[PGP: no key configured]"` to result — not an error.

Dependency: `org.bouncycastle:bcpg-jdk18on:1.79` in `email` module.

### `ImapSmtpProvider`

**File: `email/src/main/scala/jorlan/email/ImapSmtpProvider.scala`**

```scala
class ImapSmtpProvider(
  mailConfig:  MailConfig,   // emil MailConfig: host, port, user, password
  pgpService:  PgpService,
) extends EmailProvider
```

Uses `JavaMailEmil[Task]` with `import zio.interop.catz.*`. Each operation acquires a fresh connection via
`Emil[Task].connection(mailConfig).use(...)`. Email `Resource` acquisition is non-blocking (`ZIO.attemptBlockingIO`
internally via JavaMail).

Mapping:
- IMAP `getMessages` → `List[EmailMessage]` (headers only for list; full body on `getMessage`)
- SMTP `Transport.send` → wraps `EmailDraft` into `MimeMessage`, sends

PGP: on `sendDraft`, if `draft.signWithPgp`, call `pgpService.signMessage` before sending. On `getMessage`,
if message is PGP-signed, call `pgpService.verifySignature`.

### `EmailSkill`

**File: `email/src/main/scala/jorlan/email/EmailSkill.scala`**

Provider-injected; 8 tools as described in the tool table below.

### `FakeEmailProvider`

**File: `email/src/test/scala/jorlan/email/FakeEmailProvider.scala`**

In-memory: configurable message list; records send/draft/archive calls. No network I/O.

---

## `google-services` Module

### `OAuthCredentialEncryptor` (lives in `server`)

```scala
class OAuthCredentialEncryptor(secretKey: String):
  def encrypt(plaintext: String): Json      // { iv: base64, ciphertext: base64 }
  def decrypt(encrypted: Json): String
```

AES-256-GCM. Key = HKDF-SHA256(secretKey, salt="jorlan", info="jorlan-external-credentials").
JVM `javax.crypto` only — no extra library.

### `OAuthCredentialServiceImpl` (google-services module)

**File: `google-services/src/main/scala/jorlan/google/OAuthCredentialServiceImpl.scala`**

```scala
class OAuthCredentialServiceImpl(
  repo:      ExternalCredentialRepository[RepositoryTask],
  encryptor: OAuthCredentialEncryptor,
  config:    GoogleOAuthSettings,
) extends OAuthCredentialService
```

`refreshAccessToken`: POST to `https://oauth2.googleapis.com/token` with `grant_type=refresh_token` via
zio-http. On success, updates `credential_data` with new `accessToken` + new `expiresAt`.

All three Google skills use `provider = "google"` — one credential row covers Gmail, Calendar, and Drive
(same OAuth app, same token, scopes requested all at once during initial auth).

### `GmailProvider`

**File: `google-services/src/main/scala/jorlan/google/GmailProvider.scala`**

Implements `EmailProvider`. Calls `https://gmail.googleapis.com/gmail/v1/users/me/messages`.

Before each API call: load credential, check `expiresAt`, call `refreshAccessToken` if within 60s of expiry.

Mapping:
- `messages.list` → `List[EmailMessage]` (header summary)
- `messages.get?format=full` → `EmailMessage` (full body, labels)
- `messages.send` / `drafts.create` → send/draft

### `GoogleCalendarProvider` + `GoogleCalendarSkill`

Provider calls `https://www.googleapis.com/calendar/v3/`. Skill wraps provider with event log writes.

### `GoogleDriveProvider` + `GoogleDriveSkill`

Provider calls `https://www.googleapis.com/drive/v3/`. `readTextFile` exports Google Docs as `text/plain`.
`downloadFile` stores result as an `Artifact`.

### Fake Providers (test)

`FakeGmailProvider`, `FakeCalendarProvider`, `FakeDriveProvider` — same pattern as `FakeEmailProvider`.

---

## Configuration (`configuration.scala`, in `server`)

```scala
case class GoogleOAuthSettings(
  clientId:     String = "",
  clientSecret: String = "",
  redirectUri:  String = "http://localhost:8080/api/oauth/callback/google",
)

case class ImapSettings(host: String = "", port: Int = 993, ssl: Boolean = true)
case class SmtpSettings(host: String = "", port: Int = 587, startTls: Boolean = true)
case class PgpSettings(enabled: Boolean = false)

case class EmailSettings(
  imap: ImapSettings = ImapSettings(),
  smtp: SmtpSettings = SmtpSettings(),
  pgp:  PgpSettings  = PgpSettings(),
)

// Added to JorlanConfig:
google: GoogleOAuthSettings = GoogleOAuthSettings()
email:  EmailSettings       = EmailSettings()
```

New env vars (`.env.example`):
```
JORLAN_GOOGLE_CLIENT_ID=
JORLAN_GOOGLE_CLIENT_SECRET=
JORLAN_GOOGLE_REDIRECT_URI=    # optional, defaults to localhost
```

---

## OAuth2 HTTP Routes (server)

**File: `server/src/main/scala/jorlan/routes/OAuthRoutes.scala`**

| Route | Description |
|-------|-------------|
| `GET /api/oauth/start/:provider` | Authenticated. Build Google auth URL with `state=JWT(userId, provider, exp=+30m)`. Redirect. |
| `GET /api/oauth/callback/google` | Unauthenticated (Google callback). Verify state JWT. Exchange code → tokens. Store via `OAuthCredentialService`. Redirect to `/?oauth=success`. |

Google OAuth scopes requested in one flow (all three services):
```
https://www.googleapis.com/auth/gmail.modify
https://www.googleapis.com/auth/calendar
https://www.googleapis.com/auth/drive.readonly
```

For shell users, `/oauth connect google` prints the auth URL. The user opens it in a browser; `OAuthRoutes` handles the callback and stores credentials. Shell polls `oauthStatus` to detect completion.

---

## EnvironmentBuilder Wiring (server)

```scala
// Provider selection based on config
val imapProvider: EmailProvider = ImapSmtpProvider(
  MailConfig(config.email.imap.host, ..., user, password),
  pgpService,
)
val gmailProvider: EmailProvider = GmailProvider(oauthCredSvc, config.google)
val calProvider   = GoogleCalendarProvider(oauthCredSvc, config.google)
val driveProvider = GoogleDriveProvider(oauthCredSvc, config.google)

// EmailSkill uses whichever provider is configured as default
// (config.email.defaultProvider = "gmail" | "imap")
val emailSkill    = EmailSkill(if (defaultProvider == "gmail") gmailProvider else imapProvider, repo)
val calSkill      = GoogleCalendarSkill(calProvider, repo)
val driveSkill    = GoogleDriveSkill(driveProvider, repo)

skillRegistry.register(emailSkill)
skillRegistry.register(calSkill)
skillRegistry.register(driveSkill)
```

---

## EmailSkill Tools

| Tool | Capability | RiskClass | Approval |
|------|-----------|-----------|---------|
| `email.list { maxResults?, query? }` | `email.read` | ReadOnly | No |
| `email.search { query }` | `email.read` | ReadOnly | No |
| `email.read { messageId }` | `email.read` | ReadOnly | No |
| `email.draft { to, subject, body, cc?, bcc? }` | `email.write` | Low | No |
| `email.send { to, subject, body, cc?, bcc? }` | `email.send` | ExternalEffect | **Yes** |
| `email.reply { messageId, body }` | `email.send` | ExternalEffect | **Yes** |
| `email.forward { messageId, to }` | `email.send` | ExternalEffect | **Yes** |
| `email.archive { messageId }` | `email.write` | Modification | **Yes** |

## GoogleCalendarSkill Tools

| Tool | Capability | RiskClass | Approval |
|------|-----------|-----------|---------|
| `calendar.listCalendars` | `calendar.read` | ReadOnly | No |
| `calendar.listEvents { calendarId?, maxResults?, timeMin?, timeMax? }` | `calendar.read` | ReadOnly | No |
| `calendar.getEvent { calendarId?, eventId }` | `calendar.read` | ReadOnly | No |
| `calendar.createEvent { summary, start, end, ... }` | `calendar.write` | ExternalEffect | **Yes** |
| `calendar.updateEvent { eventId, ... }` | `calendar.write` | Modification | **Yes** |
| `calendar.deleteEvent { eventId, calendarId? }` | `calendar.write` | ExternalEffect | **Yes** |

## GoogleDriveSkill Tools (all read-only)

| Tool | Capability | RiskClass |
|------|-----------|-----------|
| `drive.listFiles { folderId?, query?, maxResults? }` | `drive.read` | ReadOnly |
| `drive.readFile { fileId }` | `drive.read` | ReadOnly |
| `drive.downloadFile { fileId }` | `drive.read` | ReadOnly |

---

## GraphQL Additions (server)

**Queries:**
- `oauthStatus(provider: String!): OAuthStatus!` — `{ connected: Boolean!, expiresAt: DateTime }`
- `listOAuthProviders: [String!]!` — providers with stored credentials for calling user

**Mutations:**
- `startOAuth(provider: String!): OAuthStartResult!` — `{ authUrl: String! }`
- `revokeOAuth(provider: String!): Boolean!`

---

## Shell Commands (server)

```
/oauth status                  – list connected providers
/oauth connect google          – print Google auth URL (open in browser)
/oauth revoke google           – revoke stored credentials
/email list [n=10]             – list last n emails
/email read <id>               – show full email
/email search <query>          – search inbox
/calendar today                – show today's events
/calendar list [date]          – show events for a date (YYYY-MM-DD)
```

---

## Capability Seeding (`InitService.complete`)

```
email.read      → admin, Persistent
email.write     → admin, Persistent
email.send      → admin, PerInvocation    // approval per send
calendar.read   → admin, Persistent
calendar.write  → admin, PerInvocation    // approval per write
drive.read      → admin, Persistent
```

---

## Test Plan

**Unit tests — `email` module** (no live credentials):
- `PgpServiceSpec`: sign + verify round-trip (BouncyCastle test keypair); missing key → warning not error
- `ImapSmtpProviderSpec`: via `FakeEmailProvider` (not live IMAP — just the skill layer)
- `EmailSkillSpec`: all 8 tools; `email.send` blocks without `email.send` capability; event log entries

**Unit tests — `google-services` module** (no live credentials):
- `OAuthCredentialServiceSpec`: encrypt/decrypt round-trip; refresh token mock; revoke clears row
- `GmailProviderSpec`: via `FakeGmailProvider`; token refresh triggers before expiry
- `GoogleCalendarSkillSpec`: all 6 tools via `FakeCalendarProvider`; write ops blocked without capability
- `GoogleDriveSkillSpec`: all 3 tools via `FakeDriveProvider`; download stores artifact

**Unit tests — `server`** (new):
- `OAuthRoutesSpec`: state JWT generation/verification; CSRF rejection; redirect URL construction

**Integration tests — `db`**:
- `ExternalCredentialRepositorySpec` (Testcontainers): upsert, find, delete, listByUser round-trips

---

## Open Questions (resolved)

1. ~~What goes in the module?~~ → Two modules: `email` (IMAP/SMTP) + `google-services` (Gmail/Calendar/Drive)
2. ~~Where does OAuthCredentialService live?~~ → Trait in `model`; impl in `google-services`; encryptor utility in `server`
3. ~~Gmail transport?~~ → Google REST APIs via zio-http; not email via IMAP
4. **Google scopes**: Request all three scopes (`gmail.modify`, `calendar`, `drive.readonly`) in a single OAuth flow. One `external_credentials` row with `provider = "google"` serves all three skills.
5. **IMAP per-user credentials**: IMAP username/password stored in `external_credentials` with `provider = "imap"` as encrypted JSON. Loaded by `server` at startup when wiring `ImapSmtpProvider`. If not configured, IMAP uses global `application.conf` defaults.
