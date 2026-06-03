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

## Phase 8.3 Server Personality Observations (2026-06-01)
- `fakePersonality` inline ZLayer body is identically copy-pasted in 3 test files: `AgentRunnerSpec` (line 25-29), `JorlanAPISpec` (line 85-89), `GraphQLApiSpec` (line 35-39). Should be a `val` in `InMemoryRepositories` or `FakePersonalityService` companion in the `service` package (matching the `FakeModelGateway` pattern already used).
- `extractLong` / `extractLongField` are independently defined in `JorlanAPISpec` (line 102) and `GraphQLApiSpec` (line 260) with identical bodies. Both live in different modules (server vs integration), so sharing requires either a common test utility module or accepting the duplication. Flag for future consolidation.
- `ServerPersonalityInput` in `JorlanAPI` is structurally isomorphic to `Personality` (same 5 fields, same types). The `updatePersonality` handler reconstructs a `Personality` from it field-by-field (lines 387-394). A conversion method or `apply` on `Personality.companion` would reduce the call-site noise, but the Caliban `derives Schema.SemiAuto, ArgBuilder` requirement makes the separate type necessary — duplication is intentional.
- `getOrCreate` in `OllamaModelGateway` (lines 65-78): the previous version used `sessions.get.flatMap { map => map.get(...) match { ... } }`. New version is clean — a single `Ref.modify`-style read-then-conditional-write. The two-step `sessions.get.flatMap` is technically a TOCTOU but the scaladoc notes this; `Ref.modify` would be atomic. Low risk in practice since `getOrCreate` is the only writer.
- `PersonalityServiceImpl.live`: the `Some(json)/None` match on loaded JSON (lines 43-46) could use `Option.fold` but current form is idiomatic; no change needed.
- `showPersonality` in `CommandHandler` (lines 188-213): uses a `for` comprehension inside a `gql().foldZIO(_, json => { val text = for {...} yield ...; screen(...) })`. The inner `for` is `Option`-chained navigation of the JSON response. Style is valid but the entire lambda body is 20 lines inside `foldZIO`'s success branch. Extracting `parsePersonalityText(json: Json): Option[String]` as a private helper would reduce the nesting from 5 levels to 3.
- `import scala.language.unsafeNulls` appears inside the body of `showPersonality` (line 195) and `setTrace` (line 233), rather than at the top of the file. This is non-standard; the imports should be at file scope.

