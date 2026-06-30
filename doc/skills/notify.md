# Notify Skill

Send notifications to users through their preferred communication channel (Telegram, email, etc.).

**Skill name:** `notify`  
**Tier:** Built-in (always enabled)  
**Source:** `server/src/main/scala/jorlan/service/skills/NotifySkill.scala`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Routes a text message to a user's preferred channel (Telegram preferred when connected) or to a specific channel identity.

## Tools

| Tool | Description |
|------|-------------|
| `notify.user` | Send a message to a user's preferred channel |
| `notify.channel` | Send a message to a specific channel identity |

### `notify.user`

**Input:** `{ "message": "<text>", "userId": 42 }`

Omit `userId` to notify the currently authenticated user. Use `users.find` to look up user IDs for other users.

### `notify.channel`

Sends a message to a specific user on a given connector channel. Supported `channelType` values depend on which connectors are active.

**Telegram example:**
`{ "message": "<text>", "channelType": "Telegram", "channelUserId": "123456789" }`

**Discord example:**
`{ "message": "<text>", "channelType": "Discord", "channelUserId": "<discord-user-snowflake-id>" }`

To notify via a Discord guild channel instead of a DM, use `discord.send_message` directly.

---

## Capabilities required

| Capability | Tools |
|-----------|-------|
| `notify.send` | All tools |

---

## Example prompts

- "Notify me when the task is complete"
- "Send John a message saying the report is ready"
- "Alert the team that the deployment succeeded"
- "Let Alice know the meeting is cancelled"

---

## Notes

- Notification routing uses whichever connector is active for the target user
- Telegram is preferred when the user has a linked Telegram identity
- Requires at least one connector (e.g. Telegram) to be configured and active
