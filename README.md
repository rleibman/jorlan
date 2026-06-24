# Jorlan — Secure Agent Runtime & Orchestration Platform

> A production-grade runtime for AI agents: capability-based security, durable scheduling, persistent memory, full observability, and strongly typed interfaces.

---

## Installation

> **Pre-built packages** are attached to every [GitHub Release](https://github.com/rleibman/jorlan/releases).

### Linux (Ubuntu / Debian)

```bash
# Download the latest .deb packages from GitHub Releases:
#   https://github.com/rleibman/jorlan/releases/latest
#   → jorlan-server_<version>_all.deb
#   → jorlan-shell_<version>_all.deb

# Install (requires Java 21+: sudo apt install default-jre-headless)
sudo dpkg -i jorlan-server_<version>_all.deb
sudo dpkg -i jorlan-shell_<version>_all.deb   # optional — CLI client

# Set up the database (requires MariaDB and root credentials)
sudo jorlan-init-db --root-password <root-pw> --app-password <app-pw>

# Edit the env file — at minimum set JORLAN_AUTH_SECRET_KEY
sudo nano /etc/jorlan/server.env

# Enable and start the server
sudo systemctl enable jorlan-server
sudo systemctl start jorlan-server

# Run the shell to complete first-run setup
jorlan
```

### macOS (Homebrew — recommended)

```bash
# Add the Jorlan tap
brew tap rleibman/jorlan

# Install server and shell
brew install jorlan          # server daemon
brew install jorlan-shell    # CLI client (optional)

# Start the server
brew services start jorlan   # as your user
# or:
sudo brew services start jorlan  # as a system daemon (starts at boot)

# Set up the database
jorlan-init-db --root-password <root-pw> --app-password <app-pw>

# Edit the env file
nano "$(brew --prefix)/etc/jorlan/server.env"

# Run the shell to complete first-run setup
jorlan
```

### macOS (manual tarball)

For non-Homebrew users: download `jorlan-server-<version>.tgz` and `jorlan-shell-<version>.tgz`
from the [latest release](https://github.com/rleibman/jorlan/releases/latest), then:

```bash
# Install server (requires Java 21+)
mkdir -p /tmp/jorlan-server-install
tar -xzf jorlan-server-<version>.tgz -C /tmp/jorlan-server-install --strip-components=1
sudo bash /tmp/jorlan-server-install/scripts/install-macos.sh "${PWD}/jorlan-server-<version>.tgz"
# Install shell
tar -xzf jorlan-shell-<version>.tgz -C ~/jorlan-shell --strip-components=1
echo 'export PATH="$HOME/jorlan-shell/bin:$PATH"' >> ~/.zshrc && source ~/.zshrc
```

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
- **Serialization**: zio-json (prefer `derives JsonCodec`)
- **LLM**: LangChain4j (Ollama streaming) via the `ai` module
- **Scheduling**: cron4s for cron expression parsing
- **Testing**: zio-test + Testcontainers (MariaDB)
- **Connection pool**: HikariCP
- **Web frontend**: Scala.js + React 19 + MUI v9 (via ScalablyTyped bindings in `stLib`)

---

## Module Structure

Skills and connectors are **cross-projects** (JVM + JS), allowing each to expose both a server-side implementation and a web configuration UI component from the same source tree.

```
jorlan/
├── model/               Domain types, repository traits, error hierarchy, configuration models (cross)
├── gqlClient/           Shared GraphQL client types and abstractions (cross)
├── skillApi/            Shared skill API contract — Skill, ConnectorSkill, ToolDescriptor, etc. (cross)
│
├── Skills (each is a cross-project: JVM implementation + JS configuration UI)
│   ├── calculatorSkill/     Basic arithmetic calculator
│   ├── lyrionSkill/         Lyrion music server (play, pause, search)
│   ├── emailConnector/      Email via IMAP/SMTP or Google Gmail
│   ├── unitConversionSkill/ Unit conversion (length, weight, temperature, etc.)
│   ├── httpFetchSkill/      HTTP GET with configurable host allowlist
│   ├── weatherSkill/        Current weather and forecast via Open-Meteo
│   ├── timeSkill/           Time, timezone, and duration utilities (pure java.time)
│   ├── marketDataSkill/     Stock quotes via Alpha Vantage
│   ├── searchSkill/         Web search via Tavily API
│   └── googleServices/      Gmail, Google Calendar, Google Drive, Google Contacts
│
├── telegramConnector/   Telegram Bot connector (JVM only)
├── ai/                  LLM client integrations (LangChain4j + Ollama) (JVM only)
├── server/              Caliban GraphQL API, HTTP server, agent runtime, MCP adapter, scheduler, memory
├── shell/               Interactive TUI shell — connects to server over GraphQL + WebSocket
├── web/                 Scala.js SPA — React 19 + MUI v9 (served by the server)
├── stLib/               ScalablyTyped bindings sub-project for React 19 + MUI v9 + Emotion
├── integration/         Integration tests (Testcontainers MariaDB)
└── analytics/           Analytics subsystem (future)
```

The domain layer (`model`) has no dependency on DB or connector specifics. All persistence details live in `db` (inside `server`). Each skill's JS side exports a React configuration component that the web frontend's Skills page loads dynamically.

---

## Skills

Jorlan ships with a growing library of built-in skills. All skills can be enabled or disabled at runtime via the web UI (`/skills` page) or shell (`/skills enable <name>` / `/skills disable <name>`).

| Skill | Namespace | Key Tools | Config Key |
|---|---|---|---|
| Calculator | `calculator` | `calculator.evaluate` | — (always on) |
| Unit Conversion | `unit_conversion` | `unit_conversion.convert` | — (always on) |
| Time & Timezone | `time` | `time.current`, `time.convert_timezone`, `time.format` | — (always on) |
| Email (Gmail / IMAP) | `email` | `email.list`, `email.read`, `email.send`, `email.search` | OAuth / IMAP settings |
| Google Calendar | `calendar` | `calendar.list_events`, `calendar.create_event`, etc. | OAuth |
| Google Drive | `drive` | `drive.list`, `drive.read`, `drive.search` | OAuth |
| Google Contacts | `contacts` | `contacts.find`, `contacts.list` | OAuth |
| Market Data | `market_data` | `market_data.quote`, `market_data.history` | `skill.market_data` → Alpha Vantage key |
| Lyrion Music | `lyrion` | `lyrion.play`, `lyrion.pause`, `lyrion.search` | `skill.lyrion` → server URL |
| Weather | `weather` | `weather.current`, `weather.forecast` | `skill.weather` → optional API key |
| HTTP Fetch | `http_fetch` | `http_fetch.get` | `skill.http_fetch` → host allowlist |
| Web Search | `search` | `search.web`, `search.news`, `search.extract` | `skill.search` → Tavily API key |
| MCP Adapter | `mcp.<serverName>` | dynamic (from MCP server) | `mcp.servers` → server configs |
| Shell Commands | `shell` | `shell.run`, `shell.ls`, `shell.cat`, `shell.grep`, etc. | `skill.shell` → sandbox root |
| User Management | `user_mgmt` | `user_mgmt.list_users`, `user_mgmt.create_user`, etc. | — (admin only) |
| Memory | `memory` | `memory.store`, `memory.query`, `memory.forget` | — (always on) |
| Scheduler | `scheduler` | `scheduler.create_job`, `scheduler.list_jobs`, etc. | — (always on) |

Skill configuration is stored in the `server_settings` table under the documented keys and can be updated via the web Settings page or directly in the database.

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
| `/skills` | List all registered skills and their enabled/disabled status |
| `/skills enable <name>` | Enable a skill |
| `/skills disable <name>` | Disable a skill (even built-in skills) |
| `/mcp list` | List configured MCP servers |
| `/mcp reload` | Reload MCP server configs from server_settings |
| `/contacts find <name>` | Search Google Contacts |
| `/email list [n]` | List recent emails |
| `/calendar today` | Show today's calendar events |
| `/scheduler list` | List scheduler jobs |
| `/oauth list` | List OAuth provider connections |
| `/oauth connect <provider>` | Connect to an OAuth provider (google, etc.) |
| `/users list` | List users (admin) |
| `/roles list` | List roles (admin) |
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
| **Approvals** | Review and decide pending capability approval requests |
| **Memory** | Search, remember, forget, and classify memory records |
| **Scheduler** | Browse jobs and triggers, pause/resume/cancel/run-now/delete |
| **Event Log** | Live-tail the event log via WebSocket subscription |
| **Skills** | Skill registry browser with enable/disable toggles per skill; each skill's configuration UI is embedded |
| **Users** | User, role, and permission management (admin) |
| **Settings** | Server personality editor, model selector |

### Skill configuration in the web UI

Each cross-project skill exports a React component (`*UI.scala`) that the Skills page loads to let admins configure that skill (API keys, allowlists, server URLs, etc.) without touching the database directly. Skill-specific config is stored in `server_settings` and loaded at startup.

### Building the web frontend

```bash
# Build the optimised bundle (outputs to dist/)
bash scripts/build-web.sh

# Build a fast (development) bundle (outputs to debugDist/)
sbtn --error "web/debugDist"
```

For local development, set `jorlan.http.staticContentDir = "debugDist"` (or export `JORLAN_STATIC_CONTENT_DIR=debugDist`) in `server/src/main/resources/application.conf` so the server serves the fast bundle without needing a full production build.

The `stLib` sub-project generates Scala.js bindings for React 19, MUI v9, and related npm packages using ScalablyTyped. It must be built and published to your local Ivy cache before the `web` module can compile. Do this once, and again whenever `stLib/` sources or npm deps change.

```bash
# 1. Install npm dependencies (required before sbt can run ScalablyTyped)
cd stLib && npm install   # or: yarn install

# 2. Generate bindings and publish to local Ivy cache
cd stLib && sbt publishLocal

# 3. Return to repo root for normal development
cd ..
```

npm packages for stLib are declared in `stLib/package.json`. ScalablyTyped reads them from `stLib/node_modules/` (populated by step 1) to generate the Scala façades. The generated artifact is published as `net.leibman:jorlan-stlib` to your local Ivy cache and referenced by the `web` module as a normal library dependency.

---

## Build & Test

```bash
# Compile all modules
sbt --error compile

# Run all tests (unit + server + shell + skill modules)
sbt --error test

# Run integration tests (requires Docker for Testcontainers)
sbt --error integration/test

# Format all sources
sbt scalafmtAll

# Build the web frontend
bash scripts/build-web.sh

# Regenerate GraphQL schema (after API changes in JorlanAPI.scala)
bash scripts/capture-schema.sh

# Regenerate Caliban shell/web client (after schema changes)
bash scripts/gen-client.sh
```

> **Note**: Never edit the generated GraphQL schema or `JorlanClient.scala` directly. Always modify `JorlanAPI.scala` and regenerate via the scripts above.

Test counts (as of Phase 14 refactor): **~708 unit tests** + **~180 integration tests** across all modules — all passing without a running server or Ollama.

---

## Architecture Highlights

### Cross-Project Skill Architecture

Skills are implemented as `sbt` cross-projects with two sides:

- **JVM side** (`server/`): the actual `Skill` implementation that runs in the server runtime
- **JS side** (`js/`): a React configuration UI component that the web frontend embeds on the Skills page

This structure means each skill is self-contained — its domain code, tools, tests, configuration schema, and web UI all live in one module. Adding a new skill does not require touching the `server` module.

Skills register themselves in `Jorlan.scala` based on `server_settings`; optional skills (those requiring API keys) are silently skipped if not configured.

### Agent Session Runtime

The core streaming path:

```
Shell /new → createSession mutation → AgentSessionManager.createSession
                                    → SessionHub.getOrCreate(sessionId)
                                    ← AgentSession (sessionId shown in mode bar)

Shell <text> → submitMessage mutation → AgentRunner.processMessage
                                       → SkillRegistry.allTools (ReAct loop)
                                       → ModelGateway.streamedResponse (Ollama/LangChain4j)
                                       → ZStream[String] token chunks
                                       → SessionHub.publish(ResponseChunk)
                                       → agentResponseStream subscription
                                       → WebSocket frames → shell TUI / web
```

`FakeModelGateway` is used in all unit/integration tests — no Ollama required for CI.

### Skill Registry & MCP Adapter

The `SkillRegistry` maintains all active skills and dispatches tool invocations from the ReAct loop. Skills are looked up by **longest-prefix matching** on the tool name (e.g., `mcp.my.server.com.read_file` routes to the skill named `mcp.my.server.com`), which correctly handles dotted namespaces used by the MCP adapter.

The MCP adapter reads server configs from `server_settings` key `"mcp.servers"` and registers one `McpSkillAdapter` per enabled server. Hot-reload is available via the `reloadMcpServers` GraphQL mutation.

Skills can be enabled or disabled at runtime. The disabled set is persisted to `server_settings` key `"skill.disabled"` so the state survives restarts.

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

All persistence goes through typed repository traits in `model`. The `db` module (inside `server`) provides Quill/MariaDB implementations bound to `RepositoryTask[A] = IO[RepositoryError, A]`. Flyway migrations (V001–V025+) are run automatically on startup.

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
| 9 | Memory system — checkpointing, summarization, access policy, context injection | ✅ Complete |
| 10 | Durable scheduler — cron/interval triggers, DB locking, retry/backoff | ✅ Complete |
| 11 | Telegram connector | ✅ Complete |
| 12 | Built-in skills — ReAct loop, SkillRegistry, core skills | ✅ Complete |
| 13 | Email and Calendar skills (Gmail, Google Calendar, Google Drive) | ✅ Complete |
| 14.0 | Skill enable/disable (web UI + shell + persisted in server_settings) | ✅ Complete |
| 14.3 | MCP compatibility adapter (stdio + HTTP, hot-reload, dotted namespaces) | ✅ Complete |
| 14.6 | Google Contacts skill | ✅ Complete |
| 14.7 | Weather skill (Open-Meteo) | ✅ Complete |
| 14.8 | Shell filesystem tools (ls, cat, grep, find, head, tail, wc) | ✅ Complete |
| 14.9 | User management skill (12 tools) | ✅ Complete |
| 14.10 | Time & timezone skill (pure java.time) | ✅ Complete |
| 14.11 | HTTP fetch skill (host allowlist, PerInvocation approval) | ✅ Complete |
| 14.16 | Web search skill (Tavily API) | ✅ Complete |
| 14.x | Skills refactored to cross-projects; per-skill configuration UI screens; `gqlClient` abstraction | ✅ Complete |
| 15 | Web frontend (Scala.js + React 19 + MUI v9) | ✅ Complete |
| 18 | Installer and distribution (.deb, macOS Homebrew) | ✅ Complete |
| 16 | Advanced features — declarative skills, agent-authored skills, vector memory | Planned |
| 17 | Orchestrator integration (work request submission, execution state machine) | Planned |

See `doc/development_roadmap.md` for the detailed task breakdown per phase.

---

## Documentation

| Document | Description |
|---|---|
| `doc/ExecutiveSummary.md` | Goals and philosophy |
| `doc/SoftwareRequirementsSpecification.md` | Full requirements |
| `doc/SoftwareDesignDocument.md` | Architecture and implementation guidance |
| `doc/orchestrator_integration_addendum.md` | GraphQL orchestrator API design |
| `doc/system-diagrams/` | Subsystem diagrams per domain area |
| `doc/development_roadmap.md` | Phase-by-phase task list with checkboxes |
| `doc/mini-designs/` | Per-feature design documents |
| `.env.example` | All supported environment variables with descriptions |

---

## License

Copyright © 2026 Roberto Leibman. All rights reserved. See license header in source files.
