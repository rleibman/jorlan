# Jorlan Manual Testing Guide

> Last updated: 2026-06-07 (Phase 11 — Telegram connector; use-case prompts added with gap analysis)
>
> This document is maintained alongside the codebase. Run the `scala-doc-auditor` agent after
> significant feature additions to keep this guide in sync.

---

## Prerequisites

- MariaDB running and the Jorlan database created (see `db/init-db.sh`)
- Ollama running with at least one model downloaded (e.g. `ollama pull llama3.2:3b`)
- Environment variables set (see `.env.example`): `JORLAN_AUTH_SECRET_KEY` (min 32 chars) at minimum
- Server jar or `sbt server/run`
- Shell jar (`shell/target/…/jorlan-shell-assembly-…jar`) or `sbt shell/run`
- For version-mismatch tests: a second shell jar built from `main` branch

---

## Section A — Smoke Test

Minimum steps to confirm the application is functional end-to-end.

### A1. First-run initialization

1. Start the server (`sbt server/run`). Confirm the console prints a `JORLAN SETUP TOKEN` banner.
2. Start the shell (`sbt shell/run` or `java -jar jorlan-shell.jar`).
3. Shell prompts `Server URL [http://localhost:8080]` — press **Enter** to accept the default.
4. Shell should print `Localhost detected — token not required.`
5. Enter a server name (or press **Enter** for the default), admin display name, email, and a password ≥ 12 characters.
   Confirm the password.
6. Shell prints `Server initialized. Config saved to …` followed by `Waiting for server to switch to full mode…`
7. After ~5 seconds the shell connects and prints `Connected to <server-name> as <display-name>.`
8. The input prompt shows `❯` with a blinking cursor and the mode bar shows `[session: 1]`.

**Expected:** No error messages. Session ID is set automatically.

### A2. Send a message to the LLM

1. At the `❯` prompt, type any text (e.g. `Hello`) and press **Enter**.
2. The shell displays your message.
3. After a moment the LLM response tokens stream in and appear token-by-token.
4. When the response finishes, the prompt is ready for the next message.
5. Ask the agent its name — it should respond with the configured personality name (default: `Jorlan`).

**Expected:** LLM tokens appear in the shell. No "Streaming error" or "Failed" messages.

### A3. Graceful quit

1. Type `/quit` (or press **Ctrl-C**).
2. Shell prints a goodbye message and exits cleanly within ~2 seconds.

**Expected:** Process exits. No hung JVM.

---

## Section B — Full Test

Covers all implemented functionality as of Phase 10.

### B1. Server startup and init

| Step | Action                                              | Expected                                                                    |
|------|-----------------------------------------------------|-----------------------------------------------------------------------------|
| B1.1 | Start server on a **fresh database**                | `JORLAN SETUP TOKEN` banner printed to console                              |
| B1.2 | Start shell; press Enter to accept default URL      | `Localhost detected — token not required.`                                  |
| B1.3 | Enter empty server name (press Enter)               | Default name `Jorlan` used                                                  |
| B1.4 | Enter admin details with a password **< 12 chars**  | Error shown, wizard retries                                                 |
| B1.5 | Enter mismatched passwords                          | `Passwords do not match. Please try again.`                                 |
| B1.6 | Enter valid details; confirm password               | `Server initialized. Config saved to …` then 5 s wait, then connects        |
| B1.7 | Run shell again (server already initialized)        | Shell skips the wizard, prompts for email/password directly, connects       |
| B1.8 | On a **non-localhost** client, token field is shown | Token prompt is displayed (security check — do not skip)                    |

### B2. Shell commands — built-in

| Command              | Expected output                                                                                 |
|----------------------|-------------------------------------------------------------------------------------------------|
| `/help`              | Full command list with key-binding summary at top                                               |
| `/commands`          | Same as `/help` — full command list                                                             |
| `/about`             | Shows "Jorlan Shell", version, and copyright notice                                             |
| `/status`            | Shows server URL, client version, server version + uptime, `✔ GraphQL API reachable` when up  |
| `/whoami`            | Shows logged-in user's Display name, Email, User ID, Active status (parsed, not raw JSON)       |
| `/models`            | Lists all Ollama-downloaded models with name, provider, parameter size, quantization level      |
| `/model`             | Shows active session ID, real model name (from gateway), and session status                     |
| `/quit`              | Goodbye message; process exits within ~2 s                                                      |
| `/exit`              | Same as `/quit`                                                                                 |
| `/unknowncommand`    | `Unknown command: /unknowncommand — try /help` in red; shell continues                          |
| Ctrl-C               | Process exits cleanly (no hung threads)                                                         |
| Ctrl-D               | Same as Ctrl-C                                                                                  |

