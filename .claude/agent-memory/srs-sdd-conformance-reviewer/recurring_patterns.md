---
name: recurring-patterns
description: Recurring conformance patterns and violation types seen across Jorlan reviews
metadata:
  type: project
---

## Patterns observed as of Phase 8.3/8.4 review (2026-06-01)

### Flyway migration seeding lowercase enum values while Scala derives PascalCase JSON
V018 seeds `"formality":"professional"` (lowercase) but `Formality` enum derives `JsonEncoder/JsonDecoder` via zio-json macro derivation, which encodes case names as-is: `"Professional"`, `"Casual"`, etc. The default personality loaded from DB at startup will fail to decode and silently fall back to `Personality.default`. This is a latent class of bug: whenever a Flyway seed inserts an enum JSON value, verify it matches the zio-json derived representation (PascalCase for Scala 3 enum cases by default).

### GraphQL query tests for new schema elements not added to JorlanAPISpec
When `serverPersonality` query and `updatePersonality` mutation were added, no corresponding tests were added to `JorlanAPISpec`. The spec test file covers the original mutations (createUser, createRole, etc.) but has no test exercising the personality query/mutation through the Caliban interpreter. New schema fields should always have GraphQL-level tests, not just service-level tests.

### /personality command not added to the shell commands appendix table
Phase 8.3 added `/personality` as a `ShellCommand.Personality` enum case and wired it in `CommandHandler`, but the roadmap appendix "Supported shell commands" table was not updated with a new row for `/personality`. The table is the canonical source of truth for shell command status.

### Shell /personality command implements read-only; update sub-commands not implemented
The roadmap Phase 8.3 spec says the `/personality` command should also allow "updating individual fields or the full prompt" via sub-commands or interactive prompts. The implementation only reads (calls `serverPersonality` query) and does not offer any update path. This is a partial implementation gap.

## Patterns observed as of Phase 8.1 review (2026-05-31)

### ShellConfig.layer ignores --config CLI arg during bootstrap
`ShellConfig.layer` calls `findReadFile(Nil)` — not `findReadFile(args)` — so the `--config` CLI flag is not honoured at config-load time. It is honoured for `isFirstRun` and `resolveWritePath` later in `JorlanShell.run` because those pass `args.toList`. This means the load-path priority in the spec (env var → --config → jorlan-shell.json → jorlan.json) is incomplete at bootstrap.

### isFirstRun check uses empty string rather than default URL
`ShellConfig.isFirstRun` returns `true` when `cfg.serverUrl.isEmpty`. But `ShellConfig.serverUrl` has a default of `"http://localhost:8080"`, so it will never be empty when loaded from `application.conf` defaults. The correct check is whether the file was absent OR the file existed but lacked a `serverUrl` key. This makes the file-presence branch the only effective trigger.

### InitService.complete does not hash the admin password before storing
`InitServiceImpl.complete` calls `users.createUser(adminName, Some(adminEmail), None)` — the `adminPassword` parameter is accepted, validated for length, but never passed to `createUser`. The password is dropped on the floor. The newly created admin user has no password, making normal login impossible after first-run setup.

### StatusRoutes object defined but not wired into the full-app build
`StatusRoutes` is defined in `InitRoutes.scala` but is only used inside `SetupModeApp` (setup-mode only). `buildRoutes` in `Jorlan.scala` does not include `StatusRoutes`, so `GET /api/status` returns 404 (or falls through to a 500) on an initialized server. The spec requires the endpoint to be "live at all times."

### SetupModeApp returns 503 for all validation failures, not 400
`InitRoutes.scala` maps every `JorlanError` from `InitService.complete` to HTTP 403. Validation errors (bad email, short password, empty server name) should return 400; only token/already-initialized errors should return 403.

## Patterns observed as of Phase 8 review (2026-05-29)

### DB layer leaking into server/graphql layer
`JorlanApiEnv` in `JorlanAPI.scala` directly includes `UserZIORepository` (a `db`-module type). This violates the layered architecture principle: the API layer should depend on service traits (application layer), not repository implementations (infrastructure layer). A `UserService` trait was noted as missing. This is a recurring tendency — watch for similar patterns in later phases.

### Missing event log writes for user mutations
`createUser` and `updateUser` bypass the event log entirely. Only permission-related operations (`assignRole`, `upsertPermission`, `deletePermission`) write to the event log. User CRUD should also produce `EventType.UserCreated` / `EventType.UserUpdated` events. Check this in every mutation-adding phase.

### actorId not propagated from session context
Several mutations that do write to the event log pass `actorId = None` rather than extracting the calling user's ID from `JorlanSession`. Pattern: check every event log write for proper actor attribution.

### Authorization enforcement at resolver layer must be preserved
GraphQL mutations now enforce capability checks, so this is no longer an open gap in the current branch. Future reviews should verify that new or modified resolvers continue to authorize actions consistently and do not regress to relying on `bearerSessionProvider` for identity without an explicit capability check.

### Shell enum variant carrying no payload loses the argument
Phase 8: `ShellCommand.NewSession` is a zero-arity case, so `parse("new", "llama3")` discards "llama3". Watch for `ShellCommand` cases that need to carry an argument payload — they must be parameterized variants, not singletons.

### `.ensuring` masks error-specific event types
`OllamaModelGateway` writes `ModelCallCompleted` in `.ensuring`, which fires on both success and failure. This means `ModelCallFailed` is never recorded. Pattern: use `ZStream.onError` / `ZIO.onError` for error-specific events; reserve `.ensuring` for side effects that are always needed (resource cleanup).

### listSessions resolver bypasses the service-layer search method
`JorlanAPI` `listSessions` resolver called `getSession(AgentSessionId.empty)` instead of `searchSessions`. Delegating to a lookup-by-ID in a list resolver is a recurring risk; always verify that list resolvers call the appropriate search/list method on the service layer.
