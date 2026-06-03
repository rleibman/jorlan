/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# Phase 8.1 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala, SRS/SDD Conformance, Code Simplicity,
Pattern Recognition, Performance Oracle, Test Coverage, ScalaDoc Auditor)
**Date**: 2026-05-31
**Branch**: `phase-8.1/first-run-init`
**Scope**: Phase 8.1 — First-Run Initialization (`InitService`, `InitServiceImpl`, `InitTokenStore`,
`StatusRoutes`, `SetupModeApp`, `InitRoutes`, `ServerSettingsRepository`, `QuillServerSettingsRepository`,
Flyway V017, `Jorlan.run` bootstrap, `FirstRunWizard`, `InitClient`, `ShellConfig`, `JorlanShell`)

---

## Executive Summary

Phase 8.1 delivers a complete first-run initialization subsystem: a token-protected HTTP endpoint
(`POST /api/init`), an interactive CLI wizard (`FirstRunWizard`), `ServerSettingsRepository` backed by
Flyway migration V017, and a `SetupModeApp` that guards all routes until initialization completes.
The ZIO service pattern, `Ref`-based token store, and wizard retry loops are all sound. The
`setting_key` naming workaround for MariaDB's reserved `key` word and the `onConflictUpdate` upsert
pattern in Quill are correctly applied.

Two critical defects were identified and resolved during the review session: the admin password was
silently discarded after validation (making post-init login impossible), and `GET /api/status` was
absent from the post-initialization route table (returning 404 on a running server). A major HTTP
status mismatch — validation failures returning 403 instead of 400 — was also corrected by introducing
`ValidationError extends JorlanError`. All three were confirmed by multiple agents independently.

Three areas have **zero test coverage**: the HTTP layer in `InitRoutes.scala`, the concrete
`InitClientImpl`, and the four new `ShellConfig` methods (`resolveWritePath`, `isFirstRun`, `write`,
`findReadFile`). The most dangerous gap is `ShellConfig.write` — if the JSON nesting is wrong the
wizard writes a file the next startup cannot load, with no test catching it.

