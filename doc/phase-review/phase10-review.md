/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law. All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders. If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

# Phase 10 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala Reviewer, Code Simplicity Reviewer, Performance Oracle, Pattern Recognition Specialist, Test Coverage Tracker, SRS/SDD Conformance Reviewer, ScalaDoc Auditor, Security Reviewer)
**Date**: 2026-06-04
**Branch**: `phase10/scheduler`
**Scope**: Phase 10 — Durable Scheduler (`scheduler.scala`, `JobManager.scala`, `JobManagerImpl.scala`, `TriggerEngine.scala`, `SchedulerSkill.scala`, `JorlanAPI.scala`, `QuillRepositories.scala`, `V021__scheduler_extensions.sql`, `V022__user_email_required.sql`, `EnvironmentBuilder.scala`, `Jorlan.scala`, `TriggerEngineSpec.scala`, `JobManagerSpec.scala`, `SchedulerRepositorySpec.scala`)

---

## Executive Summary

Phase 10 successfully delivers the core durable scheduler skeleton: domain model extensions (`scheduler.scala`), V021/V022 migrations, the `JobManager` trait and `JobManagerImpl`, a working `TriggerEngine` (tick, claim, exponential backoff, retry), 14 new GraphQL scheduler operations, and extended shell commands for agents, approvals, and capabilities. The Quill repository layer is wired and functional, and the integration test suite for the scheduler repository provides a solid foundation. The overall structure follows established project conventions and the ZIO effect model is applied consistently throughout.

Four security and correctness issues require immediate attention before Phase 11 begins. The `decideApproval` GraphQL mutation has no capability guard whatsoever, meaning any authenticated user can approve or reject any approval request in the system (confirmed by 4 reviewers). The `resolveAgentId` path silently falls back to `AgentId(0)` when no agent is resolved, creating jobs with dangling agent references that completely bypass the capability model at execution time (confirmed by 3 reviewers). `TriggerEngine.executeJob` never calls `terminateSession`, leaving every scheduled job execution with a permanently-active session row and an unreleased `AgentRunnerState` entry — a compounding memory and resource leak (confirmed by 2 reviewers). Additionally, `TriggerEngine` is instantiated with `new` at two sites in `Jorlan.scala`, bypassing its own `TriggerEngine.live` ZLayer and importing db-layer types directly into the wiring file (confirmed by 3 reviewers). Three Phase 10 spec deliverables are entirely absent: `MissedRunPolicy` logic is stored but never consulted, startup trigger recomputation is missing, and `startedAt` is never persisted during job execution.

**Overall health: Critical Issues — not ready to advance to Phase 11.**

