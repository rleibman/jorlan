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
