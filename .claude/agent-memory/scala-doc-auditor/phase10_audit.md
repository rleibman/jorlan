---
name: phase10-audit
description: Documentation audit results for Phase 10 durable-scheduler branch — gaps, stale docs, and verified facts
metadata:
  type: project
---

Audit run: 2026-06-04. Branch: main (Phase 10 changes committed).

## Key findings

1. `JobManager` trait — all non-trivial methods lack `@param` and `@return` ScalaDoc. Only `triggerNow` has inline doc.
2. `JobManagerImpl` — class-level ScalaDoc missing. `live` ZLayer value undocumented.
3. `TriggerEngine` — `sessionHub` injected as constructor parameter but never used in the implementation body. Class-level doc says "executes via AgentRunner" but doesn't note the unused `sessionHub` dependency. The `leaseTtl` parameter type is `Int` (seconds) but doc says "Lease TTL is 5 minutes" — this is accurate but fragile (hardcoded default 300 is 5 min).
4. `SchedulerSkill` — all public methods lack ScalaDoc. Class-level comment references a future `SkillRegistry` (Phase 12) which is good, but individual tool methods are undocumented.
5. `ServerUrl` opaque type — existing ScalaDoc is adequate. The `apply` constructor and `value` extension lack `@return` tags, but they are trivial.
6. `User.email` field ScalaDoc says "Empty string for users that only authenticate via OAuth" — stale after V022 makes email NOT NULL at DB level (backfills with `<displayName>-<id>@jorlan.internal`).
7. `SchedulerJob` — missing `@param` for `id`, `agentId`, `userId`, `skillId`, `name`, `status`, `startedAt`, `finishedAt`, `backoffPolicy`, `missedRunPolicy`, `createdAt`. Only a subset of params documented.
8. `SchedulerTrigger` — missing `@param` for `id`, `jobId`, `triggerType`, `enabled`, `createdAt`.
9. `TriggerEngine.start` — doc says "Returns a fiber" but signature is `UIO[Unit]` (the fiber is internal). The doc is misleading.
10. `MissedRunPolicy.RunAllMissed` — cap of 10 documented in ScalaDoc matches the design doc but is NOT enforced in the implementation (no cap logic found in TriggerEngine). This is a documentation-vs-implementation discrepancy.

## Verified accurate facts
- Lease TTL default = 300 seconds (5 minutes) — confirmed in TriggerEngine constructor.
- Poll interval default = 10 seconds — confirmed.
- workerId format = "hostname:pid" — confirmed in TriggerEngine.workerId.
- V021 adds: userId, maxRetries, retryCount, backoffSeconds, backoffPolicy, missedRunPolicy, leasedAt, leasedBy columns.
- V022 makes user.email NOT NULL, backfills with `CONCAT(REPLACE(displayName, ' ', '.'), '-', id, '@jorlan.internal')`.
- Exponential backoff formula: `backoffSeconds * 2^retryCount` (toLong conversion used) — confirmed in scheduleRetryOrFail.
- SchedulerJobQueued EventType exists in EventType enum but is never emitted by TriggerEngine (only Started/Completed/Failed are logged).