### B3. TUI layout and input

| Step | Scenario                    | Expected                                                                        |
|------|-----------------------------|---------------------------------------------------------------------------------|
| D1   | Status bar                  | Blue background; `● <server-name>  [<display-name>]  [<url>]`                  |
| D2   | Mode bar: active session    | `[session: N]  [model: <model-name>]` at bottom                                 |
| D3   | Input cursor                | Blinking terminal cursor visible at end of typed text in input line             |
| D4   | Terminal resize             | TUI redraws without flicker or garbled characters                               |
| D5   | Scroll with ↑↓ arrows       | Scrolls conversation history 1 line per press                                   |
| D6   | Scroll with PgUp/PgDn       | Scrolls conversation history 10 lines per press                                 |
| D7   | Home/End keys               | Jump to oldest/newest messages                                                  |
| D8   | Long line wrap              | Messages wider than terminal width wrap cleanly; no horizontal overflow         |
| D9   | Streaming response          | Tokens append to a **single** `✦` line — not a new line per token              |
| D10  | Backspace on empty input    | No effect; no crash                                                             |
| D11  | Enter on empty line         | No message sent; no error added to conversation                                 |
| D12  | Message without session     | Red error: `No active session — use /new to start one.`                         |

### B4. Version check

| Step | Client / Server versions                           | Expected                                                            |
|------|----------------------------------------------------|---------------------------------------------------------------------|
| C1   | Same version                                       | Connects normally; no version message                               |
| C2   | Client patch ≥ server patch (same major.minor)     | Connects normally                                                   |
| C3   | Client patch < server patch (same major.minor)     | `Fatal: Version incompatibility …` shown; shell exits               |
| C4   | Client minor ≠ server minor                        | `Fatal: Version incompatibility …` shown; shell exits               |
| C5   | Client major ≠ server major                        | `Fatal: Version incompatibility …` shown; shell exits               |
| C6   | Server `/api/status` unreachable                   | Version check skipped; shell continues with server URL as display name |

### B5. Session management

| Step | Action                                        | Expected                                                        |
|------|-----------------------------------------------|-----------------------------------------------------------------|
| B5.1 | After login the mode bar shows `[session: N]` | Auto-created or resumed session                                 |
| B5.2 | `/new`                                        | Creates a new session; old subscription fiber interrupted first |
| B5.3 | `/new llama3.2:3b`                            | Creates session with the specified model ID                     |
| B5.4 | `/model`                                      | Shows session ID, model name (not "default"), and status Active |
| B5.5 | `/agents list`                                | Shows the active session(s) with ID, status, and model          |
| B5.6 | `/agents stop <id>` (admin)                   | Session terminated; local state cleared; mode bar resets        |
| B5.7 | `/agents stop <id>` (no permission)           | Red error: `Access denied: no permission for 'agent.session.terminate'` |
| B5.8 | Message after `/agents stop`                  | Red error: `No active session — use /new to start one.`         |
| B5.9 | Restart shell; reconnect                      | Existing Active session is resumed (not re-created)             |

### B6. LLM messaging

| Step | Action                               | Expected                                                       |
|------|--------------------------------------|----------------------------------------------------------------|
| B6.1 | Type a message                       | Message echoed; LLM tokens stream back token-by-token          |
| B6.2 | After LLM finishes                   | Prompt ready immediately; no extra blank lines                 |
| B6.3 | Second message, same session         | Same WebSocket reused — no new `[WS] connecting to` in logs    |
| B6.4 | Long response (>100 tokens)          | Full response received; no truncation                          |
| B6.5 | Ask "what is your name?"             | Agent responds with the personality name (e.g. `Jorlan`)       |
| B6.6 | Ollama offline, send message         | `Agent error: …` displayed; shell remains usable              |

### B7. Personality

