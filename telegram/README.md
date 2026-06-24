# Telegram Connector

Bidirectional Telegram integration — receive messages from users via Telegram and send messages/photos/files back.

**Skill name:** `telegram`  
**Tier:** Built-in (Connector)  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Connects Jorlan to a Telegram Bot. Incoming Telegram messages are routed to the agent runtime. Agents can send text,
photos, and files to any authorised Telegram chat.

## Inbound (connector)

When a Telegram user sends a message to the bot, Jorlan:

1. Resolves the Telegram user ID to a Jorlan user via the identity system
2. Routes the message to the appropriate agent session

## Outbound (tools)

| Tool                    | Description                               |
|-------------------------|-------------------------------------------|
| `telegram.send_message` | Send a text message to a Telegram chat    |
| `telegram.send_photo`   | Send a photo (URL or file path) to a chat |
| `telegram.send_file`    | Send a file/document to a chat            |

### `telegram.send_message`

**Input:** `{ "chatId": "<chat-id>", "text": "<message text>", "parseMode": "MarkdownV2|HTML" }`

### `telegram.send_photo`

**Input:** `{ "chatId": "<chat-id>", "photo": "<url-or-path>", "caption": "<optional>" }`

### `telegram.send_file`

**Input:** `{ "chatId": "<chat-id>", "document": "<url-or-path>", "caption": "<optional>", "filename": "<optional>" }`

---

## Capabilities required

| Capability      | Tools              |
|-----------------|--------------------|
| `telegram.send` | All outbound tools |

---

## Example prompts

- "Send me a Telegram notification when the backup is done"
- "Tell John (via Telegram) that the meeting is at 3 PM"
- "Send the report as a file to the team chat"

---

## Configuration

`configKey`: `skill.telegram`  
`configJsModule`: `jorlan-telegram`

```json
{
  "botToken": "",
  "allowedChatIds": [],
  "allowedUserIds": [],
  "unrecognizedPolicy": "Reject",
  "useWebhook": false,
  "apiBaseUrl": "https://api.telegram.org",
  "longPollTimeoutSeconds": 5
}
```

| Field                  | Type     | Default                    | Description                                                                            |
|------------------------|----------|----------------------------|----------------------------------------------------------------------------------------|
| `botToken`             | string   | `""`                       | **Required.** Telegram Bot API token from @BotFather                                   |
| `allowedChatIds`       | string[] | `[]`                       | If non-empty, only these chat IDs are accepted                                         |
| `allowedUserIds`       | string[] | `[]`                       | If non-empty, only these user IDs are accepted                                         |
| `unrecognizedPolicy`   | string   | `"Reject"`                 | What to do with unrecognised senders: `Reject`, `CreateGuest`, or `IgnoreSilently`     |
| `useWebhook`           | boolean  | `false`                    | Use webhook instead of long-polling (webhook not yet supported; falls back to polling) |
| `apiBaseUrl`           | string   | `https://api.telegram.org` | Telegram API URL (do not change)                                                       |
| `longPollTimeoutSeconds` | int    | `5`                        | Long-poll timeout passed to `getUpdates` (seconds)                                     |
See [INSTALL.md](INSTALL.md) for setup instructions.
