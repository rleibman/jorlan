# Phase 4 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala, Code Simplicity, Performance, Architecture & Patterns, Test
Coverage)
**Date**: 2026-05-27
**Branch**: `phase-3/graphql-api`
**Scope**: Full codebase scan — `model`, `db`, `server`, architecture alignment, and test coverage

---

## Executive Summary

Jorlan's foundation is architecturally sound: the opaque ID model, two-level repository abstraction, `ZLayer.make`
wiring, `CorrelationId` propagation, and append-only event log interface are all exemplary Scala 3 / ZIO patterns. The
codebase is progressing steadily through its build-out phases.

However, five systemic risks must be resolved before the GraphQL API surface is published. The most severe is a **module
boundary violation** in `build.sbt` that pulls Quill and `zio-http` into the `model` layer, contradicting the project's
own "domain layer must not depend on DB or connector specifics" rule and forcing unnecessary coupling on every
downstream consumer. Directly connected to this, **infrastructure configuration types** (`DataSourceConfig`,
`FlywayConfig`) reside in `jorlan.model`, dragging Flyway onto consumers that have no need for it.

The second systemic risk is that the **event log audit requirement is unimplemented**: `EventLogService.log` is defined
but never called. Every mutation that will eventually route through the GraphQL API currently bypasses the audit trail
entirely. Retrofitting this after resolvers are wired will be substantially more expensive than doing it now.

Performance risks are real but scoped: the `getPendingJobs` full table scan and the `login` double round-trip are the
highest-urgency items; both have straightforward SQL fixes. The `replaySession` unbounded query is a latent OOM risk
that will surface under production load.

Test coverage has critical gaps at the security boundary: the entire `jorlan.auth` package is at 0% coverage, and
`UserZIORepository.login`/`changePassword` have no integration tests. These are the two paths most likely to harbour
security regressions.

Code duplication in `QuillRepositories.scala` — nine copies of `exec` and `ds`, and ~400 lines of repeated sort-branch
filter chains — inflates the file to the point where changes are error-prone and reviews are slow. This is the single
highest-leverage refactor available.

**Overall health: Needs Attention** — the design is right, but several pre-API gaps will become significantly more
expensive to fix once the GraphQL surface is published.

---

## Prioritized Tech Debt Table

