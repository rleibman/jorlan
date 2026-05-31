# Jorlan — Secure Agent Runtime & Orchestration Platform

> A production-grade runtime for AI agents: capability-based security, durable scheduling, full observability, and strongly typed interfaces.

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

- **Language**: Scala 3.8.3 with `-Yexplicit-nulls`, `-no-indent`, `-old-syntax`, `-Werror`
- **Effects**: ZIO 2.x throughout
- **Database**: MariaDB via Quill (`quill-jdbc-zio`) + Flyway migrations
- **API**: Caliban (GraphQL) as the primary external API
- **HTTP**: zio-http
- **Serialization**: zio-json
- **LLM**: LangChain4j (Ollama streaming) via the `ai` module
- **Testing**: zio-test + Testcontainers (MariaDB)
- **Shell execution**: zio-process
- **Connection pool**: HikariCP

---

## Module Structure

```
jorlan/
├── model/        Domain types, repository traits, error hierarchy, configuration models
├── db/           Quill repository implementations, Flyway migrations, DB configuration
├── ai/           LLM client integrations (LangChain4j + Ollama)
├── server/       Caliban GraphQL API, HTTP server, agent runtime (AgentRunner, SessionHub, ModelGateway)
├── shell/        Interactive TUI shell — connects to the server over GraphQL + WebSocket
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

### Database setup

```sql
CREATE DATABASE jorlan CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'jorlan'@'localhost' IDENTIFIED BY 'jorlan';
GRANT ALL PRIVILEGES ON jorlan.* TO 'jorlan'@'localhost';
```

Flyway migrations run automatically on server startup.

### Configuration

Create `server/src/main/resources/application.conf` (or point to an external file via `-Dconfig.file`). Minimum required:

```hocon
jorlan {
  db.dataSource {
    driver   = "org.mariadb.jdbc.Driver"
    url      = "jdbc:mariadb://localhost:3306/jorlan"
    user     = "jorlan"
    password = "jorlan"
  }
  auth.secretKey = "change-me-in-production"
}
```

To enable Ollama (Phase 8):

```hocon
jorlan.ai {
  ollamaBaseUrl = "http://localhost:11434"
  ollamaModel   = "llama3.2:3b"
}
```

All environment variable overrides are documented in `application.conf`.

---

## Running the Server

```bash
# Compile
sbt --error server/compile

# Start the server (connects to MariaDB, runs Flyway, starts HTTP on port 8080)
sbt "server/runMain jorlan.Jorlan"

# Or build a deployable package
sbt server/stage
./server/target/universal/stage/bin/jorlan-server
```

The server exposes:
- `POST /api/jorlan` — GraphQL endpoint
- `GET  /api/jorlan/ws` — GraphQL over WebSocket (subscriptions)
- `GET  /api/jorlan/graphiql` — GraphiQL IDE (browser)
- `GET  /health` — liveness probe

---

## Running the Shell

The interactive TUI shell connects to a running Jorlan server via GraphQL.

```bash
# Start the shell (requires a running server)
sbt "shell/runMain jorlan.shell.JorlanShell"

# Or with explicit server URL and credentials
sbt "shell/runMain jorlan.shell.JorlanShell --server-url http://localhost:8080 --email admin@example.com --password secret"
```

### Shell configuration

Credentials and server URL can be stored in `~/.jorlan/jorlan.json`:

```json
{
  "serverUrl": "http://localhost:8080",
  "email": "admin@example.com",
  "password": "your-password"
}
```

### Shell commands

| Command | Description |
|---|---|
| `/new [model]` | Start a new agent session (optionally specify model, e.g. `llama3.2:3b`) |
| `/status` | Check server connectivity |
| `/whoami` | Show the authenticated user |
| `/model` | Show the active session and model |
| `/trace [level]` | Set log level: `none \| error \| warning \| info \| debug` |
| `/help` | Show help summary |
| `/quit` or `/exit` | Exit the shell |
| _(plain text)_ | Send a message to the active agent session |

Key bindings: **Enter** submit · **Backspace** delete · **PgUp/PgDn** scroll · **Ctrl-C** quit

---

## Build & Test

```bash
# Compile all modules
sbt --error compile

# Run unit tests
sbt --error test

# Run integration tests (requires Docker for Testcontainers)
sbt --error integration/test

# Format all sources
sbt scalafmtAll

# Regenerate GraphQL schema (after API changes)
bash scripts/capture-schema.sh

# Regenerate Caliban shell client (after schema changes)
bash scripts/gen-client.sh
```

---

## Architecture Highlights

### Agent Session Runtime (Phase 8)

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

### Capability-Based Security

Permissions are scoped to specific resources, actions, and time windows. High-risk operations require explicit grants that are auditable, time-limited, and revocable.

### Append-Only Event Log

Every significant runtime action is recorded in a durable event log: `SessionCreated`, `UserMessageReceived`, `ModelCallStarted`, `ModelCallCompleted`, `AgentResponseCompleted`, etc.

### Repository Layer

All persistence goes through typed repository traits in `model`. The `db` module provides Quill/MariaDB implementations bound to `RepositoryTask[A] = IO[RepositoryError, A]`.

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
| **8** | **Agent session runtime + Model Gateway — streaming LLM responses** | **✅ Complete** |
| 9 | Memory system — checkpointing, summarization, access policy | Pending |
| 10 | Durable scheduler — cron/interval triggers, job locking | Pending |
| 11 | Telegram connector | Pending |
| 12 | Built-in skills — workspace, shell, notification, identity | Pending |
| 13 | Email and Calendar skills | Pending |
| 14 | Orchestrator integration | Pending |
| 15 | Web frontend | Pending |

See `doc/development_roadmap.md` for the detailed task breakdown and open items per phase.

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

---

## License

Copyright © 2026 Roberto Leibman. All rights reserved. See license header in source files.
