# Jorlan — Secure Agent Runtime & Orchestration Platform

> A production-grade runtime for AI agents: capability-based security, durable scheduling, persistent memory, full observability, and strongly typed interfaces.

---

## Overview

Jorlan is a secure, observable, extensible, and model-agnostic runtime platform for AI agents and intelligent workflows.

Most current agent frameworks prioritize demonstrations of autonomy over operational reliability, security, and maintainability. Jorlan takes a fundamentally different approach: agents are controlled runtime processes operating inside a secure orchestration environment with explicit permissions, durable state, structured execution tracing, and strongly typed interfaces.

The result is a platform that supports both autonomous AI-driven execution and human-supervised workflow automation — while remaining inspectable, auditable, replayable, and safe.

---

## Core Principles

| Principle | Description |
|---|---|
| **Safety first** | Deny-by-default capability model — no permissions unless explicitly granted |
| **Observability** | Every significant action is durably logged: model calls, tool invocations, approvals, state transitions |
| **Typed contracts** | Skills and connectors have schema-validated JSON manifests and explicit capability declarations |
| **Durable execution** | Agents survive restarts, retry automatically, and maintain persistent state |
| **Model agnosticism** | Pluggable LLM backends — local (Ollama) and cloud providers |
| **Human-in-the-loop** | Configurable supervision levels from fully autonomous to approval-gated |

---

## Technology Stack

- **Language**: Scala 3.8.4 with `-Yexplicit-nulls`, `-no-indent`, `-old-syntax`, `-Werror`
- **Effects**: ZIO 2.x throughout
- **Database**: MariaDB via Quill (`quill-jdbc-zio`) + Flyway migrations
- **API**: Caliban (GraphQL) as the primary external API
- **HTTP**: zio-http
- **Serialization**: zio-json
- **LLM**: LangChain4j (Ollama streaming) via the `ai` module
- **Scheduling**: cron4s for cron expression parsing
- **Testing**: zio-test + Testcontainers (MariaDB)
- **Connection pool**: HikariCP
- **Web frontend**: Scala.js + React 19 + MUI v9 (via ScalablyTyped bindings in `stLib`)

---

## Module Structure

```
jorlan/
├── model/        Domain types, repository traits, error hierarchy, configuration models
├── db/           Quill repository implementations, Flyway migrations, DB configuration
├── ai/           LLM client integrations (LangChain4j + Ollama)
├── server/       Caliban GraphQL API, HTTP server, agent runtime, scheduler, memory system
├── shell/        Interactive TUI shell — connects to the server over GraphQL + WebSocket
├── web/          Scala.js SPA — React 19 + MUI v9 web frontend (served directly by the server)
├── stLib/        ScalablyTyped bindings sub-project for React 19 + MUI v9 + Emotion
├── analytics/    Analytics subsystem (future)
├── integration/  Integration tests (Testcontainers)
└── util/         Shared utilities
```

The domain layer (`model`) has no dependency on DB or connector specifics. All persistence details live in `db`.

---

## Getting Started

### Prerequisites