| Step | Action                                               | Expected                                                         |
|------|------------------------------------------------------|------------------------------------------------------------------|
| B7.1 | `/personality`                                       | Shows current server personality (name, formality, prompt, etc.) |
| B7.2 | `/personality set formality Casual`                  | Updates formality; next LLM response uses casual tone            |
| B7.3 | `/personality set formality GenZ`                    | Updates to GenZ formality; response is internet-native, brief    |
| B7.4 | `/personality set formality Rude`                    | Updates to Rude; response is blunt and unfiltered                |
| B7.5 | `/personality set formality Boomer`                  | Updates to Boomer; references classic culture                    |
| B7.6 | `/personality set formality Quirky`                  | Playful, unexpected angles in responses                          |
| B7.7 | `/personality set name MyBot`                        | Updates name; ask agent its name → responds with `MyBot`         |
| B7.8 | `/personality set formality BadValue`                | Error: invalid formality; valid values listed                    |
| B7.9 | `/personality set badfield x`                        | Error: unknown field; valid fields listed                        |
| B7.10 | `/personality set languages English, French`         | Updates languages list                                           |
| B7.11 | Send message after personality change                | Agent uses new system prompt (formality + name) — rebuild noted in logs |

Valid formality values: `Casual`, `Professional`, `Academic`, `Technical`, `Quirky`, `Fresh`, `Rude`, `Boomer`, `GenX`, `Millennial`, `GenZ`, `GenAlpha`

### B8. Memory

| Step | Action                                          | Expected                                                           |
|------|-------------------------------------------------|--------------------------------------------------------------------|
| B8.1 | `/memory list`                                  | Lists User-scoped memory records (empty initially)                 |
| B8.2 | `/memory list User`                             | Same as above — case-insensitive                                   |
| B8.3 | `/memory list Shared`                           | Lists Shared-scoped memory records                                 |
| B8.4 | `/memory list badscope`                         | Red error: `Invalid scope 'badscope'. Valid: User, Shared, Workspace, Private` |
| B8.5 | `/memory remember mykey some text about me`     | Stores a memory record; confirmed via `/memory list`               |
| B8.6 | `/memory search text`                           | Returns records containing "text"                                  |
| B8.7 | `/memory forget <id>`                           | Deletes the record; `/memory list` no longer shows it              |
| B8.8 | Send a message on a topic previously remembered | Agent response may reference stored memory (context injection)     |

### B9. Capabilities

| Step | Action             | Expected                                                                   |
|------|--------------------|----------------------------------------------------------------------------|
| B9.1 | `/capabilities`    | Lists capability grants for the current user (admin has many by default)   |
| B9.2 | Non-admin user     | Fewer or no grants listed                                                  |

### B10. Approvals

| Step | Action                          | Expected                                                          |
|------|---------------------------------|-------------------------------------------------------------------|
| B10.1 | `/approvals list`              | Lists pending approval requests (empty if none pending)           |
| B10.2 | `/approvals approve <id>`      | Approves the request; no longer shown in list                     |
| B10.3 | `/approvals deny <id>`         | Denies the request; no longer shown in list                       |
| B10.4 | Approval with no permission    | Red error: `Access denied: no permission for 'approval.decide'`   |

### B11. Scheduler (Phase 10)

The scheduler is accessible via GraphQL only (no shell commands yet).

| Test                              | Expected                                                                      |
|-----------------------------------|-------------------------------------------------------------------------------|
| `createJob` mutation              | Job created with status `Pending`; appears in `jobs` query                   |
| `addTrigger` mutation (cron)      | Trigger attached; job fires at the cron expression time                       |
| `addTrigger` mutation (oneShot)   | Trigger fires once at the specified instant; job transitions to `Completed`   |
| `pauseJob` / `resumeJob`          | Job pauses/resumes; status reflects change                                    |
| `deleteJob`                       | Job removed from `jobs` query                                                 |
| `triggerNow` mutation             | Job fires immediately regardless of trigger schedule                          |
| Owner-only access                 | Non-owning user cannot `pauseJob` / `resumeJob` / `triggerNow`               |

### B12. Models

| Step | Action     | Expected                                                                           |
|------|------------|------------------------------------------------------------------------------------|
| B12.1 | `/models` | Lists all Ollama models available on the server (equivalent to `ollama list`)      |
| B12.2 | Output format | Each model shows name, provider (`ollama/3.2B/Q4_K_M`), and streaming support |
| B12.3 | Ollama offline | Falls back to showing the configured default model; warning in server logs    |
| B12.4 | `/new llama3.2:3b` then `/model` | Shows `llama3.2:3b` (not "default") in model field         |

