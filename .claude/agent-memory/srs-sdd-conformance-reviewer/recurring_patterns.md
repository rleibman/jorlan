---
name: recurring-patterns
description: Recurring conformance patterns and violation types seen across Jorlan reviews
metadata:
  type: project
---

## Patterns observed as of Phase 10 review (2026-06-04)

### Missed-run policy enum defined but logic never consulted
Phase 10 defined `MissedRunPolicy` (Skip/RunOnce/RunAllMissed) as a domain type and stored it on `SchedulerJob`, but `TriggerEngine.tick` never reads it — all pending jobs are dispatched regardless. Pattern: when a design doc specifies conditional execution logic keyed on an enum value (especially for edge-case policies like missed-run, retry, or backoff), verify there is actual branching code in the engine that reads and acts on the enum, not just a field that is stored.

### Startup recomputation step missing from engine start method
TriggerEngine.start is a simple `logInfo *> tick.repeat(...)`. The mini-design specified a startup pass to recompute next scheduledAt for all recurring triggers before the first tick. Pattern: when a design doc calls out "on startup" behavior separately from the poll loop, check for distinct startup logic in the start/init method, not just in the tick body.

### ZLayer exists but engine instantiated with `new` in Jorlan.run
TriggerEngine.live is defined but Jorlan.run uses `new TriggerEngine(...)` directly, bypassing the layer and duplicating the construction in two code paths. Pattern: when a companion object provides a ZLayer, verify it is actually used in EnvironmentBuilder or the startup flow — a layer that exists but is unused offers no compile-time wiring safety.

## Patterns observed as of Phase 9 review (2026-06-03)

### Checkpoint pipeline wired but not called
`AgentRunnerImpl` injects `MemoryService` and all checkpoint components are registered in `EnvironmentBuilder`, but `processMessage` never calls `memoryService.checkpoint(...)`. The entire summarize → classify → store pipeline is dead at runtime. Pattern: when a design doc specifies a multi-step pipeline wired into an existing method (processMessage), verify the call site explicitly, not just the layer wiring.

### FULLTEXT index created but query path does not use it
V019 adds `FULLTEXT INDEX` on `memoryRecord.value`. `QuillMemoryRepository.search` applies `textSearch` as an in-process Scala `String.contains` after fetching all rows, bypassing the index. Pattern: when a migration adds a specialized index (FULLTEXT, spatial), verify the query layer uses it via the appropriate SQL syntax (MATCH...AGAINST), not in-process filtering.

### Access control gap in destructive mutations
`forgetMemory`, `markMemoryShared`, and `markMemoryPrivate` check the capability grant (`memory.write`) but do not verify the record's `userId == requestingUserId`. Any user with the capability can modify any other user's records. Pattern: capability checks answer "can this user perform this operation class?" but ownership checks answer "can this user act on this specific resource?" — both are required for the deny-by-default model.

### Context injection queries a single scope with no relevance text
`buildMemoryContext` calls `query(MemoryScope.User, ...)` with `text = None`, missing both `Shared`/`Workspace` scopes and text-based relevance filtering. Design doc step 2 specifies querying with `lastUserMessage` as the relevance hint. Pattern: verify that context-injection queries pass all parameters described in the design, not just a subset.

## Patterns observed as of Phase 8.5 review (2026-06-02)

### Dead constructor dependencies
`AgentSessionManagerImpl` injects `SessionHub` but never calls any method on it after the redesign removed session-level teardown. When a redesign removes a use-site, audit all constructor parameters of affected classes to see if any became unused.

### Test coverage gap: `CommandHandlerSpec` missing drain-from-queue happy path
The `handleMessage` drain pattern was redesigned (tokens now flow from a pre-created queue rather than a per-message WS), but the `CommandHandlerSpec` test covering message dispatch does not exercise the case where a session IS active and the queue has tokens. The design doc explicitly listed `CommandHandlerSpec` as a file requiring updates. Pattern: when a design doc's "Files to Change" table lists a test file, verify the named test scenarios are covered, not just that the file exists.

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
