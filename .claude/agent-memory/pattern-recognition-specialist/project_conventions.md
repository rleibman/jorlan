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

## Known Technical Debt (updated 2026-05-27)
- `model` module has `quill-jdbc-zio`, `zio-http` as compile deps — domain layer should not know about DB or HTTP
- `model/configuration.scala` holds `FlywayConfig`, `DataSourceConfig`, `DatabaseConfig` — infra config in domain module
- `QuillCtx.hds` is a raw `DataSource` created eagerly — pool lifecycle not managed by ZIO acquire/release
- `PermissionRepository` has `revokeGrant`, `cancelApprovalRequest` — but no `deleteRole`, `deletePermission`, `getExpiredApprovalRequests`
- `SchedulerRepository` now has `deleteJob`/`deleteTrigger` (corrected from prior notes)
- No GraphQL API yet (phase-3 branch); `Jorlan.scala` only mounts health + auth routes
- `EventLogService` exists but nothing calls it — no application service layer wires event logging
- `CorrelationId` is implemented but has no call sites
- Sort handling in every repository handles only `sorts.headOption`; secondary sorts silently ignored
