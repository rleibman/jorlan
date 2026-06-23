# Email Skill — Installation

**Skill name:** `email`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## Providers

### Google Gmail (recommended)

Email via Gmail is provided by the Google Services integration, which uses per-user OAuth 2.0.

See **[google-services/INSTALL.md](../google-services/INSTALL.md)** for full OAuth setup.

Once Google OAuth is configured and a user has connected their Google account, Gmail is available as the email provider
automatically.

### IMAP/SMTP

Full IMAP/SMTP support is planned for a future release. A placeholder implementation exists in the codebase but
returns "not yet implemented" for all operations.

---

## Capabilities required

| Capability    | Required for                  |
|---------------|-------------------------------|
| `email.read`  | Reading and searching email   |
| `email.send`  | Sending and replying          |
| `email.write` | Drafting, archiving, deleting |

Grant via **Admin → Agents → \<agent\> → Capabilities**.
