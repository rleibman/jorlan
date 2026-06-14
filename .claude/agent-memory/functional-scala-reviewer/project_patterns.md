---
name: Jorlan Project Patterns
description: Confirmed codebase conventions, recurring idioms, and known issues in the Jorlan project
type: project
---

## Confirmed Conventions

- Opaque `Long`-backed ID types in `ids.scala`; all 19 IDs share `.value`, `empty`, `apply`, and zio-json codecs.
- Enum JSON codecs use manual `contramap(_.toString)` / `mapOrFail(values.find...)` pattern — not DeriveJson. This is intentional and consistent.
- Repository trait in `model/` is parameterised by `F[_]`; ZIO-specific aliases in `db/` fix `F = RepositoryTask`.
- `unsafeNulls` import is used in db/configuration files for Java interop (HikariCP, ConfigFactory, Flyway, Testcontainers). Scope is per-file via `import scala.language.unsafeNulls`.
- `RepositoryError.apply(Throwable)` uses `.nn` to satisfy `-Yexplicit-nulls` on `getMessage` return values.
- Quill encodings centralised in `quillUtil.scala` as top-level `given` values — imported into repos via `jorlan.db.{*, given}`.
- All Quill repos call `.provideLayer(ds).mapError(RepositoryError(_))` — consistent pattern.
- `AppConfig.dataSource` is a `lazy val` that constructs HikariCP — a deliberate side-effecting lazy initialisation in a config carrier. Documented but see known issues.
- `FlywayMigration.migrate/validate/info` all return `UIO[Unit]` by swallowing errors via `foldCauseZIO` — intentional design choice.

## Review Pass: 2026-05-27 (QuillRepositories + repository.scala + server)

- `makeDataSource` in `quillUtil.scala:34` is a bare function returning `HikariDataSource` — side-effecting, unmanaged, no `ZIO.acquireRelease`. This is the primary purity issue in the DB layer.
- `QuillCtx.hds` at line 99 is a `private val` that eagerly calls `makeDataSource`, creating a HikariCP pool at class instantiation time — an unmanaged side effect at construction.
- `quillUtil.scala` lines 122-123, 128-130, 139, 147-148, 166 — `throw new RuntimeException(...)` inside `MappedEncoding` lambdas. These are inside Quill's decode path and cannot be lifted to ZIO; they follow the Quill interop pattern. Documented as known exceptions to the no-throw rule.
- `ConnectionId.random` at `ids.scala:367` is a `def` (not `val`) that calls `java.util.UUID.randomUUID()` — pure-looking call site, but generates side effects. Callers should use `ZIO.succeed(ConnectionId.random)` or wrap; leaving as-is given this is in model not business logic.
- `QuillSchedulerRepository.getPendingJobs` (line 1042-1052) correctly accesses `Clock.instant` via ZIO. This is fine and idiomatic.
- `upsertSession`/`upsertJob`/`upsertWorkspace`/`upsertTrigger`/`upsertCapabilityGrant` use `if (id.value == 0L)` to decide insert-vs-update. This is the project's agreed sentinel pattern (`empty = 0L`). Consistent and documented.
- `QuillUserRepository.search` (lines 157-216) dispatches sort permutations via `s.sorts.headOption match` — only first sort is honoured; multi-sort silently ignored. Same pattern in every repository. Tech debt, not a correctness violation.
- `EventLogRepository.search` (lines 945-1013) only matches on 3 sort cases for a 2-field sort enum; the `_` wildcard defaults to `occurredAt DESC`. All sort cases covered by intent.
- `ArtifactSearch.workspaceId` typed as `WorkspaceId` (non-Option) at `repository.scala:119` — caller must always supply a workspace, no global search. Intentional narrowing.
- `EnvironmentBuilder.live` at line 61 is a `ULayer` that calls `.orDie` internally (via `appConfig`). Errors in layer construction become defects. Acceptable for startup but not surfacing config errors gracefully.

## Phase 3 EventLog Patterns (confirmed 2026-05-26)

- `EventLogService` uses `CorrelationId.withNew` / `withId` for fiber-local correlation via ZIO log annotations — clean, idiomatic.
- `EventLogServiceImpl` remains a thin delegation layer; replay ordering now follows the repository contract directly (`replaySession` queries ascending), rather than fetching DESC and re-sorting in the service.
- `EventLogServiceImpl.live` uses `ZLayer.fromFunction` correctly.
- `RepositoryError extends JorlanError` — so `IO[RepositoryError, A]` is assignable to `IO[JorlanError, A]` via subtype variance. This is why `mapError(identity)` is a no-op (correctly removed by scalafmt).
- `InMemoryEventLogRepo` in unit tests is Ref-based rather than `AtomicLong`/`mutable.ArrayBuffer`/`synchronized`; this is more consistent with the rest of the functional test style.
- `EventLogFilter` uses a default `limit = 100` — this default is not validated anywhere; a zero or negative limit would be silently forwarded to the DB layer.
- Integration coverage around `EventLogServiceIntegrationSpec` currently has composition/wiring issues; do not treat the layer assembly there as a confirmed-good reference pattern.
- The "correlation id propagates through log call" integration test is only evidence about correlation visibility in the logging context/test path, not that a correlation ID was persisted on the `EventLog` row — the correlation ID lives in ZIO log annotations, not in the stored event record.

## Phase 7 Shell Module Patterns (reviewed 2026-05-28)

