# Jorlan Manual Testing Guide

> Last updated: 2026-06-02 (Phase 8.5 — session connection redesign, version check, conversation logging)
>
> This document is maintained alongside the codebase. Run the `scala-doc-auditor` agent after
> significant feature additions to keep this guide in sync.

---

## Prerequisites

- MariaDB running and the Jorlan database created (see `db/init-db.sh`)
- Ollama running with a model loaded (e.g. `ollama run llama3`) — required for LLM messaging tests only
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
8. The input prompt shows `❯` and the mode bar shows `[session: 1]`.

**Expected:** No error messages. Session ID is set automatically.

### A2. Send a message to the LLM

1. At the `❯` prompt, type any text (e.g. `Hello`) and press **Enter**.
2. The shell displays your message.
3. After a moment the LLM response tokens stream in and appear token-by-token **without opening a new WebSocket**.
4. When the response finishes, the prompt is ready for the next message.
5. Send a second message in the same session — verify (via server DEBUG logs) that **no new WebSocket connection** is opened; the same long-lived subscription is reused.

**Expected:** LLM tokens appear in the shell. No "Streaming error" or "Failed" messages. Server logs show only one `[WS] connecting to` line per session.

### A3. Graceful quit

1. Type `/quit` (or press **Ctrl-C**).
2. Shell prints a goodbye message and exits cleanly within ~2 seconds.

**Expected:** Process exits. No hung JVM. Subscription fiber is torn down cleanly.

---

## Section B — Full Test

Covers all implemented functionality as of Phase 8.5.

### B1. Server startup and init

| Step | Action                                              | Expected                                                                    |
|------|-----------------------------------------------------|-----------------------------------------------------------------------------|
| B1.1 | Start server on a **fresh database**                | `JORLAN SETUP TOKEN` banner printed to console                              |
| B1.2 | Start shell; press Enter to accept default URL      | `Localhost detected — token not required.`                                  |
| B1.3 | Enter empty server name (press Enter)               | Default name `Jorlan` used                                                  |
| B1.4 | Enter admin details with a password **< 12 chars**  | `{"error":"Password must be at least 12 characters"}` shown, wizard retries |
| B1.5 | Enter mismatched passwords                          | `Passwords do not match. Please try again.`                                 |
| B1.6 | Enter valid details; confirm password               | `Server initialized. Config saved to …` then 5 s wait, then connects        |
| B1.7 | Run shell again (server already initialized)        | Shell skips the wizard, prompts for email/password directly, connects       |
| B1.8 | On a **non-localhost** client, token field is shown | Token prompt is displayed (security check — do not skip)                    |
| B1.9 | Wizard interrupted with Ctrl-C                     | Shell exits cleanly; no partial config written                              |

### B2. Shell commands

| Command                          | Expected output                                                                      |
|----------------------------------|--------------------------------------------------------------------------------------|
| `/help`                          | Lists all commands with key-binding summary                                          |
| `/about`                         | Shows "Jorlan Shell" and version info                                                |
| `/commands`                      | Lists all command names                                                              |
| `/status`                        | Shows server URL; `✔ GraphQL API reachable` when server is up                        |
| `/whoami`                        | Shows the logged-in user's display name and email                                    |
| `/models`                        | Shows informational placeholder message                                              |
| `/quit`                          | Goodbye message; process exits                                                       |
| `/exit`                          | Same as `/quit`                                                                      |
| `/unknowncommand`                | `Unknown command: /unknowncommand — try /help` in red; shell continues               |
| Ctrl-C                           | Process exits cleanly (no hung threads)                                              |
| Ctrl-D                           | Same as Ctrl-C                                                                       |

### B3. Version check (Phase 8.5)

| Step | Client / Server versions                           | Expected                                                            |
|------|----------------------------------------------------|---------------------------------------------------------------------|
| C1   | Same version                                       | Connects normally; no version message                               |
| C2   | Client patch ≥ server patch (same major.minor)     | Connects normally                                                   |
| C3   | Client patch < server patch (same major.minor)     | `Fatal: Version incompatibility …` shown; shell exits               |
| C4   | Client minor ≠ server minor                        | `Fatal: Version incompatibility …` shown; shell exits               |
| C5   | Client major ≠ server major                        | `Fatal: Version incompatibility …` shown; shell exits               |
| C6   | Both versions non-semver; client buildTime ≥ server | Connects normally                                                   |
| C7   | Both versions non-semver; client buildTime < server | `Shell is older than the server …` shown; shell exits               |
| C8   | Server `/api/status` unreachable                   | Version check skipped; shell continues to login with server URL as display name |

### B4. TUI layout and display

