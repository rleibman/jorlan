---
name: Project Conventions
description: Core architectural conventions confirmed in Jorlan codebase
type: project
---

## Module Boundaries
- `model`: domain types + repository traits + configuration (but currently also holds HikariCP/Quill/ZIO-config deps — a violation)
- `db`: Quill implementations, Flyway, ConfigurationServiceImpl
- `server`: zio-http + Caliban, wires everything
- `integration`: Testcontainers integration tests (depends on model + db + server)
- `ai`, `analytics`, `shell`, `util`: satellite modules

## Repository Pattern
- Abstract `trait XxxRepository[F[_]]` lives in `model/repository.scala`
- Concrete `trait XxxZIORepository extends XxxRepository[RepositoryTask]` in `db/Repository.scala`
- 9 Quill implementation classes (split to avoid JVM erasure "Conflicting definitions" errors for 19 opaque Long IDs)
- All repos receive a shared `QuillCtx` (single `MysqlZioJdbcContext`) and `dataSourceLayer`
- Error type: `RepositoryError(msg, cause, isTransient)` — `isTransient` from JDBC exception hierarchy

## Opaque ID Pattern
- 19 opaque types all backed by `Long`; each companion has `apply`, `empty` (0L sentinel), `.value` extension, JSON codecs
- Quill encodings in `db/quillUtil.scala` (given MappedEncoding pairs for each ID and each enum)

## Enum JSON Codec Pattern
- All enums use `JsonEncoder[String].contramap(_.toString)` + `JsonDecoder[String].mapOrFail(values.find...)` — consistent but verbose
- Quill uses `.valueOf` for DB decode (case-sensitive), JSON uses `.find(_.toString ==)` — two separate decode paths, minor risk of divergence

## ZIO Layer Pattern
- `QuillRepositories.live` creates a single `QuillCtx`, builds all 9 repos, then emits them as a product layer
- `EnvironmentBuilder.live` uses `ZLayer.make` macro for compile-time graph resolution
- `FlywayMigration.live` takes `ConfigurationService`, creates Flyway, wraps in `UIO` (swallows errors as log output)

## Phase 8 Service Placement Pattern (2026-05-29)
- `ModelGateway` trait placed in `model/` (correct — no infra deps)
- `AgentRunner` and `AgentSessionManager` traits placed in `server/` (inconsistent — they follow ZIO service pattern and should be in `model/`)
- `SessionHub` is a concrete class (not a trait+impl pair) in `server/` — acceptable if it stays server-only (depends on ZIO Hub, which is fine in model, but the class directly holds infrastructure state)
- `AgentSessionManager` doc says "Delegates to AgentService" but implementation injects `AgentZIORepository` directly — bypasses the service layer
- `AgentSessionSearch` is missing a `userId` field — listSessions cannot be properly scoped to the authenticated user without DB schema change
- `JorlanApiEnv` now includes `SessionHub` directly — subscription wiring leaks a low-level hub into the GraphQL layer instead of going through `AgentRunner`
- Magic numbers in `OllamaModelGateway`: temperature 1.1, topK 40, topP 0.9, maxMessages 1000, hub capacity 256 — duplicated from `ai/util.scala`

## Phase 8.1 Conventions Added
- `InitTokenStore` is a plain class (not ZIO service) — intentional because it holds ephemeral state only needed during startup, but prevents environment injection
- `ServerSettingsRepository` trait lives in `db` module (not `model`) — because it depends on `zio.json.ast.Json` which is acceptable, but it is a pure trait so placement in `model` would be more consistent
- `QuillRepositories.live` now destructures a 10-tuple; pattern is established but becoming fragile
- Error branching in `InitRoutes.SetupModeApp`: `ValidationError` → 400, all other `JorlanError` → 403 (not 500) — this is wrong; non-validation errors that aren't "forbidden" (e.g., a DB error) should map to 500

## Phase 8.5 Conventions Added (2026-06-02)
- `SessionHub` redesigned: Hub[ResponseChunk] → per-connection Queue[ResponseChunk] keyed by (AgentSessionId, ConnectionId); each subscriber gets its own queue; `SubscriberEntry` is the private pairing type
- `AgentRunner.subscribeToSession` now takes a `ConnectionId` parameter; the GraphQL resolver generates one via `ConnectionId.randomZIO` per subscription call
- `ConversationLogger` is a plain `object` (no ZIO service pattern) with direct SLF4J usage inside `ZIO.succeed`; acceptable because it is purely a side-channel log and carries no injected state
- `VersionCheck` is a pure object with only `check(...)`: Either[String, Unit]; correct separation of pure version logic from ZIO shell code
- `OverallWrapper` used for request/error logging in JorlanAPI; wrappers applied at schema construction with `@@`
- `JorlanClient` now contains `Personality` type alias + `PersonalityView` case class, mirrors pattern used for `AgentSession` and `ResponseChunk`
- `loadOrCreateSession` in JorlanShell establishes the long-lived WS subscription fiber at login time; `handleNewSession` in CommandHandler re-establishes it for `/new` — logic is duplicated (known issue flagged)
- `SetupModeApp.make` now accepts `initDone: Option[Promise[Nothing, Unit]]` and `tokenStore: InitTokenStore`; Promise is used to signal Jorlan.run to switch from setup mode to full routes without a restart

