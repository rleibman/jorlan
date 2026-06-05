---
name: phase10-findings
description: Phase 10 (Durable Scheduler) conformance review summary (2026-06-04)
metadata:
  type: project
---

## Phase 10 Conformance Review (2026-06-04)

Branch: main (merged from phase10/scheduler)

### Status: PARTIALLY CONFORMANT

### Critical Issues

None.

### Major Issues

1. **Missed-run policy (Skip/RunOnce/RunAllMissed) not implemented in TriggerEngine**
   - Mini-design section 5.2 specifies: "If now - scheduledAt > oneTick: Skip → advance; RunOnce → run once; RunAllMissed → queue one per window (cap 10)."
   - TriggerEngine.tick dispatches any Pending job whose scheduledAt <= now regardless of missedRunPolicy. The MissedRunPolicy enum and SchedulerJob field exist but no code reads them during execution.
   - Roadmap item checked but behavior unimplemented.

2. **Startup trigger recomputation not implemented**
   - Mini-design section 5.2: "On startup, computes the next scheduledAt for each recurring trigger."
   - TriggerEngine.start is `logInfo *> tick.repeat(Schedule.spaced(...))` — no startup pass to recompute next-fire-times for cron/interval triggers that may have drifted during downtime.

3. **startedAt field never set during job execution**
   - SchedulerJob has a `startedAt: Option[Instant]` field. TriggerEngine.executeJob logs the start event but never calls `repo.upsertJob(job.copy(startedAt = Some(now)))` before executing. The field remains None for all jobs.
   - Design doc section 3.2 lists startedAt as a persisted field with implied semantics (tracking when execution began).

4. **job and triggers GraphQL queries bypass the service layer (JobManager) and call SchedulerZIORepository directly**
   - `job` resolver: `ZIO.serviceWithZIO[SchedulerZIORepository](_.getJob(id))` — bypasses JobManager.getJob which adds error enrichment.
   - `triggers` resolver: `ZIO.serviceWithZIO[SchedulerZIORepository](_.searchTriggers(...))` — same bypass.
   - All mutations correctly delegate to JobManager. The inconsistency means some resolvers depend on the DB repo type directly in the API layer, a recurring architectural concern (see [[recurring-patterns]]).

5. **terminateSession mutation gates on `agent.session.create` capability instead of a terminate-specific one**
   - `terminateSession` resolver calls `requireCapability("agent.session.create", actorId)`.
   - The mini-design and SRS capability model (deny-by-default) suggest `agent.session.create` and `agent.session.terminate` are distinct operations with distinct risk profiles. Reusing the create capability for termination is semantically incorrect.

### Minor Issues

6. **SchedulerSkill location deviates from mini-design spec path**
   - Mini-design section 7: "Implement in `server/src/main/scala/jorlan/server/skill/SchedulerSkill.scala`"
   - Actual path: `server/src/main/scala/jorlan/service/SchedulerSkill.scala`
   - Consistent with MemorySkill placement; low practical impact but deviates from spec text.

7. **SchedulerSkill not wired into EnvironmentBuilder**
   - SchedulerSkill.live is defined but absent from EnvironmentBuilder.live. Since registry wiring is deferred to Phase 12 this is acceptable per roadmap, but the layer is not even included for potential internal use.

8. **/restart shell command listed as Phase 10 in roadmap appendix table but not implemented**
   - Roadmap appendix table (line 819): `/restart` is flagged as Phase 10, status `[ ]`.
   - ShellCommand enum and CommandHandler have no Restart case.
   - The Phase 10 roadmap section body does not include this as a required item, so this is a roadmap appendix inconsistency rather than a missing Phase 10 feature.

9. **TriggerEngine created with `new` directly in Jorlan.run rather than provided via ZLayer**
   - Jorlan.run: `new TriggerEngine(schedulerRepo, eventLogRepo, agentSM, agentRunner, sessionHub).start.forkDaemon`
   - TriggerEngine.live ZLayer exists but is not used. The engine is instantiated twice (initialized and uninitialized paths). Inconsistent with the ZIO service pattern used everywhere else, and risks duplicating the polling fiber.

### Missing Requirements (per mini-design)

- **Missed-run policies (RunOnce, RunAllMissed)**: enum and field exist, logic not implemented.
- **Startup trigger recomputation**: not implemented in TriggerEngine.start.
- **Integration tests** (SchedulerRecoverySpec, RetrySpec): explicitly deferred per roadmap — acceptable.

### Conformant Aspects

- All domain extensions match mini-design exactly (MissedRunPolicy, RetryBackoffPolicy, SchedulerJob fields, JobStatus.Paused)
- V021 migration matches spec exactly (all 8 new columns, FK constraint, lease index)
- V022 migration (user email NOT NULL with backfill) correctly implemented
- SchedulerRepository trait has all four required new methods (listJobs, claimJob, releaseJob, expireLeases)
- claimJob uses optimistic UPDATE with correct WHERE clause (status = Pending AND leasedAt IS NULL OR stale)
- TriggerEngine.tick: expire-leases → getPendingJobs → claim → fork execution — correct pattern
- Cron advancement (cron4s), interval advancement (ISO 8601), OneShot disable — all implemented
- Retry engine: Fixed and Exponential backoff formulas correct; maxRetries cap correct
- EventType additions: all 5 new event types (SchedulerJobQueued, SchedulerJobStarted, SchedulerJobCompleted, SchedulerJobFailed, SchedulerJobCancelled) defined and used
- JobManager trait matches mini-design exactly (including deleteJob addition)
- JobManagerImpl correctly delegates all operations; listJobs/cancelJob/triggerNow/deleteJob all correct
- GraphQL surface: all 7 mutations and 3 queries present; all scheduler mutations gated on scheduler.manage
- listApprovals query, decideApproval mutation, terminateSession mutation all present
- listCapabilities query present (P9-051 fix complete)
- Shell commands: AgentsList, AgentsStop, ApprovalsList, ApprovalsApprove, ApprovalsDeny — all implemented
- Shell command parser handles all new commands correctly
- CommandHandler wires all new shell commands; 8 new tests in CommandHandlerSpec
- JobManagerSpec: 9 tests, all required scenarios covered
- TriggerEngineSpec: 6 tests (tick, no-double-claim, retry increment, max-retries fail, stale lease, exponential backoff)
- SchedulerSkillSpec: 5 tests (all operations covered)
- SchedulerRepositorySpec: claimJob and releaseJob integration tests added (2 new tests)
- TriggerEngine started as daemon fiber in both initialized and post-init paths in Jorlan.run
