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
