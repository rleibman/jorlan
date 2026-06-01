# Jorlan — Secure Agent Runtime & Orchestration Platform

## Project Overview

This is a Scala 3 / ZIO / MariaDB platform for running AI agents securely with typed skills, capability-based
permissions, durable scheduling, and full observability. See `doc/` for full specs.

## Key Docs

- `doc/ExecutiveSummary.md` — Goals and philosophy
- `doc/SoftwareRequirementsSpecification.md` — Full requirements
- `doc/SoftwareDesignDocument.md` — Architecture and implementation guidance
- `doc/orchestrator_integration_addendum.md` — GraphQL orchestrator API design
- `doc/00_system_overview.md` through `doc/13_artifacts_and_effects.md` — Subsystem diagrams
- `doc/development_roadmap.md` — Development roadmap and milestones, typically contains the checked tasks that should be done for each phase

## Technology

- **Language**: Scala 3.8.3 with `-Yexplicit-nulls`, `-no-indent`, `-old-syntax`, `-Werror`
- **Effects**: ZIO 2.x throughout
- **Database**: MariaDB via Quill (quill-jdbc-zio) + Flyway migrations
- **API**: Caliban (GraphQL) as primary external API
- **HTTP**: zio-http
- **Testing**: zio-test + testcontainers-scala-mariadb
- **Shell execution**: zio-process

## SBT Module Structure

- `ai` — All the communication with ai is located here, we're using langchain4j
- `model` — domain types, shared across modules
- `db` — Quill repositories, Flyway migrations
- `server` — Caliban GraphQL, HTTP server, connectors, agent runtime
- `analytics` — analytics subsystem
- `integration` — integration tests (Testcontainers)
- `util` — shared utilities

## SBT Usage

- Always use `--error` option: `sbt --error compile`
- Any time you're going to compile two modules or more, please use `sbt --error compile test:compile`
- Use `sbt test` to run all tests 
  

## Scala Style

- Use `-old-syntax` / `-no-indent` (braces, not indentation)
- Explicit nulls are enforced (`-Yexplicit-nulls`)
- Functional style throughout; ZIO for all effects
- No `null`, no exceptions in domain code, no mutable state in domain

## Architecture Principles

1. Deny-by-default capability model — no permissions unless explicitly granted
2. Domain layer must NOT depend on DB or connector specifics
3. Every significant action writes to the append-only event log
4. GraphQL is the primary external API surface; connectors use shared application service layer internally
5. Relational DB (MariaDB) is the canonical source of truth; vector indexes are derived
6. Multi-user from day one — all inbound messages resolve to a canonical user before agent execution

## Git Discipline — MANDATORY

- **NEVER run `git commit` or `git push` without explicit user approval.** Present the diff/summary and wait for the
  user to say "commit", "push", or equivalent. Not even as a "finishing touch". Not even when the task is clearly
  complete. No exceptions.
- Before asking to commit: run `sbt --error scalafmtAll` and `sbt --error test` (or at minimum `test:compile`), then
  report results.

## Open Design Issues (do not silently assume)

1. Exact shared-memory privacy boundaries
2. Whether some connectors should run out-of-process
3. Shell connector: local-only or remotely accessible?
4. Exact MariaDB vector schema and embedding model strategy
5. How to promote agent-authored skills after repeated successful use

## Other important notes

- When I tell you to fan out subagents, run all of them, start by figuring out which ones need to be run (At last count
  there were 8 in the global directory and one in this project, but always check first), and then run them all. Do not
  assume that any of them are already running, even if you just ran them a moment ago. Always check first.
- After you run your agents, the reporting agent should place it's report in a doc/xxx-review.md file, where xxx is
  typically the Phase of the project we're at. The document should follow the guidelines of the phase_review_template.md 
