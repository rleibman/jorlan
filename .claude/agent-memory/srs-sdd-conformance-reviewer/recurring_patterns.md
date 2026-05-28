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

### Authorization enforcement absent at resolver layer
The capability kernel (Phase 5) exists but is not wired into GraphQL resolvers. There is no permission check before any mutation executes. The `bearerSessionProvider` establishes identity but does not enforce authorization. This is the most critical class of recurring issue to watch for.