- Shell module uses sttp `HttpClientZioBackend()` as a `private def` (not `val`) — creates a new `ManagedBackend` on every call. In `AuthClientImpl` and `GraphQLClientImpl`, the backend is correctly managed via `ZIO.scoped`, so each call opens and closes its own connection. This is correct but could be optimised to a single shared backend via a `ZLayer`-managed resource.
- `AuthClientImpl.backend` and `GraphQLClientImpl.backend` are `private def` — they return `ZIO[Scope, Throwable, SttpBackend[...]]`, so each call allocates a fresh backend. Intentional for isolation, but performance-suboptimal.
- `connectWithRetry` uses `Schedule.exponential(500.millis, 2.0).map(d => if (...) 60.seconds else d)` to cap backoff. The `.map` on exponential output does NOT cap the growth seen by the scheduler — the `Schedule.exponential` output is the delay already applied, so the `.map` just modifies the delay value returned to `tapOutput`. The actual retry interval IS affected, but `Schedule.upTo` or `Schedule.exponential(...).jittered` with `||` (union) would be cleaner and safer. Capping via `.map` on `Schedule` output is unusual.
- `JorlanShell.run` uses `ZIO.succeed(sys.exit(0))` to force JVM exit. The comment justifies this as an escape from stray threads. However, `ZIO.succeed` is wrong here — `sys.exit` throws `SecurityException` in some environments and performs a system-level side effect that should be wrapped in `ZIO.attempt` at minimum, or deferred to a finalizer.
- `processLoop` uses `catchAllDefect` to swallow JVM-level defects in the rendering loop. This is intentional to keep the TUI alive, but it discards stack traces silently. A `ZIO.logErrorCause` before `screen.addMessage` would preserve observability.
- `ShellCommand.parse` uses an explicit `return` keyword on line 33 — a functional purity style violation.
- `wordWrap` in `JorlanScreen.scala` uses mutable `ArrayBuffer` and `var` — isolated to a private drawing helper with no external visibility, but still a style issue given the project's functional discipline.
- `addMessage` in `LanternaScreen` calls `LocalTime.now()` directly inside a `UIO[Unit]` — this is a side effect (clock access) not wrapped in a ZIO effect. Should be `Clock.localDateTime` or similar.
- `handleKey` in `LanternaScreen` checks `ch == null` at line 199 — the project uses `-Yexplicit-nulls`, so this is forced by the Lanterna Java interop but the import `scala.language.unsafeNulls` is at file scope, making ALL null checks implicit. This is acceptable Java interop.
- `JorlanClient.scala` uses `implicit` keyword (Scala 2 style) instead of `given`/`using` (Scala 3) — this is generated Caliban client code and should not be hand-edited. Not a review issue.
- `resolveCredentials` throws `new RuntimeException("Cancelled")` via `ZIO.fail` — appropriate for signalling cancellation through the `Throwable` error channel, but the message is not distinguishable from other failures at the `catchAll` site in `run`.

## Phase 8.5 Manual Testing / Session Connection Redesign (reviewed 2026-06-02)

- `SessionHub` evolved through three iterations in a single branch: Hub→Queue (committed) → per-connection Queue with ConnectionId (unstaged). The final design is correct: sliding Queue per connection, finalizer removes only that connection's entry, broadcasts via `ZIO.foreachDiscard`.
- `ConversationLogger` uses `ZIO.succeed { withMdc(sessionId) { ... } }` wrapping a `try/finally` MDC restore. The `try/finally` inside `ZIO.succeed` is a legitimate interop boundary for a synchronous SLF4J operation — not a purity violation, but the block performs real I/O (file writes via logback) which ideally belongs in `ZIO.attemptBlocking`. Accepted as intentional given the comment in the file.
- `SubscriptionClient` (unstaged) adds `pingLoop` via `ZStream.tick(15.seconds)` and races it with `frameLoop` — correct use of `.race` for concurrent keepalive without leaking a fiber. Uses `.forkScoped` for the WS fiber.
- `AgentRunnerImpl` (unstaged) accumulates tokens in a `Ref[Vector[String]]` for `ConversationLogger.logAgentResponse` — this Ref is local to a single `processMessage` invocation, so it is safe and not shared mutable state.
- `VersionCheck.scala` is a purely functional file: no ZIO, no side effects, correctly returns `Either[String, Unit]`.
- `initialisePostLogin` in `JorlanShell.scala` now returns `IO[Throwable, Unit]` and fails with a `RuntimeException` on version mismatch — this propagates through `mainFlow.catchAll` which expects `Throwable`. Using `new RuntimeException` inside `ZIO.fromEither(...).mapError(msg => new RuntimeException(...))` is idiomatic for lifting a string error into the typed `Throwable` channel.
- `logErrors` / `logRequests` `OverallWrapper`s in `JorlanAPI.scala` use `Option(t.getMessage).getOrElse(t.getClass.getName)` — correct null-safe guard required by Java interop; `getMessage` can return null.
- `JorlanClient.scala` — `Formality` type alias (`type Formality = String`) is a Caliban-generated file convention; no strong typing here is intentional for the client-side generated layer.
- `ShellState.scala` refactored to hold `LiveSession` (sessionId + tokenQueue + subscriptionFiber). The `Queue` and `Fiber` are ZIO-managed types, which is correct functional state-holding.
- `CommandHandler.handleMessage` now drains `liveSession.tokenQueue` directly instead of opening a new subscription per message — this is the key correctness improvement of the session connection redesign.
- `appendToLastMessage` in `LanternaScreen` correctly uses `Clock.currentDateTime` (not `LocalTime.now()`) — fixes the previously noted side-effect issue from Phase 7 review.
- `VersionCheck.parse` guard `case List(maj, min, pat) if maj != null && min != null && pat != null` — with `-Yexplicit-nulls`, `unapplySeq` on `Regex` returns `Option[List[String | Null]]`, so the null guard is forced by the compiler. Not a style issue.

