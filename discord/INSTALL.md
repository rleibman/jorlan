# Discord Connector — Installation

**Skill:** `discord`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## 1. Create a Discord Application and Bot

1. Go to <https://discord.com/developers/applications>
2. Click **New Application**, give it a name (e.g. "Jorlan")
3. Navigate to **Bot** in the left sidebar
4. Click **Add Bot** (or **Reset Token** if one already exists)
5. Copy the **bot token** — this is your `DISCORD_BOT_TOKEN`

---

## 2. Configure Bot Permissions and Intents

In the **Bot** section:

1. Enable the following **Privileged Gateway Intents**:
    - **Message Content Intent** — required to read message text
    - **Server Members Intent** — required for identity resolution (optional but recommended)
2. Under **OAuth2 → URL Generator**, select the following scopes and permissions:
    - Scopes: `bot`
    - Bot permissions:
        - Read Messages/View Channels
        - Send Messages
        - Read Message History
3. Copy the generated invite URL and use it to add the bot to your server

---

## 3. Add a Discord Connector in Jorlan

1. Navigate to **Admin → Connectors** in the Jorlan UI
2. Click **+ Add Connector**, select **Discord**
3. Fill in:
    - **Bot Token** — the token from step 1
    - **Mention Only** — check this to only respond when @mentioned in guild channels (recommended)
    - **Allowed Guild IDs** — optional; restrict to specific servers by pasting their Snowflake IDs (one per line)
    - **Allowed User IDs** — optional; restrict to specific users by Snowflake ID (one per line)
    - **Unrecognized Identity Policy**:
        - `Reject` — silently drop messages from users not mapped to a Jorlan user
        - `Quarantine` — hold messages for admin review
4. Click **Save**

The bot starts listening immediately after save.

---

## 4. Map Discord Users to Jorlan Users

For agents to respond to Discord messages, each Discord user must be linked to a Jorlan user:

1. Navigate to **Admin → Users → \<user\> → Identities**
2. Add a **Discord identity** with the user's Discord Snowflake user ID

To find a user's Snowflake ID: in Discord, enable **Developer Mode** (Settings → Advanced), then right-click the user and select **Copy User ID**.

---

## 5. Grant Capabilities

| Capability      | Allows                                                    |
|-----------------|-----------------------------------------------------------|
| `discord.send`  | Agent can send messages and DMs on Discord                |
| `discord.read`  | Agent can read channel history and channel metadata       |

Grant via **Admin → Agents → \<agent\> → Capabilities**.

---

## 6. (Optional) Discord Social Login

To allow users to log into Jorlan using their Discord account:

1. In the Discord Developer Portal, go to **OAuth2 → General**
2. Add a redirect URI: `http://localhost:8080/oauth/discord/callback` (adjust host/port as needed)
3. Copy the **Client ID** and **Client Secret**
4. Add to Jorlan configuration:

```env
DISCORD_LOGIN_CLIENT_ID=your-client-id
DISCORD_LOGIN_CLIENT_SECRET=your-client-secret
DISCORD_LOGIN_REDIRECT_URI=http://localhost:8080/oauth/discord/callback
```

This is separate from the bot token — it allows Jorlan's web login page to show "Continue with Discord".

---

## Troubleshooting

| Symptom                                  | Check                                                                              |
|------------------------------------------|------------------------------------------------------------------------------------|
| Bot is online but not responding         | Confirm **Message Content Intent** is enabled in the Bot settings                 |
| Bot responds only in DMs, not channels   | Uncheck **Mention Only** or @mention the bot in the channel                        |
| Messages from a user are silently dropped | The user's Discord ID is not in **Allowed User IDs**, or not mapped to a Jorlan user |
| "Quarantine" messages not visible         | Check **Admin → Approvals** in the Jorlan UI                                       |
| Bot token invalid / "401 Unauthorized"   | Regenerate the token in Discord Developer Portal and update Jorlan config          |