### B13. Security: capability enforcement

| Test                                                             | Expected                                                  |
|------------------------------------------------------------------|-----------------------------------------------------------|
| Access GraphQL mutation without auth token                       | `Not authenticated` error                                 |
| Admin user can call all mutations                                | No `Access denied` errors                                 |
| Freshly created non-admin user calls `createSession`             | `Access denied: no permission for 'agent.session.create'` |
| Non-admin terminates another user's session                      | `Access denied: no permission for 'agent.session.terminate'` |

### B14. Server lifecycle

| Step  | Action                                 | Expected                                                          |
|-------|----------------------------------------|-------------------------------------------------------------------|
| B14.1 | Start server on already-initialized DB | No setup token printed; full routes served immediately            |
| B14.2 | `GET /health`                          | HTTP 200                                                          |
| B14.3 | `GET /api/status`                      | JSON with `initialized: true`, correct `serverName` and `version` |
| B14.4 | Restart server mid-session             | Shell reconnects via heartbeat; existing session resumes          |

### B15. Tracing / log levels

| Step | Action            | Expected                                   |
|------|-------------------|--------------------------------------------|
| B15.1 | `/trace debug`   | Sets level to `debug`; debug output appears in logs |
| B15.2 | `/trace info`    | Sets level back to `info`                  |
| B15.3 | `/trace none`    | Logging suppressed                         |
| B15.4 | `/trace badlevel`| Error: invalid level; level unchanged      |

---

## Section C — Connection Heartbeat & Reconnect

| Step | Scenario                 | Expected                                                                       |
|------|--------------------------|--------------------------------------------------------------------------------|
| K1   | 30s idle, server healthy | Heartbeat ping sent every ~15 s; connection maintained; no error message       |
| K2   | Server stop and restart  | Shell shows "Lost connection" message; retries with exponential backoff        |
| K3   | Exponential backoff      | Retry intervals double: ~500ms, 1s, 2s, 4s…; shown in mode bar                |
| K4   | 4xx client error         | `isClientError` detected; retry loop stops; error shown; no infinite loop      |
| K5   | Network partition        | Shell shows disconnected state; recovers automatically on reconnect            |
| K6   | Reconnect success        | Mode bar updates to connected state; user can start a new session with `/new`  |

---

## Section D — Subscription Fiber Lifecycle

Verify that exactly one WebSocket connection exists per session and fiber cleanup is correct.

| Step | Scenario                          | Expected                                                                             |
|------|-----------------------------------|--------------------------------------------------------------------------------------|
| L1   | Send three messages, same session | Server DEBUG logs show exactly **one** `[WS] connecting to` line                    |
| L2   | `/new` while session active       | `[Shell] subscription stream ended for session=<old>` in logs; one new WS opened    |
| L3   | Server killed during streaming    | Error appears in TUI on next message; mode bar shows no-session state               |
| L4   | `/quit` during active streaming   | Subscription fiber interrupted; WS closed; process exits within ~2 s; no hung JVM   |
| L5   | Long-lived session (>1 hour idle) | No memory leak; fiber and queue stable; heartbeat keeps connection alive             |

---

## Section E — Error Display and Message Kinds

| Message Kind | Color / Prefix | Example trigger                                   |
|--------------|----------------|---------------------------------------------------|
| `Error`      | Red / `✗`      | `/unknowncommand`, streaming error, access denied |
| `System`     | Cyan / `⚙`     | `/help`, `/whoami`, `/status` output              |
| `User`       | White / `❯`    | Typed message                                     |
| `Server`     | Green / `✦`    | LLM streaming response                            |
| `Raw`        | White (no prefix/timestamp) | Welcome banner, goodbye message      |

---

## Section F — Cross-Cutting Verification

| Check | Expected                                                                              |
|-------|---------------------------------------------------------------------------------------|
| CC1   | No raw Java stack traces visible to the end user in any scenario                      |
| CC2   | TUI redraws without visible flicker during LLM streaming (~30 fps)                    |
| CC3   | Scroll buffer capped at 2000 messages; oldest dropped without crash                   |
| CC4   | Commands are case-sensitive: `/Quit` → "Unknown command: /Quit"                       |
| CC5   | Server logs show one `[WS] connecting to` per `/new`; one `subscription stream ended` per teardown |
| CC6   | Conversation log files appear under `logs/conversations/session-<id>.log` after messaging |