| Status | Severity | Area          | Issue                                                                                         | File : Line                                            | Recommended Action                                                                                    |
|--------|----------|---------------|-----------------------------------------------------------------------------------------------|--------------------------------------------------------|-------------------------------------------------------------------------------------------------------|
| [x]    | Critical | Architecture  | `model` module depends on `quill-jdbc-zio` and `zio-http`                                     | `build.sbt:146-148`                                    | Move Quill/Flyway deps to `db`; move `MediaType` codec to `server`                                    |
| [x]    | Critical | Architecture  | DB/Flyway config types in domain package                                                      | `model/.../configuration.scala:21-51`                  | Move `DataSourceConfig`, `DatabaseConfig`, `FlywayConfig` to `jorlan.db`                              |
| [x]    | Critical | Architecture  | Event log audit requirement unimplemented — `EventLogService.log` never called                | entire `db`/`server` layer                             | Build `AgentService`, `ConversationService` etc. with event log calls before adding GraphQL resolvers |
| [x]    | Critical | Performance   | `getPendingJobs` full table scan — SQL predicate applied in Scala memory                      | `QuillRepositories.scala:1042`                         | Push `scheduledAt <= now` into the SQL `WHERE` clause                                                 |
| [x]    | Critical | Performance   | `login` executes two DB round-trips                                                           | `QuillRepositories.scala:272-285`                      | Merge into a single query returning the full `User` row                                               |
| [x]    | Critical | Performance   | `replaySession` unbounded query — no `LIMIT`                                                  | `QuillRepositories.scala:1015-1023`                    | Add configurable `LIMIT`; consider cursor/chunk pattern for large sessions                            |
| [x]    | Critical | Test          | `jorlan.auth` package at 0% coverage — security boundary untested                             | `server/.../auth/JorlanAuthServer.scala`               | Add `AuthServerSpec` integration test (Testcontainers) covering login, changePassword, OAuth paths    |
| [x]    | Critical | Test          | `UserZIORepository.login` and `changePassword` — 0% coverage                                  | `QuillRepositories.scala` (UserZIORepository)          | Add `UserRepositoryAuthSpec` integration test                                                         |
| [x]    | Critical | Correctness   | `MappedEncoding` decode lambdas throw `RuntimeException` with no comment                      | `quillUtil.scala:122-166`                              | Add co-located comment explaining the Quill interop constraint; consider typed decode error           |
| [x]    | Critical | Correctness   | `makeDataSource` / HikariCP pool never registered for shutdown                                | `quillUtil.scala:34`, `QuillRepositories.scala:99`     | Wrap pool construction and teardown in `ZIO.acquireRelease` / `ZLayer.scoped`                         |
| [x]    | High     | Code Quality  | `private def exec` and `private val ds` copy-pasted across all 9 repository classes           | `QuillRepositories.scala` throughout                   | Extract `QuillRepoBase(qc: QuillCtx)` base class or mixin                                             |
| [x]    | High     | Code Quality  | ~400 lines of repeated filter chain in every `search` method                                  | `QuillRepositories.scala` (all search methods)         | Extract `val base = qXxx.filter(...)` before sort branch; each branch becomes one line                |
| [x]    | High     | Correctness   | `userByEmail` uses `LIKE '%value%'` instead of equality — unindexable full scan               | `QuillRepositories.scala:288`                          | Replace `.contains(lift(email))` with `== lift(email)`                                                |
| [x]    | High     | Test          | All 11 domain enums have zero codec roundtrip tests                                           | `model/.../domain/` (all enum files)                   | Add enum codec suite to `DomainSpec`                                                                  |
| [x]    | High     | Correctness   | All `search` methods silently discard secondary sorts                                         | `QuillRepositories.scala` (all search methods)         | Either narrow `sorts: List[Sort]` to `sorts: Option[Sort]` in API, or implement multi-column ORDER BY |
| [x]    | High     | Architecture  | `PermissionRepository` missing `deleteRole`, `deletePermission`, `getExpiredApprovalRequests` | `repository.scala` / `QuillRepositories.scala`         | Implement missing permission management operations to enable deny-by-default enforcement              |
| [x]    | Medium   | Correctness   | `ConnectionId.random` calls `UUID.randomUUID()` in non-ZIO context                            | `ids.scala:367`                                        | Rename to `unsafeRandom` and add `randomZIO: UIO[ConnectionId]`                                       |
| [ ]    | Medium   | Correctness   | `Jorlan.scala` `e.getMessage` can return null                                                 | `Jorlan.scala:52-53`                                   | Replace with `Option(e.getMessage).getOrElse(e.getClass.getName)`                                     |
| [x]    | Medium   | Architecture  | `JorlanError.apply` silently discards message when cause is already a `JorlanError`           | `errors.scala:32-39`                                   | Document the behavior, or preserve the message as additional context                                  |
| [x]    | Medium   | Architecture  | Capability names are bare `String` — typo silently creates a dead grant                       | `permission.scala:31-38`                               | Introduce `opaque type CapabilityName` or at-construction validation                                  |
| [x]    | Medium   | Test          | `MemorySearch` `Workspace` and `Private` scopes never exercised                               | integration tests                                      | Add scope-specific cases to `MemoryRepositorySpec`                                                    |
| [x]    | Medium   | Test          | Search pagination (`page > 0`) never tested                                                   | any search spec                                        | Add one pagination offset test to an existing search spec                                             |
| [x]    | Medium   | Performance   | HikariCP pool missing `keepaliveTime` and `idleTimeout` — stale connection risk               | `quillUtil.scala:34-46`                                | Add recommended HikariCP idle/keepalive configuration                                                 |
| [x]    | Medium   | Performance   | `exec` calls `q.provideLayer(ds)` on every invocation — repeated allocation under load        | `QuillRepositories.scala` (`exec` in all repo classes) | Hoist `ds` layer construction outside `exec`; resolved by `QuillRepoBase` refactor                    |
| [ ]    | Low      | Architecture  | 9-way `&` return type of `QuillRepositories.live` — hard to read                              | `QuillRepositories.scala:104-139`                      | Add a type alias for the compound environment type                                                    |
| [ ]    | Low      | Code Quality  | 9-tuple positional destructuring in `QuillRepositories.live` — fragile                        | `QuillRepositories.scala:129`                          | Use named extraction or per-repo `ZLayer.fromZIO`                                                     |
| [ ]    | Low      | Code Quality  | 13 `Search` case classes each repeat `page`, `pageSize`, `sorts`                              | `repository.scala`                                     | Informational — Scala limitation; consider a `SearchParams` base trait if structure grows             |
| [x]    | Low      | Test          | `OrchestratorIdentity` has no CRUD or codec test                                              | integration / domain tests                             | Add to existing CRUD test matrix                                                                      |
| [x]    | Low      | Test          | `MemoryRepository.getById` never called in any test                                           | integration tests                                      | Add one `getById` assertion to `MemoryRepositorySpec`                                                 |
| [ ]    | Low      | Documentation | `ArtifactSearch` lacks note that cross-workspace search is unsupported                        | `model/.../repository.scala`                           | Add a scaladoc comment to `ArtifactSearch` noting the constraint                                      |
| [ ]    | Low      | Performance   | `purgeExpired` filters on `memoryRecord.ttl` — index not verified                             | Flyway migrations                                      | Confirm a DB index on `memory_record.ttl` exists in migration scripts                                 |