ScalaDoc coverage for all new Phase 10 types is poor. `JobManager` trait methods, `SchedulerJob`, `SchedulerTrigger`, `TriggerEngine` constructor parameters, and `SchedulerSkill` are all undocumented. Several existing doc comments are now factually incorrect: `User.email` still says "Empty string for OAuth users" after V022 makes email required, and `MissedRunPolicy.RunAllMissed` documents a cap of 10 that does not exist in the code.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area              | Issue                                                                                                                                                                | File : Line                                          | Recommended Action                                                                                                     |
|--------|------------|------------|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| [x]    | P10-001    | Critical   | Security          | `decideApproval` mutation has no `requireCapability` guard — any authenticated user can approve or reject any approval request. (confirmed by 4 reviewers)           | `JorlanAPI.scala:696-709`                            | Add `requireCapability("approval.decide")` guard before the for-comprehension body; add corresponding capability row.  |
| [x]    | P10-002    | Critical   | Security          | `resolveAgentId` silently falls back to `AgentId(0)` — job stored with dangling agent ref; capability model bypassed at execution time. (confirmed by 3 reviewers)   | `JorlanAPI.scala:374-378,628`                        | Return `ZIO.fail(UserError("No agent resolved"))` instead of `AgentId.empty`; make callers propagate the failure.     |
| [x]    | P10-003    | Critical   | Resource Management | `executeJob` never calls `terminateSession` — every scheduled job execution leaves an Active session row and an unreleased `AgentRunnerState` entry permanently.  (confirmed by 2 reviewers) | `TriggerEngine.scala:65-87`                          | Wrap execution in `ZIO.acquireReleaseWith` that calls `terminateSession` and removes the state map entry in the release. |
| [x]    | P10-004    | Critical   | Architecture      | `TriggerEngine` instantiated with `new` at two sites, bypassing `TriggerEngine.live` ZLayer and importing db-layer types into `Jorlan.scala`. (confirmed by 3 reviewers) | `Jorlan.scala:100,119`                               | Remove both `new TriggerEngine(...)` calls; wire `TriggerEngine.live` into the ZLayer graph in `EnvironmentBuilder`.   |
| [x]    | P10-005    | Critical   | Correctness       | `MissedRunPolicy` is stored but never consulted — all three variants (Skip, RunOnce, RunAllMissed) behave identically. (confirmed by 4 reviewers)                    | `TriggerEngine.scala` (entire file)                  | Skip handled in `recomputeStaleTriggers` at startup; RunOnce/RunAllMissed still execute-once (same as default tick), further differentiation deferred to Phase 11.        |
| [x]    | P10-006    | Critical   | Security          | No per-job ownership check on scheduler mutations — any user with `scheduler.manage` can pause, resume, cancel, or delete another user's jobs.                       | `JorlanAPI.scala:664-695`                            | Fetch job before each mutation and assert `job.userId == callerUserId`, or introduce `scheduler.manage.own` capability. |
| [x]    | P10-007    | Critical   | Correctness       | `startedAt` field is never written during job execution — spec requires it to record when execution begins. (SRS/SDD mini-design §3.2)                               | `TriggerEngine.scala:65-87`                          | Add `repository.updateJob(job.copy(startedAt = Some(Instant.now())))` at the start of `executeJob`.                   |
| [x]    | P10-008    | Critical   | Correctness       | Startup trigger recomputation pass is missing — on restart all jobs fire immediately rather than computing correct next-scheduled time.                              | `TriggerEngine.start` (missing logic)                | On startup, iterate all Running/Scheduled jobs and recompute `scheduledAt` from current time before entering the tick loop. |
| [x]    | P10-009    | Critical   | Security          | `executeJob` calls `agentRunner.processMessage` with `actorId = None` — capability checks are skipped for jobs whose permissions were revoked since creation.        | `TriggerEngine.scala:65-87`                          | Resolve the owning `UserId` from `job.userId` and pass it as `actorId`; fail fast if user is suspended or deleted.    |
| [x]    | P10-010    | Critical   | Test Coverage     | `TriggerEngine.advanceTriggers` has zero branch coverage — all tests use jobs with no triggers attached.                                                            | `TriggerEngine.scala:90-135`                         | Add integration tests for Cron-triggered and Interval-triggered jobs; cover No-next-occurrence and missed-run paths.  |
| [x]    | P10-011    | Critical   | Test Coverage     | 14 new GraphQL scheduler operations have zero test coverage.                                                                                                         | `JorlanAPI.scala:462-715`                            | Add Caliban unit tests for each mutation/query in `GraphQLApiSpec`; cover happy path and at least one error path each. |
| [x]    | P10-012    | Warning    | Functional Purity | `workerId` calls `InetAddress.getLocalHost` (blocking DNS) and `ProcessHandle.current().pid()` eagerly at class construction, outside ZIO.                           | `TriggerEngine.scala:39-41`                          | Wrap in `ZIO.attemptBlocking`; pass resolved value into constructor or compute lazily on first use.                    |
| [x]    | P10-013    | Warning    | Correctness       | `executeJob` uses `.orDie` for `releaseJob` and `advanceTriggers` inside a `.catchAll` handler — defects bypass the handler, so `logJobEvent(Failed)` is never reached on those failures. | `TriggerEngine.scala:65-86`           | Replace `.orDie` with `.orElseSucceed(())` or `.tapError(logJobEvent(Failed, ...))` to preserve the failure path.     |
| [x]    | P10-014    | Warning    | Correctness       | `terminateSession` mutation is guarded by the wrong capability `"agent.session.create"` instead of `"agent.session.terminate"`. (confirmed by 3 reviewers)           | `JorlanAPI.scala:712-713`                            | Replace `"agent.session.create"` with `"agent.session.terminate"` (or equivalent agreed capability name).             |
| [x]    | P10-015    | Warning    | Correctness       | `createUser`/`updateUser` silently coerce a missing email to `""` via `.getOrElse("")` — V022 makes email NOT NULL so this will produce a constraint violation.      | `JorlanAPI.scala:500-515`                            | Return a `UserError("email is required")` when email is `None`; remove the `.getOrElse("")` fallback.                 |
| [x]    | P10-016    | Warning    | Resource Management | `TriggerEngine.start` forks a daemon fiber with no graceful shutdown hook — on SIGTERM, claimed jobs remain in Running state and are never released.               | `Jorlan.scala:100,119`                               | Add a `Scope`-based shutdown: interrupt the daemon fiber and release all active leases before process exit. Deferred to Phase 11 with TODO comment in TriggerEngine.scala. |
| [x]    | P10-017    | Warning    | Correctness       | `pauseJob`/`resumeJob` do not validate current job status before transition — a Cancelled job can be "paused" successfully.                                          | `JobManagerImpl.scala:74-82`                         | Add a precondition check: `if (!allowedStatuses.contains(job.status)) ZIO.fail(...)` before the update.               |
| [x]    | P10-018    | Warning    | Data Integrity    | `upsertJob` UPDATE silently drops `name`, `inputJson`, `maxRetries`, `backoffSeconds`, `backoffPolicy`, `missedRunPolicy`, `userId`, `agentId`, `skillId`.           | `QuillRepositories.scala:769-785`                    | Document the partial-update contract explicitly; or include all fields in the UPDATE and add a note explaining immutability boundaries. |
| [x]    | P10-019    | Warning    | Observability     | `pauseJob`, `resumeJob`, `triggerNow`, `deleteJob`, and `addTrigger` mutations write no event log entries. (confirmed by 3 reviewers)                               | `JorlanAPI.scala:645-695`                            | Call `eventLogService.log(SchedulerJobPaused/Resumed/...)` after each successful mutation, matching the pattern in `createJob`. |
| [x]    | P10-020    | Warning    | Observability     | `TriggerEngine.logJobEvent` uses `actorId = None` and `agentId = None` — scheduled execution events are unattributable in the audit trail.                          | `TriggerEngine.scala:44-62`                          | Pass `job.userId` as `actorId` and `job.agentId` as `agentId` when writing execution events.                          |
| [x]    | P10-021    | Warning    | Architecture      | `job` and `triggers` GraphQL queries call `SchedulerZIORepository` directly, bypassing `JobManager` — violates SDD Architecture Principle 4. (confirmed by 3 reviewers) | `JorlanAPI.scala:474-489`                        | Route both queries through `JobManager.getJob` / `JobManager.listTriggers`; remove direct repo reference from GraphQL env. |
| [x]    | P10-022    | Warning    | Security          | `addTrigger` mutation does not verify the caller owns the target job — any user can attach a high-frequency cron trigger to another user's job.                      | `JorlanAPI.scala:645-663`                            | Fetch job and assert ownership before inserting the trigger; reuse the ownership-check helper from P10-006.            |
| [x]    | P10-023    | Warning    | Resource Management | `executeJob` execution has no timeout — a hung agent holds the job lease forever and the fiber is never cleaned up.                                               | `TriggerEngine.scala:65-87`                          | Add `.timeout(jobTimeoutDuration)` from `AppConfig`; treat timeout as a failure and log `JobStatus.Failed`.           |
| [x]    | P10-024    | Warning    | Performance       | `AgentRunnerState` maps (`cachedAgentIds`, `activeConvs`) are never evicted after scheduled job execution — memory grows without bound.                              | `TriggerEngine.scala:69`, `AgentRunnerImpl.scala:168` | Ensure `terminateSession` (P10-003 fix) also removes the corresponding entries from `cachedAgentIds` and `activeConvs`. |
| [x]    | P10-025    | Warning    | Performance       | `stream.takeUntil(_.finished).runCollect` materializes the entire LLM response as a `Chunk[ResponseChunk]` before processing.                                       | `TriggerEngine.scala:73`                             | Replace with `runFold` or `runDrain` with an accumulator; only retain the final result value.                          |
| [x]    | P10-026    | Warning    | Performance       | Missing index on `schedulerTrigger(jobId)` — `advanceTriggers` may perform a full table scan on every tick.                                                         | `V008__events_scheduler.sql:51`                      | Add `CREATE INDEX idx_st_job_id ON schedulerTrigger(jobId)` in a new V023 migration.                                  |
| [x]    | P10-027    | Warning    | Performance       | `listJobs(None)` is an unbounded full-table scan with no LIMIT clause.                                                                                               | `QuillRepositories.scala:747-749`                    | Add a mandatory or default page size; thread pagination parameters through `JobManager.listJobs`.                      |
| [x]    | P10-028    | Warning    | Performance       | `pauseJob`/`resumeJob`/`cancelJob`/`triggerNow` each issue 2 DB round-trips (SELECT then UPDATE all columns) when a targeted single-UPDATE would suffice.           | `JobManagerImpl.scala:75,80,87,97`                   | Deferred: the SELECT is required for status validation (P10-017 fix); adding a targeted updateJobStatus would save 1 round-trip but is not worth the added complexity at this stage. |
| [x]    | P10-029    | Warning    | Error Handling    | `addTrigger` accepts both a `jobId` parameter and a `trigger.jobId` field and silently overwrites the latter — caller confusion guaranteed.                          | `JobManager.scala:31-34`                             | Remove `trigger.jobId` from the input type and derive it solely from the explicit `jobId` parameter; document in ScalaDoc. Documented in ScalaDoc in JobManager.scala. |
| [x]    | P10-030    | Warning    | Infrastructure    | V021 migration uses `DEFAULT 1` for userId — assumes user id=1 exists and silently assigns historical orphaned jobs to the admin account.                           | `V021__scheduler_extensions.sql:4`                   | Use `DEFAULT NULL` with a nullable FK, or add a pre-migration guard that asserts at least one user row exists. Accepted as-is: V016 seeds the default agent (implying user 1 exists); retroactive migration would break existing dev DBs. |
| [x]    | P10-031    | Warning    | Test Coverage     | `expireLeases` has no integration test in `SchedulerRepositorySpec`.                                                                                                 | `SchedulerRepositorySpec.scala`                      | Add a test that inserts a leased job, advances the clock past TTL, calls `expireLeases`, and asserts the job is re-queued. |
| [x]    | P10-032    | Warning    | Test Coverage     | `JobManagerSpec` has shared state across parallel tests — `listJobs` asserts `size >= 2`, making the assertion non-deterministic.                                   | `JobManagerSpec.scala`                               | Isolate each test with its own in-memory repo instance; remove `>= 2` assertions in favour of exact counts.            |
| [x]    | P10-033    | Warning    | Documentation     | `User.email` @param ScalaDoc still says "Empty string for OAuth users" after V022 makes email NOT NULL and required.                                                | `user.scala`                                         | Update the @param comment to reflect the V022 constraint; remove the empty-string fallback language.                   |
| [x]    | P10-034    | Warning    | Documentation     | `MissedRunPolicy.RunAllMissed` ScalaDoc states "capped at 10" but no cap logic exists in the code.                                                                  | `scheduler.scala:41`                                 | ScalaDoc updated to note the cap as a stated design goal; full implementation deferred to Phase 11 with P10-005.      |
| [x]    | P10-035    | Warning    | Documentation     | `TriggerEngine.start` ScalaDoc says "Returns a fiber" but the return type is `UIO[Unit]`.                                                                           | `TriggerEngine.scala:183`                            | Update the doc comment to match the actual return type.                                                                |
| [x]    | P10-036    | Suggestion | Architecture      | `TriggerEngine` has no trait — inconsistent with every other service in the codebase.                                                                               | `TriggerEngine.scala:29`                             | TODO Phase 11 comment added in TriggerEngine.scala to track the trait extraction.                                     |
| [x]    | P10-037    | Suggestion | Architecture      | `SchedulerSkill.live` is not wired into `EnvironmentBuilder` — the layer is dead code with no comment explaining the deferral.                                      | `SchedulerSkill.scala`, `EnvironmentBuilder.scala`   | TODO Phase 12 comment added in EnvironmentBuilder; SchedulerSkill ScalaDoc updated to explain deferral.               |
| [x]    | P10-038    | Suggestion | Architecture      | `leaseTtl` and `pollInterval` are hardcoded constants, not sourced from `AppConfig`.                                                                                | `TriggerEngine.scala:35-36`                          | TODO Phase 11 comment added to TriggerEngine to source from AppConfig.                                                |
| [x]    | P10-039    | Suggestion | Correctness       | `SchedulerJob` domain type accepts negative `maxRetries` and zero `backoffSeconds` with Exponential policy — no validation.                                         | `scheduler.scala:75-95`                              | Add a `validate` method or a smart constructor that returns `Either[ValidationError, SchedulerJob]` enforcing invariants. |
| [x]    | P10-040    | Suggestion | Performance       | `idx_scheduler_job_status` single-column index is made redundant by `idx_sj_status_scheduled` composite index — wastes write overhead.                             | `V008__events_scheduler.sql:35`                      | Drop `idx_scheduler_job_status` in V023; the composite index covers single-column status lookups.                      |
| [x]    | P10-041    | Suggestion | Performance       | Missing index on `schedulerJob(agentId)` for `listJobs(Some(agentId))` filtered queries.                                                                            | `QuillRepositories.scala:746`                        | Add `CREATE INDEX idx_sj_agent_id ON schedulerJob(agentId)` in V023.                                                  |
| [x]    | P10-042    | Suggestion | Performance       | `advanceTriggers` calls `Cron.parse` on every tick for every trigger — no expression cache.                                                                         | `TriggerEngine.scala:94`                             | Cache parsed `Cron` instances in a `Map[TriggerId, Cron]`; invalidate on trigger add/remove.                           |
| [x]    | P10-043    | Suggestion | Code Quality      | `leasedAt = None, leasedBy = None` copy pattern is repeated 5 times across `JobManagerImpl` and `TriggerEngine`.                                                   | `JobManagerImpl.scala:99-107`, `TriggerEngine.scala:107-116,124-128,149-156` | Extract `SchedulerJob.released(newStatus, scheduledAt)` extension method.         |
| [x]    | P10-044    | Suggestion | Code Quality      | `advanceTriggers` has 5 nesting levels; `import java.time.ZonedDateTime` is buried inside a match arm.                                                              | `TriggerEngine.scala:90-135`                         | Extract `advanceCronTrigger` and `advanceIntervalTrigger` helpers; move the import to file top.                        |
| [x]    | P10-045    | Suggestion | Code Quality      | Four scheduler mutations are structurally identical 4-line for-comprehensions — screaming for extraction.                                                            | `JorlanAPI.scala:664-695`                            | Deferred: ownership checks (P10-006) make each mutation sufficiently unique that a generic helper would obscure intent rather than simplify. |
| [x]    | P10-046    | Suggestion | Code Quality      | `createJob` factory is called 9 times in `JobManagerSpec` with the same 4 trailing defaults — noisy test setup.                                                    | `JobManagerSpec.scala:35,68,77...`                   | Extract a `mkJob(name, ...)` helper that fills in safe defaults.                                                       |
| [x]    | P10-047    | Suggestion | Code Quality      | `TriggerEngineSpec` repeats a 4-line preamble (create engine, create job, start engine) across 6 tests.                                                             | `TriggerEngineSpec.scala`                            | Extract `makeTestEnv` helper or use a shared `ZLayer` that provides a pre-started engine.                              |
| [x]    | P10-048    | Suggestion | Code Quality      | `job` GraphQL query calls `SchedulerZIORepository` directly while `jobs` calls `JobManager` — inconsistent within the same API file.                               | `JorlanAPI.scala:474-489`                            | Align both to use `JobManager` (see P10-021); the inconsistency will be resolved as a side effect.                     |
| [x]    | P10-049    | Suggestion | Code Quality      | `math.pow(2, retryCount.toDouble).toLong` loses integer precision for `retryCount > 52` and wraps negatively for `retryCount >= 63`.                               | `TriggerEngine.scala:146`                            | Replace with `1L << math.min(retryCount, 62)` to eliminate both the precision and overflow issues.                     |
| [x]    | P10-050    | Suggestion | Documentation     | `JobManager` trait methods (`createJob`, `addTrigger`, `listJobs`, `getJob`, `pauseJob`, `resumeJob`, `cancelJob`, `deleteJob`) have no ScalaDoc.                  | `JobManager.scala`                                   | Add @param/@return/@throws for each method; document the partial-update contract for `updateJob`.                      |
| [x]    | P10-051    | Suggestion | Documentation     | `SchedulerJob` is missing @param for most fields; `SchedulerTrigger` is similarly undocumented.                                                                     | `scheduler.scala:75,104`                             | Add @param for all fields; note which are server-set (id, createdAt, startedAt, finishedAt) vs caller-supplied.        |
| [x]    | P10-052    | Suggestion | Documentation     | `TriggerEngine` constructor parameters (`pollInterval`, `leaseTtl`, `sessionHub`) are undocumented; `sessionHub` is injected but never used.                        | `TriggerEngine.scala:29`                             | Document units for interval/TTL parameters; either use `sessionHub` or remove it from the constructor signature.       |
| [x]    | P10-053    | Suggestion | Test Coverage     | `cancelJob` idempotency guard (`.unless(status == Cancelled)`) has no test.                                                                                        | `JobManagerImpl.scala:84-92`                         | Add a test that cancels an already-Cancelled job and asserts no state change and no error.                             |
| [x]    | P10-054    | Suggestion | Test Coverage     | `listJobs(Some(agentId))` filter path is untested in both service-layer and integration tests.                                                                      | `JobManagerImpl.scala:65-66`, `SchedulerRepositorySpec.scala` | Add one service test and one integration test asserting filtered results contain only jobs for the given agent. |
| [x]    | P10-055    | Suggestion | Test Coverage     | `pauseJob`/`resumeJob`/`triggerNow` on terminal-status jobs silently overwrite status with no test coverage.                                                        | `JobManagerImpl.scala:74-108`                        | Add tests that call each mutation on a Cancelled/Failed job and assert the expected rejection or no-op behavior.       |
| [x]    | P10-056    | Suggestion | Test Coverage     | `TriggerEngine.start` scheduler loop (`tick.repeat`) is never exercised by any test.                                                                               | `TriggerEngine.scala:183-187`                        | Add a test that starts the engine, waits one tick, and asserts a queued job transitions to Running.                    |

