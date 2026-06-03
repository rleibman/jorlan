# Jorlan — Phase 0 / 1 / 2 Code Review

Date: 2026-05-25  
Reviewers: Simplicity agent, Functional Scala agent, Architecture/Pattern agent

---

## Severity Legend

- 🔴 Critical — correctness bug or structural violation; fix before merging
- 🟡 Warning — design smell, safety gap, or maintenance hazard; fix soon
- 🟢 Note — suggestion or observation; low urgency

---

## 🔴 Critical Issues

### 1. `AppConfig.dataSource` is an unmanaged, side-effecting `lazy val`
**File:** `model/src/main/scala/jorlan/configuration.scala:71`

`lazy val dataSource: HikariDataSource` constructs a HikariCP connection pool outside ZIO's resource management.  
Problems:
- Pool construction exceptions become `ExceptionInInitializerError`, not typed errors.
- No `ZIO.acquireRelease` finalizer — pool leaks on abnormal shutdown or in tests.
- `unsafeNulls` is imported file-wide just to call Hikari's Java setters.

**Fix:** Wrap in `ZLayer.scoped` with `ZIO.acquireRelease`:
```scala
val dataSourceLayer: ZLayer[AppConfig, Nothing, DataSource] =
  ZLayer.scoped {
    ZIO.serviceWithZIO[AppConfig] { config =>
      ZIO.acquireRelease(
        ZIO.attempt(config.buildDataSource).orDie
      )(ds => ZIO.attempt(ds.close()).ignoreLogged)
    }
  }
```

---

### 2. `EventLogRepository.search` — date filter applied *after* SQL `LIMIT`
**File:** `db/src/main/scala/jorlan/db/repository/QuillRepositories.scala` (~line 499)

`limit` is applied at the SQL level (`.take(lift(limit))`), then `from`/`to` are filtered in Scala memory. If `limit = 10` and all 10 returned rows are outside the date window, the caller receives zero results even though matching rows exist further back.

**Fix:** Push date filters into SQL before `LIMIT`. Use raw SQL (as `purgeExpired` does) since Quill can't translate `Instant` comparisons:
```sql
WHERE ... AND occurredAt >= ? AND occurredAt <= ? ORDER BY occurredAt DESC LIMIT ?
```

---

### 3. `model` module depends on HikariCP / Quill — violates module boundary
**File:** `build.sbt`; `model/src/main/scala/jorlan/configuration.scala`

`configuration.scala` imports `com.zaxxer.hikari.*` and constructs a `HikariDataSource` directly. The architecture rule is "Domain layer must NOT depend on DB or connector specifics." Any module that depends on `model` (ai, shell, analytics) transitively pulls in Quill and HikariCP.

**Fix:** Move `AppConfig`, `DataSourceConfig`, `DatabaseConfig`, `FlywayConfig`, `HttpConfig`, `JorlanConfig`, `ConfigurationError`, `ConfigLoadError`, `ConfigurationService` from `model/` to `db/`. The `model` module should only need `zio-core` and `zio-json`.

---

### 4. `ConfigurationServiceImpl.appConfig` body runs file I/O outside ZIO
**File:** `db/src/main/scala/jorlan/db/configuration.scala:30`

`val confFileName = ...`, `new File(confFileName)`, `ConfigFactory.parseFile(...)` all execute eagerly inside a `lazy val`, outside ZIO. File-not-found and parse errors throw Java exceptions that bypass the `IO[ConfigurationError, AppConfig]` error channel.

**Fix:**
```scala
override val appConfig: IO[ConfigurationError, AppConfig] =
  ZIO.attempt {
    val path = Option(System.getProperty("application.conf"))
      .getOrElse("./src/main/resources/application.conf")
    ConfigFactory.parseFile(new File(path)).withFallback(ConfigFactory.load()).resolve()
  }.mapError(e => ConfigLoadError(e.getMessage.nn, Some(e)))
   .flatMap(AppConfig.read)
```

---

## 🟡 Warnings

### 5. `Permission.id: RoleId` and `ChannelIdentity.id: UserId` — wrong opaque types as PKs
**Files:** `permission.scala:38`, `user.scala:61`

Two entities reuse unrelated opaque ID types as their own PKs. The comment acknowledges the workaround. The type system offers no protection: a `Permission.id` can be passed to a function expecting a `RoleId` and the compiler won't catch it.