---

## Grouped Sections

### Architectural Issues

**A1 — Module boundary violation: `model` depends on `quill-jdbc-zio` and `zio-http`** [Critical]

`build.sbt` lines 146-148 pull Quill and `zio-http` into the `model` module. This directly contradicts the documented
architecture rule ("domain layer must NOT depend on DB or connector specifics") and means every module that depends on
`model` — including the shell connector and analytics subsystem — inherits transitive Quill and HTTP dependencies. Fix:
move all Quill/Flyway dependencies to `db`; move any `MediaType` or HTTP codec to `server`.

**A2 — Infrastructure configuration in the domain package** [Critical]

`DataSourceConfig`, `DatabaseConfig`, and `FlywayConfig` are defined in
`model/src/main/scala/jorlan/configuration.scala`. These are infrastructure concerns. Their presence in the domain
package forces Flyway onto all consumers. Fix: move these types to `jorlan.db`.

**A3 — Event log audit requirement entirely unimplemented** [Critical]

`EventLogService.log` is defined but never called from any repository or service. Every mutation — user creation,
conversation start, permission grant — currently produces no audit record. The architecture specifies that every
significant action writes to the event log. This must be addressed before GraphQL resolvers are added; retrofitting
after the API surface exists is substantially more expensive.

**A4 — Missing GraphQL endpoint** [Known phase gap]

`Jorlan.scala:40-65` mounts only `/health` and auth routes. No GraphQL endpoint is wired despite Caliban being
configured. This is the explicit Phase 3 → 4 transition gap and is expected, but listed here because A3 must be resolved
first.

**A5 — Deny-by-default permission model incompletely implemented** [High]

`PermissionRepository` is missing `deleteRole`, `deletePermission`, and `getExpiredApprovalRequests`. Without these,
permissions can be granted but not revoked, and expired approval requests accumulate indefinitely.

**A6 — `JorlanError.apply` silently discards message context** [Medium]

When the `cause` is already a `JorlanError`, the `msg` argument is silently dropped. This loses context at call sites
that add a message to an existing typed error. Document the behavior or preserve the message as secondary context.

**A7 — Capability names are unvalidated bare strings** [Medium]

`permission.scala:31-38` accepts capability names as plain `String`. A typo at a call site silently creates a dead grant
that will never match. An `opaque type CapabilityName` with at-construction validation (or at minimum a sealed
enumeration) would make the type system catch this class of error.

---

### Functional Correctness

**F1 — `MappedEncoding` decode lambdas throw `RuntimeException` without explanation** [Critical]

