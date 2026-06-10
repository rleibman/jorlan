---
name: Recurring Code Smells
description: Technical debt and code quality issues identified in initial review
type: project
---

## Critical
- `model` module declares `quill-jdbc-zio`, `zio-http`, `zio-config-typesafe` as compile dependencies — violates module isolation; model should only need ZIO core and zio-json
- `AppConfig.dataSource` is a `lazy val` creating a `HikariDataSource` — not managed by ZIO resource lifecycle; pool is never explicitly closed
- Keep `JorlanAPI` environment types constrained to service-level abstractions (`UserService`, `PermissionService`, `CapabilityEvaluator`) and avoid reintroducing `db`-layer repository types into the GraphQL schema module

## Warnings
- `EventLogRepository.search`: date range (`from`/`to`) filtered in-memory AFTER taking `limit` rows; if all `limit` rows fall outside the date range the caller gets empty results. Needs raw SQL or two-pass approach.
- Enum valueOf decode in `quillUtil.scala` will throw `IllegalArgumentException` on unknown values from DB — should be wrapped in `Try` or caught
- `RepositoryError.apply(cause)` falls through to a bare `cause.getMessage.nn` catch-all for non-SQL exceptions — swallows non-JDBC transience classification
- `ConfigurationServiceImpl.live` hardcodes `"./src/main/resources/application.conf"` as fallback path — breaks when running from an installed package
- `PermissionRepository` missing: `deleteRole`, `deletePermission`, `getExpiredApprovalRequests` (revokeGrant and cancelApprovalRequest now exist)
- `SchedulerRepository` now has `deleteJob`/`deleteTrigger` (corrected)
- `testcontainers-scala-mariadb` appears as a compile (not test-scoped) dependency in both `db` and `server` modules
- `integration` module depends on `server` — but the tests only use `db`-level repositories; server dependency pulls in heavy transitive deps unnecessarily

## Suggestions
- 19 opaque ID companions are structurally identical; a macro or code-generation approach could eliminate the repetition
- 12 enum JSON codecs follow identical pattern; a shared helper `enumCodec[E](values: Array[E])` would DRY this up
- `QuillRepositories.live` destructures a 9-tuple (now 10-tuple with ServerSettings) — fragile as repositories are added; consider accumulating with `ZLayer.succeed(x) ++ ...` directly
- `RepositoryTask` type alias is defined in `db` package — callers outside `db` must spell out `IO[RepositoryError, A]`; consider re-exporting from `model`
- `F[_]` abstraction in `model/repository.scala` adds complexity with no current non-ZIO consumer; revisit when a second effect type materializes

## Phase 8.5 Findings (2026-06-02)
- `SessionHub` still injected into `AgentSessionManagerImpl` even though it is no longer used by that class (createSession no longer calls getOrCreate); dead dependency in the layer and constructor
- `AgentSessionManagerImpl` and `AgentRunnerImpl` both receive `SessionHub` yet it is only used in `AgentRunnerImpl` — `AgentSessionManagerImpl.live` should remove it from its URLayer signature
- `ConversationLogger` uses SLF4J MDC directly inside `ZIO.succeed` — safe only if MDC is thread-local (Logback uses ThreadLocal MDC), but ZIO fibers can be multiplexed across OS threads; the doc comment claims safety but is subtly wrong for non-blocking Logback appenders
- `SessionHub` uses `Queue.sliding(1024)` — the switch from `Hub` is documented as "buffers all chunks", but `sliding` silently drops oldest entries when the queue is full; the class javadoc claims no chunks are lost, which is wrong under backpressure
- `JorlanClient.Formality` is typed as `String` in the client type aliases — loses the closed enum constraint that the server-side `Formality` enum provides; a stringly-typed argument is passed to `updatePersonality` with no validation
- Caliban `OverallWrapper` implemented with anonymous `new OverallWrapper` syntax instead of the idiomatic `EffectWrapper`/`OverallWrapper` Caliban DSL helpers; minor but inconsistent with Caliban documentation style
- `createSession` mutation type in JorlanAPI.scala: the Mutations struct says `CreateSessionInput => ...` but the diff comment says "Removes CreateSessionInput wrapper"; the code still has CreateSessionInput — partial cleanup left an intermediate inconsistency (one side uses the wrapper, other removed it in the PR diff context)
- `logRequests` wrapper logs the full query body at DEBUG — for long messages or production prompts this can be an unbounded log entry; consider truncation
- `loadOrCreateSession` in JorlanShell.scala duplicates the subscription fiber fork setup found identically in `handleNewSession` in CommandHandler.scala — the LiveSession setup code (Queue.bounded + ZIO.scoped + SubscriptionClient.agentResponseStream + forkScoped + setLiveSession) appears in two places; extraction to a shared helper is needed

