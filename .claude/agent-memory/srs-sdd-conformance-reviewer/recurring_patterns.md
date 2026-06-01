---
name: recurring-patterns
description: Recurring conformance patterns and violation types seen across Jorlan reviews
metadata:
  type: project
---

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