## Phase 8.3 Server Personality & Phase 8.4 AI CI Patterns (reviewed 2026-06-01)

- `PersonalityServiceImpl.live` initialises the `Ref` cache atomically via `ZLayer.fromZIO` — correct; DB read and `Ref.make` are sequenced in ZIO, no race on startup.
- `PersonalityServiceImpl.update` encodes with `p.toJsonAST` and lifts the `Either[String, Json]` via `ZIO.fromEither.mapError(...)` — clean, no exceptions.
- `PersonalityServiceImpl.live` uses `.getOrElse(Personality.default)` on a failed JSON decode at startup — silently discards a corrupt stored personality rather than surfacing the parse error. Acceptable fallback, but could log a warning.
- `OllamaModelGateway.getOrCreate` uses `sessions.get.flatMap { ... sessions.update(...) }` — this is a TOCTOU pattern: two separate `Ref` operations allow concurrent callers to both see `None` and both create a new `StreamAssistant` for the same session. The second write overwrites the first, leaking the first assistant. Should use `Ref.modify` to make the check-and-set atomic.
- `Personality.buildSystemPrompt` is a pure function — no side effects, correct behaviour.
- `AgentRunnerImpl.processMessage` reads personality then builds `systemPrompt = Personality.buildSystemPrompt(personality)` as a pure val inside the for-comprehension before the model call — correctly sequenced.
- `serverPersonality` GraphQL query (`JorlanAPI.scala` line 291) calls `PersonalityService.get()` which is `UIO` — it is assigned to type `ZIO[JorlanApiEnv & JorlanSession, JorlanError, Personality]`. The `UIO` return widens to the richer error channel automatically, which is fine.
- `updatePersonality` mutation requires `"admin.personality.update"` capability — gated correctly.
- `ArgBuilder[Formality]` uses `Formality.valueOf` (line 214) which can throw `IllegalArgumentException` on unknown strings — the same unsafe `valueOf` pattern exists for all enums in the file, established before Phase 8.3.
- `initialisePostLogin` uses `.orElse(ZIO.succeed(serverUrl))` as a fallback when `checkStatus` fails — semantically correct; serverUrl is the safe default display name.
- `InMemoryPermissionRepo.expireAllStaleApprovalRequests` (InMemoryRepositories.scala line 189) calls `Instant.now()` directly inside `Ref.modify` — a bare side-effecting clock call inside an otherwise pure state transition. Test-only code but inconsistent with ZIO Clock discipline.
- `StreamedChatSpec` (`ai/src/test/`) tests only `LangChainConfig` defaults — no stream logic is tested. Documented intentionally; streaming coverage delegated to `AgentRunnerSpec` via `FakeModelGateway`.
- CI workflow (`scala.yml`) caches `~/.ollama` under key `${{ runner.os }}-ollama-llama3.2-1b` and only pulls on cache miss — correctly avoids re-downloading the model on every run.
- `ShellCommand.Personality` case is a Scala 3 enum variant with no fields — correct; `/personality` has no parameters.
- `showPersonality` in `CommandHandler` uses `import scala.language.unsafeNulls` scoped to the `json => { ... }` block — narrowly scoped, acceptable.

## Phase 8.1 First-Run Init Patterns (reviewed 2026-05-31)

- `QuillServerSettingsRepository` uses `.orDie` to satisfy `UIO` contract in `ServerSettingsRepository` trait — DB errors become defects. Intentional: trait contract is `UIO`, not `IO[RepositoryError, _]`.
- `isInitialized` logic (`settings.get("initialized").map { case Some(Json.Bool(v)) => v; case _ => false }`) is duplicated in `InitServiceImpl`, `StatusRoutes`, and `Jorlan.run` — three places, no shared helper.
- `InitServiceImpl` is constructed manually in `Jorlan.run` via `new InitServiceImpl(...)`, bypassing `InitServiceImpl.live`. The live layer exists but is unused at the only call site.
- `adminPassword` is accepted by `InitService.complete` and validated, but is NOT passed to `users.createUser`. This appears to be a correctness bug — the first admin user may be created without a stored password.
- `SetupModeApp.make` takes live service instances as plain parameters rather than using ZIO environment. Intentional for the setup mode app, but inconsistent with ZLayer convention.
- `ZIO.scoped` in `FirstRunWizard.run` is a no-op — nothing inside uses `Scope`. Should be removed.
- `ShellConfig.findReadFile` and `resolveWritePath` call `System.getProperty`, `System.getenv`, and `File.exists()` inside `ZIO.succeed` — these are I/O side effects that should be in `ZIO.attempt`.
- `generateToken()` in `InitTokenStore` is called as a plain `val` before the ZIO pipeline — the SecureRandom side effect is not wrapped in ZIO.
- Error JSON bodies in `InitRoutes.scala` are hand-interpolated strings, not zio-json serialized. Risk of malformed JSON if `getMessage` contains quotes.
- `InitClient.live` correctly uses `ZLayer.scoped` with `HttpClientZioBackend.scoped()` — backend lifecycle is properly managed.
- `ServerStatus` is defined independently in both server (`InitRoutes.scala`) and shell (`InitClient.scala`) modules — intentional for module separation, but JSON contract divergence risk.

