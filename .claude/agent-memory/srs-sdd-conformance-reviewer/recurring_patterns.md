---
name: recurring-patterns
description: Recurring conformance patterns and violation types seen across Jorlan reviews
metadata:
  type: project
---

## Patterns observed as of Phase 6 review (2026-05-27)

### DB layer leaking into server/graphql layer
`JorlanApiEnv` in `JorlanAPI.scala` directly includes `UserZIORepository` (a `db`-module type). This violates the layered architecture principle: the API layer should depend on service traits (application layer), not repository implementations (infrastructure layer). A `UserService` trait was noted as missing. This is a recurring tendency — watch for similar patterns in later phases.

### Missing event log writes for user mutations
`createUser` and `updateUser` bypass the event log entirely. Only permission-related operations (`assignRole`, `upsertPermission`, `deletePermission`) write to the event log. User CRUD should also produce `EventType.UserCreated` / `EventType.UserUpdated` events. Check this in every mutation-adding phase.

### actorId not propagated from session context
Several mutations that do write to the event log pass `actorId = None` rather than extracting the calling user's ID from `JorlanSession`. Pattern: check every event log write for proper actor attribution.

### Authorization enforcement at resolver layer must be preserved
GraphQL mutations now enforce capability checks, so this is no longer an open gap in the current branch. Future reviews should verify that new or modified resolvers continue to authorize actions consistently and do not regress to relying on `bearerSessionProvider` for identity without an explicit capability check.