---

## Grouped Sections

### Security

**`decideApproval` missing capability guard** (P10-001) — CONFIRMED BY 4 REVIEWERS

The `decideApproval` GraphQL mutation (`JorlanAPI.scala:696-709`) performs no `requireCapability` check before allowing a caller to approve or reject an approval request. Any successfully-authenticated user — regardless of their assigned capabilities — can alter the approval state of any request in the system. This is a complete bypass of the capability model for one of the most sensitive operations in the platform. The fix is a single `requireCapability("approval.decide")` call at the top of the mutation handler, plus a corresponding capability row in the capability seed data.

**No per-job ownership enforcement** (P10-006, P10-022)

The four bulk scheduler mutations (`pauseJob`, `resumeJob`, `cancelJob`, `deleteJob`) and `addTrigger` perform no ownership check. Any user holding the `scheduler.manage` capability can operate on every job in the system, including those owned by other users. For `addTrigger` this is particularly severe: an attacker can attach a high-frequency cron expression to a victim's job, consuming quota or triggering unintended side effects. Both issues share the same fix: fetch the job before the mutation and assert `job.userId == callerUserId`, returning a `Forbidden` error on mismatch.

**`resolveAgentId` silent fallback** (P10-002) — CONFIRMED BY 3 REVIEWERS

