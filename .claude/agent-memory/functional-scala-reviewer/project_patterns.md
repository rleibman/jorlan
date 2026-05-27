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
