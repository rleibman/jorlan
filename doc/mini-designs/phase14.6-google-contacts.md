# Phase 14.6 — Google Contacts Skill Mini-Design

## Overview

Add a Google Contacts skill to Jorlan that allows agents to search, list, and retrieve contacts from the authenticated user's Google Contacts (People API v1). This lives in the existing `google-services` SBT module alongside Gmail, Calendar, and Drive providers.

## Google API

- **API**: [Google People API v1](https://developers.google.com/people/api/rest)
- **Library**: `com.google.apis:google-api-services-people:v1-rev20241217-2.0.0`
- **Client class**: `com.google.api.services.people.v1.PeopleService`
- **Primary endpoints used**:
  - `people.searchContacts` — full-text search across contacts
  - `people.connections.list` — list all contacts with pagination
  - `people.get` — fetch a single contact by resource name

## OAuth Scope

- `https://www.googleapis.com/auth/contacts.readonly` — read-only access to Google Contacts
- No write access needed for this phase; no new `server_settings` key required
- Existing `OAuthCredentialService` + `refreshAccessToken` flow is reused as-is

## Architecture

### `GoogleContactsProvider` (in `google-services` module)

Extends `GoogleApiProvider[PeopleService]`. Exposes:

```
searchContacts(userId, query, maxResults): IO[JorlanError, List[GoogleContact]]
listContacts(userId, maxResults):          IO[JorlanError, List[GoogleContact]]
getContact(userId, resourceName):          IO[JorlanError, Option[GoogleContact]]
```

The `GoogleContact` domain model (defined inside `google-services`, not in `model`) captures:
- `resourceName: String` — the People API resource identifier (e.g. `people/c1234567890`)
- `displayName: Option[String]`
- `emails: List[String]`
- `phones: List[String]`
- `organizations: List[String]`

### `GoogleContactsSkill` (in `server` module)

Skill name / namespace: `google_contacts`. Tools:

| Tool name                            | Description                               | Required capability       |
|--------------------------------------|-------------------------------------------|---------------------------|
| `google_contacts.list_contacts`      | List contacts, optional maxResults        | `google_contacts.read`    |
| `google_contacts.search_contacts`    | Full-text search, required `query`        | `google_contacts.read`    |
| `google_contacts.get_contact`        | Fetch one contact by `resourceName`       | `google_contacts.read`    |

All tools are `RiskClass.ReadOnly` (no approval required). Each tool appends a `ContactRead` event to the event log.

## Event Type

Add `ContactRead` to the `EventType` enum in `model/shared/src/main/scala/jorlan/event.scala`.

## Capability Grant

Add `google_contacts.read` to the `systemCapabilities` list in `InitService.scala` so admin users receive it on initialization (and on `topUpAdminCapabilities`).

## Config

No new config key needed. The skill is always registered (like `GoogleCalendarSkill`). If the user has not completed Google OAuth, API calls will fail at invocation time with an informative error.

## SBT Module

No new module. Add to `googleServices` in `build.sbt`:
```
"com.google.apis" % "google-api-services-people" % "v1-rev20241217-2.0.0" withSources(),
```

## Test Strategy

`GoogleContactsSkillSpec` with a `FakeGoogleContactsProvider` backed by in-memory `Ref`. Tests:
- `google_contacts.list_contacts` returns all contacts
- `google_contacts.search_contacts` filters by query
- `google_contacts.get_contact` returns a contact by resource name
- `google_contacts.get_contact` on unknown resource name returns `not found` JSON
- Unknown tool returns `JorlanError`