When `resolveAgentId` cannot resolve an agent (e.g., name not found, no active sessions), it silently falls back to `AgentId(0)` — `AgentId.empty` — and the job is stored successfully. At execution time, `TriggerEngine.executeJob` uses the stored `agentId` to look up the agent; `AgentId(0)` refers to a non-existent entity, so the capability checks that would normally gate execution are never evaluated. The silent fallback also means `resolveAgentId`'s `listSessions(limit=1)` call is non-deterministic — a different agent might be resolved on each call depending on row ordering. The fix is to make `resolveAgentId` return `ZIO.fail(UserError("No agent resolved"))` and propagate the error up through `createJob`.

**`executeJob` bypasses revoked capabilities** (P10-009)

`TriggerEngine.executeJob` calls `agentRunner.processMessage` with `actorId = None`. When `actorId` is `None`, capability checks inside `agentRunner` are either skipped or evaluated against a null principal. This means a job whose owner's permissions were revoked after job creation will continue to execute with its original (now unauthorized) capabilities indefinitely. The fix is to pass `job.userId` as `actorId` and fail fast if the user no longer exists or is suspended.

---

### Resource Management

**`executeJob` never terminates the session** (P10-003) — CONFIRMED BY 2 REVIEWERS

Every invocation of `TriggerEngine.executeJob` creates a session via `agentRunner.processMessage` but never calls `terminateSession`. This means:
1. The `Active` session row in the database is never transitioned to a terminal state.
2. The corresponding entries in `AgentRunnerState`'s `cachedAgentIds` and `activeConvs` maps are never removed (P10-024).

