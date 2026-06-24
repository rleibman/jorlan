# Telegram Connector — Installation

**Skill name:** `telegram`  
**Config key:** `skill.telegram`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## 1. Create a Telegram Bot

1. Open Telegram and start a chat with **@BotFather**
2. Send `/newbot` and follow the prompts
3. Copy the **Bot API Token** (format: `1234567890:ABCdef...`)

---

## 2. Configure the connector

### Via web UI

**Skills → Telegram → Configure:**

```json
{
  "botToken": "1234567890:ABCdef...",
  "allowedChatIds": [],
  "allowedUserIds": [],
  "unrecognizedPolicy": "Reject"
}
```

### Via SQL

```sql
INSERT INTO server_settings (`key`, `value`)
VALUES ('skill.telegram', '{"botToken":"your-token","unrecognizedPolicy":"Reject"}')
ON DUPLICATE KEY UPDATE `value` = VALUES(`value`);
```

Restart the Jorlan server to start the long-polling loop.

---

## 3. Link user identities

For Jorlan to route incoming Telegram messages to the right user:

1. Navigate to **Settings → Profile** (as the user)
2. Add a Telegram identity: enter your Telegram user ID or username
3. Alternatively, use the `identity.link` tool from the `contacts` skill

To find your Telegram user ID, send `/start` to **@userinfobot**.

---

## 4. Security

- `allowedChatIds` and `allowedUserIds` restrict which Telegram entities can interact
- `unrecognizedPolicy: "Reject"` silently drops messages from unmapped users
- `unrecognizedPolicy: "CreateGuest"` creates a guest user for each new sender

---

## Grant capabilities

Agents need the `telegram.send` capability to send outbound messages.