## Phase 8 Agent Session Runtime Patterns (reviewed 2026-05-29)

- `SessionHub` correctly uses `ZStream.unwrapScoped` + `hub.subscribe` (a `ZIO[Scope, ...]` call) so the subscription queue is released when the stream ends. Clean pattern.
- `OllamaModelGateway.streamedResponse` uses `ZStream.async` with LangChain4j callbacks (`.onPartialResponse`, `.onCompleteResponse`, `.onError`, `.start()`). `start()` runs inside the `ZStream.async` callback — this is the project's established interop pattern (matches `ai/util.scala`). Not a purity violation.
- `AgentRunnerImpl.live` uses `ZLayer.fromFunction` — correct when no `ZIO` effects are needed in construction.
- `OllamaModelGateway.live` uses `ZLayer.fromZIO` + `Ref.make` — correct as Ref construction requires ZIO.
- `submitMessage` mutation in `JorlanAPI.scala` uses `.forkDaemon` to decouple model execution from HTTP response. This is intentional but introduces a risk: if the server shuts down while a daemon fiber is mid-stream, no graceful cleanup occurs. For Phase 8 this is acceptable.
- `listSessions` query (JorlanAPI line 231) calls `getSession(AgentSessionId.empty)` — this is a correctness bug; it should call `searchSessions(AgentSessionSearch(...))` with pagination from `input`.
- `AgentSessionManagerImpl.createSession` (line 95) calls `sessionHub.getOrCreate(AgentSessionId.empty)` before persisting — this creates a hub slot for the sentinel ID `0` which is immediately abandoned. The real hub is created at line 107 with the saved ID. The line-95 call is dead code.
- `suspendSession` and `terminateSession` do not write event log entries — only `createSession` does. Significant state transitions are unobserved in the audit trail.
- `HumanApprovalNotifier` does not write sessionId or agentId on the `ApprovalRequested` event — payload is thin.
- `SubscriptionClient` uses `Queue.unbounded` for the WebSocket message queue — could grow without bound if the consumer is slow. `Queue.bounded` with backpressure would be safer.
- `SubscriptionClient` wraps JSON parse errors in `new RuntimeException(...)` (lines 91, 96) inside the `asWebSocketAlways` callback — these exceptions propagate as `Throwable` errors rather than typed `String` errors. They are then caught by `.mapError(e => s"WebSocket request failed: ${e.getMessage}")` further up, so they do surface as errors, but via the exception path rather than through the typed error channel.
- `CommandHandler.setTrace` (lines 184-215) uses an `if/else` where pattern matching over a `val normalized match` would be idiomatic. The double `case "_"` branch inside the inner `match` is dead code (only reached after `valid.contains(normalized)` is true).

## Phase 10 Durable Scheduler Patterns (reviewed 2026-06-04)

- `TriggerEngine.workerId` is a `private val` evaluated at class construction time via `InetAddress.getLocalHost` and `ProcessHandle.current().pid()` — two side-effecting Java calls not wrapped in ZIO. Acceptable for a daemon class that is constructed once, but inconsistent with ZIO discipline; should use `ZIO.attempt` or at minimum `scala.util.Try` more robustly.
- `Jorlan.scala` lines 96-100 and 114-119: `TriggerEngine` is constructed manually with `new TriggerEngine(...)` twice (both in the `initialized` branch and the first-run-then-restart branch) rather than using `TriggerEngine.live`. This bypasses the ZLayer wiring pattern and creates two objects that each resolve services from the environment via direct `ZIO.service` calls. Should use `ZIO.serviceWith[TriggerEngine](_.start.forkDaemon)` after adding `TriggerEngine` to `JorlanEnvironment`.
- `TriggerEngine.executeJob` uses `executeJob(job).forkDaemon` — daemon fibers are not supervised, so if the server shuts down mid-execution, jobs remain leased (Running) and will be un-claimed by `expireLeases` on the next restart. This is acceptable for the current phase but creates a window where a job can double-execute across a fast restart.
- `TriggerEngine.scheduleRetryOrFail` computes exponential backoff via `math.pow(2, job.retryCount.toDouble).toLong` — floating-point `Double` to `Long` truncation is safe for small counts but is a style note; prefer `1L << job.retryCount` (bit-shift) which is exact for integer powers of 2.
- `TriggerEngine.advanceTriggers`: `MissedRunPolicy` is stored on `SchedulerJob` and written to DB, but is never read or applied in `advanceTriggers` or anywhere in the engine. `RunOnce` and `RunAllMissed` variants are dead/unimplemented.
- `JorlanAPI.decideApproval` mutation has no `requireCapability` guard — any authenticated user can approve or deny any approval request for any other user. Likely a missing authorization check.
- `JorlanAPI.terminateSession` is guarded by `"agent.session.create"` — this should be `"agent.session.terminate"` or similar; create permission is not semantically correct for termination.
- `JorlanAPI.ArgBuilder` for `TriggerType`, `RetryBackoffPolicy`, `MissedRunPolicy` all use `valueOf` (throws on unknown string). Consistent with existing project-wide enum ArgBuilder pattern documented in Phase 8.3 and 9. Not a new deviation.
- `quillUtil.scala`: new `MappedEncoding` for `RetryBackoffPolicy`/`MissedRunPolicy` use `valueOf` in `MappedEncoding` — same Quill interop exception-in-decode pattern documented in Phase 7 review. Not a new deviation.
- `User.email` changed from `Option[String]` to required `String` — V022 migration correctly backfills nulls. `JorlanAPI.createUser`/`updateUser` use `.getOrElse("")` on the GraphQL input's `Option[String] email` — callers must pass email or get empty string silently.
- `InMemorySchedulerRepo` is a clean `Ref`-based implementation with correct atomic `claimJob` via `jobs.modify` — idiomatic and correct.
- `TriggerEngineSpec` uses `TestAspect.withLiveClock` on all tests because `awaitFinalStatus` uses `repeatUntil` which requires real time. The daemon fiber started by `forkDaemon` inside `tick` needs to actually complete asynchronously. Correct use of live clock for async assertion.

