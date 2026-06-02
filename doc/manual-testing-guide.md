# Jorlan Manual Testing Guide

> Last updated: 2026-06-01 (Phase 8.3 — server personality)
>
> This document is maintained alongside the codebase. Run the `scala-doc-auditor` agent after
> significant feature additions to keep this guide in sync.

---

## Prerequisites

- MariaDB running and the Jorlan database created (see `db/init-db.sh`)
- Ollama running with a model loaded (e.g. `ollama run llama3`)
- Environment variables set (see `.env.example`): `JORLAN_AUTH_SECRET_KEY` at minimum
- Server jar or `sbt server/run`
- Shell jar (`shell/target/…/jorlan-shell-assembly-…jar`) or `sbt shell/run`

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
3. After a moment the LLM response tokens stream in and appear token-by-token.
4. When the response finishes, the prompt is ready for the next message.

**Expected:** LLM tokens appear in the shell. No "Streaming error" or "Failed" messages.

### A3. Graceful quit

1. Type `/quit` (or press **Ctrl-C**).
2. Shell prints a goodbye message and exits cleanly within ~2 seconds.

**Expected:** Process exits. No hung JVM.

---

## Section B — Full Test

Covers all implemented functionality as of Phase 8.3.

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

### B2. Shell commands

| Command     | Expected output                                   |
|-------------|---------------------------------------------------|
| `/help`     | Lists all commands with short descriptions        |
| `/about`    | Shows "Jorlan Shell" and version info             |
| `/commands` | Lists all command names                           |
| `/status`   | Shows server URL and version                      |
| `/whoami`   | Shows the logged-in user's display name and email |
| `/quit`     | Goodbye message; process exits                    |
| `/exit`     | Same as `/quit`                                   |
| Ctrl-C      | Process exits cleanly (no hung threads)           |

### B3. Session management

| Step | Action                                        | Expected                                                  |
|------|-----------------------------------------------|-----------------------------------------------------------|
| B3.1 | After login the mode bar shows `[session: N]` | Auto-created or resumed session                           |
| B3.2 | `/new`                                        | Creates a new session; mode bar updates to new session ID |
| B3.3 | `/new some-model-id`                          | Creates session with the specified model ID               |
| B3.4 | Type `/model`                                 | Shows active session ID                                   |
| B3.5 | Restart shell; reconnect                      | Existing Active session is resumed (not re-created)       |

### B4. LLM messaging

| Step | Action                                        | Expected                                              |
|------|-----------------------------------------------|-------------------------------------------------------|
| B4.1 | Type a message                                | Message echoed; LLM tokens stream back token-by-token |
| B4.2 | After LLM finishes                            | Prompt is ready immediately                           |
| B4.3 | Check server DEBUG logs                       | `incoming message` and `LLM token` log lines visible  |
| B4.4 | Ask the LLM something while Ollama is offline | `Agent error: …` displayed; shell remains usable      |

### B5. Personality

| Step | Action                                    | Expected                                                 |
|------|-------------------------------------------|----------------------------------------------------------|
| B5.1 | `/personality`                            | Shows current server personality (name, formality, etc.) |
| B5.2 | `/personality set formality Casual`       | Updates formality; confirmed in next `/personality` call |
| B5.3 | `/personality set name Jorlan Assistant`  | Updates name                                             |
| B5.4 | `/personality set name Multi Word Name`   | Updates name to multi-word value                         |
| B5.5 | Send a message after changing personality | LLM response reflects new system prompt behavior         |

### B6. Tracing / log levels

| Step | Action            | Expected                                   |
|------|-------------------|--------------------------------------------|
| B6.1 | `/trace`          | Shows current trace level (default `info`) |
| B6.2 | `/trace debug`    | Sets level to `debug`                      |
| B6.3 | `/trace info`     | Sets level back to `info`                  |
| B6.4 | `/trace badlevel` | Error: invalid level                       |

### B7. GraphQL API (via shell or direct HTTP)

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

### B8. Security: capability enforcement

| Test                                                             | Expected                                                  |
|------------------------------------------------------------------|-----------------------------------------------------------|
| Access GraphQL mutation without auth token                       | `Not authenticated` error                                 |
| Admin user can call all mutations                                | No `Access denied` errors                                 |
| Freshly created non-admin user (no grants) calls `createSession` | `Access denied: no permission for 'agent.session.create'` |

### B9. Server lifecycle

| Step | Action                                 | Expected                                                          |
|------|----------------------------------------|-------------------------------------------------------------------|
| B9.1 | Start server on already-initialized DB | No setup token printed; full routes served immediately            |
| B9.2 | `GET /health`                          | HTTP 200                                                          |
| B9.3 | `GET /api/status`                      | JSON with `initialized: true`, correct `serverName` and `version` |
| B9.4 | Restart server mid-session             | Shell reconnects via heartbeat; existing session resumes          |

---

## Known Limitations (as of Phase 8.3)

- Token-per-invocation and session-scoped capability approvals are not yet exercisable from the shell (Phase 9+).
- Scheduler, memory, artifact, and connector subsystems are not yet implemented (Phase 9–13).
- The shell shows LLM tokens in plain text; no markdown rendering yet.
- Only one shell client per session is supported (Queue-based hub, no broadcast to multiple subscribers).
