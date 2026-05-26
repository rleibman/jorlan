# Phase 3 Code Review — EventLogService & CorrelationId

**Reviewed by**: Multi-agent review (Functional Scala, Pattern Recognition, Code Simplicity, Test Coverage)
**Date**: 2026-05-26
**Branch**: `phase-3/graphql-api`
**Scope**: `EventLogService` ZIO layer, `CorrelationId` propagation, in-memory and integration tests

---

## Executive Summary

Phase 3 delivers a clean, well-structured `EventLogService` with idiomatic ZIO layering and a correct `CorrelationId` implementation. The service abstraction is solid and the two-tier testing strategy (in-memory unit + Testcontainers integration) is the right approach.

However, three issues undermine correctness and must be resolved before this code is relied upon:

1. **`from`/`to` date filters are applied in-memory after the SQL `LIMIT`** — a correctness bug that silently returns wrong results under common filter combinations.
2. **The scaladoc on `EventLogService.log` falsely claims correlation ID is persisted** — it is not; the `correlationId` field does not exist on `EventLog`, and `EventLogServiceImpl.log` never reads the fiber annotation.
3. **The shared-state test harness produces order-dependent tests** that may pass or fail depending on execution order.

Test coverage for the `from`/`to` filter path is zero at every layer, meaning the correctness bug has no safety net.

Overall health: **Needs Attention** — the design foundation is sound but these three items must be resolved before Phase 4 builds on top of it.

---

## What's Working Well

- **Clean service abstraction**: no DB or Quill details leak through `EventLogService`. The trait boundary is correctly drawn.
- **Idiomatic ZIO layering**: `ZLayer.fromFunction` usage is correct throughout.
- **`CorrelationId` implementation**: `withNew` using `ZIO.randomWith(_.nextUUID)` and `ZIO.logAnnotate` is idiomatic and correct.
- **`EventLogRow` isolation**: `toRow`/`fromRow` correctly confine Quill internals to the `db` module.
- **Append-only enforcement**: the interface correctly exposes no delete or update operations.
- **`EventLogServiceImpl` decomposition**: 41 lines with three single-responsibility methods is a model for future services.
- **Two-tier test strategy**: using `InMemoryEventLogRepo` for unit tests and Testcontainers for integration tests is the right pattern for this stack.

---

## Findings by Severity

### Critical

#### C1 — Test harness uses impure code inside `ZIO.succeed` with shared mutable state

**Agents**: 1, 2
**Files**:
- `server/src/test/scala/jorlan/service/EventLogServiceSpec.scala`

`InMemoryEventLogRepo` uses `AtomicLong` + `mutable.ArrayBuffer` + `synchronized` blocks wrapped in `ZIO.succeed`. `ZIO.succeed` signals a pure, non-blocking value — wrapping side-effectful, blocking synchronized code in it is incorrect: it bypasses ZIO's thread pool management and can cause subtle ordering bugs under the ZIO runtime.

Additionally, all tests share a single `InMemoryEventLogRepo` instance. Events accumulate across test cases, creating hidden ordering dependencies. The assertion `allEvents.length >= 2` only passes because prior tests have already inserted events into the shared store.

**Required action**: Replace `AtomicLong` + `mutable.ArrayBuffer` + `synchronized` with `Ref[List[EventLog[Json]]]`. Use `ZIO.attempt` (or `attemptBlocking`) for any remaining synchronized code. Provide each test suite with a fresh `Ref` via `ZLayer` scoping rather than a shared object.

---

### Major

#### M1 — `from`/`to` date filters applied in-memory after SQL `LIMIT` — correctness bug

**Agents**: 2, 3
**Files**:
- `server/src/main/scala/jorlan/service/EventLogServiceImpl.scala` (or `QuillEventLogRepository`)
- `integration/src/test/scala/jorlan/db/EventLogServiceIntegrationSpec.scala` (no covering test)

The SQL query applies `LIMIT` before the in-memory `from`/`to` filter. If the `limit` rows returned by the DB all fall outside the requested time window, the caller receives an empty list even though matching rows exist further in the result set. This is a silent data-loss bug in any filtered query.

**Required action**: Move `from`/`to` into the SQL `WHERE` clause. At minimum, add a prominent `// BUG:` comment until the fix is in place. Add a regression test: insert events at `t-2s`, `t`, `t+2s`; query with `from=t-1s, to=t+1s`; assert exactly the middle event is returned.

#### M2 — Scaladoc falsely claims correlation ID is persisted; it is not

**Agents**: 1, 2, 3
**Files**:
- `model/src/main/scala/jorlan/service/EventLogService.scala` (scaladoc on `log`)
- `server/src/main/scala/jorlan/service/EventLogServiceImpl.scala` (`log` method body)
- `integration/src/test/scala/jorlan/db/EventLogServiceIntegrationSpec.scala` (misleading test name)