## Phase 8.3/8.4 Findings (2026-06-01)
- Inline anonymous `PersonalityService` duplicated in 3 test files (AgentRunnerSpec, JorlanAPISpec, GraphQLApiSpec) — should be extracted to `FakePersonalityService.layer` in `FakeModelGateway.scala` alongside the existing `FakeModelGateway`
- `serverPersonality` query has NO authentication or capability guard — any unauthenticated caller can read the server personality; inconsistent with `listSessions` which calls `actorIdFromSession` + `requireCapability`
- `ServerPersonalityInput` naming is inconsistent: all other mutation input types follow `VerbNounInput` (e.g., `UpdateUserInput`, `CreateRoleInput`); this one is `ServerPersonalityInput` (describes a noun, not the action)
- `OllamaModelGateway.getOrCreate` docstring claims "all reads/writes use `Ref.modify`" but the implementation uses separate `.get` + `.update` — a real TOCTOU race window exists under concurrent calls with the same sessionId
- CI `sbt test` command missing `--error` flag (project CLAUDE.md mandates `--error` for all sbt invocations)
- `OllamaModelGateway.availableModels` hardcodes `contextWindow = 4096` as a placeholder — magic number with a comment; acceptable short-term but flagged

## Phase 10 Findings (2026-06-04)
- `TriggerEngine` is not added to `JorlanEnvironment` type or `EnvironmentBuilder.live`; it is instantiated with `new` directly in `Jorlan.run`, bypassing the ZLayer graph and using db-layer types directly as constructor params
- `TriggerEngine` constructor takes `SchedulerZIORepository` and `EventLogZIORepository` directly — `server/` service layer should depend on abstract `SchedulerRepository[RepositoryTask]` (or a service interface), not the db-layer ZIO aliases
- `TriggerEngine` instantiated twice with identical code in the initialized vs. fresh-init branches of `Jorlan.run` (lines 100 and 119) — shotgun surgery risk
- `MissedRunPolicy.RunOnce` and `RunAllMissed` are declared in the domain model but neither is consulted anywhere in `TriggerEngine` — the field stored in DB is silently ignored; jobs always behave as `Skip`
- `upsertJob` UPDATE in `QuillSchedulerRepository` does NOT include `name`, `inputJson`, `userId`, `agentId`, `skillId`, `maxRetries`, `backoffSeconds`, `backoffPolicy`, `missedRunPolicy` — those fields cannot be changed after insert; if `pauseJob` or any mutation that calls `upsertJob` with an existing ID tries to change these it silently no-ops
- `getPendingJobs` filters `leasedAt.isEmpty` — but after `expireLeases` resets stale `Running` rows back to `Pending`, their `leasedAt` may not be NULL (expireLeases only sets status/leasedAt). Cross-check: expireLeases DOES set leasedAt to NULL, so this is OK, but the filter still excludes `Running` jobs that were never properly expired first
- `SchedulerSkill` is wired with `val live` but never added to `EnvironmentBuilder` or `JorlanEnvironment` — the layer exists but is dead code until Phase 12
- `JorlanAPI` calls `SchedulerZIORepository.getJob` and `SchedulerZIORepository.searchTriggers` directly (bypassing `JobManager`) for the `job` and `triggers` queries — inconsistent: some scheduler paths go through the service, others hit the db layer directly
- `ArgBuilder[TriggerType/RetryBackoffPolicy/MissedRunPolicy]` use bare `.valueOf` which throws `IllegalArgumentException` on unknown enum values — should use `.Try(valueOf)` like `MemoryScope` does
- Missing event log writes: `pauseJob`, `resumeJob`, `triggerNow`, `deleteJob`, `addTrigger` mutations in JorlanAPI have no `logEvent` call; `SchedulerJobPaused`/`SchedulerJobResumed`/`SchedulerJobDeleted` event types don't even exist in `EventType`
- `decideApproval` mutation has no `requireCapability` guard — any authenticated user can approve/reject any approval request