## Phase 8.1 First-Run Init Observations (2026-05-31)
- `isInitialized` decoded from `Json.Bool` is duplicated in 3 places: `InitServiceImpl.isInitialized`, `StatusRoutes.routes`, and `Jorlan.run`. Should be extracted to a private helper in `InitService` companion or `ServerSettingsRepository`.
- `serverName` decoded from `Json.Str` with `.collect { case Json.Str(s) => s }.getOrElse("Jorlan")` is duplicated identically in `StatusRoutes` (line 56) and `SetupModeApp` (line 98). Extract to a private helper.
- `ServerStatus` case class is defined in both `InitRoutes.scala` (server) and `InitClient.scala` (shell). These are intentionally separate (different JSON derivations: `JsonEncoder` server-side, `JsonDecoder` shell-side) — not consolidatable due to project module boundaries (model can't depend on zio-http). Acceptable duplication.
- `InitRequest` is similarly defined in both modules with different derivations. Same justification.
- `ZIO.scoped {}` in `FirstRunWizard.run` (line 31) wraps code with no scoped resources — `JorlanScreen` methods are all `UIO`, no `Scope` is consumed. The wrapper is unnecessary noise.
- `adminPassword` is accepted by `InitService.complete`, validated for length, but NOT passed to `createUser` — there is no password storage yet. This is intentional (password auth is a later phase) but the parameter creates an implied contract that isn't honored. Consider a TODO comment or narrowing the signature later.
- `homeDir` extraction (`System.getProperty("user.home", "")`) and `JORLAN_SHELL_CONFIG` env var reading are duplicated between `findReadFile` and `resolveWritePath` in `ShellConfig.scala`. A private `resolveConfigPath` helper would unify them.
- `args.sliding(2).collectFirst { case List("--config", path) => new File(path) }` is duplicated in both `findReadFile` and `resolveWritePath`. Same extraction opportunity.
- `ZIO.when(cond)(ZIO.fail(...)).unit` pattern in `InitServiceImpl.complete` (lines 121, 123): mixing `.unit` and `*>` for guard-then-fail is fine but verbose. `ZIO.when(cond)(ZIO.fail(...)).unit` can just be `ZIO.when(cond)(ZIO.fail(...))` since `.unit` on `IO[E, Option[Unit]]` works — the `.unit` is not wrong but adds noise.
- `initLoop` in `FirstRunWizard` (line 131): `serverName = if (nameRaw.trim.isEmpty) defaultServerName else nameRaw.trim` — `nameRaw.trim.pipe(s => if (s.isEmpty) defaultServerName else s)` or just `nameRaw.trim.nonEmpty.fold(defaultServerName, nameRaw.trim)` is cleaner, or same pattern used in `setupLoop` (line 72). The inconsistency is that `setupLoop` uses `if/else` in a `val` while `initLoop` uses the same idiom — both are fine; note the pattern is consistent.
- `InitTokenStore.generateToken()` allocates a new `SecureRandom` on every call. `SecureRandom` is thread-safe; a `private val rng` would be slightly more efficient and idiomatic.
- Integration test `InitServiceIntegrationSpec` wraps all 5 tests with `@@ TestAspect.sequential` — correct pattern for shared container lifecycle. The numbered test names ("1. fresh DB…") signal intended execution order, which is good practice for sequential integration tests.
- `layer` in `ShellConfig` calls `findReadFile(Nil)` (ignoring CLI args). This means if a user passes `--config /path/to/file` at the CLI, the `ShellConfig.layer` ignores it and falls back to default paths. The `applyArgs` call in `JorlanShell.run` partially compensates (overrides loaded values) but the file-loading step still won't use the `--config` file. This is a pre-existing behavior gap, not introduced in this branch.

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

## Phase 9 Memory System Observations (2026-06-03)
- `markShared` and `markPrivate` in `MemoryServiceImpl` share an identical 5-step pattern: `getById → mapError → fromOption/orElseFail → Clock.instant → upsert(copy(scope=...))`. Extract a private `rescope(id, newScope)` helper.
- `MemoryServiceImpl.checkpoint` uses `case false => ZIO.unit; case true => ...` where `ZIO.when(condition)(effect)` would be cleaner.
- `OllamaModelGateway.streamedResponse` defines three nearly-identical `logStarted`/`logCompleted`/`logFailed` EventLog builders inline; the EventLog construction is 12-line verbatim across all three. The pattern of `Clock.instant.flatMap { now => eventLogRepo.append(EventLog(id=empty, ..., sessionId=Some(sessionId), occurredAt=now)).ignore }` repeats. A private `logModelEvent(sessionId, eventType)` helper would unify them.
- `AgentRunnerImpl` constructs two verbatim `EventLog` blocks (lines 64-76, 105-118) within the same method that differ only in `eventType` (UserMessageReceived vs AgentResponseCompleted). A `logSessionEvent(eventType)` helper would eliminate the duplication.
- `MemoryServiceSpec` declares `private val layers = directLayers` on line 47 but `directLayers` is defined *after* it on line 61. The `layers` val is unused — `directLayers` is used directly in the `.provide()` calls. Dead val.
- `MemoryServiceSpec`: two `.provide(...)` blocks (line 157 and 171) each rebuild the full layer stack inline. The second block duplicates 4 of the 5 layer lines from `directLayers`. Extract the common 4-line sub-stack to a shared `val`.
- `QuillMemoryRepository.search` applies `textSearch` as a post-SQL in-memory filter (line 592-596) after fetching up to `pageSize` rows from the DB. Text search is not pushed to SQL (no `LIKE` or `MATCH`). This means the returned page may have fewer than `pageSize` results even when more matches exist — a silent correctness issue. Should either push `textSearch` to SQL or document this limitation.
- `CheckpointSummarizerImpl.summarize` uses `if/else` at the top level and then again inside `.flatMap`. The early-exit `if (messages.isEmpty) ZIO.succeed(Nil)` and the inner `if (summary.isBlank) ZIO.succeed(Nil)` are fine, but they create a 4-level nesting. A for-comprehension with `.filterOrElse` or `ZIO.unless` guards would flatten one level.
- `AgentRunnerImpl.live` uses `ZLayer.fromZIO(for { s1 <- ZIO.service[T1]; ...; refs <- Ref.make(...) } yield new ...)` — the correct pattern when `Ref.make` must run at startup. No simplification possible here; the pattern is correct.
- `MemoryClassifierImpl.classify` unwraps as `UIO` but the entire body is pure. `ZIO.succeed { ... match ... }` (a single `succeed` wrapping the pure if/else) would be marginally simpler, but current form is readable.
- `JorlanAPI` memory mutations `forgetMemory`, `markMemoryShared`, `markMemoryPrivate` all have the pattern: `actorId <- actorIdFromSession; _ <- requireCapability("memory.write", actorId); _ <- ZIO.serviceWithZIO[MemoryService](_.xxx(id))`. The `forgetMemory` returns `Boolean` (always `true`) rather than `Unit`; `Unit` or a `Long` (rows deleted) would be more honest.
- `agentId = agentSessions.headOption.map(_.agentId).getOrElse(AgentId.empty)` appears **twice** in `JorlanAPI` at lines 377 and 480 (in `listMemory` and `storeMemory`). Extract to a private helper `resolveAgentId(sessions)` or consolidate into a single method.
- `CommandHandler.listMemory` and `searchMemory` have near-identical `foldZIO` bodies; they differ only in the error message string and the "no results" message. The formatting of a record (`[id] (scope) key: value`) is duplicated between lines 268 and 279.
- `ShellCommand.parse` uses a `return` statement on line 52 (`return Message(line)`) — non-idiomatic. Previously flagged in Phase 7 review. Still present.

## Phase 8.5 Manual Testing Branch Observations (2026-06-02)
- `logErrors` / `logRequests` wrappers in JorlanAPI: inline `new OverallWrapper[Any]` bodies are verbose; Caliban supports `wrapper` via `OverallWrapper.apply` or the `wrap` DSL. Both wrappers currently use nearly identical boilerplate.
- `AgentRunnerImpl.processMessage` uses nested `Ref.make.flatMap { … Ref.make.flatMap { … } }` — extracting both Refs before the for-comp is cleaner.
- `setPersonalityField` in CommandHandler fetches personality from server AND re-sends the full 5 fields — a pattern repeated identically in `showPersonality`; the fetch is duplicated.
- `loadOrCreateSession` in JorlanShell and `handleNewSession` in CommandHandler both call `applySession`-like code (set live session, update status bar, add message) with nearly identical logic. The only difference is the message string.
- `SessionHub.subscribe` logs info (not debug) on every subscriber add/remove — will be very noisy in production even without debug logging enabled.
- `SubscriptionClient` frameLoop for fragmentBuf is correct but nesting depth is 6+ levels inside the `asWebSocketAlways` block; extracting `processFrame(text: String)` would cut nesting by 2.
- `ConversationLogger.withMdc` correctly saves/restores MDC but uses `java.util.Collections.emptyMap` — `new java.util.HashMap[String, String]()` or just `MDC.clear()` is simpler.
- `VersionCheck.parse` uses `semver.unapplySeq(v).flatMap { case List(...) if ... != null => ... }` — null guards are unidiomatic with `-Yexplicit-nulls`; prefer `.collect { case List(maj, min, pat) => ... }`.
- `InitRoutes.initR` handler has nested `for` inside a `match` arm — a known pattern in this codebase; acceptable but the double `yield result` at the end (`yield resp` inner, `yield result` outer) is redundant — the outer yield adds no value and can be collapsed.
