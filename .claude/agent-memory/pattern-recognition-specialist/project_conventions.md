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

## Known Technical Debt
- `EventLogRepository.search` does date filtering in-memory after fetching `limit` rows from DB — breaks pagination semantics
- `model/configuration.scala` imports HikariCP and Quill — couples the domain layer to DB infrastructure
- `AppConfig.dataSource` is a mutable `lazy val` creating a HikariDataSource — lifecycle not managed by ZIO
- `PermissionRepository` has no `deleteRole`, `deletePermission`, `revokeGrant`, `getExpiredApprovalRequests` — incomplete for access revocation lifecycle
- `SchedulerRepository` has no `deleteJob`/`deleteTrigger` — no cleanup path for completed jobs
- `Permission.id` field is typed as `RoleId` rather than a dedicated `PermissionId` — misleading
- `ChannelIdentity.id` field is typed as `UserId` — same reuse-of-wrong-type issue