The scaladoc states "The correlation ID is automatically captured from the current ZIO log annotations if present." Neither `EventLog` nor `EventLogRow` has a `correlationId` field, and `EventLogServiceImpl.log` never calls `CorrelationId.get`. The integration test named `"correlation id propagates through log call"` only validates fiber-annotation scoping, not persistence.

**Required action**:
1. Replace the scaladoc claim with a `// TODO: capture correlationId from ZIO log annotations and persist it in EventLog` comment.
2. Rename the integration test to `"correlation id is scoped to fiber annotation (not yet persisted)"` or remove it.
3. Add `correlationId: Option[String]` to `EventLog` and wire it through `EventLogServiceImpl.log` when the persistence requirement is implemented.

#### M3 — `replay` fetches `Int.MaxValue` rows and re-sorts in-memory; undocumented and uncapped

**Agents**: 1, 2, 3
**Files**:
- `server/src/main/scala/jorlan/service/EventLogServiceImpl.scala` (`replay` method)

`replay` passes `Int.MaxValue` as the `limit` argument, bypassing all `EventLogFilter.limit` enforcement, then re-sorts the descending DB result ascending in-memory. The re-sort is correct but the contract is completely undocumented. If the descending `ORDER BY` is ever changed in `QuillEventLogRepository`, the re-sort becomes both incorrect and invisible.

**Required action**:
1. Add an `ORDER BY occurredAt ASC` repository query method (`replaySession` or similar) and use it in `replay`, eliminating the in-memory sort.
2. If the in-memory sort must remain temporarily, add an explicit comment: `// search returns DESC; replay requires ASC — re-sort here until replaySession is added`.
3. Replace `Int.MaxValue` with a named constant (e.g., `ReplayRowCap = 100_000`) and enforce it in `replay`.

#### M4 — Service implementation depends on concrete `db`-module type, not the abstract repository

**Agents**: 2
**Files**:
- `server/src/main/scala/jorlan/service/EventLogServiceImpl.scala`

`EventLogServiceImpl` depends on `EventLogZIORepository` (a `db`-module Quill-aware subtype) rather than the abstract `EventLogRepository[RepositoryTask]`. This means the F\[\_\] abstraction in `repository.scala` provides no decoupling: the unit test's `InMemoryEventLogRepo` must also extend a `db`-module type, and swapping the persistence backend requires changing the service wiring.

**Required action**: Change the `EventLogServiceImpl` constructor to accept `EventLogRepository[RepositoryTask]` (the abstract type). The `ZLayer` provider in `server` can still summon the Quill implementation; the service itself should be agnostic.

---

### Minor

#### m1 — `append` silently drops JSON encoding failures

**Agents**: 2, 3
**Files**:
- `server/src/test/scala/jorlan/service/EventLogServiceSpec.scala` (`InMemoryEventLogRepo.append`)
- `server/src/main/scala/jorlan/db/QuillEventLogRepository.scala` (`toRow`)

Both `InMemoryEventLogRepo.append` and `QuillEventLogRepository.toRow` silently discard JSON encoding failures via `.toOption`. An append-only audit trail that silently drops events is dangerous for observability.

**Required action**: Change both to return a `ZIO.fail(EncodingError(...))` on encoding failure rather than silently ignoring it. Add a test asserting that an un-encodable payload surfaces as an error.

#### m2 — Misleading scaladoc on `EventLogService.log` (correlation ID)

Covered under **M2** above. Recorded here as a separate line item to track the doc fix independently from the implementation fix.

#### m3 — `EventLogRepository.search` should accept `EventLogFilter` directly

**Agents**: 3
**Files**:
- `server/src/main/scala/jorlan/service/EventLogServiceImpl.scala` (`query`)
- `model/src/main/scala/jorlan/repository/repository.scala` (TODO at line 19)

`EventLogServiceImpl.query` manually unpacks all five `EventLogFilter` fields when calling `search`. The repository's own TODO comment at line 19 already flags this. Phase 3 is the natural place to resolve it.

**Required action**: Change `EventLogRepository.search` to accept `EventLogFilter` directly. Remove the manual field unpacking in `EventLogServiceImpl.query`.

#### m4 — No `logCorrelated` helper on `EventLogService` companion

**Agents**: 2
**Files**:
- `model/src/main/scala/jorlan/service/EventLogService.scala`

The companion object provides `log`/`query`/`replay` accessors but no `logCorrelated` helper that wraps a block with `CorrelationId.withNew` automatically. Callers must remember to combine these manually, which is easy to forget.