| Step | Scenario                  | Expected                                                                        |
|------|---------------------------|---------------------------------------------------------------------------------|
| D1   | Status bar: connected      | Blue background; `● <server-name>  [<display-name>]  [<url>]`                  |
| D2   | Mode bar: active session   | `[session: N]  [model: default]` at bottom                                      |
| D3   | Terminal resize            | TUI redraws without flicker or garbled characters                               |
| D4   | Scroll with many messages  | PageUp/Down scrolls history 10 lines per press; Home/End jump to extremes       |
| D5   | Long line wrap             | Messages wider than terminal width wrap cleanly; no horizontal overflow         |
| D6   | Scroll buffer cap          | After 2000 messages, oldest are dropped; no OOM or crash                        |
| D7   | Streaming response         | Tokens append to a **single** `✦` line — not a new line per token               |

### B5. Input handling

| Step | Scenario                  | Expected                                                        |
|------|---------------------------|-----------------------------------------------------------------|
| E1   | Backspace                 | Removes last character from input field                         |
| E2   | Backspace on empty input  | No effect; no crash                                             |
| E3   | Enter on empty line       | No message sent; no error added to conversation                 |
| E4   | PageUp / PageDown         | Scrolls conversation history; does not modify input field       |
| E5   | Home key                  | Jumps scroll to oldest messages                                 |
| E6   | End key                   | Resets scroll to newest messages                                |
| E7   | Ctrl-C / Ctrl-D           | Shell exits cleanly via quit path; subscription fiber torn down |

### B6. Session management

| Step | Action                                        | Expected                                                        |
|------|-----------------------------------------------|-----------------------------------------------------------------|
| B3.1 | After login the mode bar shows `[session: N]` | Auto-created or resumed session                                 |
| B3.2 | `/new`                                        | Creates a new session; old subscription fiber interrupted first |
| B3.3 | `/new some-model-id`                          | Creates session with the specified model ID                     |
| B3.4 | `/new` when `/new` already ran                | No orphan fibers; each invocation replaces previous             |
| B3.5 | `/model`                                      | Shows active session ID                                         |
| B3.6 | Restart shell; reconnect                      | Existing Active session is resumed (not re-created)             |
| B3.7 | `/new` fails (server error)                   | Error shown; shell enters no-session state                      |

### B7. LLM messaging

| Step | Action                                        | Expected                                                      |
|------|-----------------------------------------------|---------------------------------------------------------------|
| B4.1 | Type a message                                | Message echoed; LLM tokens stream back token-by-token         |
| B4.2 | After LLM finishes                            | Prompt is ready immediately; no extra blank lines             |
| B4.3 | Second message, same session                  | Same WebSocket reused — no new `[WS] connecting to` in logs   |
| B4.4 | Long response (>100 tokens)                   | Full response received; no truncation; sentinel consumed       |
| B4.5 | Response with multi-line content              | Newlines in LLM output appear as real line breaks in TUI      |
| B4.6 | Ask the LLM something while Ollama is offline | `Agent error: …` displayed; shell remains usable             |
| B4.7 | Type before previous response finishes        | Stale sentinel is drained before new drain loop; response visible |

### B8. Personality

| Step | Action                                    | Expected                                                 |
|------|-------------------------------------------|----------------------------------------------------------|
| B5.1 | `/personality`                            | Shows current server personality (name, formality, etc.) |
| B5.2 | `/personality set formality Casual`       | Updates formality; confirmed in next `/personality` call |
| B5.3 | `/personality set name Jorlan Assistant`  | Updates name                                             |
| B5.4 | `/personality set name Multi Word Name`   | Updates name to multi-word value                         |
| B5.5 | `/personality set languages English, French` | Updates languages list; both items stored             |
| B5.6 | `/personality set expertise a,b,c`        | Expertise list set to three items                        |
| B5.7 | `/personality set badfield x`             | Error: unknown field; valid fields listed                |
| B5.8 | Send a message after changing personality | LLM response reflects new system prompt behavior         |

### B9. Tracing / log levels

| Step | Action            | Expected                                   |
|------|-------------------|--------------------------------------------|
| B6.1 | `/trace debug`    | Sets level to `debug`; debug output appears in logs |
| B6.2 | `/trace info`     | Sets level back to `info`                  |
| B6.3 | `/trace none`     | Logging suppressed                         |
| B6.4 | `/trace badlevel` | Error: invalid level; level unchanged      |

### B10. GraphQL API (via shell or direct HTTP)

These can be verified via `/status` output or by inspecting the database directly.

| Endpoint                              | Test                                                                        |
|---------------------------------------|-----------------------------------------------------------------------------|
| `users` query                         | Returns at least the seeded `server` user and the admin created during init |
| `user(value: N)`                      | Returns the user by ID; returns null for unknown ID                         |
| `createUser` mutation                 | Creates a new user; appears in subsequent `users` query                     |
| `updateUser` mutation                 | Updates display name / email                                                |
| `createRole` mutation                 | Creates a role                                                              |
| `assignRole` / `revokeRole` mutations | Assigns and removes role from user                                          |
| `grantPermission` mutation            | Grants a capability to a user                                               |
| `revokePermission` mutation           | Removes the permission                                                      |
| `permissions(userId)` query           | Returns permissions for a user                                              |
| `roles(userId)` query                 | Returns roles for a user                                                    |
| `createSession` mutation              | Creates a session; returned in `listSessions`                               |
| `listSessions` query                  | Returns active sessions for the current user                                |
| `submitMessage` mutation              | Triggers LLM processing; tokens appear via subscription                     |
| `serverPersonality` query             | Returns current personality                                                 |
| `updatePersonality` mutation          | Updates the server personality                                              |

