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
