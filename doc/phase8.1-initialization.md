# Phase 8.1 — First-Run Initialization

**Branch:** `phase-8.1/first-run-init`
**Depends on:** Phase 8 (agent session runtime complete)
**Goal:** A freshly installed Jorlan server and a freshly installed Jorlan shell can reach a fully operational state
without any out-of-band file editing, DBA scripting, or manual SQL.

---

## Problem Statement

After Phase 8 the system requires significant manual setup before first use:

1. The shell reads `~/.jorlan/jorlan.json` or falls back to defaults in `application.conf`. There is no wizard that
   prompts for the server address when the file is absent.
2. The server requires a populated MariaDB schema and at least one admin user to function. Both must be provisioned
   externally before starting.
3. There is no in-process mechanism for the server to detect it is uninitialized and enter a guarded setup mode
   rather than serving a broken half-initialized state.

---

## Architectural Principle: `server_settings` as a JSON Key-Value Store

All server-level configuration lives in a single table:

```sql
CREATE TABLE `server_settings` (
  `key`   VARCHAR(64) NOT NULL,
  `value` JSON        NOT NULL,
  PRIMARY KEY (`key`)
);
```

The `value` column is always valid JSON — scalar, array, or object — so settings can evolve in shape without schema
migrations. A `ServerSettingsRepository` service (`get(key): UIO[Option[Json]]`, `set(key, value: Json): UIO[Unit]`)
is the only access point. Initial seed values from V017:

| key          | value       | meaning                              |
|--------------|-------------|--------------------------------------|
| `initialized`| `false`     | JSON boolean; flipped on first setup |
| `serverName` | `"Jorlan"`  | JSON string; set during init wizard  |

Phase 8.2 adds `personality` (JSON object) to the same table. No service reads `server_settings` with raw SQL.

---

## What "Uninitialized" Means

### Server

The server is uninitialized when `server_settings` contains `initialized = false` (JSON boolean).

This is an **explicit, unambiguous flag** — not derived from any other state. In particular:

- A server with no admin user but `initialized = true` is considered initialized (the admin may have been deleted;
  that is an operational problem, not a first-run problem).
- A server with users in the database but `initialized = false` is in setup mode regardless.

**How the flag gets there:** Flyway migration V017 creates the table and seeds `initialized = false` and
`serverName = "Jorlan"`. After the setup wizard completes, `initialized` is flipped to `true` and `serverName` is
updated in the same transaction that creates the admin user. Resetting to `false` requires direct database access,
which is intentional — it prevents accidental re-initialization.

**Why the database, not a config file:** The server process needs DB connectivity to function regardless. Writing
back to `application.conf` or a filesystem flag from within the running process is fragile and platform-dependent.
The database is the canonical source of truth for all server state; the initialization flag belongs there too.

### Shell

The shell is uninitialized when `~/.jorlan/jorlan-shell.json` does not exist or does not contain a valid `serverUrl`.

Note the file name change: `jorlan.json` → `jorlan-shell.json`. The location is configurable via
`--config /path/to/file.json` (CLI flag) or `JORLAN_SHELL_CONFIG` environment variable. Running two shell instances
against different servers is achieved by pointing each at a different config file.

---

## Key Insight: Flyway Already Handles an Empty Database

Flyway's `migrate()` on a completely empty (but existing) MariaDB database applies all migrations from scratch. The
server today already runs `FlywayMigration.runMigrations` at startup via `EnvironmentBuilder`. Therefore:

- **Schema creation is already solved** — if the database exists and the application user has the right grants,
  Flyway builds the full schema including the new `server_settings` table.
- **The only new server work** is checking the flag, entering setup mode when false, and flipping it to true after
  the wizard completes.

The `init-db.sh` script (Phase 8b) handles the DBA step (`CREATE DATABASE` + `GRANT`). Phase 8.1 assumes that
script has been run (or the equivalent), so the database exists and is accessible.

---

## Security Model

An uninitialized server that accepts setup data from any connecting client is a significant attack surface.

**Mitigation: one-time init token.**

- On startup, if `initialized = 'false'`, the server generates a cryptographically random 32-hex-character token.
- The token is printed to stdout **once** and **never written to any file**:
  ```
  ╔══════════════════════════════════════════════════════╗
  ║  JORLAN SETUP TOKEN  (this process lifetime only)    ║
  ║  a3f8c2d1e9b74f0a6c2e5d8b1f3a7c9e2d4b6f8a0c2e4d6  ║
  ╚══════════════════════════════════════════════════════╝
  ```