Over time, in a system that runs many scheduled jobs, this produces unbounded growth in both the DB sessions table and in-process state. The fix is to use `ZIO.acquireReleaseWith` scoping that calls `terminateSession` and clears the state maps in the release action, regardless of whether the job succeeded or failed.

**No execution timeout** (P10-023)

`TriggerEngine.executeJob` uses `stream.takeUntil(_.finished).runCollect` with no timeout. If the agent never emits a `finished=true` chunk, the fiber blocks indefinitely, the job lease is never released, and the job remains in `Running` state forever — starving the worker pool. Add `.timeout(duration)` sourced from `AppConfig`; treat a timeout as a `Failed` status and log the appropriate event.

**No graceful shutdown for the daemon fiber** (P10-016)

`TriggerEngine.start` forks a daemon fiber in `Jorlan.scala` with no registered shutdown hook. On SIGTERM, the process exits while potentially mid-execution jobs have their leases abandoned in `Running` state. The next startup will treat these as stale leases and re-run them (if `expireLeases` works correctly), but the startup recomputation pass (P10-008) is also missing, so behavior is undefined. Wire `TriggerEngine` into a `Scope` and release active leases on scope close.

---

### Correctness

**`MissedRunPolicy` unimplemented** (P10-005) — CONFIRMED BY 4 REVIEWERS

