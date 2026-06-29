# Discord Connector Skill

Bidirectional Discord integration — receive user messages from Discord channels/DMs and have the agent reply via the same channel.

**Skill name:** `discord`  
**Tier:** Built-in connector  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it provides

The Discord connector acts as both an **ingress** (receiving messages from Discord) and an **egress** skill (sending messages to Discord channels or users).

### Ingress (receive)

Messages sent to the bot in:
- **DM** — any direct message
- **Guild text channels** — only when the bot is @mentioned (configurable)

are routed into a Jorlan agent session for the sender. The agent's reply is sent back to the same channel/DM automatically.

### Egress tools

| Tool                        | Description                                                 | Capability      |
|-----------------------------|-------------------------------------------------------------|-----------------|
| `discord.send_message`      | Send a message to a guild text channel by Snowflake channel ID | `discord.send` |
| `discord.send_dm`           | Send a direct message to a user by Snowflake user ID        | `discord.send`  |
| `discord.get_history`       | Retrieve recent messages from a channel (up to 100)         | `discord.read`  |
| `discord.get_channel_info`  | Get channel name, guild, and type by Snowflake channel ID   | `discord.read`  |

---

## Filtering options

| Setting               | Default      | Description                                                   |
|-----------------------|--------------|---------------------------------------------------------------|
| `botToken`            | (required)   | Discord bot token                                             |
| `allowedGuildIds`     | (empty = all)| If set, messages from guilds not in this list are dropped     |
| `allowedUserIds`      | (empty = all)| If set, only messages from listed users are processed         |
| `mentionOnly`         | `true`       | In guild channels, only process messages that @mention the bot|
| `unrecognizedPolicy`  | `Reject`     | `Reject` or `Quarantine` unknown senders                      |

---

## Example prompts

- "Send a Discord notification to the #deployments channel"
- "Get the last 20 messages from Discord channel 1234567890"
- "DM user 9876543210 on Discord with today's summary"

---

## Capabilities

| Capability      | Required for                                |
|-----------------|---------------------------------------------|
| `discord.send`  | `discord.send_message`, `discord.send_dm`   |
| `discord.read`  | `discord.get_history`, `discord.get_channel_info` |

Grant via **Admin → Agents → \<agent\> → Capabilities**.

---

See [INSTALL.md](INSTALL.md) for setup instructions.