## Phase 10 Conventions Added (2026-06-04)
- `TriggerEngine` is a plain class (not ZIO service pattern) with `val start: UIO[Unit]` and `private[service] val tick: UIO[Unit]`; started via `forkDaemon` in `Jorlan.run`
- `JobManager` trait placed correctly in `model/service/` with companion `ZIO.serviceWithZIO` accessors; `JobManagerImpl` in `server/service/` follows established pattern
- `SchedulerSkill` class in `server/service/` wraps `JobManager` with `val live: URLayer[JobManager, SchedulerSkill]`; not yet wired into application environment (deferred to Phase 12)
- `TriggerEngine.live` provides `URLayer[SchedulerZIORepository & EventLogZIORepository & AgentSessionManager & AgentRunner & SessionHub, TriggerEngine]` but is NOT used — `Jorlan.run` constructs `new TriggerEngine(...)` directly from ZIO.service calls

## Phase 11 Conventions Added (2026-06-07)
- `connector-api` module holds `Skill`, `ConnectorSkill`, `MessageIngress`, `InboundMessage`, `ChatKind`, `UnrecognizedIdentityPolicy` — pure ZIO/zio-json traits with no server/db deps
- `telegram` module holds `TelegramApiClient`, `TelegramConnectorSkill`, `TelegramMessageNormalizer`, `TelegramConfig`, `FakeTelegramApiClient`; depends on `model + connectorApi` only (no `server` dep in build.sbt)
- `ConnectorManager` trait + `ConnectorManagerImpl` live in `server/service/`; wired via `liveConnectorManagerLayer` in `EnvironmentBuilder.live`; queries `SkillZIORepository.searchConnectors` at boot to discover `ConnectorInstance` records, then instantiates `TelegramConnectorSkill` per record
- `MessageIngressImpl` wired via `ZLayer.fromFunction` into `MessageIngress`; currently takes db-layer ZIO repositories directly (known inconsistency with established pattern)
- `TelegramConnectorSkill.make` returns `UIO[TelegramConnectorSkill]` (not a ZLayer) — caller in `EnvironmentBuilder` uses `ZIO.foreach` to build skills then wraps in `ConnectorManager.fromSkills`
- `FakeTelegramApiClient` is in production source (`telegram/src/main/scala/`) — intentional choice for cross-module test reuse, but deviates from convention
- Scheduler queries (`job`, `triggers`) in `JorlanAPI` call `SchedulerZIORepository` directly, not through `JobManager` — inconsistent with other scheduler mutations which go through `JobManager`
- `ServerUrl` opaque type added to `shell/` module; correct placement, follows opaque type pattern

## Known Technical Debt (updated 2026-05-29)
- `model` module has `quill-jdbc-zio`, `zio-http` as compile deps — domain layer should not know about DB or HTTP
- `model/configuration.scala` holds `FlywayConfig`, `DataSourceConfig`, `DatabaseConfig` — infra config in domain module
- `QuillCtx.hds` is a raw `DataSource` created eagerly — pool lifecycle not managed by ZIO acquire/release
- `PermissionRepository` now has `deleteRole`, `deletePermission`, `getExpiredApprovalRequests` (previously flagged as missing — now resolved)
- `SchedulerRepository` has `deleteJob`/`deleteTrigger`
- GraphQL API now implemented (phase-3 branch); `JorlanAPI`, `JorlanRoutes`, `SchemaGen` added in Phase 6
- `EventLogService` now wired in `PermissionServiceImpl` for role/permission mutations — partially resolved
- `CorrelationId` is implemented but has no call sites
- Sort handling in every repository handles only `sorts.headOption`; secondary sorts silently ignored
- `JorlanAPI` user CRUD now depends on `UserService` rather than calling `UserZIORepository` directly (previously flagged — now resolved)
- Mutation resolvers do read `JorlanSession` for capability checks; authorization wiring exists, though broader coverage may still need review
- `QuickAdapter(interp)` instantiated twice in `JorlanRoutes` (once per handler call) — minor inefficiency
- `extractLongField` in `GraphQLApiSpec` uses regex on JSON strings — fragile; breaks on field reordering
- `listSessions` in `JorlanAPI` calls `getSession(AgentSessionId.empty)` — returns at most one session (or none), not a list; broken semantics
- `createSession` in `AgentSessionManagerImpl` calls `sessionHub.getOrCreate(AgentSessionId.empty)` before saving — creates a hub entry under the sentinel ID, wasted allocation
- `ensureDefaultAgent` in `AgentSessionManagerImpl` — violates SRP; session manager should not be responsible for provisioning agent records
- `HumanApprovalNotifier` is a plain class; should follow the ZIO service pattern (trait + companion) for testability
- `AgentRunnerImpl` and `AgentSessionManagerImpl` live in same file as their traits; the impl companion is named `AgentRunnerImpl`/`AgentSessionManagerImpl` not following the `XxxImpl.live` object-inside-companion pattern used elsewhere