**Required action**: Add `def logCorrelated[R](event: EventLog[?]): ZIO[EventLogService, ?, Unit]` (or a `withCorrelation` wrapper) to the companion once **M2** is resolved.

#### m5 — Verbose `EventLog` constructor repeated 15+ times across test files

**Agents**: 3
**Files**:
- `server/src/test/scala/jorlan/service/EventLogServiceSpec.scala`
- `integration/src/test/scala/jorlan/db/EventLogServiceIntegrationSpec.scala`
- `integration/src/test/scala/jorlan/db/RepositorySpec.scala`

Every test constructs `EventLog[T](EventLogId.empty, EventType.X, None, None, None, None, None, now)` inline. This is fragile — adding a field breaks all test files.

**Required action**: Extract a `testEvent(eventType: EventType)` helper in a shared `TestFixtures` object and use it in all three specs.

#### m6 — `EventLogFilter.limit` has no upper-bound validation

**Agents**: 1, 2
**Files**:
- `model/src/main/scala/jorlan/service/EventLogService.scala` (`EventLogFilter`)

A caller (including a future GraphQL endpoint) can pass `limit = Int.MaxValue` or `limit = -1` and the value flows silently through to the DB query.

**Required action**: Validate `limit` in `EventLogFilter` construction (or at the service layer): reject values `<= 0`, cap at a configurable maximum (e.g., `MaxQueryLimit = 10_000`). Return a `ZIO.fail(ValidationError(...))` for out-of-range values.

---

### Nits

#### n1 — `now = Instant.now()` captured at class-load time in test specs

**Agents**: 1, 2, 3
**Files**:
- `server/src/test/scala/jorlan/service/EventLogServiceSpec.scala`
- `integration/src/test/scala/jorlan/db/EventLogServiceIntegrationSpec.scala`

`val now = Instant.now()` is evaluated when the class is loaded, not inside a ZIO effect. For time-ordered queries this is fine in practice but semantically wrong — if a test suite is slow, events inserted "at now" may have a stale timestamp.

**Recommended fix**: Use `val now = Instant.parse("2024-01-15T12:00:00Z")` (a fixed literal) for full determinism.

#### n2 — `RepositorySpec` order and limit assertions are weaker than needed

**Agents**: 1
**Files**:
- `integration/src/test/scala/jorlan/db/RepositorySpec.scala` (eventLogSuite)

- `"append and search events"` does not assert descending order.
- `"limit caps the result set"` uses `<= 2` — this would pass even if the limit was not applied, as long as fewer than 2 events happened to be in the store.

**Recommended fix**: Assert `result.map(_.occurredAt) == result.map(_.occurredAt).sorted(Ordering[Instant].reverse)` for order, and `== 2` (not `<= 2`) for the limit test.

#### n3 — `svc.log(e1) *> svc.log(e2) *> svc.log(e3)` verbosity

**Agents**: 3
**Files**:
- `server/src/test/scala/jorlan/service/EventLogServiceSpec.scala`

Can be written as `ZIO.foreachDiscard(List(e1, e2, e3))(svc.log)`.

#### n4 — `CorrelationId` tests share `serviceLayer` unnecessarily

**Agents**: 1
**Files**:
- `server/src/test/scala/jorlan/service/EventLogServiceSpec.scala`

`CorrelationId` tests use `serviceLayer` but have no dependency on it. This couples unrelated tests.

**Recommended fix**: Give `CorrelationId` tests their own minimal layer (or no layer).

---

## Cross-Cutting Patterns

Three themes appear independently across multiple agents, confirming they are not marginal issues:

| Theme | Agents | Summary |
|---|---|---|
| `from`/`to` filter correctness | 2, 3, 4 | All three independently identified the in-memory post-LIMIT filter as a correctness bug with zero test coverage |
| CorrelationId not persisted | 1, 2, 3 | All three flagged the gap between scaladoc claim and implementation; integration test name amplifies the confusion |
| Shared mutable state in tests | 1, 2, 4 | All three flagged test isolation problems; Agent 4 noted it directly causes the zero-coverage gap for the order guarantee |

The co-occurrence of the `from`/`to` bug and its zero test coverage is the most concerning pattern. It means the bug could survive into Phase 4 undetected.

---

## Contradictions and Ambiguities

| Item | Agents | Tension | Assessment |
|---|---|---|---|
| `synchronized` in `InMemoryEventLogRepo` | 1 (wrong), 3 (correct) | Agent 3 says `synchronized` is "correct for an ArrayBuffer"; Agent 1 says wrapping it in `ZIO.succeed` is wrong | Both are right in different dimensions. The `synchronized` call itself is valid; the problem is the `ZIO.succeed` wrapper, not the synchronization. Agent 1's fix (`Ref[List[...]]` + `ZIO.attempt`) is the correct ZIO-idiomatic resolution. |
| Where to fix the in-memory filter | 2 (SQL WHERE), 3 (comment first) | Agent 2 says push into SQL; Agent 3 says add a comment as a minimum | Not a real contradiction — Agent 3's "at minimum" language is a pragmatic fallback. The correct fix is Agent 2's SQL WHERE approach. |