---

---

## Section G — Use-Case Prompts

Natural-language prompts that exercise end-to-end agent behaviour. Each prompt is listed with its current
readiness status. Prompts marked **🚧 Phase 12+** cannot pass today; the gap analysis explains exactly what
is missing and what needs to be built.

---

### G1. Simple direct Telegram message

**Prompt:** `Send a telegram message to Sarah saying hello from Roberto.`

**Status: 🚧 Phase 12+ — not testable today**

**What would need to happen:**
1. The LLM recognises the intent and decides to call the `telegram.send_message` tool.
2. It maps "Sarah" to a Telegram `chatId` (e.g. `123456789`).
3. It invokes `telegram.send_message` with `{ "chatId": "123456789", "text": "Hello from Roberto." }`.
4. The connector sends the message via the Telegram Bot API.

**Gaps (things that do not exist yet):**

| Gap | Description | Planned phase |
|-----|-------------|---------------|
| Tool calling in `AgentRunnerImpl` | `AgentRunnerImpl.processMessage` streams text only; it never calls `ModelGateway` with tool definitions or handles tool-call results. The LLM has no mechanism to invoke skills. | Phase 12 |
| `SkillRegistry` wired into the runner | The registry that enumerates available tools and routes `invoke` calls is deferred (noted in tech debt). Without it the LLM receives no tool descriptions and cannot request a tool call. | Phase 12 |
| Name → chat ID resolution | "Sarah" is a display name. The connector needs a `chatId` (Telegram numeric ID). No contact directory or channel-identity lookup skill exists yet. The most natural path: query `ChannelIdentity` records by display name/email, retrieve the stored Telegram `channelUserId`. | Phase 12 |

**When all gaps are closed:** start the server with a running Telegram bot, ensure a `ChannelIdentity` record
for Sarah exists (with `channelType = Telegram` and her `channelUserId`), type the prompt into the shell, and
confirm the Telegram message arrives in Sarah's DM.

---

### G2. Group Telegram message to multiple recipients

**Prompt:** `Send a telegram group message to Sarah and Roberto telling them you love them.`

**Status: 🚧 Phase 12+ — not testable today**

**What would need to happen:**
1. The LLM recognises multiple recipients and a Telegram group intent.
2. It resolves "Sarah" and "Roberto" to their Telegram chat IDs (or a shared group chat ID).
3. It calls `telegram.send_message` once per recipient, or once to a group chat that includes both.

**Gaps (same as G1, plus):**

| Gap | Description | Planned phase |
|-----|-------------|---------------|
| All gaps from G1 | See G1. | Phase 12 |
| Multi-recipient dispatch | A single `telegram.send_message` call targets one `chatId`. Sending to multiple individuals requires the LLM to issue multiple tool calls, which requires the tool-calling loop in `AgentRunnerImpl` to support sequences of calls in a single turn. | Phase 12 |
| Group chat ID awareness | If the intent is a shared group (not individual DMs), the system needs to store a group `chatId` and associate it with a set of members. There is currently no group-chat concept in `ChannelIdentity`. | Phase 13+ |

**When all gaps are closed:** verify two separate DMs arrive (one to Sarah, one to Roberto) for the per-user
path, or one group-chat message if a registered group chat ID is stored.

---

## Known Limitations (as of Phase 11)

