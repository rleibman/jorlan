# Contacts Skill

Look up Jorlan users by name, find users by their channel identity (e.g. Telegram ID), and manage channel identity links.

**Skill name:** `contacts`  
**Tier:** Built-in (always enabled)  
**Source:** `server/src/main/scala/jorlan/service/skills/ContactsSkill.scala`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Provides agents with access to the Jorlan user directory and the identity mapping system that links external channel accounts (Telegram, email) to Jorlan users.

## Tools

| Tool | Description |
|------|-------------|
| `contacts.find` | Search users by display name (fuzzy/phonetic matching) |
| `identity.resolve` | Find a user linked to a specific channel identity |
| `identity.link` | Create or update a channel identity for a user |
| `identity.listAliases` | List all channel identities for a user |

### `contacts.find`

**Input:** `{ "name": "Roberto", "page": 0, "pageSize": 10 }`

Fuzzy matching: `"Rob"` matches `"Roberto"`, `"Sara"` matches `"Sarah"`. Omit `name` to list all users.

### `identity.resolve`

**Input:** `{ "channelType": "Telegram", "channelUserId": "123456789" }`

**Output:** Jorlan user record if found

### `identity.link`

**Input:** `{ "channelType": "Telegram", "channelUserId": "123456789", "userId": 42 }`

Omit `userId` to link to the currently authenticated user.

---

## Capabilities required

| Capability | Tools |
|-----------|-------|
| `contacts.read` | `contacts.find`, `identity.resolve`, `identity.listAliases` |
| `identity.manage` | `identity.link` |

---

## Example prompts

- "Who is Roberto in the system?"
- "Find a user named Alice"
- "What Telegram account is linked to user 42?"
- "Link my Telegram ID 123456789 to my account"
- "List all channel identities for Roberto"

---

## Configuration

No configuration required. Data is stored in the Jorlan database.
