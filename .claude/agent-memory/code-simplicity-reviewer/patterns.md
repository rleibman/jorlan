---
name: Jorlan Codebase Patterns
description: Recurring idioms, duplication hotspots, and conventions confirmed across Phases 0-2 code review
type: project
---

## Confirmed Conventions
- Braces required (`-old-syntax`, `-no-indent`), `-Yexplicit-nulls`, `-Werror`
- Opaque Long-backed IDs in `model/domain/ids.scala` — 19 types, each identical except name
- Domain enums use `.values.find(_.toString == s).toRight(...)` for JSON decoding — 12 enums all identical pattern
- All Quill repositories: every method ends with `.provideLayer(ds).mapError(RepositoryError(_))`
- `onConflictUpdate` tuples use multi-line `(t, e) =>` style (Quill macro requirement)
- `RepositoryTask[A] = IO[RepositoryError, A]` is the universal ZIO repo effect alias
- ZIO repo traits are thin aliases: `trait XxxZIORepository extends XxxRepository[RepositoryTask]`
- `QuillRepositories.live` constructs all 9 repos from one `QuillCtx`, then fans out into ZLayer per repo type

## Duplication Hotspots
1. **ids.scala** — 19 identical companion object bodies; only the type name varies. Each needs 6 lines. A macro or typeclass approach can't be used due to opaque type limitations, but a code-comment noting the pattern is sufficient.
2. **Enum JSON decoders** — 12 enums, each has the same 3-line decoder body. Could use a shared inline helper.
3. **Quill repo methods** — Every method wraps with `.provideLayer(ds).mapError(RepositoryError(_))`. A private helper method `run[A](q: => Task[A])` in a base class could eliminate this repetition.
4. **`onConflictUpdate` formatting** — multi-line tuple style is forced by Quill's inline macro; not a style choice.

## Bugs / Design Notes Found
- `ChannelIdentity.id` is typed as `UserId` but is the PK of the channel_identity table — should logically be its own opaque ID type (flagged as design question in review)
- `Permission.id` is typed as `RoleId` — same concern
- `EventLogRepository.search` applies `from`/`to` filters in-memory after SQL `take(limit)` — this means date filtering happens on an already-truncated result set; the limit applies before the date filter (potential correctness bug)
- `ConfigurationServiceImpl.appConfig` is a `lazy val` on a `new ConfigurationService { ... }` — calling `.appConfig` twice returns the same cached `IO`, which is fine since `orDie` makes it a `UIO` internally but the declared type is `IO[ConfigurationError, AppConfig]`

## Phase 3 EventLog Observations (2026-05-26)
- `InMemoryEventLogRepo` in unit tests intentionally mirrors the current `QuillEventLogRepository.search` semantics so unit tests exercise the same filter/order/limit behavior as production code without hitting the database. The duplication is acceptable but worth watching if search parameters expand.
- `EventLogServiceImpl.replay` now relies on the repository's search ordering/filters rather than performing an additional in-memory re-sort. The important contract to keep documented is the ordering returned by `repo.search`, since `replay` depends on that behavior.
- Correlation ID is stored only in ZIO log annotations (fibre-local); it is NOT persisted to the EventLog row, even though the service scaladoc implies it is captured. This is a documented gap — no `correlationId` column in EventLogRow, no `correlation_id` in the DB schema.
- `EventLogServiceSpec` and `EventLogServiceIntegrationSpec` each declare `private val now = Instant.now()` at class level, evaluated once at spec construction. Tests that share the same `now` across parallel runs are not affected since the value is frozen. Pattern is fine.
- All five test files in the EventLog suite use positional `EventLog[T](EventLogId.empty, …, None, None, None, None, None, now)` constructors with 6 explicit `None`s. A test helper `def emptyEvent[R](eventType, resource)` would reduce noise significantly.

## Simplification Patterns Applied / Suggested
- Shared `runQ` helper in a base Quill repo class would reduce per-method boilerplate significantly
- Inline helper `enumDecoder[E <: reflect.Enum](valueOf: String => E)` could unify all 12 enum decoders
- `QuillRepositories.live` ZLayer fan-out could use `ZLayer.succeedEnvironment(ZEnvironment(...))` to avoid 9 separate `ZLayer.succeed` concatenations