## Phase 11 Telegram Connector Patterns (reviewed 2026-06-07)

- `TelegramMessageNormalizer.normalizeMessage` calls `java.time.Instant.now()` directly inside a pure function (line 52 of TelegramConnectorSkill.scala) — a bare side-effectful clock call not wrapped in ZIO. Should use `Clock.instant` in a ZIO effect.
- `TelegramApiClientLive.sendMessage/sendPhoto/sendDocument` all use `ZIO.fail(new Exception(b))` on HTTP error status (lines 136, 162, 186 of TelegramApiClient.scala) — constructing a plain `Exception` inside a ZIO pipeline that expects `JorlanError`. The wrapping `.mapError(e => JorlanError(...))` catches this, but the intermediate typed error is `Throwable` not `JorlanError`, creating a widening gap in the error channel.
- `TelegramConnectorSkill.invoke` line 124: `args.asObject.getOrElse(Json.Obj().asObject.get)` uses `.get` on `Option` — unsafe, can throw `NoSuchElementException`. `Json.Obj().asObject` can be `None`. Should use `getOrElse(Json.Obj().asObject.getOrElse(Map.empty))` or restructure.
- `TelegramConnectorSkill.invoke` lines 127-141: missing required fields (empty string fallbacks for `chatId`, `text`, `file`) — silently send empty/corrupt API calls instead of failing with a typed error. Should fail with `ZIO.fail(JorlanError(...))` on missing required fields.
- `TelegramConnectorSkill` has an unused `agentRunner: AgentRunner` constructor parameter and field — all dispatching goes through `MessageIngress`. Should be removed.
- `TelegramConnectorSkill.start` forks the poll loop as a daemon but the `pollLoop` is a simple recursive `def`. With ZIO's trampolining this is safe, but a stack-growing loop without `.forever` or `ZStream`-based iteration is an unusual pattern; document intentionality.
- `TelegramConfig.unrecognizedPolicy` field is declared and stored but `MessageIngressImpl.handleUnrecognized` always logs+drops (Reject behaviour) regardless of the policy. `Quarantine` variant is fully dead code.
- `TelegramManualTest`: `private val botToken` and `private val chatId` are object-level `val`s that call `sys.env.getOrElse(...)` — side effects at class initialisation time, not wrapped in `ZIO.attempt`. This is a manual test so acceptable but inconsistent.
- `TelegramManualTest.pollAndPrint` line 89: `.mapError(e => new RuntimeException(e.getMessage, null))` — passes `null` as the second argument to `RuntimeException(message, cause)`, which violates `-Yexplicit-nulls` intent and suppresses the original cause chain. Use `.mapError(e => new RuntimeException(e.getMessage))` or `JorlanError` if the error channel permits.
- `TelegramManualTest.pollAndPrint` is a non-tail-recursive `def` calling itself — with ZIO's async scheduling this is safe, but `@tailrec` cannot annotate it (it wraps in ZIO). No annotation, no stack concern at Scala level, correct.
- `EnvironmentBuilder.liveConnectorManagerLayer` line 85-86: `.mapError(e => new RuntimeException(e.msg)).orDie` — converts `RepositoryError` to `RuntimeException` then to defect. Acceptable in layer construction but the intermediate RuntimeException is needless; `.orDie` directly on `IO[RepositoryError, _]` works via `.orDieWith`.
- `MessageIngressImpl.resolveOrCreateSession` uses `pageSize = 100` (hardcoded) to search sessions, then does a Scala-level `.find`. A user who has more than 100 sessions for the same agent may not have the correct existing session found — query should filter by `chatRef` at the DB level when that becomes supported, or use an indexed lookup.
- `MessageIngressImpl.handleKnown` match on `EvaluationResult` (line 72-83): uses `_ =>` wildcard for the "allow" branch — any new `EvaluationResult` variant added in the future would silently allow message dispatch. Prefer explicit allow cases.
- `ConnectorManager.startAll`/`stopAll` use `.ignore` after `.tapError` — errors are swallowed silently after the log. This is intentional by design (one connector failure should not block others) but worth noting.
- `FakeTelegramApiClient.sentMessages` is a `val` (public) — in a test double it's fine, but the `Ref` type signals mutable shared state which is idiomatic for test assertions.

## Phase 9 Memory System Patterns (reviewed 2026-06-03)