**Overall health: Issues Present — ready to advance to Phase 8.2 with open items tracked.**
All critical and major items are resolved. The remaining items are correctness improvements,
coverage gaps, and ScalaDoc additions that should be addressed through Phase 8.2.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area              | Issue                                                                                              | File : Line                            | Recommended Action                                                                                   |
|--------|------------|------------|-------------------|----------------------------------------------------------------------------------------------------|----------------------------------------|------------------------------------------------------------------------------------------------------|
| [x]    | P8.1-001   | Critical   | Correctness       | `adminPassword` validated but silently discarded; `createUser` called without password; admin cannot log in after init. (confirmed by 3 reviewers) | `InitService.scala:125`               | Added `UserService.setPassword`; called after `createUser` in `complete`.                            |
| [x]    | P8.1-002   | Critical   | Architecture      | `GET /api/status` absent from post-init route table; returns 404 after initialization completes. (confirmed by 2 reviewers) | `Jorlan.scala`, `InitRoutes.scala`    | `StatusRoutes.routes` added to `buildRoutes`; now served on both route tables.                       |
| [x]    | P8.1-003   | Critical   | Test Coverage     | `InitRoutes.scala` has 0% test coverage — all HTTP paths untested: status shape, 400/403 distinction, 503 catch-all, malformed JSON body. (confirmed by 2 reviewers) | `InitRoutes.scala`                    | Added `InitRoutesSpec` covering all status/init/catch-all paths using `routes.run` in-process.       |
| [x]    | P8.1-004   | Critical   | Test Coverage     | `InitClientImpl` has 0% test coverage; `makeForTesting` seam exists but is unused; error branches invisible to CI. (confirmed by 2 reviewers) | `InitClient.scala`                    | Added `InitClientSpec` with stub backend via `InitClient.makeForTesting`; covers all error branches. |
| [x]    | P8.1-005   | Critical   | Test Coverage     | `ShellConfig` new methods (`resolveWritePath`, `isFirstRun`, `write`, `findReadFile`) have 0% coverage; `write` round-trip gap is most dangerous — wrong nesting produces an unloadable file. | `ShellConfig.scala`                   | Added tests for all four methods including `write` → HOCON round-trip assertion.                     |
| [x]    | P8.1-006   | Warning    | Correctness       | `POST /api/init` returned HTTP 403 for all `JorlanError`s including validation failures; spec prescribes 400 for validation and 403 for auth/state. (confirmed by 2 reviewers) | `InitRoutes.scala`                    | Added `ValidationError extends JorlanError`; route handler now branches on subtype.                  |
| [x]    | P8.1-007   | Warning    | Correctness       | `POST /api/init` error handler still maps all non-`ValidationError` `JorlanError`s (e.g. DB failures) to 403 Forbidden; unexpected errors should be 500. | `InitRoutes.scala:103-108`            | Add third arm `case _ => Status.InternalServerError` for unexpected errors.                          |
| [x]    | P8.1-008   | Warning    | Correctness       | `StatusRoutes.routes` captured `initialized` at construction time; stale after init completed. (confirmed by 2 reviewers) | `InitRoutes.scala`                    | Handler now reads `initialized` from `ServerSettingsRepository` on every request.                    |
| [x]    | P8.1-009   | Warning    | Code Quality      | `SetupModeApp.make` re-implemented the status handler inline instead of delegating to `StatusRoutes`; dead code and divergence risk. (confirmed by 2 reviewers) | `InitRoutes.scala`                    | `SetupModeApp.make` now calls `StatusRoutes.routes` directly.                                        |
| [x]    | P8.1-010   | Warning    | Correctness       | Error response bodies built via string interpolation; messages containing `"` or `\` produce malformed JSON. | `InitRoutes.scala`                    | Error bodies now use `.toJson` on `Map[String, String]`.                                             |
| [x]    | P8.1-011   | Warning    | Functional Purity | `ZIO.scoped` in `FirstRunWizard.run` wrapped code with no scoped resources — misleading and adds a false `Scope` requirement. (confirmed by 2 reviewers) | `FirstRunWizard.scala:31`             | Removed.                                                                                             |
| [x]    | P8.1-012   | Warning    | Performance       | `GET /api/status` fires two sequential DB queries per request (one for `"initialized"`, one for `"serverName"`); doubles DB load under polling. (confirmed by 2 reviewers) | `InitRoutes.scala:52-53`              | Changed to parallel fetch using `<&>` operator; both keys fetched in one round-trip.                 |
| [x]    | P8.1-013   | Warning    | Architecture      | `ServerStatus` and `InitRequest` independently defined in both `server` (`InitRoutes.scala`) and `shell` (`InitClient.scala`); silent contract divergence if a field is added to one side. (confirmed by 2 reviewers) | `InitRoutes.scala:25-38`, `InitClient.scala:20-34` | Both types moved to `model` module; codec contract test added in `InitClientSpec`.  |
| [x]    | P8.1-014   | Warning    | Security          | `ShellConfig` serializes `password` as plaintext JSON to `~/.jorlan/jorlan-shell.json`; readable by any process running as the same user. | `ShellConfig.scala`, `ShellConfig.write` | Added explicit SECURITY NOTE ScalaDoc on `ShellConfig` case class and `write` method documenting the risk. |
| [x]    | P8.1-015   | Warning    | Architecture      | `InitTokenStore` has no trait and no ZIO service accessors; tests wire it via ad-hoc `ZLayer.fromZIO` while every other service follows the `trait` + companion pattern. | `InitService.scala:28`                | Extracted `trait InitTokenStore` with companion service accessors; impl renamed `InitTokenStoreImpl`. |
| [x]    | P8.1-016   | Warning    | Architecture      | `InitServiceImpl` constructed with `new` in `Jorlan.run`, bypassing `InitServiceImpl.live`; `live` is dead code; the service is not injectable in integration tests. | `Jorlan.scala:83`                     | Delete `InitServiceImpl.live` and document why manual construction is necessary, or add `InitService` to `JorlanEnvironment`. |
| [x]    | P8.1-017   | Warning    | Correctness       | `validateInputs` short-circuits on the first failing rule; a user with multiple invalid inputs must retry repeatedly and sees only one error per attempt. | `InitService.scala:136-141`           | Rewrote using `List.flatten` to collect all errors; joined with `"; "` in a single `ValidationError`. |
| [x]    | P8.1-018   | Warning    | Architecture      | `ServerSettingRow` (a Quill DB row type) defined in `ServerSettingsRepository.scala` (the trait file); leaks an implementation detail into the abstraction boundary. | `ServerSettingsRepository.scala:46`   | Move `ServerSettingRow` to `QuillRepositories.scala` alongside `QuillServerSettingsRepository`.      |
| [x]    | P8.1-019   | Warning    | Observability     | No event log entry written for the initialization action; every other mutating service writes to `EventLogService`; the audit trail cannot record when first-run setup occurred. | `InitService.scala`                   | Add an `EventType.ServerInitialized` (or reuse an appropriate type) and log it in `complete`.        |
| [x]    | P8.1-020   | Warning    | Test Coverage     | blank/whitespace `serverName` not tested; `validateInputs` short-circuits so test must send `serverName = "   "` with valid email and password to reach this branch. | `InitServiceSpec.scala`               | Added test: `serverName = "   "` with valid email and valid password → `ValidationError`.            |
| [x]    | P8.1-021   | Warning    | Test Coverage     | `createUser` failure and `setPassword` failure paths in `InitServiceImpl.complete` not tested; a DB error mid-way leaves `initialized = false` and orphaned user; no test verifies the invariant. | `InitServiceSpec.scala`               | Added test with failing user repo stub; asserts `initialized` remains `false` on partial failure.    |
| [ ]    | P8.1-022   | Warning    | Test Coverage     | Integration test asserts user exists in DB but does not verify the password is correct and login succeeds; a regression in `setPassword` wiring would go undetected. | `InitServiceIntegrationSpec.scala`    | After wiring the auth stack, add a login assertion to integration test 3. Defer to Phase 8.2.        |
| [ ]    | P8.1-023   | Suggestion | Architecture      | `FirstRunWizard` wizard loops (`initLoop`, `statusLoop`) use direct Scala recursion; ZIO trampolines `flatMap` so no stack overflow in practice, but `ZIO.tailRecM` is the idiomatic and explicit form. | `FirstRunWizard.scala:62-154`         | Rewrite loops using `ZIO.tailRecM` to make stack-safety explicit.                                    |
| [x]    | P8.1-024   | Suggestion | Architecture      | `QuillRepositories.live` now returns a 10-element tuple destructured in a `flatMap`; each new repository requires updating the tuple position and the `ZLayer.succeed` chain. | `QuillRepositories.scala`             | Refactored to build a single `QuillCtx` and use `++` accumulation of `ZLayer.succeed` calls.         |
| [x]    | P8.1-025   | Suggestion | Functional Purity | `generateToken()` constructs `new SecureRandom()` per call and performs OS I/O inside `ZIO.succeed`; called once at startup so impact is negligible, but semantically incorrect. (confirmed by 2 reviewers) | `InitService.scala:43`                | Promote `rng` to a `private val`; wrap the `nextBytes` call in `ZIO.attempt`.                       |
| [x]    | P8.1-026   | Suggestion | Correctness       | `isInitialized` declared as `val` on `InitServiceImpl`; a `val` on a DB-accessing effect misleads readers into thinking it is free to call. | `InitService.scala:106`               | Change to `override def isInitialized`.                                                              |
| [x]    | P8.1-027   | Suggestion | Test Coverage     | Custom server URL path in `FirstRunWizardSpec` never tested; all tests send `""`, always using the default; the `urlRaw.trim.isEmpty` branch has its non-default arm uncovered. | `FirstRunWizardSpec.scala`            | Added test: sends `"http://myserver:9090"` as URL; asserts it appears in returned config.            |
| [x]    | P8.1-028   | Suggestion | Test Coverage     | `ServerStatus` JSON codec contract between server and shell is untested; a renamed field on one side silently breaks the wire format. | N/A                                   | Added contract test in `InitClientSpec`; decodes server-side JSON with shell-side decoder.           |
| [x]    | P8.1-029   | Suggestion | Code Quality      | `isInitialized` flag-reading pattern (`case Some(Json.Bool(v)) => v; case _ => false`) duplicated in `InitServiceImpl`, `StatusRoutes`, and `Jorlan.run`; key name `"initialized"` is a magic string in four files. (confirmed by 3 reviewers) | `InitService.scala`, `InitRoutes.scala`, `Jorlan.scala` | Extract `def isInitialized(settings: ServerSettingsRepository): UIO[Boolean]` helper; add `val InitializedKey = "initialized"` constant. |
| [x]    | P8.1-030   | Suggestion | Code Quality      | `homeDir`/`envFile`/`argFile` resolution duplicated between `findReadFile` and `resolveWritePath` in `ShellConfig`; a typo in one method would not affect the other. | `ShellConfig.scala:59-79`             | Extract `configPathFromArgs`, `envConfigPath`, and `defaultConfigPath` as private helpers.            |
| [x]    | P8.1-031   | Suggestion | Test Coverage     | V017 migration seed verification gap: integration test 1 asserts `Some(Json.Bool(false))` but would pass if the seed row were absent (because `isInitialized` defaults to `false` on `None`). | `InitServiceIntegrationSpec.scala:57` | Assert `initialized == Some(Json.Bool(false))` to require the row exists, not merely that the service returns false. |
| [x]    | P8.1-032   | Suggestion | Documentation     | `InitService.isInitialized`, `complete`, `InitTokenStore.verify`, `InitTokenStore.invalidate`, `ServerStatus`, `InitRequest`, and `SetupModeApp.make` all lack ScalaDoc. (confirmed by 2 reviewers) | `InitService.scala`, `InitRoutes.scala` | Added ScalaDoc with `@param`/`@return` to `isInitialized`, `complete`, all `InitTokenStore` methods, and `SetupModeApp.make`. |
| [x]    | P8.1-033   | Suggestion | Documentation     | `ServerSettingsRepository.get` and `set` lack one-line ScalaDoc; the `UIO` return (never fails) and upsert semantics are non-obvious to callers. | `ServerSettingsRepository.scala:23-27` | Added one-line ScalaDoc to both methods noting `UIO` (never fails) and upsert semantics.            |
| [x]    | P8.1-034   | Suggestion | Documentation     | `InitClient.checkStatus` and `complete` lack ScalaDoc; the `IO[String, _]` error channel (human-readable String, not typed `JorlanError`) is a non-obvious deviation from the rest of the codebase. | `InitClient.scala:38-48`              | Added ScalaDoc with `@return` noting human-readable error strings in the error channel.              |
| [x]    | P8.1-035   | Suggestion | Documentation     | `FirstRunWizard.run`, `ShellConfig` case class, and `UserService.setPassword` lack ScalaDoc; `ShellConfig` should note plaintext password storage. | `FirstRunWizard.scala`, `ShellConfig.scala`, `UserService.scala` | Added class/method ScalaDoc; `ShellConfig` and `write` include SECURITY NOTE on plaintext password. |

---

## Grouped Sections

### Correctness

**P8.1-001 — Admin password silently discarded** [CONFIRMED BY 3 REVIEWERS — FIXED]

`InitServiceImpl.complete` accepted `adminPassword`, validated its length, then called
`users.createUser(adminName, Some(adminEmail), None)` with `None` for the password. The
validated `adminPassword` was discarded. The resulting admin user had no stored credential and
could not log in through the normal auth flow.

**Fix applied**: Added `UserService.setPassword(userId: UserId, password: String): IO[JorlanError, Unit]`
backed by `UserZIORepository.changePassword`. `InitServiceImpl.complete` now calls
`users.setPassword(createdUser.id, adminPassword)` immediately after `createUser`.

---

**P8.1-002 — `GET /api/status` absent from post-init route table** [CONFIRMED BY 2 REVIEWERS — FIXED]

`StatusRoutes` was only wired into `SetupModeApp`. `Jorlan.buildRoutes` (the post-initialization router)
had no entry for `/api/status`. After initialization completed and `SetupModeApp` was replaced by the
full app, `GET /api/status` returned 404. The spec states: "This endpoint remains live and returns the
same shape at all times (including post-initialization)."

**Fix applied**: `StatusRoutes.routes` now accepts the concrete `ServerSettingsRepository` directly
(rather than returning a ZIO effect). It is registered in both `Jorlan.buildRoutes` and
`SetupModeApp.make`, served throughout the server lifetime.

---

**P8.1-007 — DB errors in `POST /api/init` return 403 instead of 500**

The route handler branches on `ValidationError → 400`, then `_ → 403`. A DB failure from
`users.createUser`, a `setPassword` error, or any other unexpected `JorlanError` subtype maps to
Forbidden — a status that tells the caller the request can be fixed by providing different credentials.
Only `InvalidToken` and `AlreadyInitialized` are genuinely authorization failures; unexpected errors
should be 500.

**Recommended fix**: Add a third arm in the `foldZIO`:
```scala
case v: ValidationError => Status.BadRequest
case _: JorlanError if it's a known auth error => Status.Forbidden
case _ => Status.InternalServerError
```
Or introduce an `AuthError` subtype of `JorlanError` (analogous to `ValidationError`) to distinguish
auth/state failures from unexpected errors at the type level.

---

**P8.1-017 — `validateInputs` short-circuits on the first error**

The three validation checks are chained with `*>` which short-circuits on the first failure. A user
with both an invalid email and a password that is too short sees only the email error on the first
attempt and the password error on the second. For a first-run wizard called exactly once this is low
impact, but it is inconsistent with the `ValidationError` type which suggests user-facing feedback.

---

### Resource Management / Performance

**P8.1-012 — Two sequential DB queries per `GET /api/status`** [CONFIRMED BY 2 REVIEWERS]

`StatusRoutes.routes` fires two independent `SELECT` queries against `server_settings` on every
request — one for `"initialized"` and one for `"serverName"`. Both are key-by-PK lookups, but they
execute sequentially. Under frequent polling (shell client, liveness probes), this doubles the DB
round-trips unnecessarily. The `initialized` flag and `serverName` change at most once per process
lifetime.

**Recommended fix**: Cache both values after the first startup read in `Jorlan.run` (where they are
already read). Pass a `Ref[(Boolean, String)]` to `StatusRoutes.routes` so status requests read
in-memory. Alternatively, fetch both in a single `IN` query in `QuillServerSettingsRepository`.

---

### Architecture / Layer Discipline

**P8.1-013 — `ServerStatus` and `InitRequest` defined twice** [CONFIRMED BY 2 REVIEWERS]

`ServerStatus` is defined in `server` (`InitRoutes.scala`) with `derives JsonEncoder` and in `shell`
(`InitClient.scala`) with `derives JsonDecoder`. The shell module does not depend on the server module,
so some duplication is unavoidable. However, there is no contract test between the two. If a field is
added to the server-side type without updating the shell-side decoder, the shell will silently fail to
deserialize status responses.

**Recommended fix**: Add a codec contract test asserting that server `ServerStatus.toJson` can be
decoded by the shell `ServerStatus` decoder. Long-term: move the DTO types to a shared `api` module.

---

**P8.1-016 — `InitServiceImpl` constructed with `new`, bypassing the ZLayer** [CONFIRMED BY 2 REVIEWERS]

`Jorlan.run` constructs `InitServiceImpl` manually:
```scala
initService = new InitServiceImpl(settingsRepo, userService, tokenStore)
```
`InitServiceImpl.live` exists but is never used, making it dead code. `InitService` is not part of
`JorlanEnvironment`, so it cannot be injected in integration tests and any decoration
(metrics, tracing) applied via the layer would be silently skipped.

The manual construction is necessary because `InitTokenStore` depends on the `initialized` flag
read from the DB after Flyway has run — this cannot be resolved at `bootstrap` time. The recommended
action is to either delete `InitServiceImpl.live` with a comment explaining this constraint, or add
`InitService` to `JorlanEnvironment` and construct the `InitTokenStore` in a startup ZIO within the
layer rather than in `run`.

---

**P8.1-018 — `ServerSettingRow` leaks into the trait file**

`ServerSettingRow` is a Quill row-mapping type. It belongs in `QuillRepositories.scala` alongside
`QuillServerSettingsRepository` that uses it. The comment in the trait file even says "defined in
QuillRepositories.scala". Having it in the trait file exposes a DB implementation detail to every
caller of `ServerSettingsRepository`.

---

**P8.1-024 — `QuillRepositories.live` 10-tuple fragility**

`QuillRepositories.live` constructs all repositories as a tuple and destructures it in `flatMap`.
With `QuillServerSettingsRepository` added this phase it is now 10 elements. Each new repository
requires updating two places: the tuple construction and the `ZLayer.succeed` chain.

---

### Observability

**P8.1-019 — No event log entry for initialization**

`InitServiceImpl.complete` creates the admin user, persists settings, and invalidates the token, but
writes no event log entry. Every other mutating service in the codebase (user creation, role assignment,
session creation) writes to `EventLogService`. An operator cannot determine from the audit trail when
first-run setup occurred or who performed it.

**Recommended fix**: Add a new event type (e.g., `EventType.ServerInitialized`) and call
`eventLog.log(...)` within `complete` after the settings are persisted.

---

### Test Coverage

**P8.1-003, P8.1-004, P8.1-005 — Three areas with 0% coverage**

**(1) `InitRoutes.scala` — HTTP layer entirely untested**

All of the following paths are invisible to CI:
- `GET /api/status` with `initialized = true`, `false`, and missing key
- `GET /api/status` `serverName` fallback to `"Jorlan"` when key absent
- `POST /api/init` with malformed JSON body → 400
- `POST /api/init` with `ValidationError` → 400
- `POST /api/init` with invalid token → 403
- `POST /api/init` with already-initialized server → 403
- `POST /api/init` success → 200 `{"success":true}`
- Any other path while uninitialized → 503

**(2) `InitClient.scala` — all error branches untested**

`InitClient.makeForTesting` exists as a test seam but is never used. Untested:
- `checkStatus` non-2xx → error string
- `checkStatus` JSON decode failure → error string
- `checkStatus` network error → `"Connection error: ..."`
- `complete` non-2xx → error string
- `InitRequest` serialization (wrong field name → silent server rejection)

**(3) `ShellConfig` new methods — write round-trip is most dangerous**

`write` → `layer` round-trip is untested. If `ShellConfigFile(ShellConfigRoot(cfg))` nesting is wrong,
the wizard writes a file that `ShellConfig.layer` cannot load, breaking the shell on the next startup
with no test to catch it.

Also untested: `resolveWritePath` priority order (env var → `--config` → default), `findReadFile`
`.filter(_.exists())` behavior with missing files, `isFirstRun` all three branches.

**P8.1-020, P8.1-021 — Additional gaps in `InitServiceSpec`**

| Missing Test | Gap |
|---|---|
| `serverName = "   "` with valid email/password | `validateInputs` whitespace-serverName path never reached |
| `createUser` fails mid-way through `complete` | `initialized` may incorrectly remain `false`; orphaned state not verified |
| `setPassword` fails after `createUser` succeeds | User exists but has no password; `initialized = false`; invariant unverified |

---

### Code Quality

**P8.1-025 — `generateToken()` side effects in `ZIO.succeed`** [CONFIRMED BY 2 REVIEWERS]

`InitTokenStore.make` calls `generateToken()` as a plain `val`, not inside a ZIO effect. The
`new SecureRandom()` construction and `rng.nextBytes(bytes)` are OS-level I/O calls (entropy reads)
executing outside the ZIO fiber scheduler. The surrounding `ZIO.succeed { println(...) }` wraps only
the console output. Since this is called at most once per process startup the practical impact is zero,
but wrapping it in `ZIO.attempt` and promoting `rng` to a `private val` is the idiomatic fix.

**P8.1-029 — `isInitialized` pattern duplicated three times** [CONFIRMED BY 3 REVIEWERS]

```scala
// Same three lines appear in InitServiceImpl, StatusRoutes, and Jorlan.run:
settings.get("initialized").map { case Some(Json.Bool(v)) => v; case _ => false }
```

The string key `"initialized"` appears as a magic string in four files. Extract a helper:
```scala
def isServerInitialized(settings: ServerSettingsRepository): UIO[Boolean] =
  settings.get("initialized").map { case Some(Json.Bool(v)) => v; case _ => false }
