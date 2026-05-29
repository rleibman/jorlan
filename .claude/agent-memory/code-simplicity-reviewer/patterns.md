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
- `JorlanAPI`: `ArgBuilder` for `Long`-parameter queries/mutations (e.g. `user: Long => ...`) uses Caliban's flattened-argument convention, not a wrapper `input:` type. The scaladoc in `GraphQLApiSpec` correctly explains this. No issue, but worth preserving the comment.
- `Jorlan.scala`: Error handler in `handleErrorCause` has an `e: AuthError` catch-all branch before the final `case e` branch; the final `case e` is still needed for non-AuthError throwables, so this is correct, not dead code.

## Phase 8 Agent Session Runtime Observations (2026-05-29)
- `EventLog(id=EventLogId.empty, ..., occurredAt=now)` construction is repeated 6 times across AgentRunner, AgentSessionManager, OllamaModelGateway, HumanApprovalNotifier. A private helper `def eventEntry(eventType, actorId, agentId, sessionId, resource)` on EventLogService or a local builder would eliminate the pattern.
- `AgentSessionManagerImpl` injects `AgentZIORepository` directly (bypassing `AgentService`), while the scaladoc says "delegates to AgentService". This is an architectural layer violation — the service layer should call `AgentService`, not the repository directly.
- `listSessions` in `JorlanAPI` calls `getSession(AgentSessionId.empty).map(_.toList)` which looks up exactly one session by the zero-ID sentinel and returns at most 1 result. It should call `AgentSessionManager.searchSessions` (or a new list method) with pagination params. This is a critical functional stub bug.
- `AgentSessionManagerImpl.createSession` calls `sessionHub.getOrCreate(AgentSessionId.empty)` (line 95) — this pre-creates a hub slot for the zero-ID, which is never used. The real hub is created on line 107 after `saved.id` is known. Line 95 should be deleted.
- `AgentRunnerImpl.live` uses an explicit multi-parameter lambda inside `ZLayer.fromFunction` — the same factories in the codebase use `new Impl(arg1, arg2, arg3)` directly with `ZLayer.derive` or constructor reference. The explicit lambda is verbose but not wrong.
- `setTrace` in CommandHandler has a dead match arm `case _ => Level.INFO` (line 197) — the `valid` Set guard above guarantees only "none"|"error"|"warning"|"info"|"debug" enter the match, so the wildcard is unreachable.
- `handleNewSession` in CommandHandler parses the session ID by chaining `.asObject`/`.get`/`.asNumber` on `Json.ast` (lines 155-161). Using a zio-json case class decoder would be safer, less verbose, and consistent with how `SubscriptionClient` decodes `NextPayload`.
- `import scala.math.BigDecimal.javaBigDecimal2bigDecimal` (CommandHandler line 17) is needed because `Json.Num.value` returns `java.math.BigDecimal` and the conversion enables `.toLong`. It is used on line 160. Not dead code, but a comment would clarify why the import is needed.
- `SubscriptionClient` has four nested private case classes (`WsMsg`, `ChunkData`, `ResponseData`, `NextPayload`) that exist only to decode the `next` payload. `ResponseData` and `NextPayload` could be replaced by a single direct decoder for `ChunkData` via `Json.Obj` lens, or at minimum merged into two classes.
- `OllamaModelGateway.streamedResponse` calls `Clock.instant` inside `ensuring` via a closure (not inside the for-comp). Both the start and end events call `Clock.instant` separately — the `now` captured at start is not reused for the completion event. This is intentional (measures actual end time) but the `ensuring` closure pattern is slightly unusual; a note is fine.

## Phase 7 Shell Interface Observations (2026-05-28)
- `resolveCredentials` in `JorlanShell`: two nearly-identical prompt arms for email and password; each does `setInputPrompt` + `readLine` + cancel-check. Extracted helper `promptField(label)` would remove duplication.
- `AuthClient.login` / `GraphQLClient.execute`: `private def backend = HttpClientZioBackend()` is a `def` (not `val`) — creates a new `HttpClientZioBackend` on every call. Within a single `ZIO.scoped` this is one creation per call, which is intended, but the naming is confusing (reads like a field). No correctness bug; note for future review.
- `AuthClient.login`: nested `tokenOpt match` inside `if (resp.code.isSuccess)` block adds 3 levels of nesting; could be flattened with `for`-comprehension or `EitherT`-style chaining.
- `JorlanClientDecoders`: `ArgEncoder` instances all follow `(id: T) => ArgEncoder.long.encode(id.value)` — 7 identical bodies differing only in type. No macro solution due to opaque types; current pattern is acceptable but worth noting.
- `JorlanClient.scala` is Caliban-generated code — do not suggest refactors there.
- `ShellCommand.parse`: `return` on line 33 is non-idiomatic Scala; should be an `if/else` or guard match. Minor.
- `expandOne` in `JorlanScreen`: `MessageKind.Raw` has two match arms — one guard at the top (early return) and one dead arm inside the main match. The dead arm `case MessageKind.Raw => ...` on line 316 is unreachable; remove it.
- `wordWrap` in `JorlanScreen`: uses mutable `ArrayBuffer` + `StringBuilder`; idiomatic Scala would use an accumulator-based recursive function or `foldLeft`. Acceptable for performance in a render-loop context, but the mixing styles (functional outer, mutable inner) is notable.
- `CommandHandler.setTrace`: validates against a `Set` but doesn't actually change any log level — stubbed. The `@annotation.unused` on `handleMessage`'s `text` parameter signals this pattern is intentional for Phase 7.
- `ShellCommandSpec`: 14 separate `test(...)` blocks each with a single `assertTrue`; could be table-driven with `check(Gen.fromIterable(...))` but current style is clear and explicit. No action needed.
- `ShellConfigSpec`: `val cfg = ShellConfig()` repeated in every test; could be a `val` in suite scope, but tests are short enough that it's not a problem.

## Simplification Patterns Applied / Suggested
- Shared `runQ` helper in a base Quill repo class would reduce per-method boilerplate significantly
- Inline helper `enumDecoder[E <: reflect.Enum](valueOf: String => E)` could unify all 12 enum decoders
- `QuillRepositories.live` ZLayer fan-out could use `ZLayer.succeedEnvironment(ZEnvironment(...))` to avoid 9 separate `ZLayer.succeed` concatenations