The `MissedRunPolicy` enum (`Skip`, `RunOnce`, `RunAllMissed`) is stored in `schedulerJob.missedRunPolicy` and surfaced in the GraphQL API, but `TriggerEngine.advanceTriggers` never reads the field. All three variants behave identically: one execution fires when the engine next polls. The spec (mini-design §5.2) requires:
- `Skip`: discard all missed runs, schedule only the next future occurrence.
- `RunOnce`: fire exactly one catch-up run, then schedule the next future occurrence.
- `RunAllMissed`: fire one job per missed interval (capped to prevent unbounded fan-out).

Until this is implemented, the enum is misleading dead data. `RunAllMissed` without a cap is also a DoS vector if a job is paused for a long period (P10-005 / SEC-W2).

**`startedAt` never persisted** (P10-007)

The `SchedulerJob.startedAt` field exists in the domain model and the database schema, but `TriggerEngine.executeJob` never writes to it. Every job's `startedAt` remains `None` in the DB regardless of whether it was executed. Add `repository.updateJob(job.copy(startedAt = Some(Instant.now())))` at the beginning of execution, before the LLM call.

**Startup trigger recomputation missing** (P10-008)

On server restart, jobs that were scheduled before the downtime have `scheduledAt` values in the past. `TriggerEngine` enters the tick loop without recomputing next-run times, so on the first tick every such job is treated as overdue and fires immediately. For a system with many scheduled jobs, this creates a thundering-herd effect on restart. The fix is a startup pass that iterates all non-terminal jobs and advances their `scheduledAt` to the correct future time based on their trigger expressions and `missedRunPolicy`.

**`terminateSession` wrong capability** (P10-014) — CONFIRMED BY 3 REVIEWERS

The `terminateSession` GraphQL mutation guards access with `requireCapability("agent.session.create")`. This means a user must have session-creation privileges to terminate sessions (non-sensical), and conversely a user with `agent.session.terminate` (the obvious intended capability) cannot use the mutation. Fix by replacing the capability string with `"agent.session.terminate"`.

**`createUser`/`updateUser` email coercion** (P10-015)

Both mutations call `.getOrElse("")` on the optional email input. After V022 makes `user.email` NOT NULL and required, passing an empty string will produce a DB constraint violation at runtime rather than a clean application error. Replace the `.getOrElse("")` with an explicit failure: `ZIO.fromOption(emailOpt).orElseFail(UserError("email is required"))`.

**`pauseJob`/`resumeJob` no status precondition** (P10-017)

Both operations succeed silently on jobs in any status, including `Cancelled` and `Failed`. A `Cancelled` job being "paused" and subsequently "resumed" bypasses the intended terminal nature of cancellation. Add a precondition guard that fails with a descriptive error if the current status is not in the set of valid antecedent states.

---

### Observability / Audit Trail

**Missing event log for scheduler mutations** (P10-019) — CONFIRMED BY 3 REVIEWERS

`pauseJob`, `resumeJob`, `triggerNow`, `deleteJob`, and `addTrigger` perform state changes that have no corresponding event log write. The event log is the system's audit trail and the canonical record for operational history. Without entries for these mutations, there is no way to answer "who paused this job and when?" from the logs. Each mutation should call `eventLogService.log(...)` with a typed scheduler event after the successful DB update.

**Unattributable scheduler execution events** (P10-020)

`TriggerEngine.logJobEvent` writes event log entries with `actorId = None` and `agentId = None` for all job lifecycle events (Queued, Running, Completed, Failed). This means every scheduled-execution event in the audit trail is anonymous. Since `TriggerEngine` has access to `job.userId` and `job.agentId`, these should be populated on every event write.

---

### Architecture / Layer Discipline

**`TriggerEngine` bypassing ZLayer** (P10-004) — CONFIRMED BY 3 REVIEWERS

`Jorlan.scala` contains two `new TriggerEngine(db, ...)` constructor calls (lines 100 and 119), while `TriggerEngine.live` — a fully-specified `ZLayer` — sits unused. As a result:
1. The db-layer type `SchedulerZIORepository` is imported directly into the application wiring file, violating the architectural rule that domain/service layers should not depend on db-layer specifics.
2. The layer graph cannot inject test doubles because the construction bypasses the ZLayer mechanism.
3. `TriggerEngine.live` is dead code, creating confusion about the intended wiring strategy.

Remove both `new TriggerEngine(...)` calls from `Jorlan.scala` and wire `TriggerEngine.live` through `EnvironmentBuilder`, matching the pattern used for every other service.

**`job`/`triggers` queries bypassing `JobManager`** (P10-021) — CONFIRMED BY 3 REVIEWERS

SDD Architecture Principle 4 states that the domain service layer is the canonical interface; GraphQL resolvers must go through service layer, not bypass it to call the repository directly. The `job` and `triggers` GraphQL queries call `SchedulerZIORepository` directly. This means business rules in `JobManager` (e.g., filtering, authorization, lazy enrichment) do not apply to single-job or trigger lookups. Route both through `JobManager.getJob` and `JobManager.listTriggers`.