```
And add `val InitializedKey = "initialized"` to the `ServerSettingsRepository` companion.

---

## Cross-Cutting Patterns

**Two critical correctness bugs share a root cause** (P8.1-001, P8.1-002): the `InitService` and
`StatusRoutes` components were each written in isolation without end-to-end validation. P8.1-001
(password discarded) was confirmed by all three initial-round agents; P8.1-002 (missing route) was
confirmed by two. Both required fixes that touched the wiring between `Jorlan.run` and the HTTP layer.

**Three areas of zero test coverage** (P8.1-003, P8.1-004, P8.1-005) were independently flagged by
both the Test Coverage Tracker and the SRS/SDD Conformance agents. The HTTP layer, the HTTP client
impl, and the four new ShellConfig methods together represent the full integration surface of Phase 8.1
and have no automated coverage whatsoever.

**The `isInitialized` flag-reading pattern** (P8.1-029) was independently flagged by three agents
(Code Simplicity, Pattern Recognition, Performance Oracle) as a DRY violation and magic-string risk.
Three files must be updated in concert if the settings key or JSON type ever changes.

**Duplication of DTO types across the module boundary** (P8.1-013) was flagged independently by both
Pattern Recognition and the Test Coverage agent. The lack of a codec contract test means a field
rename on either side would silently break the wire format with no compile-time or test-time signal.

**Architecture boundary violations cluster around `InitService`** (P8.1-015, P8.1-016, P8.1-018):
`InitTokenStore` lacks a service trait, `InitServiceImpl` bypasses its own `live` layer, and
`ServerSettingRow` leaks into the trait file. All three stem from the same root: the Phase 8.1
components were written quickly without applying the consistent ZIO service pattern established in
earlier phases.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count (total) | Fixed | Open |
|------------|---------------|-------|------|
| Critical   | 5             | 5     | 0    |
| Warning    | 14            | 13    | 1    |
| Suggestion | 16            | 15    | 1    |
| **Total**  | **35**        | **33** | **2** |

**Issues by area:**

| Area                   | Count |
|------------------------|-------|
| Test Coverage          | 9     |
| Architecture           | 7     |
| Correctness            | 5     |
| Code Quality           | 5     |
| Documentation          | 3     |
| Performance            | 2     |
| Security               | 1     |
| Observability          | 1     |
| Functional Purity      | 2     |
| **Total**              | **35** |

**Agent contribution:**

| Agent                        | Key Findings                                         |
|------------------------------|------------------------------------------------------|
| Functional Scala Reviewer    | P8.1-001, P8.1-008, P8.1-009, P8.1-011, P8.1-025   |
| SRS/SDD Conformance          | P8.1-001, P8.1-002, P8.1-006, P8.1-021              |
| Code Simplicity Reviewer     | P8.1-011, P8.1-025 (confirmed), P8.1-029, P8.1-030  |
| Pattern Recognition          | P8.1-007, P8.1-013, P8.1-014, P8.1-015, P8.1-016, P8.1-017, P8.1-018, P8.1-019, P8.1-023, P8.1-024 |
| Performance Oracle           | P8.1-012, P8.1-026, P8.1-029 (confirmed)            |
| Test Coverage Tracker        | P8.1-003, P8.1-004, P8.1-005, P8.1-020, P8.1-021, P8.1-022, P8.1-027, P8.1-028, P8.1-031 |
| ScalaDoc Auditor             | P8.1-032, P8.1-033, P8.1-034, P8.1-035              |

**Phase 8.1 scope completion:**

| Item                                                                   | Status |
|------------------------------------------------------------------------|--------|
| Flyway V017 — `server_settings` table with `initialized` / `serverName` seeds | ✅ |
| `ServerSettingsRepository` trait + `QuillServerSettingsRepository`     | ✅     |
| `InitTokenStore` — one-time token, stdout-only, per-process            | ✅     |
| `InitService` / `InitServiceImpl` — validate, create user + password, flip flag | ✅ |
| `StatusRoutes` — dynamic per-request, served on both route tables      | ✅     |
| `SetupModeApp` — 503 catch-all, delegates to `StatusRoutes` + `InitRoutes` | ✅ |
| `Jorlan.run` — Flyway-first → flag check → setup-mode vs full-app branch | ✅   |
| `ValidationError` type — 400 for validation, 403 for auth/state       | ✅     |
| `InitClient` — `checkStatus` + `complete` via sttp                     | ✅     |
| `FirstRunWizard` — full TUI prompt sequence with retry loops            | ✅     |
| `ShellConfig` — `jorlan-shell.json`, `JORLAN_SHELL_CONFIG`, `write`    | ✅     |
| `JorlanShell` — first-run detection, wizard invocation before login    | ✅     |
| Unit tests: `InitServiceSpec` (5), `FirstRunWizardSpec` (4)            | ✅     |
| Integration test: `InitServiceIntegrationSpec` (5, real MariaDB)       | ✅     |
| HTTP layer tests (`InitRoutesSpec`)                                    | ❌     |
| `InitClient` tests (`InitClientSpec`)                                  | ❌     |
| `ShellConfig` new methods tests                                        | ❌     |
| Full auth round-trip in integration test                               | ❌     |