**Fix:** Add `PermissionId` and `ChannelIdentityId` to `ids.scala` (and corresponding `MappedEncoding` pairs in `quillUtil.scala`). The erasure problem is why we split into 9 repository *classes* — it does not justify sharing unrelated ID types.

---

### 6. `testcontainers-scala-mariadb` in compile scope (not test scope)
**File:** `build.sbt` (~lines 200, 252)

Both `db` and `server` modules declare Testcontainers without `% Test`. The production JAR gains Docker/container management transitive dependencies.

**Fix:** Add `% Test` to both declarations.

---

### 7. `integration` module depends on `server` only to access SQL migration scripts
**File:** `build.sbt:269` — `.dependsOn(model, db, server)`

This pulls Caliban, zio-http, JWT, sttp, and the full server graph into the integration test classpath. The dependency exists solely to get the Flyway SQL files on the classpath.

**Fix:** Move SQL migration scripts to `db/src/main/resources/sql/` so they belong to the `db` classpath. Then `integration` can depend only on `model` and `db`.

---

### 8. `FlywayMigration.migrate` swallows all errors — server starts with broken schema
**File:** `db/src/main/scala/jorlan/db/FlywayMigration.scala:85`

`migrate` returns `UIO[Unit]` by catching errors via `foldCauseZIO`. A failed mid-migration lets the server start with a partially-applied schema and no indication of failure beyond a log line.

**Fix:** Change `migrate` to `Task[Unit]` and let the startup caller (e.g. `Jorlan.scala`) explicitly `.orDie`.

---

### 9. `SchedulerJob.getPendingJobs` returns future-dated jobs
**File:** `QuillRepositories.scala` (~line 535)

The query returns all `Pending` jobs regardless of `scheduledAt`. A job scheduled for next week is returned alongside a job due now.

**Fix:** Add `AND scheduledAt <= NOW()` to the query (raw SQL, consistent with `purgeExpired`).

---

### 10. `ConfigurationServiceImpl` hardcodes a dev-only fallback path
**File:** `db/src/main/scala/jorlan/db/configuration.scala:33`

`.getOrElse("./src/main/resources/application.conf")` only resolves correctly when running `sbt run` from the project root. Any other working directory (installed package, Docker container) will silently fall back to empty config.

**Fix:** Remove the fallback or use a conventional absolute path (`/etc/jorlan-server/application.conf`).

---

### 11. `ConfigurationError` extends `Exception` with `Throwable | Null`
**File:** `model/src/main/scala/jorlan/configuration.scala:100`

Mixing the functional error hierarchy with the JVM exception hierarchy. The `null` default for `cause` weakens the null safety enforced by `-Yexplicit-nulls`.

**Fix:** Make it a plain `sealed trait` (not `Exception`) carrying `cause: Option[Throwable]`.

---

### 12. `AppConfig.read` silently converts config errors to defects
**File:** `model/src/main/scala/jorlan/configuration.scala:91`

The return type is `UIO[AppConfig]` but it calls `.orDie` internally. Callers have no indication this can crash a fiber.

**Fix:** Return `Task[AppConfig]` and let callers decide whether to `.orDie`.

---

### 13. `JorlanContainer` — test container not managed by ZIO `Scope`
**File:** `integration/src/test/scala/jorlan/db/JorlanContainer.scala:43`

`containerLayer` starts the MariaDB container but never registers a `ZIO.acquireRelease` finalizer to call `container.stop()`.

**Fix:**
```scala
ZLayer.scoped(
  ZIO.acquireRelease(
    ZIO.attemptBlocking { val c = MariaDBContainer(); c.container.start(); c }
  )(c => ZIO.succeed(c.container.stop()))
)
```

---

### 14. `MissedRepository` — `OrchestratorIdentity` has no repository
**File:** `model/src/main/scala/jorlan/repository.scala`

`OrchestratorIdentity` is defined in domain and has a `JorlanSchema` query schema entry, but there is no `OrchestratorRepository` trait or Quill implementation.

**Fix:** Either add `OrchestratorRepository` or remove the schema entry and mark the feature as TODO.

---

### 15. `PermissionRepository` and `SchedulerRepository` missing delete/revoke operations
**File:** `model/src/main/scala/jorlan/repository.scala`