## Phase 11 Findings (2026-06-07)
- `TelegramConnectorSkill` injects `AgentRunner` but never calls it — dead dependency; only `MessageIngress` is used for dispatch
- `TelegramConnectorSkill` imports `jorlan.service.AgentRunner` from `server` module while `telegram` module only declares `dependsOn(model, connectorApi)` — cross-module boundary violation (only compiles because server is a transitive dep via test scope; fragile)
- `MessageIngressImpl` depends on `UserZIORepository`, `AgentZIORepository`, `EventLogZIORepository` directly — server/service layer should depend on abstract repository traits or service interfaces, not db-layer ZIO aliases
- `resolveOrCreateSession` loads up to 100 sessions and filters in-memory for `chatRef` match — should pass `chatRef` to `AgentSessionSearch` and filter at DB level; race condition on concurrent messages too
- `UnrecognizedIdentityPolicy.Quarantine` enum case defined but completely unimplemented — `handleUnrecognized` always drops regardless of configured policy
- `EvaluationResult.CapabilityGrantAllows` is not handled in `MessageIngressImpl.handleKnown` — if a grant requires human approval the message is silently dispatched anyway; the `_` wildcard match swallows the case
- `java.time.Instant.now()` used directly in `TelegramMessageNormalizer.normalizeMessage` (line 52) — bypasses ZIO Clock; not testable deterministically
- `ZIO.sleep` used bare in `TelegramConnectorSkillSpec` tests (lines 118, 128) without `TestAspect.withLiveClock` wrapping at the correct level — contradicts the project's established test clock discipline (though `@@ TestAspect.withLiveClock` is applied at test level, the practice of wall-clock sleeps is fragile)
- `pollLoop` is a naked ZIO tail-recursive method (`def pollLoop(offset)` calling itself) — works because it is forked as a daemon, but ZIO cannot optimize it via `tailRecM`; prefer `ZStream.unfoldZIO` or `ZIO.iterate`
- `stopAll` is never called on server shutdown — `ConnectorManager.stopAll` exists but is not invoked in `Jorlan.run`'s teardown path; Telegram polling fibers leak on shutdown
- `startAll` is duplicated identically in both branches of `Jorlan.run` (lines 98 and 114) — shotgun surgery risk, same pattern as `TriggerEngine` duplication flagged in Phase 10
- `chatRef` session lookup does not scope to `channelType` — two connectors with coincidentally identical numeric chat IDs (Telegram + Slack both use numeric IDs) would share sessions incorrectly
- `FakeTelegramApiClient` lives in production source (`telegram/src/main/scala/`) not test source — test doubles belong in `src/test/scala/` to avoid shipping them in the production JAR
- `val boundary = "ZioBoundary"` duplicated in both `sendPhoto` and `sendDocument` — should be a private constant; also a static string is not RFC 2046-safe as a real multipart boundary
- `resolveAgentId` silently falls back to `AgentId.empty` (0 sentinel) when no session exists; a job created with `AgentId.empty` stored in DB will fail the FK constraint on any schema that enforces `agentId` references

## Phase 8.1 Findings (2026-05-31)
- `isInitialized` decoding pattern (settings.get("initialized").map { case Some(Json.Bool(v)) => ... }) duplicated across Jorlan.scala:78, InitServiceImpl:107, and StatusRoutes:52 — should be extracted to `ServerSettingsRepository` or a helper
- `ServerStatus` and `InitRequest` case classes defined twice: once in `server/init/InitRoutes.scala` and again in `shell/client/InitClient.scala` — structural duplication; modules are independent, but the field names and JSON shape must stay in sync manually
- `InitServiceImpl` constructed with `new` in `Jorlan.run` (bypasses `InitServiceImpl.live` ZLayer) — inconsistent wiring; the layer exists but is not used at the call site
- `InitTokenStore` is not a ZIO service (no trait, no companion `ZIO.serviceWithZIO` accessor, no layer type) — cannot be injected via ZIO environment; callers must hold the concrete class directly
- `ServerSettingRow` placement: belongs to `db` implementation details but is in the `ServerSettingsRepository.scala` trait file — creates a leaky abstraction (the trait file reveals the storage row type)
- `initLoop` in `FirstRunWizard` is direct recursion with growing call stack — accumulates stack frames on repeated retry cycles; `ZIO.tailRecM` or a `Ref`-based loop would be safer
- `Jorlan.run` reads `settingsRepo.get("initialized")` and then `buildRoutes` also calls `StatusRoutes.routes(startTime, settingsRepo)` which re-reads it per request — minor double-read at startup, acceptable but noted
- Password stored in `ShellConfig` as plaintext `Option[String]` — written to disk in JSON by `ShellConfig.write`; this is a security risk (credential exposure on disk)
- `validateInputs` uses sequential `*>` chaining — only reports the first failing validation; user cannot see all errors at once
