---
name: recurring-patterns
description: Recurring conformance patterns and violation types seen across Jorlan reviews
metadata:
  type: project
---

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