### B11. Security: capability enforcement

| Test                                                             | Expected                                                  |
|------------------------------------------------------------------|-----------------------------------------------------------|
| Access GraphQL mutation without auth token                       | `Not authenticated` error                                 |
| Admin user can call all mutations                                | No `Access denied` errors                                 |
| Freshly created non-admin user (no grants) calls `createSession` | `Access denied: no permission for 'agent.session.create'` |

### B12. Server lifecycle

| Step  | Action                                 | Expected                                                          |
|-------|----------------------------------------|-------------------------------------------------------------------|
| B9.1  | Start server on already-initialized DB | No setup token printed; full routes served immediately            |
| B9.2  | `GET /health`                          | HTTP 200                                                          |
| B9.3  | `GET /api/status`                      | JSON with `initialized: true`, correct `serverName` and `version` |
| B9.4  | Restart server mid-session             | Shell reconnects via heartbeat; existing session resumes          |

---

## Section C — Connection Heartbeat & Reconnect (Phase 8.5)

These tests require the ability to stop and restart the server or partition the network.

| Step | Scenario                 | Expected                                                                       |
|------|--------------------------|--------------------------------------------------------------------------------|
| K1   | 30s idle, server healthy | Heartbeat ping sent every ~15 s; connection maintained; no error message       |
| K2   | Server stop and restart  | Shell shows "Lost connection" message; retries with exponential backoff        |
| K3   | Exponential backoff      | Retry intervals double: ~500ms, 1s, 2s, 4s…; shown in mode bar                |
| K4   | 4xx client error         | `isClientError` detected; retry loop stops; error message shown; no infinite loop |
| K5   | Network partition        | Shell shows disconnected state; recovers automatically on reconnect            |
| K6   | Reconnect success        | Mode bar updates to connected state; user can start a new session with `/new`  |

---

## Section D — Subscription Fiber Lifecycle (Phase 8.5)

Verify that exactly one WebSocket connection exists per session and that fiber cleanup is correct.

| Step | Scenario                          | Expected                                                                             |
|------|-----------------------------------|--------------------------------------------------------------------------------------|
| L1   | Send three messages, same session | Server DEBUG logs show exactly **one** `[WS] connecting to` line                    |
| L2   | `/new` while session active       | `[Shell] subscription stream ended for session=<old>` in logs; one new WS opened    |
| L3   | Server killed during streaming    | Error appears in TUI on next message; mode bar shows no-session state               |
| L4   | `/quit` during active streaming   | Subscription fiber interrupted; WS closed before JVM exits; no hung process         |
| L5   | Long-lived session (>1 hour idle) | No memory leak; fiber and queue stable; heartbeat keeps connection alive             |
| L6   | Stale sentinel cleared            | After response finishes, typing a second message does **not** produce a blank response |

---

## Section E — Error Display and Message Kinds

| Message Kind | Color / Prefix | Example trigger         |
|--------------|---------------|--------------------------|
| `Error`      | Red / `✗`     | `/unknowncommand`, streaming error |
| `System`     | Cyan / `⚙`    | `/help`, `/whoami` output          |
| `User`       | White / `❯`   | Typed message                      |
| `Server`     | Green / `✦`   | LLM streaming response             |
| `Raw`        | White (no prefix/timestamp) | Welcome banner, goodbye message |

---

## Section F — Cross-Cutting Verification

Apply these throughout all test sections:

| Check | Expected                                                                              |
|-------|---------------------------------------------------------------------------------------|
| CC1   | No raw Java stack traces visible to the end user in any scenario                      |
| CC2   | TUI redraws without visible flicker during LLM streaming (~30 fps)                    |
| CC3   | Scroll buffer capped at 2000 messages; oldest dropped without crash                   |
| CC4   | Commands are case-sensitive: `/Quit` → "Unknown command: /Quit"                       |
| CC5   | Server logs show one `[WS] connecting to` per `/new`; one `subscription stream ended` per teardown |
| CC6   | Conversation log files appear under `logs/conversations/session-<id>.log` after messaging |

---

## Known Limitations (as of Phase 8.5)

- Token-per-invocation and session-scoped capability approvals are not yet exercisable from the shell (Phase 9+).
- Scheduler, memory, artifact, and connector subsystems are not yet implemented (Phase 9–13).
- The shell shows LLM tokens in plain text; no markdown rendering yet.
- Multiple shell clients per session can subscribe (Queue-based hub supports it), but only the most recently connected subscriber reliably receives all tokens if the prior subscriber disconnects mid-response.
- The `/models` command is a placeholder that does not yet query the server model list.
- Password is currently stored in plaintext in the shell config for reconnect purposes; JWT token refresh is planned for Phase 9.
