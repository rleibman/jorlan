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

## Phase 3 GraphQL API Branch Observations (2026-05-27)
- `QuillRepositories.scala` (~1500 lines): the dominant complexity driver is the `search` method duplication — each of the 9 repos has a sort-dispatch match block that repeats the full filtered query per case. The base query (filters + pagination) is identical within each repo; only the `.sortBy` call differs. This is the single highest-impact refactoring opportunity.
- `getPendingJobs` in `QuillSchedulerRepository` (line 1041): the `scheduledAt <= now` check is done in-memory after SQL fetch. The Quill query already fetches only `Pending` jobs, but `now` is filtered in Scala after the result set is returned. This means all pending jobs are fetched from DB regardless of count. For large job tables this is a scalability issue; the filter should be pushed to SQL.
- `upsertSession`, `upsertJob`, `upsertTrigger`, `upsertWorkspace`, `upsertCapabilityGrant` all share the same insert-vs-update dispatch: `if (x.id.value == 0L)`. This pattern is repeated 5 times. A named helper or a typeclass `HasId` approach could unify it.
- `private def exec[A]` is copy-pasted identically into all 9 Quill repo classes (9 duplicates of 2 lines each).
- EventLogRepository.search (line 945): the `from`/`to` Instant range filters are pushed to SQL correctly via `.filter`, unlike in some earlier reviews. No in-memory truncation issue here (previous concern was resolved).

## Phase 3 EventLog Observations (2026-05-26)
- `InMemoryEventLogRepo` in unit tests intentionally mirrors the current `QuillEventLogRepository.search` semantics so unit tests exercise the same filter/order/limit behavior as production code without hitting the database. The duplication is acceptable but worth watching if search parameters expand.
- `EventLogServiceImpl.replay` now relies on the repository's search ordering/filters rather than performing an additional in-memory re-sort. The important contract to keep documented is the ordering returned by `repo.search`, since `replay` depends on that behavior.
- Correlation ID is stored only in ZIO log annotations (fibre-local); it is NOT persisted to the EventLog row, even though the service scaladoc implies it is captured. This is a documented gap — no `correlationId` column in EventLogRow, no `correlation_id` in the DB schema.
- `EventLogServiceSpec` and `EventLogServiceIntegrationSpec` each declare `private val now = Instant.now()` at class level, evaluated once at spec construction. Tests that share the same `now` across parallel runs are not affected since the value is frozen. Pattern is fine.
- All five test files in the EventLog suite use positional `EventLog[T](EventLogId.empty, …, None, None, None, None, None, now)` constructors with 6 explicit `None`s. A test helper `def emptyEvent[R](eventType, resource)` would reduce noise significantly.

## Phase 5 Capability Kernel Observations (2026-05-27)
- `CapabilityEvaluatorImpl.evaluate` uses 3-level nested `if/else` inside a for-comprehension; can be flattened with a recursive helper or short-circuit ZIO chain.
- `ApprovalPolicyEngineImpl.decide` has 4 `-> AuthorizationResult.Allowed` cases in a row; grouping them saves 3 lines and signals intent more clearly.
- `ApprovalPolicyEngineImpl` uses `isInstanceOf[AuthorizationResult.Denied]` in test assertions — use `==` or pattern match for exhaustive safety.
- `CapabilityKernelSpec`: `new RiskClassifierImpl` instantiated 18 times in separate tests; should be a `val` shared in `riskClassifierSuite` scope.
- `CapabilityKernelSpec`: 7 `PendingApproval` tests use identical `result match { case PendingApproval(_, Mode) => true; case _ => false }` pattern; `assertTrue(result.isInstanceOf[...])` is insufficient (doesn't check mode); a helper `assertPending(mode)` would deduplicate.
- `ApprovalServiceImpl.expireStaleRequests` fetches all expired requests then maps over them sequentially; `ZIO.foreachPar` or a bulk-expire DB method would be more efficient for large sets.
- `ApprovalServiceImpl.live` ZLayer uses explicit lambda instead of `ZLayer.derive` — acceptable but verbose.
- `RiskClassifierImpl.classify` uses `Option.get(_._2)` via `.map(_._2)` after `.find`; idiom is correct and readable.
- `loadExistingApprovals` uses `grant.approvalMode == ApprovalMode.Once || grant.approvalMode == ApprovalMode.Session` — a named helper `requiresPreload` on `ApprovalMode` or a `Set` membership check would be cleaner.

## Phase 6 GraphQL API Observations (2026-05-27)
- `JorlanAPI`: User-repo queries use `.mapError(JorlanError(_))` while PermissionService queries use `.mapError(identity)` — inconsistent wrapping because `PermissionService` already returns `IO[JorlanError, _]` but `UserZIORepository` returns `IO[RepositoryError, _]`. The `JorlanError(_)` call is actually redundant in the user case because `RepositoryError extends JorlanError` — `.mapError(identity)` would compile and unify the error type without the wrapping. Worth verifying; if confirmed, all `.mapError(JorlanError(_))` calls in JorlanAPI can be changed to `.mapError(identity)`.
- `JorlanAPI`: `createUser` and `updateUser` both call `Clock.instant` + `upsert` in a for-comprehension; only 2 lines each so no extraction needed, but the `now` timestamp passed to upsert for `updatedAt` is wrong on create — both `createdAt` and `updatedAt` are set to `now`, which is fine, but on update the `createdAt` is also overwritten with `now` which is likely a bug (see finding).
- `JorlanAPI`: The `QuickAdapter(interp)` is instantiated twice inside `JorlanRoutes.routes` — once for `.handlers.api`, once for `.handlers.webSocket`. Should be a single `val adapter = QuickAdapter(interp)`.
- `JorlanAPI`: `ArgBuilder` for `Long`-parameter queries/mutations (e.g. `user: Long => ...`) uses Caliban's flattened-argument convention, not a wrapper `input:` type. The scaladoc in `GraphQLApiSpec` correctly explains this. No issue, but worth preserving the comment.
- `GraphQLApiSpec`: `extractLongField` uses a regex over the raw `.toString` of a Caliban `ResponseValue`. This is fragile — if the `ResponseValue.toString` format changes the helper silently returns `0L`. A safer alternative is parsing the result as JSON using `zio-json`.
- `GraphQLApiSpec`: `JorlanAPI.api.interpreter.orDie` is called at the start of every single test (9 times). Should be lifted to `suite`-level using `ZIO.serviceWithZIO` or provided once via `ZLayer`.
- `Jorlan.scala`: Error handler in `handleErrorCause` has an `e: AuthError` catch-all branch before the final `case e` branch; the final `case e` is still needed for non-AuthError throwables, so this is correct, not dead code.

## Simplification Patterns Applied / Suggested
- Shared `runQ` helper in a base Quill repo class would reduce per-method boilerplate significantly
- Inline helper `enumDecoder[E <: reflect.Enum](valueOf: String => E)` could unify all 12 enum decoders
- `QuillRepositories.live` ZLayer fan-out could use `ZLayer.succeedEnvironment(ZEnvironment(...))` to avoid 9 separate `ZLayer.succeed` concatenations
