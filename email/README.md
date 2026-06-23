# Email Skill

Read, send, draft, archive, and search email messages. Supports Google Gmail (via OAuth) and standard IMAP/SMTP
accounts.

**Skill name:** `email`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Gives agents full access to email — listing the inbox, reading messages, sending, drafting, archiving, and searching.

## Tools

| Tool            | Description                         |
|-----------------|-------------------------------------|
| `email.list`    | List recent messages from the inbox |
| `email.read`    | Read a specific message by ID       |
| `email.send`    | Send an email immediately           |
| `email.draft`   | Create a draft without sending      |
| `email.archive` | Archive a message                   |
| `email.delete`  | Delete a message                    |
| `email.reply`   | Reply to a message                  |
| `email.search`  | Search messages by query            |

### `email.list`

**Input:** `{ "maxResults": 10, "query": "<optional filter>" }`

**Output:** list of `{ id, from, to, subject, snippet, date, isRead }`

### `email.send`

**Input:**

```json
{
  "to": [
    "recipient@example.com"
  ],
  "subject": "Hello",
  "body": "Message body",
  "cc": [],
  "bcc": [],
  "isHtml": false
}
```

### `email.search`

**Input:** `{ "query": "from:boss@example.com subject:report" }`

---

## Capabilities required

| Capability    | Tools                                          |
|---------------|------------------------------------------------|
| `email.read`  | `email.list`, `email.read`, `email.search`     |
| `email.send`  | `email.send`, `email.reply`                    |
| `email.write` | `email.draft`, `email.archive`, `email.delete` |

---

## Example prompts

- "Do I have any unread emails from my boss?"
- "Send an email to team@example.com saying the meeting is rescheduled"
- "Draft a reply to the latest email from Alice"
- "Archive all emails older than 30 days with subject 'Newsletter'"
- "Search my email for invoices from last month"

---

## Providers

| Provider             | Status                                                                      |
|----------------------|-----------------------------------------------------------------------------|
| Google Gmail (OAuth) | Available — see [google-services/INSTALL.md](../google-services/INSTALL.md) |
| IMAP/SMTP            | Placeholder — full implementation in a future release                       |

See [INSTALL.md](INSTALL.md) for setup instructions.