- `CheckpointSummarizerImpl.summarize` uses `AgentSessionId(0L)` (the sentinel "empty" value) as a temporary session key when calling `ModelGateway.streamedResponse` for summarization. This risks polluting `OllamaModelGateway.sessions` with a permanent `0L` entry if `invalidateSession` is never called. Not a crash but a resource/state leak.
- `QuillMemoryRepository.search` applies `textSearch` as a post-SQL in-memory filter after the SQL-level `LIMIT/OFFSET` is applied. This means a page of 20 records may return fewer than 20 even when more matching records exist in the DB. Same pattern seen in `EventLogRepository`. Documented as consistent tech debt, not a new violation.
- `InitTokenStore.make` uses `println` inside `ZIO.succeed` for printing the setup token. `println` is a side effect that should be in `ZIO.attempt` or `Console.printLine`. Same pattern pre-existed from Phase 8.1 — documented as a known deviation.
- `InMemoryMemoryRepo.purgeExpired` calls `java.time.Instant.now()` directly inside `Ref.modify` — a side-effecting clock call inside a pure state transition. Same pattern as `InMemoryPermissionRepo.expireAllStaleApprovalRequests` (documented in Phase 8.3). Test-only.
- `CheckpointSummarizerSpec` declares `private val now = Instant.now()` at class level — a side-effectful value evaluated once at object initialisation. Fine for tests but inconsistent with ZIO Clock discipline.
- `MemoryServiceSpec` declares `val now = java.time.Instant.now()` inside test cases (not top-level `val`) — same pattern, test-only.
- `MemoryServiceSpec` has an unused `private val layers = directLayers` at line 47 shadowed by a second `private val directLayers` at line 61. The first assignment is dead code.
- `MemoryAccessPolicyImpl` wraps a pure `.filter` call in `ZIO.succeed` — correct since the trait contract is `UIO`, not because any actual IO is done. Pattern is correct and consistent.
- `ArgBuilder[MemoryScope]` uses `MemoryScope.valueOf` which throws `IllegalArgumentException` on invalid enum names. This is the existing project-wide pattern for all enum ArgBuilders (pre-existing, documented in Phase 8.3 review). Same applies to all `Formality.valueOf`, `RiskClass.valueOf` etc. in JorlanAPI.
- `AgentRunnerImpl.processMessage` uses `Ref.make(new StringBuilder)` for token accumulation — `StringBuilder` is mutable. However, the Ref holds a reference to a single-use `StringBuilder` that is only accessed via `Ref` operations within a single fiber context for this call. The `StringBuilder` is not shared across fibers. Acceptable, but using `Ref[String]` with `update(_ + chunk)` or `Ref[Vector[String]]` would be more functionally pure.
- `AgentRunnerImpl.ensureSeeded` reads `seeded` Ref then conditionally updates it — two separate Ref operations, TOCTOU risk for concurrent calls on the same sessionId. Should use `Ref.modify` to make the check-and-add atomic.
- `AgentRunnerImpl.getOrCreateConversation` reads `activeConvs` then conditionally updates — same TOCTOU risk as `ensureSeeded` for concurrent first messages to the same session.
- `OllamaModelGateway.getOrCreate` now correctly uses `Ref.modify` for the commit step (improved from Phase 8.3's TOCTOU). However the fast path at line 86 still does a plain `Ref.get` outside the atomic block, so two concurrent fibers may both miss and both call `buildAssistant`. The `Ref.modify` at commit time correctly handles this — the loser's assistant is discarded. This is intentional and correct per the code comment.
- `JorlanAPI.listMemory` and `storeMemory` both call `listSessions(actorId, 0, 1)` to derive the `agentId` for memory operations, using `.headOption.map(_.agentId).getOrElse(AgentId.empty)`. If the user has no sessions, `AgentId.empty` (0L) is used, which means stored memory will have a sentinel agentId. Records stored with `agentId = Some(AgentId.empty)` will not match the access policy filter for any real session.
- `MemorySkill` is a class (not an object/trait), which is fine — it takes `MemoryService` as a constructor arg and delegates. Its `live` layer correctly uses `ZLayer.fromFunction`.
- `CheckpointSummarizerImpl.summarize` does not call `modelGateway.invalidateSession(tempSessionId)` after use. The `OllamaModelGateway` will retain a `sessions` entry for `AgentSessionId(0L)` indefinitely (accumulating summarization history across checkpoints). This is a resource leak.
- `AgentRunnerImpl.buildMemoryContext` catches all errors from `memoryService.query` via `.catchAll(_ => ZIO.succeed(""))` — silently swallows memory fetch errors in a way that is invisible to the caller. A `ZIO.logWarning` before the fallback would preserve observability.

## Phase 12 Built-in Skills Patterns (reviewed 2026-06-10)

- `ContactsSkill.identityLink` (line 183) calls `Instant.now()` directly inside ZIO pipeline — bare clock side effect, should be `Clock.instant`.
- `AgentRunnerImpl.runCheckpoint` (line 368) calls `java.time.Instant.now()` directly inside a `UIO` fold — same bare clock issue, should be `Clock.instant` or a `for`-comprehension with the outer `Clock.instant` threaded in.
- `WorkspaceSkill.safePath` (line 114) uses `throw new SecurityException(...)` inside `ZIO.attempt` — the throw is caught by `ZIO.attempt` so this is functionally equivalent to `Left`, but the pattern of deliberately throwing inside `ZIO.attempt` is stylistically suboptimal. Prefer `ZIO.fail` directly via a guard.
- `WorkspaceSkill.workspaceSearch` (line 159) calls `Files.isDirectory(p)` inside `.map { p => ... }` on a ZIO effect chain — this is a blocking I/O call outside any `ZIO.attempt`/`ZIO.attemptBlocking` wrapper. It sits between a safe `safePath` call and the next `ZIO.attempt` block, so an OS-level error here becomes an unmanaged exception.
- All four `WorkspaceSkill` I/O operations use `ZIO.attempt` not `ZIO.attemptBlocking` — `Files.readAllBytes`, `Files.write`, `Files.walk`, and `Files.deleteIfExists` are potentially blocking POSIX calls; should use `ZIO.attemptBlocking` to avoid pinning a ZIO fiber thread.
- `AgentRunnerImpl.logSkillEvent` (line 192): the `payload: String` parameter is accepted but never used in the `EventLog`; `payloadJson` stores only `{"tool": toolName}`, silently discarding the invocation args and result.
- `ContactsSkill` has a dead `private def contactFind` delegated via a one-liner `private def contactsFind` — `contactFind` should simply be renamed `contactsFind`, removing the indirection.
- `AgentRunnerImpl.ensureSeeded` now correctly uses `Ref.modify` to make the seeded-check-and-add atomic — improvement over the TOCTOU pattern seen in Phase 9 review.
- `AgentRunnerImpl.getOrCreateConversation` now correctly uses `Ref.modify` with `Left(())/Right(id)` sentinel — clean atomic check-and-reserve pattern.
- `SkillRegistryLive.validateRequiredFields` correctly encodes the schema validation in a pure `Either`-for-comprehension then lifts via `ZIO.succeed` — idiomatic separation of pure logic from effect layer.
- `AgentRunnerReActSpec` correctly uses `ZIOSpec[ZIORepositories]` + `bootstrap` + `ZLayer.makeSome` via `provideSome` — consistent with the test patterns documented in Phase 9.
- `NotificationRouter.notifyChannel` uses non-exhaustive pattern on `ChannelType` (only Telegram/Slack/Email covered; Shell/WhatsApp/Sms/GraphQL/Google/GitHub/Discord fall through to `None`) — sealed enum wildcard `_` silently handles future variants. This is intentional design (unknown channels return an error string) but the match structure in `channelTypeToConnectorType` should be documented.

## Phase 15 Web Frontend Patterns (reviewed 2026-06-10)

- Adapter construction (`ScalaJSClientAdapter(Uri.parse(...).fold(_ => throw new Exception("bad uri"), identity), ...)`) is copy-pasted verbatim in 8 page files and in `AppShell` — the `throw` path inside `fold` is the primary purity violation; should be extracted once into a helper returning `Either` or `AsyncCallback`.
- `var socket` and `var connectionState` in `ScalaJSClientAdapter.makeWebSocketClient` anonymous class are mutable fields — intentional for JS WebSocket interop where mutation is necessary; but `connectionState` is a case class mutated via `connectionState = connectionState.copy(...)` which means concurrent JS callbacks can race on it (single-threaded JS so technically safe, but not structurally obvious).
- `return` statement in `attemptReconnect()` (ScalaJSClientAdapter.scala line 179) — style violation under the project's no-explicit-return rule.
- `Instant.now()` called directly in `ScalaJSClientAdapter` (lines 182, 245, 261) — bare side-effectful clock calls, not wrapped in any effect type.
- `println` used in ScalaJSClientAdapter lines 149, 179, 186, 193 — raw side effects, not `Callback.log` or any controlled channel.
- `ClientConfiguration.live` is an object-level `val` initialised eagerly via `window.location` DOM access — side effect at class load time.
- `JorlanWebApp.connectionId` is a top-level object-level `val` calling `ConnectionId.unsafeRandom` — side effect at object initialisation time (acceptable for JS singleton entry-point, documented as known pattern).
- `AppShell.useEffectOnMountBy` wraps the entire async chain in a `Callback { ... .runNow() }` pattern — the `Callback` wrapper hides the async operation from React's lifecycle; the `.runNow()` fires-and-forgets, and errors from `completeWith(_ => Callback.empty)` are silently discarded. This recurs in every page's `useEffectOnMount`. Error feedback to the user is absent in most pages.
- `SessionsPage` / `ApprovalsPage` / `MemoryPage` / `UsersPage` Terminate/action buttons call `Callback.empty.runNow()` as a stub — these are no-ops masquerading as UI actions; no error or placeholder feedback.
- `ChatPage.streamBuffer` state field is managed but never populated from the WebSocket subscription — `streaming` flag is set but the subscription is not wired up, so streamed responses are silently dropped.
- `EventLogPage.makeWebSocketClient` result (the `WebSocketHandler`) is not stored anywhere; `close()` is never called, so the WebSocket leaks on component unmount.
- `AppRouter.useEffect(Callback.empty)` on line 51 is a no-op placeholder with a comment "hash change listener placeholder" — browser `popstate`/`hashchange` events are not wired, so navigating back/forward with the browser does not update the page state.
- `JorlanClientDecoders` uses `implicit val` throughout rather than `given` — intentional for Caliban's `import _` pattern (documented). Not a new deviation.

## Phase 13 Email/Calendar/Drive Patterns (reviewed 2026-06-12)

- `GmailProvider`, `GoogleCalendarProvider`, `GoogleDriveProvider` each initialise `transport` and `jsonFactory` as eager `private val` at class construction time via `GoogleNetHttpTransport.newTrustedTransport()` — two blocking I/O calls (SSL handshake to CRL/OCSP) not wrapped in ZIO. Should be lazy or `ZIO.acquireRelease`-managed.
- `makeGmail/makeCalendar/makeDrive` functions are called inside the `f` lambda passed to `ZIO.attemptBlocking` — the `GoogleCredentials.create(new AccessToken(accessToken, null))` call passes a literal `null` as the Java `Date` expiry, which is intentional (expiry managed by `OAuthCredentialServiceImpl`). Correct Java interop, documented.
- `verifyStateJwt` in `OAuthRoutes.scala` line 86 calls `Instant.now().getEpochSecond` directly inside an `Either`-returning pure function — a bare clock side effect. Should accept `now: Instant` as a parameter or be lifted to ZIO; currently called from inside a ZIO for-comprehension where `Clock.instant` is available.
- `GoogleDriveProvider.listFiles` line 62 assigns `null` to `q: String | Null` and then null-checks it on line 66 — this is valid Java interop with `unsafeNulls`, but a more idiomatic approach would use `foldLeft`/`mkString` unconditionally or pass the query as an `Option` to the Google API wrapper method.
- `OAuthCredentialEncryptor.encrypt/decrypt` use `try/catch` and return `Either[JorlanError, _]` — this is the correct pattern for a pure crypto operation that cannot be lifted to ZIO at this layer; the caller in `OAuthCredentialServiceImpl` correctly lifts via `ZIO.fromEither`. Pattern is acceptable and consistent.
- `GmailProvider.listMessages` calls `withGmail` twice per message: once to list refs and once per message to fetch full content — N+1 calls to `refreshAccessToken` followed by N+1 calls to Gmail API. Access token refresh is idempotent (same token returned if not expired) but each call performs an HTTP round-trip. Minor performance concern, not a purity issue.
- `invokeTool` GraphQL mutation (JorlanAPI.scala line 926) has no `requireCapability` guard — any authenticated user can invoke any registered skill tool directly. This is a significant authorization gap; the mutation relies entirely on individual skill `requiredCapabilities` checked at `SkillRegistry.invoke` level, which may not be enforced.
- `startOAuth` GraphQL mutation (JorlanAPI.scala line 914) returns a plain string path `/api/oauth/start/$provider` instead of calling the actual OAuth route logic or constructing the full URL — caller must make a second HTTP request to that path, which then requires the `X-User-Id` header. The indirection is not documented; a caller who issues the `startOAuth` GQL mutation and then visits the returned path must be authenticated on both legs.
- `QuillExternalCredentialRepository.upsert` conflict resolution clause does not include `updatedAt` — on conflict, `updated_at` in the DB is set by `ON UPDATE CURRENT_TIMESTAMP(3)` on the column, so the `now` value computed by `Clock.instant` in `createdAt` and `updatedAt` fields of the row is written for insert but `updatedAt` in the conflict-update clause is absent. This is correct because the DB column has `ON UPDATE` semantics, but the `updatedAt` field in the Scala `ExternalCredentialRow` is misleading — it is ignored on conflict update.
- `FakeEmailProvider` is defined as a `class` (not `object`) inside `EmailSkillSpec.scala` (test file), while `FakeGmailProvider` is a separate `class` in `google-services/src/test/` — these are functionally identical implementations. There is a duplication of the fake email provider: one in `email/src/test/FakeEmailProvider.scala` (referenced by import in EmailSkillSpec) and one in `google-services/src/test/`. Neither is DRY.

## Known Issues Found (first review, 2026-05-25)

- `ChannelIdentity.id` typed as `UserId` (a PK reuse), which is confusing and weakens type safety; no dedicated `ChannelIdentityId` type.
- `Permission.id` typed as `RoleId` — same reuse confusion, no dedicated `PermissionId`.
- `AppConfig.dataSource` is a side-effecting `lazy val` — HikariCP pool creation should be wrapped in `ZIO.attempt`/`ZIO.acquireRelease`.
- `ConfigurationServiceImpl.appConfig` is a `lazy val` — its type says `IO[ConfigurationError, AppConfig]` but `AppConfig.read` calls `.orDie` internally, so config errors become defects silently. The error channel is a lie.
- `EventLogRepository.search` filters `from`/`to` in Scala after fetching (post-SQL filter) while `limit` is applied before; date-range queries with `limit` can return fewer rows than expected.
- `RepositoryError` extends `Exception` — mixing functional (sealed hierarchy) with OO inheritance. Not a violation per se but the `cause.orNull` in the `Exception` constructor is notable.
- `QuillCtx` is a `private class` with an inner `object ctx` — the `object` declaration inside a class instance creates a singleton per-instance, which is correct for Quill but unusual and worth documenting.
- `QuillRepositories.live` returns a 9-tuple from `ZLayer.fromZIO` then destructures it in `.flatMap` — correct but unusual; a case class or HList would be more readable.
- `MemoryEmbedding.vector` stores float array as a JSON `String` — acknowledged in comment but worth noting as a future migration risk.
- Test `now` is a `private val` at class level — correct for deterministic tests.
- Integration test uses `.get` on `Option` in assertion via `fetched.get.displayName` — safe in test context but triggers `-Yexplicit-nulls` concerns in non-test code.