- The `/api/init` endpoint requires this token in the request body.
- Once initialization succeeds the token is discarded from memory; subsequent calls to `/api/init` return 403.

This requires physical or SSH access to the server machine to read the token — the same access level needed to read
the database credentials — which is the correct security boundary.

---

## HTTP API

### `GET /api/status`

Always unauthenticated. Returns a JSON object describing the current server state. Safe to call before login or
before initialization.

```json
{
  "initialized": false,
  "version":     "1.2.3",
  "serverName":  "Jorlan",
  "uptimeMs":    12345
}
```

This endpoint remains live and returns the same shape at all times (including post-initialization, where
`initialized` becomes `true`). It replaces the need for a separate `/health` check.

### `POST /api/init`

Only available when `initialized = false`. Returns 403 after the first successful call.

**Request body:**
```json
{
  "token":         "a3f8c2...",
  "serverName":    "My Jorlan",
  "adminEmail":    "alice@example.com",
  "adminName":     "Alice",
  "adminPassword": "..."
}
```

**Responses:**
- `200 OK`: `{ "success": true }` — initialization complete; full API now available
- `400 Bad Request`: `{ "error": "validation message" }` — e.g. bad email format, password too short (≥ 12 chars)
- `403 Forbidden`: `{ "error": "already initialized" | "invalid token" }`

All other existing GraphQL and REST endpoints return HTTP 503 with `{"error": "server not initialized"}` while the
flag is false.

---

## Server Initialization Flow

```
Server starts
  │
  ├─ Connect to DB (fail fast with clear error if unreachable)
  ├─ FlywayMigration.runMigrations
  │    └─ V017 creates server_settings, inserts ('initialized', 'false') if table didn't exist
  │
  └─ SELECT value FROM server_settings WHERE key = 'initialized'
      │
      ├─ 'true'  → mount full application layer, normal startup
      │
      └─ 'false' → SETUP MODE
           │
           ├─ Generate init token, print to stdout (see box above)
           ├─ Mount minimal HTTP:
           │    GET  /api/status  → { initialized: false, ... }
           │    POST /api/init    → setup handler
           │    *    /*           → 503 not initialized
           └─ Wait for POST /api/init
                │
                ├─ Validate token (fail: 403 invalid token)
                ├─ Validate inputs (fail: 400 with message)
                ├─ BEGIN TRANSACTION
                │    INSERT INTO user (adminEmail, adminName, hashedPassword, ...)
                │    UPDATE server_settings SET value=true  WHERE key='initialized'
                │    UPDATE server_settings SET value='"My Jorlan"' WHERE key='serverName'
                ├─ COMMIT
                ├─ Discard token from memory
                └─ Hot-swap HTTP handler: replace 503 layer with full application layer
                   (no restart required — uses Ref[HttpApp] swapped atomically)
```

---

## Shell First-Run Flow

```
Shell starts
  │
  ├─ Load config file
  │    ├─ JORLAN_SHELL_CONFIG env var or --config flag → use that path
  │    └─ Default: ~/.jorlan/jorlan-shell.json
  │         ├─ File exists and has serverUrl → skip wizard
  │         └─ File absent or serverUrl missing → FIRST-RUN WIZARD
  │
  └─ FIRST-RUN WIZARD
       │
       ├─ Display: "Jorlan Shell — First Run Setup"
       ├─ Prompt: Server URL  [default: http://localhost:8080]
       ├─ GET /api/status
       │    ├─ Connection error → display error, offer retry
       │    │
       │    ├─ { "initialized": true }
       │    │    └─ Server already set up; prompt email + password → normal login flow
       │    │
       │    └─ { "initialized": false }
       │         ├─ Display:
       │         │    "Server needs setup. Find the JORLAN SETUP TOKEN in the server's"
       │         │    "console output, then enter it here."
       │         ├─ Prompt: Setup token
       │         ├─ Prompt: Server name  [default: Jorlan]
       │         ├─ Prompt: Admin display name
       │         ├─ Prompt: Admin email
       │         ├─ Prompt: Admin password  (hidden; Phase 11 adds masking)
       │         ├─ Prompt: Confirm password
       │         └─ POST /api/init { token, adminName, adminEmail, adminPassword }
       │              ├─ 400/403 → display server error message, retry from token prompt
       │              └─ 200 success
       │                   ├─ Save config to jorlan-shell.json (or --config path)
       │                   └─ Proceed to normal login with just-created credentials
       │
       └─ Normal login → command loop
```

---

## Config File Changes

### Shell: `~/.jorlan/jorlan-shell.json` (renamed from `jorlan.json`)

