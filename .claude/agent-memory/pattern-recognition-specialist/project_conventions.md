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