## Sprint 4 Conventions Added (2026-07-06) — Use-Case Manifest Importer
- New `shellClient` module extracted from `shell`: holds `ShellConfig`, `ServerUrl`, `AuthClient`, `GraphQLClient`, `ZIOClientRepositories` — shared by `shell` (interactive TUI) and new `useCaseImporter` (headless CLI). Clean rename-based split (verified via `git diff --stat` renames), no logic duplicated across the boundary.
- `useCaseImporter` module depends only on `shellClient` + `modelJVM`; correctly excludes Lanterna/TUI deps. `UseCaseImporterApp.run` is `$COVERAGE-OFF$` (requires live server), consistent with existing shell-app convention.
- Package naming inconsistency: `UseCaseImporterApp` lives in package `jorlan.shell` (module `useCaseImporter`), and `ZIOClientRepositories` lives in `jorlan.shell.client` (module `shellClient`) — package names no longer match module/artifact names now that the code has been split three ways (`shell`, `shellClient`, `useCaseImporter` all under `jorlan.shell*`). Cosmetic today but worth renaming packages to match modules if a 4th shellClient consumer appears.
- `ZIOClientRepositories` (1073 lines) continues the pre-existing pattern of floating top-level methods (`upsertMcpServer`, `allCustomSkills`, `createSkillDraft`, `createJobWithPipeline`, `updateRole`, `addTrigger`, etc.) declared directly on the `ZIOClientRepositories` trait instead of inside the proper `Repositories[F[_]]` sub-repo traits (agent/permission/scheduler/skill) — this re-introduces the exact anti-pattern that [[repo_refactor_floating_methods]] previously fixed on the server side; client-side was never brought in line.
- `UseCaseManifest` schema (model/shared/.../usecase/UseCaseManifest.scala): one case class per provisioning concern (role/agent/memorySeed/mcpServer/declarativeSkill/job+trigger), all with sensible defaults; declarative skills passed through as raw JSON string deliberately (avoids duplicating a codec that already exists server-side) — good extensibility pattern, new use-case concerns can be added as new optional fields without breaking existing manifests.
- `UseCaseImporterApp` provisioning steps (role→grants→role-assignment→agent→memory→mcp→skills→job→trigger) all follow an identical hand-rolled shape (search-for-existing, SKIP-if-found, else create+log, `catchAll` logs FAIL and returns None/unit) repeated 8 times with no shared combinator — strong Template Method / higher-order-function extraction opportunity (e.g. `provisionStep[A](label)(find: ...)(create: ...)`).
- The CLI's `run` never inspects any step's FAIL outcome — the process always exits 0 and prints "Import complete" even if every single step failed. For a headless/scriptable/automatable tool this is a real gap: no way for CI or a wrapper script to detect partial failure.
- `provisionMcpServers` always calls `upsertMcpServer` unconditionally (no existence/SKIP check before writing) — inconsistent with every other provisioning step's SKIP-on-already-exists convention, though harmless if the server-side upsert is naturally idempotent.
- `QuillAgentRepository.upsert` bug fixed: MariaDB's `INSERT ... ON DUPLICATE KEY UPDATE` still advances the auto-increment counter, so `returningGenerated` (via `LAST_INSERT_ID()`) returned a bogus id on update, silently creating duplicate rows. Fixed by branching explicitly on `agent.id == AgentId.empty` (insert vs. explicit `.update`) instead of relying on `onConflictUpdate`. Worth checking other `onConflictUpdate(...).returningGenerated` usages elsewhere in `QuillRepositories.scala` for the same class of bug.
- The systemic "limit-before-sort" bug flagged in earlier phase reviews (`EventLogRepository.search`) has now been fixed consistently across all ~17 search methods in `QuillRepositories.scala` — every one now does `sortBy(...).drop(offset).take(pageSize)` in that order. Confirmed via grep; no longer a live finding.