---

## Test Coverage Summary

| Component | Estimated Coverage | Key Zero-Coverage Paths |
|---|---|---|
| `EventLogService` trait + filter | ~80% | Combined filters, time-range boundary conditions |
| `CorrelationId` | ~60% | Scope isolation, nesting, UUID uniqueness |
| `EventLogServiceImpl` | ~85% | `replay` with unordered input |
| `QuillEventLogRepository` | ~60% | `from`/`to` filters; `actorId` roundtrip; error paths |

---

## Summary Statistics

| Severity | Count |
|---|---|
| Critical | 1 |
| Major | 4 |
| Minor | 6 |
| Nit | 4 |
| **Total** | **15** |

**Findings by agent (before deduplication)**:

| Agent | Raw findings |
|---|---|
| Functional Scala (1) | 8 |
| Pattern Recognition (2) | 8 |
| Code Simplicity (3) | 8 |
| Test Coverage (4) | 10 |

**Findings by area**:

| Area | Findings |
|---|---|
| Test harness correctness | C1, m5, n2, n3, n4 |
| `from`/`to` filter correctness | M1 |
| CorrelationId persistence claim | M2, m4 |
| `replay` design | M3 |
| Repository abstraction | M4 |
| Silent error swallowing | m1 |
| Filter API | m3, m6 |
| Test timestamps | n1 |

---

## Prioritized Action List

Actions are ordered by: severity, then blast radius (how much Phase 4 work will be affected if left unresolved).

1. **[C1] Fix test harness: replace `ArrayBuffer` + `synchronized` + `ZIO.succeed` with `Ref[List[...]]`; isolate per-test state.**
   Affects `EventLogServiceSpec`. This is a prerequisite for trusting any unit test results.

2. **[M1] Move `from`/`to` date filters into the SQL `WHERE` clause.**
   Affects `QuillEventLogRepository`. Add the regression test described in M1 simultaneously.

3. **[M2] Remove the false scaladoc claim about correlation ID persistence; rename the misleading integration test.**
   Affects `EventLogService.scala` and `EventLogServiceIntegrationSpec.scala`. Low effort, high confusion-prevention value.

4. **[M3] Replace `Int.MaxValue` sentinel and in-memory sort in `replay` with a named cap constant and an `ORDER BY ASC` repository method.**
   Affects `EventLogServiceImpl` and `QuillEventLogRepository`.

5. **[M4] Change `EventLogServiceImpl` constructor to accept the abstract `EventLogRepository[RepositoryTask]`.**
   Affects service wiring in `server`. Enables the `InMemoryEventLogRepo` in unit tests to stop depending on a `db`-module type.

6. **[m6] Add upper-bound and lower-bound validation to `EventLogFilter.limit`.**
   Affects `EventLogFilter`. Must be done before the GraphQL layer exposes this field to external callers.

7. **[m1] Change `append` encoding failures from silent `.toOption` to `ZIO.fail(EncodingError(...))`.**
   Affects `InMemoryEventLogRepo` and `QuillEventLogRepository`.

8. **[m3] Change `EventLogRepository.search` to accept `EventLogFilter` directly; remove manual unpacking in `query`.**
   Resolves the existing TODO at `repository.scala` line 19.

9. **[4-gaps] Add missing test coverage (in priority order)**:
   a. Time-range filter regression test (see M1 — do this with item 2 above).
   b. `replay` with events inserted in reverse order.
   c. Combined multi-filter query (agentId AND eventType).
   d. `query` descending-order assertion.
   e. `CorrelationId` scope isolation and `withNew` UUID uniqueness.
   f. Error path: broken `DataSource` → `RepositoryError`.
   g. `actorId` roundtrip (requires valid `UserId` FK in integration).
   h. `EventLogService` companion accessor methods.

10. **[m4] Add `logCorrelated` helper to `EventLogService` companion** once item 3 (CorrelationId persistence) is resolved.

11. **[m5] Extract `testEvent(eventType)` helper** into a shared `TestFixtures` object used by all three test files.

12. **[n1] Replace `Instant.now()` at class-load time** with a fixed `Instant.parse(...)` literal in all test specs.

13. **[n2] Strengthen `RepositorySpec` assertions**: descending-order check; `== 2` not `<= 2` for the limit test.

14. **[n3, n4] Clean up minor test style**: use `ZIO.foreachDiscard` for sequential log calls; remove `serviceLayer` dependency from `CorrelationId` tests.