- `PermissionRepository`: no `revokeGrant`, `cancelApprovalRequest`, `getExpiredApprovalRequests`.
- `SchedulerRepository`: no `deleteJob`, `deleteTrigger`. Completed jobs accumulate forever.

**Fix:** Add at minimum `revokeGrant(id: CapabilityGrantId): F[Unit]`, `cancelApprovalRequest(id: ApprovalRequestId): F[Unit]`, `deleteJob(id: SchedulerJobId): F[Unit]`, `deleteTrigger(id: SchedulerTriggerId): F[Unit]`.

---

### 16. `FlywayConfig.target` uses empty string as sentinel instead of `Option[String]`
**File:** `model/src/main/scala/jorlan/configuration.scala:38`

`target: String = ""` — empty string means "no target". Easy to forget.

**Fix:** `target: Option[String] = None`.

---

## 🟢 Notes / Suggestions

### 17. Introduce `runQ` helper in each Quill repository
**File:** `QuillRepositories.scala` — every method ends with `.provideLayer(ds).mapError(RepositoryError(_))`

A private `inline def runQ[A](q: => Task[A]): RepositoryTask[A]` would remove ~50 repetitions and make each method body express only its query logic.

---

### 18. Enum JSON decoder boilerplate (12 identical 3-line blocks)
All enum companions use the same `JsonDecoder[String].mapOrFail { s => values.find(_.toString == s).toRight(...) }` pattern. A shared helper reduces 12 copies to 1:
```scala
inline def enumDecoder[E](name: String, values: Array[E]): JsonDecoder[E] =
  JsonDecoder[String].mapOrFail(s => values.find(_.toString == s).toRight(s"Unknown $name: $s"))
```
*(Note: this is already superseded by the upcoming `derives` syntax migration.)*

---

### 19. `assertTrue` should use multi-argument form in tests
**File:** `RepositorySpec.scala`

`assertTrue(a) && assertTrue(b)` → `assertTrue(a, b)` gives better failure messages.

---

### 20. `fetched.get` in test assertion
**File:** `RepositorySpec.scala:49`

`.get` on `Option` throws `NoSuchElementException` on failure. Use `fetched.exists(_.displayName == "Alice")` instead.

---

### 21. `deactivate`/`delete` methods return `F[Unit]` with no row-count feedback
Deleting a non-existent ID is silently treated as success. Consider `F[Boolean]` or `F[Long]` (rows affected) to allow callers to detect "not found" without a round-trip.

---

### 22. `EventLog` uses parallel `resourceType: String` + `resourceId: Long` instead of a typed ADT
A `sealed trait EventPayload` with typed case classes per `EventType` would make the event log self-describing at the type level. Deferred refactor candidate.

---

### 23. `ConnectorInstance.status` is a free-form `String`
Consider a `ConnectorStatus` enum (`Connected | Disconnected | Error`) before the DB schema stabilises.

---

### 24. `MemoryEmbedding.vector` should migrate to MariaDB `VECTOR` column
Add a `// TODO: migrate to VECTOR column (MariaDB 11.7+) when schema matures` comment.

---

### 25. `ApprovalStatus.Denied` shadows `ApprovalMode.Denied`
In code that imports both, the two `Denied` cases can cause reader confusion. Consider renaming `ApprovalStatus.Denied` to `Rejected`.

---

### 26. Missing integration test suites
`SchedulerRepository`, `ArtifactRepository`, and `PermissionRepository` have no test coverage. `PermissionRepository` is the most critical omission given the deny-by-default security model.

---

## Top Priorities (ordered)

| # | Issue | Effort |
|---|-------|--------|
| 1 | Fix `EventLogRepository.search` date/limit ordering bug | Low |
| 2 | Wrap `AppConfig.dataSource` in `ZIO.acquireRelease` | Medium |
| 3 | Move `configuration.scala` from `model` to `db` | Medium |
| 4 | Add `PermissionId` and `ChannelIdentityId` opaque types | Low |
| 5 | Add `% Test` to Testcontainers dependencies | Low |
| 6 | Add `revokeGrant` / `cancelApprovalRequest` / job cleanup methods | Low |
| 7 | Fix `getPendingJobs` to filter `scheduledAt <= NOW()` | Low |
| 8 | Add integration test suites for Scheduler / Artifact / Permission repos | Medium |