Five decode lambdas in `quillUtil.scala:122-166` throw `RuntimeException` on unrecognised values. This is a Quill
interop constraint (Quill's `MappedEncoding` does not support typed errors), but it is not documented. A developer
extending this code will replicate the pattern without understanding why, or worse will assume it is safe to call
outside the `exec` try-catch boundary. Add a co-located block comment explaining the constraint.

**F2 — HikariCP pool lifecycle unmanaged** [Critical]

`makeDataSource` constructs a `HikariDataSource` as a bare function; `QuillCtx.hds` initialises it eagerly as a `val`.
The pool is never closed. Construction failures become defects rather than typed ZIO errors. Fix: wrap in
`ZLayer.scoped` with `ZIO.acquireRelease(makeDataSource)(ds => ZIO.succeed(ds.close()))`.

**F3 — `userByEmail` uses substring match instead of equality** [High]

`QuillRepositories.scala:288` compiles to `WHERE email LIKE '%value%'` rather than `WHERE email = 'value'`. This is both
a correctness issue (partial matches accepted) and a performance issue (full table scan, unindexable). Replace
`.contains(lift(email))` with `== lift(email)`.

**F4 — Secondary sorts silently ignored across all `search` methods** [High]

Every repository `search` method extracts only `s.sorts.headOption`. The public API type `sorts: List[Sort]` implies
multi-column sorting is supported. Fix: either narrow the API to `sorts: Option[Sort]`, or implement multi-column ORDER
BY. The current state is a silent lie in the API contract.

**F5 — `ConnectionId.random` is not ZIO-safe** [Medium]

`ids.scala:367` calls `UUID.randomUUID()` in a non-ZIO `def`. Callers in a ZIO context can invoke this in pure position,
bypassing the effect system. Add `randomZIO: UIO[ConnectionId]` using `ZIO.randomWith` and rename the current method to
`unsafeRandom`.

**F6 — Null-unsafe `e.getMessage` in `Jorlan.scala`** [Medium]

`e.getMessage` can return `null` in Java. `Jorlan.scala:52-53` uses it directly in string interpolation, producing the
literal `"null"` in error output. Replace with `Option(e.getMessage).getOrElse(e.getClass.getName)`.

---

### Performance

**P1 — `getPendingJobs` full table scan** [Critical — confirmed by Agents 1, 2, 3]

`QuillRepositories.scala:1042` fetches all `Pending` jobs and post-filters `scheduledAt <= now` in Scala memory. As the
job queue grows this becomes a full table scan on every scheduler tick. The fix is a single SQL predicate addition to
the Quill query.

**P2 — `login` executes two sequential DB round-trips** [Critical]

`QuillRepositories.scala:272-285` first fetches the user `id` via raw SQL, then calls `getById`. These can be merged
into a single query returning the full `User` row, halving latency on every authentication.

**P3 — `replaySession` is unbounded** [Critical]

`QuillRepositories.scala:1015-1023` replays an entire session's event log with no `LIMIT`. An active long-running
session can accumulate thousands of rows; a full replay at that scale risks OOM and will produce unacceptable latency.
Add a configurable `LIMIT` and consider a chunk/cursor pattern for deep replays.

**P4 — HikariCP not configured for idle connection management** [Medium]

`quillUtil.scala:34-46` constructs a `HikariDataSource` without `keepaliveTime` or `idleTimeout`. Under low traffic,
stale connections will silently fail. Add standard HikariCP idle management settings.

**P5 — `exec` re-provides layer on every call** [Medium — resolved by P6 / H1 refactor]

Each of the nine repository classes calls `q.provideLayer(ds)` inside `exec`. This is minor allocation overhead per
query but accumulates under high write throughput. Hoisting the layer to class construction (addressed naturally by the
`QuillRepoBase` refactor) eliminates it.

**P6 — `purgeExpired` requires a DB index on `memory_record.ttl`** [Low]

Verify in the Flyway migration scripts that `memory_record.ttl` is indexed. Without it, `purgeExpired` becomes a full
scan on an unbounded table.

---

### Code Quality / Simplicity

**H1 — Nine copy-pasted `exec` / `ds` implementations** [High — confirmed by Agents 1, 2]

Every one of the nine repository classes in `QuillRepositories.scala` contains an identical `private def exec[A]` and
`private val ds`. This is the highest-leverage single refactor in the codebase. Extract a `QuillRepoBase(qc: QuillCtx)`
base class or trait. The resulting reduction is approximately 90-120 lines, and future changes to the execution wrapper
apply in one place.

**H2 — ~400 lines of repeated filter chain in `search` methods** [High]

Each `search` method repeats the complete `qXxx.filter(...)` expression inside every sort branch. Extract
`val base = qXxx.filter(...)` before the `match`; each branch then adds only the `sortBy` call. Estimated reduction: ~
400 lines. This also happens to be the correct place to add multi-column sort support (F4).

**H3 — 9-tuple positional destructuring in `QuillRepositories.live`** [Low]

`QuillRepositories.scala:129` unpacks a 9-element tuple by position. Inserting or reordering a repository silently
shifts all downstream bindings. Use per-repo `ZLayer.fromZIO` or named deconstruction.

**H4 — Unreadable compound environment type** [Low]

The 9-way `&` return type of `QuillRepositories.live` is difficult to scan. A type alias (e.g.,
`type AllRepositories = UserRepository & ConversationRepository & ...`) improves readability at declaration and call
sites.

---

### Test Coverage

**T1 — `jorlan.auth` at 0% coverage — security boundary** [Critical]

`JorlanAuthServer` (59 statements) covers `login`, `changePassword`, `createOAuthUser`, and `linkOAuthToUser`. These are
the highest-risk paths in the system and have no test coverage at all. An `AuthServerSpec` integration test using
Testcontainers is the required fix.

**T2 — `UserZIORepository.login` and `changePassword` at 0% coverage** [Critical]

Password hashing and credential validation only run on the SQL side. These paths require Testcontainers integration
tests to verify correctly. Add `UserRepositoryAuthSpec`.

**T3 — 11 domain enums with no codec roundtrip tests** [High]

`ApprovalMode`, `ApprovalStatus`, `SessionStatus`, `MessageRole`, `EventType`, `MemoryScope`, `SkillTier`,
`SkillStatus`, `ConnectorType`, `JobStatus`, and `TriggerType` all derive zio-json codecs with zero roundtrip coverage.
Codec derivation can fail silently on enum renames or additions. A parametric enum codec suite in `DomainSpec` covers
all 11 at once.

**T4 — `RepositoryError` SQL exception branching untested** [High] ✅

The `SQLTransientException`/`SQLNonTransientException` branches in `RepositoryError.apply(Throwable)` are never
triggered in tests. Add a `RepositoryErrorSpec` unit test constructing synthetic SQL exceptions.

> Done: `RepositoryErrorSpec` added in `server/src/test/scala/jorlan/` covering transient, non-transient, and plain exception branches.

**T5 — `MemorySearch` scope coverage gaps** [Medium]

`Workspace` and `Private` memory scopes are never exercised in integration tests. Add scope-specific assertions to
`MemoryRepositorySpec`.

**T6 — Search pagination never tested** [Medium]

No test exercises `page > 0`. A single additional assertion in any existing search spec covers the pagination offset
path.

**T7 — `OrchestratorIdentity` has no CRUD or codec test** [Low]

**T8 — `MemoryRepository.getById` never called in any test** [Low]

---

## Quick Wins

Items that can be fixed in under 30 minutes each:

| #   | Item                             | Where                               | Fix                                                                           |
|-----|----------------------------------|-------------------------------------|-------------------------------------------------------------------------------|
| Q1  | Null-safe `e.getMessage`         | `Jorlan.scala:52-53`                | `Option(e.getMessage).getOrElse(e.getClass.getName)`                          |
| Q2  | `userByEmail` equality fix       | `QuillRepositories.scala:288`       | Change `.contains(lift(email))` to `== lift(email)`                           |
| Q3  | `getPendingJobs` SQL predicate   | `QuillRepositories.scala:1042`      | Add `&& _.scheduledAt <= lift(now)` to Quill filter                           |
| Q4  | `MappedEncoding` decode comments | `quillUtil.scala:122-166`           | Add block comment explaining the Quill interop constraint                     |
| Q5  | `ConnectionId.randomZIO`         | `ids.scala:367`                     | Add `val randomZIO: UIO[ConnectionId] = ZIO.randomWith(...)`                  |
| Q6  | Enum codec roundtrip suite       | `DomainSpec.scala`                  | Parametric test asserting `encode(x) >>= decode == Right(x)` for all 11 enums |
| Q7  | Pagination offset test           | Any existing search spec            | Add one assertion with `page = 1, pageSize = 1` against a seeded dataset      |
| Q8  | `ArtifactSearch` scaladoc        | `repository.scala`                  | Add comment: cross-workspace search is not supported                          |
| Q9  | `JorlanError.apply` comment      | `errors.scala:32-39`                | Document the message-discard behaviour when cause is a `JorlanError`          |
| Q10 | `replaySession` LIMIT            | `QuillRepositories.scala:1015-1023` | Add `.take(limit)` or SQL `LIMIT` clause; default 1000                        |

---

## Before the GraphQL API is Added

These items are substantially cheaper to address now than after the GraphQL resolver surface is published. Each one
either changes a public API shape or requires threading new concerns through every resolver.

**B1 — Module boundary fix (A1, A2)** [Critical]

Moving Quill/HTTP dependencies out of `model` and infrastructure config out of `jorlan.model` changes the dependency
graph that GraphQL resolvers will be compiled against. Do this before any resolver is written.

**B2 — Application service layer with event log calls (A3)** [Critical]

Every GraphQL mutation will need to write to the event log. Building `AgentService`, `ConversationService`, and
`PermissionService` now — each wrapping a repository call followed by `EventLogService.log` — means resolvers can
delegate to services directly. Adding this after resolvers exist requires touching every resolver.

**B3 — HikariCP pool lifecycle (`ZLayer.scoped`)** (F2) [Critical]

If pool construction is not wrapped in `ZLayer.scoped` before the application's ZIO runtime is fully exercised under
test, shutdown leaks will be masked. Fix this before integration tests run against the full server.

**B4 — Permission management completeness (A5)** [High]

`deleteRole`, `deletePermission`, and `getExpiredApprovalRequests` are missing from `PermissionRepository`. GraphQL
mutations for permission management will be incomplete without them.

**B5 — API contract correctness for `sorts` (F4)** [High]

The `sorts: List[Sort]` field on all `Search` case classes implies multi-column sort support. GraphQL clients will pass
multiple sorts and expect them to be honoured. Either implement multi-column ORDER BY or narrow the API to
`sorts: Option[Sort]` before the schema is published.

**B6 — `CapabilityName` type safety (A7)** [Medium]

A capability name passed through a GraphQL mutation as a plain `String` will silently create a dead grant if it does not
match a known capability. Introducing an `opaque type` or validation at construction before the API is published means
the GraphQL schema can surface validation errors rather than silently accepting invalid inputs.

**B7 — `AuthServerSpec` integration tests (T1, T2)** [Critical]

Security boundary coverage should exist before the auth paths are exposed through GraphQL. Adding these tests now also
validates that the `ZLayer.scoped` pool fix (B3) works end-to-end.

---

## Summary Statistics

**Issues by severity:**

| Severity  | Count  |
|-----------|--------|
| Critical  | 10     |
| High      | 7      |
| Medium    | 10     |
| Low       | 8      |
| **Total** | **35** |

**Issues by source agent (deduplicated cross-references increase confidence):**

| Agent                      | Findings contributed | Notable cross-confirmations                                                    |
|----------------------------|----------------------|--------------------------------------------------------------------------------|
| Agent 1 — Functional Scala | 8                    | `getPendingJobs`, `exec`/`ds` duplication (also Agent 2, 3)                    |
| Agent 2 — Code Simplicity  | 6                    | `getPendingJobs` (also Agent 1, 3), `exec`/`ds` duplication (also Agent 1)     |
| Agent 3 — Performance      | 7                    | `getPendingJobs` (also Agent 1, 2), `login` double round-trip, `replaySession` |
| Agent 4 — Architecture     | 9                    | Module boundary (unique), event log gap (unique), permission gaps (unique)     |
| Agent 5 — Test Coverage    | 8                    | Auth 0% (unique), enum codec gaps (unique)                                     |

**Three findings confirmed by multiple independent agents (highest confidence):**

1. `getPendingJobs` full-table-scan post-filter — Agents 1, 2, 3
2. `exec`/`ds` copy-paste across all repository classes — Agents 1, 2
3. Secondary sorts silently discarded — Agents 1, 3, 4

**Issues by area:**

| Area                      | Count  |
|---------------------------|--------|
| Architecture / Design     | 8      |
| Functional Correctness    | 6      |
| Performance               | 6      |
| Code Quality / Simplicity | 5      |
| Test Coverage             | 10     |
| **Total**                 | **35** |