- JDK 21+
- SBT 1.x
- MariaDB 10.6+ (or use the Testcontainers integration test setup)
- [Ollama](https://ollama.com/) running locally (for live LLM streaming; optional — server starts without it)

### Database bootstrap

Run the provided script once before first server start (requires temporary MySQL root credentials):

```bash
bash server/src/main/scripts/init-db.sh
```

This creates the `jorlan` database and application user. All schema migrations run automatically on server startup via Flyway.

Alternatively, create the DB manually:

```sql
CREATE DATABASE jorlan CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'jorlan'@'localhost' IDENTIFIED BY 'jorlan';
GRANT ALL PRIVILEGES ON jorlan.* TO 'jorlan'@'localhost';
```

### Configuration

Set required environment variables (see `.env.example` for the full list):

```bash
export JORLAN_AUTH_SECRET_KEY="$(openssl rand -hex 32)"
export JORLAN_DB_URL="jdbc:mariadb://localhost:3306/jorlan"
export JORLAN_DB_USER="jorlan"
export JORLAN_DB_PASSWORD="jorlan"
```

Optional — enable Ollama:

```bash
export JORLAN_AI_OLLAMA_BASE_URL="http://localhost:11434"
export JORLAN_AI_OLLAMA_MODEL="llama3.2:3b"
```

All env vars are documented in `.env.example`. The server validates required vars on startup and exits with a human-readable error if any are missing.

---

## Running the Server

```bash
# Compile
sbt --error server/compile

# Start the server
sbt "server/runMain jorlan.Jorlan"
```

### First-run initialization

On first start the server enters **setup mode** and prints a one-time setup token to the console:

```
╔══════════════════════════════════════════════════╗
║   JORLAN SETUP TOKEN: a3f8b2c1...               ║
║   Use this token to complete server setup.       ║
╚══════════════════════════════════════════════════╝
```

The server returns **503** for all non-status/init endpoints until setup completes. Use the shell wizard (below) or POST directly to `POST /api/init` with `{ token, serverName, adminEmail, adminName, adminPassword }`.

### Server endpoints

| Endpoint | Description |
|---|---|
| `GET  /api/status` | Server status — always available (unauthenticated) |
| `POST /api/init` | First-run initialization (token-gated; 403 after first use) |
| `POST /api/jorlan` | GraphQL endpoint |
| `GET  /api/jorlan/ws` | GraphQL over WebSocket (subscriptions) |
| `GET  /api/jorlan/graphiql` | GraphiQL IDE (browser) |
| `GET  /health` | Liveness probe |
| `GET  /` | Web frontend SPA (served from `jorlan.web.root`, defaults to `/opt/jorlan/www`) |

---

## Running the Shell

The interactive TUI shell connects to a running Jorlan server via GraphQL.

```bash
# Start the shell (requires a running server)
sbt "shell/runMain jorlan.shell.JorlanShell"

# Or with explicit server URL and credentials
sbt "shell/runMain jorlan.shell.JorlanShell --server-url http://localhost:8080 --email admin@example.com --password secret"
```

### First-run wizard

When no config file exists, the shell launches an interactive first-run wizard that:

1. Prompts for the server URL (defaults to `http://localhost:8080`)
2. Checks the server status
3. If the server is uninitialized, prompts for the setup token and admin credentials, then completes initialization via `POST /api/init`
4. If already initialized, prompts for login credentials
5. Saves the config to `~/.jorlan/jorlan-shell.json`

### Shell configuration

Config is stored in `~/.jorlan/jorlan-shell.json`:

```json
{
  "serverUrl": "http://localhost:8080",
  "email": "admin@example.com",
  "password": "your-password"
}
```

Config search order: `JORLAN_SHELL_CONFIG` env var → `--config` flag → `~/.jorlan/jorlan-shell.json` → `~/.jorlan/jorlan.json` (legacy, read-only) → `application.conf` defaults.

### Shell commands

| Command | Description |
|---|---|
| `/new [model]` | Start a new agent session (optionally specify model, e.g. `llama3.2:3b`) |
| `/status` | Check server connectivity |
| `/whoami` | Show the authenticated user |
| `/model` | Show the active session and model |
| `/trace [level]` | Set log level: `none \| error \| warning \| info \| debug` |
| `/personality` | Show server personality; `/personality set <field> <value>` to update (admin) |
| `/capabilities` | List your current capability grants (from server) |
| `/memory list [scope]` | List memory records (`User \| Shared \| Workspace \| Private`) |
| `/memory search <text>` | Search memory records by text |
| `/memory remember <key> <text>` | Store a new memory record |
| `/memory forget <id>` | Delete a memory record by id |
| `/agents list` | List active agent sessions |
| `/agents stop <id>` | Terminate an agent session |
| `/approvals list` | List pending approval requests |
| `/approvals approve <id>` | Approve a pending request |
| `/approvals deny <id>` | Deny a pending request |
| `/help` | Show help summary |
| `/commands` | List all available commands |
| `/quit` or `/exit` | Exit the shell |
| _(plain text)_ | Send a message to the active agent session |

Key bindings: **Enter** submit · **Backspace** delete · **PgUp/PgDn** scroll · **Ctrl-C** quit

---

## Web Frontend

The `web` module is a Scala.js single-page application that connects to the Jorlan server via the same Caliban GraphQL API used by the shell. It is served directly by the server — no nginx or separate web server required.

### Pages

| Page | Description |
|---|---|
| **Chat** | Start sessions, send messages, stream responses in real time |
| **Sessions** | Browse active/past sessions, create or terminate |
| **Approvals** | Review and decide pending capability approval requests (live badge in nav) |
| **Memory** | Search, remember, forget, and classify memory records |
| **Scheduler** | Browse jobs and triggers, pause/resume/cancel/run-now/delete |
| **Event Log** | Live-tail the event log via WebSocket subscription |
| **Skills** | Skill registry browser (stub — awaiting `listSkillVersions` GraphQL query) |
| **Users** | User, role, and permission management (admin) |
| **Settings** | Server personality editor, model selector |

### Building the web frontend

```bash
# Build the optimised bundle (outputs to dist/)
bash scripts/build-web.sh

# Build a fast (development) bundle (outputs to debugDist/)
sbtn "web/debugDist"
```

The `stLib` ScalablyTyped sub-project must be published once before the web module can compile. Run this manually from the repo root when `stLib/` sources change:

```bash
cd stLib && sbt publishLocal
```

For local development, set `jorlan.web.root = "debugDist"` in `server/src/main/resources/application.conf` so the server serves the fast-opt bundle without needing a full production build.

---

## Build & Test

```bash
# Compile all modules
sbt --error compile

# Run all tests (unit + server + shell + web)
sbt --error test

# Run integration tests (requires Docker for Testcontainers)
sbt --error integration/test

# Format all sources
sbt scalafmtAll

# Build the web frontend
bash scripts/build-web.sh

# Regenerate GraphQL schema (after API changes)
bash scripts/capture-schema.sh

# Regenerate Caliban shell client (after schema changes)
bash scripts/gen-client.sh
```

Test counts (as of Phase 15): **843 tests** across `ai`, `server`, `shell`, `web`, and `integration` modules — all passing without a running server or Ollama.

---

## Architecture Highlights

### Agent Session Runtime

The core streaming path:

```
Shell /new → createSession mutation → AgentSessionManager.createSession
                                    → SessionHub.getOrCreate(sessionId)
                                    ← AgentSession (sessionId shown in mode bar)

Shell <text> → submitMessage mutation → AgentRunner.processMessage
                                       → ModelGateway.streamedResponse (Ollama/LangChain4j)
                                       → ZStream[String] token chunks
                                       → SessionHub.publish(ResponseChunk)
                                       → agentResponseStream subscription
                                       → WebSocket frames → shell TUI
```

`FakeModelGateway` is used in all unit/integration tests — no Ollama required for CI.

### Memory System (Phase 9)

Three-layer memory architecture:

1. **Conversation history** — `Message` rows per session, loaded into `MessageWindowChatMemory` before each model call
2. **Episodic memory** — facts summarized at session end (via `CheckpointSummarizer`) and stored in `MemoryRecord` with scope-based access control (`User`, `Private`, `Shared`, `Workspace`)
3. **Context injection** — relevant memory records retrieved and prepended to the system prompt before each model call

Memory is classified by `MemoryClassifier` (PII → Private, share language → Shared, default → User) and governed by `MemoryAccessPolicy`.

### Durable Scheduler (Phase 10)

The `TriggerEngine` runs as a daemon fiber and drives scheduled jobs:

- **Trigger types**: `Cron` (cron4s expressions), `Interval` (ISO 8601 duration), `OneShot`, `Event`
- **Missed-run policies**: `Skip`, `RunOnce`, `RunAllMissed`
- **Retry policies**: `Fixed` or `Exponential` backoff with configurable max retries
- **Distributed safety**: DB-level optimistic locking via `claimJob` UPDATE with lease TTL (prevents duplicate execution)
- **Execution**: claimed job → `AgentSessionManager.createSession` → `AgentRunner.processMessage` → result stored in `resultJson`

Jobs are managed via GraphQL mutations (`createJob`, `addTrigger`, `pauseJob`, `resumeJob`, `cancelJob`, `triggerNow`) gated on the `scheduler.manage` capability.

### Capability-Based Security

Permissions are scoped to specific resources, actions, and time windows. High-risk operations require explicit grants that are auditable, time-limited, and revocable. The evaluation chain:

```
ExplicitDeny → ResourcePermission → RolePermission → CapabilityGrant → ConnectorPolicy → SkillPolicy → DefaultDeny
```

### Append-Only Event Log

Every significant runtime action is recorded in a durable event log. Key event types include: `SessionCreated`, `UserMessageReceived`, `ModelCallStarted`, `ModelCallCompleted`, `AgentResponseCompleted`, `MemoryWritten`, `MemoryCheckpointed`, `SchedulerJobStarted`, `SchedulerJobCompleted`, `CapabilityAllowed`, `CapabilityDenied`.

### Repository Layer

All persistence goes through typed repository traits in `model`. The `db` module provides Quill/MariaDB implementations bound to `RepositoryTask[A] = IO[RepositoryError, A]`. Flyway migrations (V001–V022) are run automatically on startup.

---

## Development Status

| Phase | Description | Status |
|---|---|---|
| 0 | Project foundation, module structure, DB connection, startup/shutdown | ✅ Complete |
| 1 | Core domain model — all types, JSON codecs | ✅ Complete |
| 2 | Persistence — Flyway migrations, Quill repositories, integration tests | ✅ Complete |
| 3 | Event logging service, correlation IDs | ✅ Complete |
| 4 | User, identity, and role management | ✅ Complete |
| 5 | Capability / permission kernel, approval policy engine | ✅ Complete |
| 6 | GraphQL API skeleton — queries, mutations, subscriptions, JWT auth | ✅ Complete |
| 7 | Shell interface — Lanterna TUI, auth, command handling, REPL | ✅ Complete |
| 8 | Agent session runtime + Model Gateway — streaming LLM responses | ✅ Complete |
| 8.1 | First-run initialization — server wizard, shell wizard, one-time token | ✅ Complete |
| 8.2 | Database bootstrap script (`init-db.sh`) | ✅ Complete |
| 8.3 | Server personality and system prompt injection | ✅ Complete |
| 8.4 | AI module testing on CI with `FakeModelGateway` | ✅ Complete |
| **9** | **Memory system — checkpointing, summarization, access policy, context injection** | **✅ Complete** |
| **10** | **Durable scheduler — cron/interval triggers, DB locking, retry/backoff** | **✅ Complete** |
| **11** | **Telegram connector** | **✅ Complete** |
| **12** | **Built-in skills — ReAct loop, SkillRegistry, 8 skills** | **✅ Complete** |
| 13 | Email and Calendar skills | Planned |
| 14 | Orchestrator integration | Planned |
| **15** | **Web frontend (Scala.js + React 19 + MUI v9)** | **✅ Complete** |
| 16 | Additional features (shell autocomplete, etc.) | Planned |
| 17 | Advanced features — declarative skills, MCP adapter, vector memory | Planned |
| 18 | Installer and distribution (.deb, macOS tarball) | Planned |

See `doc/development_roadmap.md` for the detailed task breakdown per phase.

---

## Documentation

| Document | Description |
|---|---|
| `doc/ExecutiveSummary.md` | Goals and philosophy |
| `doc/SoftwareRequirementsSpecification.md` | Full requirements |
| `doc/SoftwareDesignDocument.md` | Architecture and implementation guidance |
| `doc/orchestrator_integration_addendum.md` | GraphQL orchestrator API design |
| `doc/00_system_overview.md` – `doc/13_artifacts_and_effects.md` | Subsystem diagrams |
| `doc/development_roadmap.md` | Phase-by-phase task list with checkboxes |
| `doc/mini-designs/` | Per-feature design documents (phase 9, 10, etc.) |
| `.env.example` | All supported environment variables with descriptions |

---

## License

Copyright © 2026 Roberto Leibman. All rights reserved. See license header in source files.
