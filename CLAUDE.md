# Jorlan — Secure Agent Runtime & Orchestration Platform

## Project Overview

This is a Scala 3 / ZIO / MariaDB platform for running AI agents securely with typed skills, capability-based
permissions, durable scheduling, and full observability. See `doc/` for full specs.

## Key Docs

### Core Specs

- `doc/ExecutiveSummary.md` — Goals and philosophy
- `doc/SoftwareRequirementsSpecification.md` — Full requirements
- `doc/SoftwareDesignDocument.md` — Architecture and implementation guidance
- `doc/orchestrator_integration_addendum.md` — GraphQL orchestrator API design
- `doc/development_roadmap.md` — Canonical roadmap; checkbox items track per-phase completion

### System Diagrams

- `doc/system-diagrams/00_system_overview.md` through `doc/system-diagrams/13_artifacts_and_effects.md` — Subsystem
  diagrams per domain area

### Phase Mini-Designs

Created before a phase begins to capture detailed design decisions:

- `doc/mini-designs/phase9-memory-system.md`
- `doc/mini-designs/phase10-durable-scheduler.md`
- `doc/mini-designs/session-connection-redesign.md`
- `doc/mini-designs/testing_ai_on_ci.md`

### Phase Reviews

Multi-agent tech-debt reports, written after implementation using `doc/phase_review_template.md`:

- `doc/phase-review/phase0_1_2_review.md` through `doc/phase-review/phase10-review.md`

### Other

- `doc/phase_review_template.md` — Template all phase reviews must follow
- `doc/manual-testing-guide.md` — Manual QA checklist

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

- Always use `--error` option: `sbtn --error compile`
- Any time you're going to compile two modules or more, please use `sbtn --error compile test:compile`
- Use `sbtn test` to run all tests

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

## Phase Execution Strategy

Each phase follows this sequence, commanded and supervised manually by the user:

0. **Save & reset** — Save memory, clear context
1. **Branch** — `git checkout -b phaseN/feature-name`
2. **Plan** — Update `doc/development_roadmap.md` with checkbox items for the phase; create a
   `doc/mini-designs/phaseN-*.md` if the design is non-trivial
3. **Implement** — Execute the phase, checking off items in `doc/development_roadmap.md` as each
   is completed
4. **Save & reset** — Save memory, clear context
5. **Fan out agents** — Launch all review agents in parallel (check counts first — at last count
   8 in `~/.claude/agents/` and 1 in `.claude/agents/`; always verify before running). After all
   agents complete, launch the `report-coordinator` agent to write
   `doc/phase-review/phaseN-review.md` following `doc/phase_review_template.md`
6. **Fix review items** — Work through the review document top-down, checking off each item
   `[x]` as it is resolved
7. **Manual visual review** — User reviews code directly
8. **Manual testing** — User runs the app and tests the golden path and edge cases
9. **Copilot PR review** — GitHub Copilot does a final pass on the PR for any remaining gaps
10. **PR approved and merged**

## Other important notes

- When I tell you to fan out subagents, run all of them. Start by checking which agents exist
  (both `~/.claude/agents/` and `.claude/agents/`), do not assume any are already running.
- After agents complete, the `report-coordinator` agent writes the report to
  `doc/phase-review/phaseN-review.md` following `doc/phase_review_template.md`.
- Whenever you're working on a list of items from a document (e.g. the roadmap or a phase review) you must always check
  off the items as you go, otherwise it's really hard to figure out where you were if something goes wrong