```json
{
  "jorlan": {
    "shell": {
      "serverUrl": "http://myserver:8080",
      "email":     "alice@example.com",
      "password":  "..."
    }
  }
}
```

`ShellConfig.layer` checks, in order:
1. `JORLAN_SHELL_CONFIG` environment variable (absolute path)
2. `--config` CLI flag (absolute path)
3. `~/.jorlan/jorlan-shell.json`
4. `~/.jorlan/jorlan.json` (backwards compatibility — read only, never written)
5. `application.conf` defaults

On first-run write, the resolved path from step 1, 2, or 3 is used. Step 4 is never written.

### Server: New Flyway migration V017

```sql
CREATE TABLE `server_settings` (
  `key`   VARCHAR(64) NOT NULL,
  `value` JSON        NOT NULL,
  PRIMARY KEY (`key`)
);
INSERT INTO `server_settings` (`key`, `value`) VALUES ('initialized', 'false');
INSERT INTO `server_settings` (`key`, `value`) VALUES ('serverName',  '"Jorlan"');
```

The `value` column is always valid JSON. Simple scalars (`false`, `"Jorlan"`) are valid JSON; complex settings
(Phase 8.2 personality) store a JSON object. No changes to `application.conf`.

---

## Implementation Tasks

### Server

| ID       | Description |
|----------|-------------|
| P8.1-S1  | Flyway V017: `server_settings` table (`key VARCHAR(64) PK, value JSON NOT NULL`); seed `initialized = false` and `serverName = "Jorlan"`. |
| P8.1-S1a | `ServerSettingsRepository` trait: `get(key): UIO[Option[Json]]`, `set(key, Json): UIO[Unit]`; Quill-backed impl; shared by all Phase 8.x services. |
| P8.1-S2  | `InitService` trait: `isInitialized: UIO[Boolean]`, `complete(token, serverName, email, name, password): IO[JorlanError, Unit]`. `InitServiceImpl` uses `ServerSettingsRepository` and verifies the token. |
| P8.1-S3  | `InitTokenStore`: `Ref[Option[String]]` that generates a random 32-hex token on construction when uninitialized; invalidated (set to `None`) on successful `complete`. |
| P8.1-S4  | `StatusRoutes`: `GET /api/status` — always available, returns `initialized`, version, server name, uptime. |
| P8.1-S5  | `InitRoutes`: `POST /api/init` — delegates to `InitService`, returns 403 when already initialized or token invalid. |
| P8.1-S6  | `SetupModeHttpApp`: an `HttpApp` that serves `StatusRoutes` + `InitRoutes` and returns 503 for everything else. |
| P8.1-S7  | `EnvironmentBuilder` update: after Flyway, check `isInitialized`; if false, build setup-mode app and install into `Ref[HttpApp]`; `InitServiceImpl.complete` atomically swaps it for the full app. |
| P8.1-S8  | Unit tests for `InitService`: (a) invalid token returns error, (b) duplicate init returns error, (c) successful init flips DB flag and invalidates token. |
| P8.1-S9  | Integration test (Testcontainers): fresh DB → server enters setup mode → POST `/api/init` → GET `/api/status` returns `initialized: true` → login with new credentials succeeds. |

### Shell

| ID       | Description |
|----------|-------------|
| P8.1-C1  | `ShellConfig.layer` updated: load order described above; add `JORLAN_SHELL_CONFIG` env var and `--config` flag support. |
| P8.1-C2  | `ShellConfig` writer: write resolved config (serverUrl, email, password) to the determined path after successful first-run. |
| P8.1-C3  | `InitClient`: `checkStatus(serverUrl): IO[String, ServerStatus]`, `complete(serverUrl, token, email, name, password): IO[String, Unit]`. |
| P8.1-C4  | `FirstRunWizard`: ZIO effect driving the TUI prompts described in the flow. Runs before `connectWithRetry` when the config file is absent/incomplete. |
| P8.1-C5  | `JorlanShell.bootstrap` updated: invoke `FirstRunWizard` when config has no `serverUrl`. |
| P8.1-C6  | Unit tests for `FirstRunWizard` (via `FakeScreen`): (a) server already initialized path, (b) setup path with bad token then success, (c) connection error retry, (d) password mismatch retry. |

---

## Deferred

| Item | Phase |
|------|-------|
| Password masking in TUI prompts | Phase 11 |
| Multi-server profiles | Phase 9+ |
| OAuth/SSO first-run | Phase 12 |
| Packaging / `init-db.sh` | Phase 8b |
| Re-initialization (reset) via UI | Out of scope; require direct DB access |