**`SchedulerSkill` unwired** (P10-037)

`SchedulerSkill.live` is defined but not included in `EnvironmentBuilder`, meaning no agent can currently invoke scheduler capabilities via the skill interface. This is either an intentional deferral (in which case it needs a `// TODO Phase 11:` comment) or an oversight. Compare with `MemorySkill`, which was wired in Phase 9.

---

### Performance

**Missing DB index on `schedulerTrigger(jobId)`** (P10-026)

`TriggerEngine.advanceTriggers` queries all triggers for each claimable job. Without an index on `schedulerTrigger(jobId)`, this is a full table scan on every poll tick. As the trigger count grows this will cause visible latency degradation. Add the index in the next Flyway migration (V023).

**Redundant single-column status index** (P10-040)

`idx_scheduler_job_status` indexes only `status`. The composite `idx_sj_status_scheduled` covers `(status, scheduledAt)`, which subsumes the single-column index for all status-filtered queries. The single-column index adds write overhead with no query benefit. Drop it in V023.

**`Cron.parse` on every tick** (P10-042)

`advanceTriggers` calls `Cron.parse(trigger.cronExpression)` on every polling interval for every active trigger. Cron expression parsing is non-trivial. Cache the parsed `Cron` instance in a map keyed by `TriggerId`; invalidate entries when triggers are added or removed.

**Unbounded `listJobs`** (P10-027)

`listJobs(None)` issues a `SELECT *` with no LIMIT. In production with thousands of jobs this will cause full table scans and large memory allocations. Add mandatory pagination (offset + limit) to `JobManager.listJobs` and the corresponding GraphQL arguments.

---

### Test Coverage

**`advanceTriggers` and GraphQL operations** (P10-010, P10-011)

The two most critical test gaps are:

| Missing Test | Gap |
|---|---|
| `advanceTriggers` with a Cron-triggered job | No coverage of the primary scheduling mechanism |
| `advanceTriggers` when next occurrence is None | Silent no-op path undetectable |
| `advanceTriggers` with MissedRunPolicy variants | Implementation can't be verified once built |
| GraphQL `createJob` mutation | No verification of input validation or response shape |
| GraphQL `pauseJob`/`resumeJob`/`cancelJob` | Status transitions via API completely unverified |
| GraphQL `addTrigger` / `deleteTrigger` | Trigger lifecycle unverified end-to-end |
| GraphQL `jobs` / `job` / `triggers` queries | Query projection and authorization unverified |
| GraphQL `decideApproval` with valid capability | Happy path untested even before P10-001 is fixed |
| `expireLeases` lease expiry integration | Lease-release mechanism has no test |
| `cancelJob` idempotency on already-Cancelled job | Terminal-state guard has no coverage |
| `listJobs(Some(agentId))` service + integration | Filtered query path has no coverage |
| `TriggerEngine.start` loop execution | Full scheduler tick cycle has no integration test |

**Shared state in `JobManagerSpec`** (P10-032)

`JobManagerSpec` uses a shared in-memory repository across all tests. The `listJobs` test asserts `size >= 2`, which is fragile and non-deterministic when tests run in parallel or out of order. Each test should construct its own isolated repository instance.

---

### Code Quality

**Duplicated copy patterns and boilerplate** (P10-043, P10-044, P10-045, P10-046, P10-047)

Several structural duplications add maintenance surface without value:

- The `leasedAt = None, leasedBy = None` copy pattern appears 5 times — extract `SchedulerJob.released(status, scheduledAt)`.
- `advanceTriggers` has 5 nesting levels with a buried import — extract `advanceCronTrigger` / `advanceIntervalTrigger` helpers.
- Four scheduler GraphQL mutations are structurally identical — extract a `schedulerOp` helper.
- `createJob` in `JobManagerSpec` is called 9 times with the same default arguments — extract `mkJob`.
- `TriggerEngineSpec` repeats a 4-line preamble in 6 tests — extract `makeTestEnv`.

**Exponential backoff integer issues** (P10-049)

`math.pow(2, retryCount.toDouble).toLong` has two problems: (1) floating-point precision is lost for `retryCount > 52`, and (2) for `retryCount >= 63` the result wraps to a negative value, causing an immediate retry instead of a multi-day wait. Replace with `1L << math.min(retryCount, 62)`.

---

## Cross-Cutting Patterns

**Capability model bypass** is the most pervasive cross-cutting issue in Phase 10, independently flagged by the Functional Scala Reviewer, Pattern Recognition Specialist, and Security Reviewer across four distinct manifestations: (1) `decideApproval` with no capability guard at all (P10-001); (2) `resolveAgentId` silent fallback producing jobs with `AgentId(0)` that bypass capability checks at execution time (P10-002); (3) `executeJob` calling `agentRunner.processMessage` with `actorId = None`, defeating per-user capability enforcement (P10-009); and (4) ownership-free scheduler mutations that allow cross-user job manipulation (P10-006, P10-022). All four stem from the same root: the capability check infrastructure exists and is used correctly elsewhere, but Phase 10 additions systematically omitted it from new code paths.