- Token-per-invocation and session-scoped capability approvals are not yet exercisable from the shell (planned for a future phase).
- Scheduler shell commands are not yet implemented; use GraphQL directly.
- The shell shows LLM tokens in plain text; no markdown rendering.
- Multiple shell clients per session can subscribe, but only the most recently connected subscriber reliably receives all tokens if the prior disconnects mid-response.
- Password is stored in plaintext in the shell config for reconnect purposes; JWT token refresh is planned for a future phase.
- Context window size is not reported by `/models` (Ollama's tag endpoint does not include it; requires a per-model `showInformation` call).
- Personality changes that reset conversation history are not surfaced to the user — the model silently starts fresh.
- The Telegram connector receives inbound messages and routes them through the agent pipeline, but outbound tool-calling (agent sending a Telegram message in response to a prompt) is not yet wired — `AgentRunnerImpl` has no tool-calling loop and the `SkillRegistry` is deferred. See Section G for the detailed gap analysis.

---

## Section H — Phase 15: Web Frontend

> Last updated: 2026-06-11

### Prerequisites

- `JORLAN_WEB_ROOT` set to the output of `sbt debugDist` (default: `debugDist/` in the project root)
- Browser with developer tools open (Network tab, Console tab)
- An existing user account in the database (see Section A)

### H1. Authentication gate

1. Navigate to `http://localhost:8080/` in the browser.
2. Confirm the Login page appears (not the main app).
3. Enter invalid credentials — confirm an error message appears.
4. Enter valid credentials — confirm redirect to the Chat page.
5. Refresh the page — confirm you remain on the Chat page (JWT persisted in localStorage).
6. Click the **Logout** button — confirm redirect back to the Login page.

### H2. Chat page — create session and stream

1. On the Chat page, click **New Session**.
2. Confirm a session is created and the "Session: …" label appears in the header.
3. Type a short message and press **Enter**.
4. Confirm the user message appears immediately in the message log.
5. Confirm the AI response streams in token by token (the blinking cursor `▊` should be visible during streaming).
6. After streaming completes, confirm the full response appears as an "assistant" message.
7. Open the Network tab — confirm a WebSocket connection to `/api/jorlan/ws` is open.

### H3. Sessions page

1. Navigate to **Sessions** in the sidebar.
2. Confirm the session created in H2 appears in the list.
3. Click **+ New Session** — confirm a dialog opens with a model picker.
4. Select a model (or leave default) and click **Create** — confirm a new session row appears.
5. Click **Terminate** on an Active session — confirm the row disappears (or status changes to Cancelled).

### H4. Approvals page

1. Trigger a capability request from the shell that requires approval (e.g. a destructive skill).
2. Navigate to **Approvals** in the sidebar.
3. Confirm the pending approval appears in the table.
4. Click **Approve** — confirm the row disappears.
5. Trigger another request; without refreshing, confirm it appears in real time (subscription check).
6. Click **Deny** — confirm the row disappears and the capability is not granted.

### H5. Memory page

1. Navigate to **Memory**.
2. Click **+ Remember**; fill in Key, Text, and optionally Scope. Click **Remember**.
3. Confirm the new record appears in the table.
4. Type a search term in the search box — confirm the list is filtered (server-side query).
5. Click **Share** on a private memory — confirm the scope chip changes to "shared".
6. Click **Privatize** on a shared memory — confirm the scope chip reverts.
7. Click **Forget** — confirm the row disappears.

### H6. Scheduler page

1. Navigate to **Scheduler**.
2. Confirm any existing jobs appear in the table.
3. Click **▼** on a job — confirm the triggers sub-table expands.
4. Test **Pause**, **Resume**, **Run Now**, **Cancel**, and **Delete** actions on a job.
5. Click **Refresh** — confirm the list updates.

### H7. Event Log page

1. Navigate to **Event Log**.
2. Confirm the "Live" chip is green and a WebSocket connection is open.
3. Perform an action (send a message, approve a request) — confirm the corresponding event appears.
4. Click the expand **▼** on an event with a payload — confirm the JSON payload renders.
5. Click **Disconnect** — confirm the chip changes to "Disconnected" and no further events arrive.

### H8. Users page

1. Navigate to **Users**.
2. Confirm the list of users is displayed with correct columns.

### H9. Settings page

1. Navigate to **Settings**.
2. Change the Formality dropdown to **Professional**.
3. Edit the System Prompt field.
4. Click **Save** — confirm "Saved!" appears.
5. Change a field — confirm "Saved!" disappears.

### H10. GraphiQL (developer tool)

1. Navigate to `http://localhost:8080/api/graphiql`.
2. Confirm GraphiQL 3.x loads without console errors.
3. Run an introspection query (`{ __schema { queryType { name } } }`) — confirm it returns data.
4. Confirm the JWT from localStorage is sent in the Authorization header.

### H11. Path traversal guard

1. In the browser address bar, try `http://localhost:8080/../../etc/passwd`.
2. Confirm the response is `index.html` (SPA fallback), not a system file.

### H12. Cache-Control headers

1. With browser dev tools open (Network tab), load any `.js` asset.
2. Confirm the response has `Cache-Control: max-age=31536000`.
3. Load `index.html` directly — confirm the response has `Cache-Control: no-cache`.

