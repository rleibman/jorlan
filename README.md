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
- **Testing**: zio-test + Testcontainers (MariaDB)
- **Shell execution**: zio-process
- **Connection pool**: HikariCP

---

## Module Structure

```
jorlan/
├── model/        Domain types, repository traits, error hierarchy, configuration models
├── db/           Quill repository implementations, Flyway migrations, DB configuration
├── server/       Caliban GraphQL API, HTTP server, connectors, agent runtime
├── ai/           LLM client integrations (Ollama, etc.)
├── shell/        Shell connector for subprocess execution
├── analytics/    Analytics subsystem
├── integration/  Integration tests (Testcontainers)
└── util/         Shared utilities
```

The domain layer (`model`) has no dependency on DB or connector specifics. All persistence details live in `db`.

---

## Getting Started

### Prerequisites

- JDK 21+
- SBT 1.x
- MariaDB (or use the Testcontainers integration test setup)
- `application.conf` on the classpath or pointed to via `-Dapplication.conf=/path/to/config`

### Configuration

Jorlan reads its configuration from Typesafe Config. A minimal `application.conf`:

```hocon
jorlan {
  db {
    dataSource {
      driver   = "org.mariadb.jdbc.Driver"
      url      = "jdbc:mariadb://localhost:3306/jorlan"
      user     = "jorlan"
      password = "secret"
    }
  }
}
```

Optional overrides for connection pool, Flyway, and HTTP are documented in `model/src/main/scala/jorlan/configuration.scala`.

### Build

```bash
# Compile all modules
sbt --error compile

# Run tests (unit)
sbt --error test

# Run integration tests (requires Docker)
sbt --error integration/test

# Format all sources
sbt scalafmtAll
```

---

## Architecture Highlights

### Capability-Based Security

Permissions are scoped to specific filesystem paths, tools, network destinations, time windows, and connector instances. High-risk operations (file deletion, shell execution, credential access, network calls) require explicit grants that are auditable, time-limited, and revocable.

### Append-Only Event Log

Every significant runtime action is recorded in a durable event log. The log supports replayability, failure diagnosis, regression analysis, and operational auditing.

### Repository Layer

All persistence goes through typed repository traits in `model` (e.g. `UserRepository[F[_]]`, `AgentRepository[F[_]]`). The `db` module provides Quill/MariaDB implementations bound to `RepositoryTask[A] = IO[RepositoryError, A]`.

### Error Hierarchy

```
JorlanError (root)
├── ConfigurationError
│   └── ConfigLoadError
├── RepositoryError
└── NotFoundError
```

All errors extend `JorlanError extends Exception`, giving a single catch site and unified `isTransient` classification.

---

## Development Status

| Phase | Description | Status |
|---|---|---|
| 0 | Domain model, IDs, error hierarchy | Complete |
| 1 | DB schema, Quill repositories, Flyway | Complete |
| 2 | Integration tests, capability/permission model | Complete |
| 3 | GraphQL API (Caliban), HTTP server | Pending |
| 4 | Agent runtime, skill execution | Pending |
| 5 | Scheduler, durable jobs | Pending |
| 6 | LLM connectors, model routing | Pending |
| 7 | Shell connector, external tools | Pending |
| 8 | Analytics, observability dashboard | Pending |

See `doc/development_roadmap.md` for the detailed task list.

---

## Documentation

| Document | Description |
|---|---|
| `doc/ExecutiveSummary.md` | Goals and philosophy |
| `doc/SoftwareRequirementsSpecification.md` | Full requirements |
| `doc/SoftwareDesignDocument.md` | Architecture and implementation guidance |
| `doc/orchestrator_integration_addendum.md` | GraphQL orchestrator API design |
| `doc/00_system_overview.md` – `doc/13_artifacts_and_effects.md` | Subsystem diagrams |

---

## License

Copyright © 2026 Roberto Leibman. All rights reserved. See license header in source files.
