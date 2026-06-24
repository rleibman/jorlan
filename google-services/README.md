# Google Services Skills

Gmail, Google Calendar, Google Contacts, and Google Drive — all via OAuth 2.0, one per-user authorisation flow.

**Skill names:** `email` (Gmail), `calendar`, `google_contacts`, `drive`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it provides

| Skill             | Description                                         |
|-------------------|-----------------------------------------------------|
| `email`           | Read, send, draft, archive, search Gmail            |
| `calendar`        | List calendars, CRUD events, respond to invites     |
| `google_contacts` | List, search, and retrieve contacts                 |
| `drive`           | List files, read text, download files, upload files |

---

## Google Calendar tools

| Tool                      | Description                                         |
|---------------------------|-----------------------------------------------------|
| `calendar.listCalendars`  | List all calendars for the authenticated user       |
| `calendar.listEvents`     | List events in a calendar with optional time filter |
| `calendar.getEvent`       | Get details for a specific event                    |
| `calendar.createEvent`    | Create a new event                                  |
| `calendar.updateEvent`    | Update an existing event                            |
| `calendar.deleteEvent`    | Delete an event                                     |
| `calendar.respondToEvent` | Accept, decline, or tentatively accept an invite    |

**Capabilities:** `calendar.read` (list, get), `calendar.write` (create, update, delete, respond)

---

## Google Contacts tools

| Tool                              | Description                                    |
|-----------------------------------|------------------------------------------------|
| `google_contacts.list_contacts`   | List contacts (with optional maxResults)       |
| `google_contacts.search_contacts` | Search by name, email, or text                 |
| `google_contacts.get_contact`     | Retrieve a contact by People API resource name |

**Capabilities:** `google_contacts.read` (all tools)

---

## Google Drive tools

| Tool                 | Description                                                   |
|----------------------|---------------------------------------------------------------|
| `drive.listFiles`    | List files, optionally filtered by folder or query            |
| `drive.readFile`     | Read the text content of a file (Docs exported as plain text) |
| `drive.downloadFile` | Download a binary file (stored as an Artifact)                |
| `drive.uploadFile`   | Upload a file to Drive                                        |
| `drive.deleteFile`   | Delete a file                                                 |
| `drive.createFolder` | Create a new folder                                           |

**Capabilities:** `drive.read` (list, read, download), `drive.write` (upload, delete, createFolder)

---

## Example prompts

**Calendar:**

- "What meetings do I have tomorrow?"
- "Create a dentist appointment for next Friday at 2 PM"
- "Accept the lunch invite from Sarah"

**Contacts:**

- "Find John's email address in my contacts"
- "List all my Google contacts"

**Drive:**

- "List the files in my Drive"
- "Read the content of 'Project Plan.docx'"
- "Upload the backup report to Drive"

---

## Authentication

Authentication is per-user via Google OAuth 2.0. No server-wide credentials are stored for sensitive scopes.

**Required Google API scopes:**

- Gmail: `https://www.googleapis.com/auth/gmail.modify`
- Calendar: `https://www.googleapis.com/auth/calendar`
- Contacts: `https://www.googleapis.com/auth/contacts.readonly`
- Drive: `https://www.googleapis.com/auth/drive`

See [INSTALL.md](INSTALL.md) for the complete setup guide.