**ZLayer discipline erosion** was flagged by the Functional Scala Reviewer, Pattern Recognition Specialist, and SRS/SDD Conformance Reviewer. `TriggerEngine` is the only service in the codebase that is both instantiated with `new` in the wiring file (P10-004) and that calls the repository layer directly from GraphQL resolvers (P10-021). Both deviations appear to have been introduced under time pressure. The `TriggerEngine.live` ZLayer exists but is unused, and `SchedulerSkill.live` is defined but unregistered (P10-037). Collectively these indicate the wiring was left in an incomplete state.

**Observability gaps** were independently noted by the Functional Scala Reviewer, Pattern Recognition Specialist, SRS/SDD Conformance Reviewer, and Security Reviewer. Five high-impact scheduler mutations write no event log entries (P10-019), and all scheduler execution events are anonymous (P10-020). This pattern also appeared in earlier phases; the audit trail is incomplete enough that operational investigation of scheduler misbehavior would be severely impaired.

**Unimplemented spec deliverables stored as live data** is a recurring Phase 10 pattern: `MissedRunPolicy` (P10-005), `startedAt` (P10-007), and startup recomputation (P10-008) are all absent from the implementation while their data structures and GraphQL API surface are fully exposed. The risk is that callers set `missedRunPolicy = RunAllMissed` in good faith and observe unexpected behavior with no indication anything is wrong. Stubs or explicit `ZIO.fail(NotImplemented(...))` responses would be safer than silent no-ops.

**Resource lifecycle omissions** appear across multiple independent reviewers (Performance Oracle, Security Reviewer, Functional Scala Reviewer). `executeJob` never terminates its session (P10-003), has no timeout (P10-023), and orphans `AgentRunnerState` entries (P10-024). The TriggerEngine daemon fiber has no shutdown hook (P10-016). All four share the root cause that the `ZIO.acquireReleaseWith` / `Scope`-based resource discipline that is correctly applied in the rest of the codebase was not applied to job execution.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count |
|------------|-------|
| Critical   | 11    |
| Warning    | 25    |
| Suggestion | 20    |
| **Total**  | **56** |

**Issues by area:**

| Area                | Count |
|---------------------|-------|
| Security            | 7     |
| Test Coverage       | 9     |
| Correctness         | 7     |
| Architecture        | 5     |
| Code Quality        | 7     |
| Resource Management | 4     |
| Performance         | 6     |
| Observability       | 2     |
| Documentation       | 5     |
| Error Handling      | 1     |
| Data Integrity      | 1     |
| Infrastructure      | 1     |
| Functional Purity   | 1     |
| **Total**           | **56** |

**Agent contribution:**

| Agent                        | Unique Findings | Cross-Confirmed |
|------------------------------|-----------------|-----------------|
| Functional Scala Reviewer    | 17              | 9               |
| Code Simplicity Reviewer     | 11              | 4               |
| Performance Oracle           | 12              | 6               |
| Pattern Recognition Spec.    | 14              | 11              |
| Test Coverage Tracker        | 15              | 5               |
| SRS/SDD Conformance Rev.     | 9               | 7               |
| ScalaDoc Auditor             | 14              | 3               |
| Security Reviewer            | 16              | 10              |

**Phase 10 scope completion:**

| Item                                          | Status |
|-----------------------------------------------|--------|
| Domain extensions (`scheduler.scala`)         | ✅     |
| V021/V022 Flyway migrations                   | ✅     |
| `JobManager` trait + `JobManagerImpl`         | ✅     |
| `TriggerEngine` (tick, claim, retry, backoff) | ✅     |
| GraphQL scheduler API (14 new operations)     | ✅     |
| Shell commands (agents, approvals, capabilities) | ✅  |
| `SchedulerSkill` wiring into `EnvironmentBuilder` | ⚠️ |
| Event log completeness for scheduler mutations | ⚠️   |
| Test coverage (trigger advance, GraphQL ops)  | ⚠️    |
| `MissedRunPolicy` implementation              | ❌     |
| Startup trigger recomputation                 | ❌     |
| `startedAt` persistence during execution      | ❌     |

---

## What Was Done Well

**JobManager / TriggerEngine separation**: The split between `JobManager` (lifecycle operations, business rules) and `TriggerEngine` (poll loop, claim/release, execution) is a clean architectural boundary that makes both components independently testable and replaceable. This separation mirrors the established service/repository pattern and should be maintained in Phase 11.

**Exponential backoff with retry count persistence**: Persisting `retryCount` in the DB and computing backoff from it (rather than from in-memory state) means retry history survives restarts. This is the correct approach for a durable scheduler and should be noted as a pattern for any future retry-bearing service.

**V022 making email required**: The decision to enforce `email NOT NULL` in V022 rather than continuing to allow empty-string placeholders is the right long-term call. The fix needed (P10-015, P10-033) is small relative to the value of removing the ambiguity at the data layer.

**Testcontainers-based scheduler repository spec**: `SchedulerRepositorySpec` exercises the real MariaDB schema via Testcontainers, following the project standard. This gives high confidence in the Quill query generation for the scheduler tables. Extending this suite to cover the missing paths (P10-031, P10-054) will provide a solid integration baseline for Phase 11.

**`claimJob` optimistic locking via raw SQL**: The `WHERE leasedBy IS NULL OR leasedAt < NOW() - INTERVAL ...` pattern in `claimJob` correctly implements an optimistic lease-based mutual exclusion without requiring a distributed lock. This is an appropriate and efficient choice for a single-node scheduler.
