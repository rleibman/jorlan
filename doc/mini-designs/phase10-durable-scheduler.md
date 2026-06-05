<!--
 Copyright (c) 2026 Roberto Leibman - All Rights Reserved
-->

# Phase 10: Durable Scheduler Design

**Branch:** `phase-10/durable-scheduler` (planned)  
**Status:** Design — 2026-06-03  
**Date:** 2026-06-03

---

## 1. Problem Statement

Phase 9 left agents with no way to schedule work beyond the current session. Phase 10 adds a
durable scheduler that survives server restarts, supports cron and interval triggers, prevents
duplicate execution, retries failed jobs with backoff, and exposes all management operations to
agents via a Tier-0 skill.

---

## 2. Pre-work: Tech Debt Cleanup

Before new Phase 10 code, do these first:

1. **`LangChainConfig` stays** — `ai` module intentionally kept provider-agnostic; no rename needed.
2. **Roadmap text fix** — "Orchestrator runs job payload" → "AgentRunner runs job payload".
3. **P9-051** — `/capabilities` shell command hardcoded; add `listCapabilities` GQL query and wire.

---

## 3. Domain Extensions

### 3.1 New enums / types

```scala
// In model/src/main/scala/jorlan/domain/scheduler.scala

enum MissedRunPolicy derives JsonEncoder, JsonDecoder:
  case Skip, RunOnce, RunAllMissed

enum RetryBackoffPolicy derives JsonEncoder, JsonDecoder:
  case Fixed, Exponential
```

### 3.2 Extended `SchedulerJob`

Add fields to the existing case class:

```scala
case class SchedulerJob(
  id:              SchedulerJobId,
  agentId:         AgentId,
  userId:          UserId,           // NEW: which user "owns" this job (for session creation)
  skillId:         Option[SkillId],
  name:            String,
  inputJson:       Option[String],
  status:          JobStatus,
  scheduledAt:     Instant,
  startedAt:       Option[Instant],
  finishedAt:      Option[Instant],
  resultJson:      Option[String],
  maxRetries:      Int,              // NEW: 0 = no retry
  retryCount:      Int,              // NEW: number of retries attempted so far
  backoffSeconds:  Int,              // NEW: base seconds for retry backoff (doubles if Exponential)
  backoffPolicy:   RetryBackoffPolicy, // NEW
  missedRunPolicy: MissedRunPolicy,  // NEW
  leasedAt:        Option[Instant],  // NEW: when this worker claimed the job
  leasedBy:        Option[String],   // NEW: worker instance identifier (hostname:pid)
  createdAt:       Instant,
) derives JsonEncoder, JsonDecoder
```

**Note on `userId`:** Scheduled jobs must create agent sessions to run. Sessions always belong
to a user. For admin-scheduled jobs, use the admin user created during init. For agent-created
jobs (`SchedulerSkill`), use the session's owning user.

### 3.3 V021 migration

```sql
-- V021__scheduler_extensions.sql
ALTER TABLE `schedulerJob`
  ADD COLUMN `userId`          BIGINT       NOT NULL DEFAULT 1      AFTER `agentId`,
  ADD COLUMN `maxRetries`      INT          NOT NULL DEFAULT 0      AFTER `resultJson`,
  ADD COLUMN `retryCount`      INT          NOT NULL DEFAULT 0      AFTER `maxRetries`,
  ADD COLUMN `backoffSeconds`  INT          NOT NULL DEFAULT 60     AFTER `retryCount`,
  ADD COLUMN `backoffPolicy`   VARCHAR(32)  NOT NULL DEFAULT 'Fixed' AFTER `backoffSeconds`,
  ADD COLUMN `missedRunPolicy` VARCHAR(32)  NOT NULL DEFAULT 'Skip' AFTER `backoffPolicy`,
  ADD COLUMN `leasedAt`        DATETIME     NULL                    AFTER `missedRunPolicy`,
  ADD COLUMN `leasedBy`        VARCHAR(255) NULL                    AFTER `leasedAt`,
  ADD CONSTRAINT `fk_scheduler_job_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`);

CREATE INDEX `idx_scheduler_lease` ON `schedulerJob` (`status`, `leasedAt`);
```

---

## 4. Repository Extensions

Add to `SchedulerRepository[F[_]]` trait:

```scala
def listJobs(agentId: Option[AgentId]): F[List[SchedulerJob]]
def claimJob(id: SchedulerJobId, workerId: String, now: Instant, leaseTtlSeconds: Int): F[Boolean]
def releaseJob(id: SchedulerJobId, status: JobStatus, resultJson: Option[String], finishedAt: Instant): F[Unit]
def expireLeases(olderThan: Instant): F[Long]  // reset stale leases to Pending
```

`claimJob` must use an optimistic UPDATE with a WHERE clause checking `status = 'Pending' AND
(leasedAt IS NULL OR leasedAt < :staleBefore)` — a single UPDATE returning rows-affected. If
rows-affected == 1, the claim succeeded; if 0, another worker won the race.

---

## 5. Services

### 5.1 `JobManager` (trait in `model`, impl in `server`)

```scala
trait JobManager {
  def createJob(agentId: AgentId, userId: UserId, name: String, inputJson: Option[String],
                maxRetries: Int, backoffSeconds: Int, backoffPolicy: RetryBackoffPolicy,
                missedRunPolicy: MissedRunPolicy): IO[JorlanError, SchedulerJob]
  def addTrigger(jobId: SchedulerJobId, trigger: SchedulerTrigger): IO[JorlanError, SchedulerTrigger]
  def listJobs(agentId: Option[AgentId]): UIO[List[SchedulerJob]]
  def getJob(id: SchedulerJobId): IO[JorlanError, SchedulerJob]
  def pauseJob(id: SchedulerJobId): IO[JorlanError, Unit]
  def resumeJob(id: SchedulerJobId): IO[JorlanError, Unit]
  def cancelJob(id: SchedulerJobId): IO[JorlanError, Unit]
  def triggerNow(id: SchedulerJobId): IO[JorlanError, Unit]
}
```

`pauseJob` sets `status = Paused` (new `JobStatus` variant). `resumeJob` sets back to `Pending`.
`cancelJob` sets `status = Cancelled`. `triggerNow` forces `scheduledAt = now()` and `status =
Pending` so the next `TriggerEngine` tick picks it up.

Add `Paused` to `JobStatus` enum.

### 5.2 `TriggerEngine` (in `server`)

The engine runs as a daemon ZIO fiber started during server startup. It:

1. Polls `SchedulerRepository.getPendingJobs` on a fixed interval (configurable, default 10s).
2. For each ready job, calls `claimJob`; on success, forks a fiber to execute the job.
3. Also calls `expireLeases` on each tick to reset stale leases (lease TTL = 5 minutes).
4. On startup, computes the next `scheduledAt` for each recurring trigger.

**Cron library**: Use `cron4s` (`"com.github.alonsodomin.cron4s" %% "cron4s-core" % "0.8.2"`) for
parsing and next-fire-time computation. It integrates cleanly with ZIO via `ZIO.fromEither`.

**Interval triggers**: Parse ISO 8601 duration with `java.time.Duration.parse`. Compute next fire
time as `finishedAt + duration`.

**OneShot triggers**: After successful execution, set trigger `enabled = false`.

**Missed-run handling**: On startup and after each claim, compare `scheduledAt` to `now()`. If
`now - scheduledAt > oneTick`:
- `Skip` — advance to next fire time without running missed executions.
- `RunOnce` — run once immediately, then advance.
- `RunAllMissed` — queue one execution per missed window (capped at 10 to prevent storms).

### 5.3 `RetryEngine` (integrated into `TriggerEngine` job execution)

After a job finishes with `JobStatus.Failed`:

1. If `retryCount < maxRetries`: increment `retryCount`, compute next `scheduledAt` as:
   - `Fixed`: `now + backoffSeconds`
   - `Exponential`: `now + backoffSeconds * 2^retryCount`
   Set `status = Pending`, `leasedAt = null`, `leasedBy = null`.
2. If `retryCount >= maxRetries`: leave as `Failed`, log `SchedulerJobFailed` event.

### 5.4 Job Execution Flow

When a job is claimed:

```
TriggerEngine claims job
  → AgentSessionManager.createSession(job.userId, modelId = None)
  → AgentRunner.processMessage(newSession.id, job.inputJson.getOrElse(""), job.userId)
  → collect result (we don't stream back — collect to string for resultJson)
  → SchedulerRepository.releaseJob(id, Succeeded, resultJson, now)
  → advance trigger scheduledAt for recurring jobs
  → log SchedulerJobStarted / SchedulerJobCompleted / SchedulerJobFailed events
```

For the result, `AgentRunner.processMessage` writes to `SessionHub`. The `TriggerEngine` can
subscribe to the hub for the session and collect chunks until `finished = true`.

**EventType additions**: `SchedulerJobQueued`, `SchedulerJobStarted`, `SchedulerJobCompleted`,
`SchedulerJobFailed`, `SchedulerJobCancelled`.

---

## 6. GraphQL Surface

### Queries
```graphql
jobs(agentId: Long): [SchedulerJob!]!
job(id: Long!): SchedulerJob
triggers(jobId: Long!): [SchedulerTrigger!]!
```

### Mutations
```graphql
createJob(input: CreateJobInput!): SchedulerJob!
addTrigger(input: AddTriggerInput!): SchedulerTrigger!
pauseJob(id: Long!): Boolean!
resumeJob(id: Long!): Boolean!
cancelJob(id: Long!): Boolean!
triggerNow(id: Long!): Boolean!
deleteJob(id: Long!): Boolean!
```

Capability gate: all scheduler mutations require `scheduler.manage` capability grant.

---

## 7. `SchedulerSkill` (Tier 0 — logic only, no registry wiring)

Implement in `server/src/main/scala/jorlan/server/skill/SchedulerSkill.scala`.

Exposes the same operations as `JobManager` as named tools callable by the model:
- `scheduler.create_job` — create a named job with trigger
- `scheduler.list_jobs` — list current agent's jobs
- `scheduler.pause_job` / `scheduler.resume_job` / `scheduler.cancel_job`
- `scheduler.trigger_now` — force immediate run

**Registry wiring deferred to Phase 12** (same as `MemorySkill`).

---

## 8. Shell Changes

Add to `CommandHandler` / `ShellCommand`:

- `/agents list` — lists active sessions (uses existing `listSessions` query)
- `/agents stop <id>` — terminates a session (calls `terminateSession`)
- `/approvals list` — lists pending approval requests
- `/approvals approve <id>` / `/approvals deny <id>` — decision mutations (need GQL mutations)

The `/approvals` mutations (`decideApproval`) are not yet in the GraphQL schema — add them as
part of Phase 10 since the roadmap lists `/approvals` as a Phase 10 shell command.

---

## 9. `TriggerEngine` Startup / Shutdown

Wire into `Jorlan.run` as a background fiber:

```scala
TriggerEngine.start.forkDaemon
```

The engine fiber runs until the server shuts down. Use `ZIO.interrupt` on shutdown signal (ZIO's
runtime handles this via `ZIO.addFinalizer` in the server layer).

---

## 10. Tests

### Unit tests
- `JobManagerSpec` — createJob, pauseJob/resumeJob, cancelJob, triggerNow (fake repo)
- `TriggerEngineSpec` — tick fires pending job, missed-run skip policy, missed-run RunOnce policy
- `RetryEngineSpec` — fixed/exponential backoff schedules, max-retries stops
- `SchedulerSkillSpec` — each tool invocation via `JobManager` mock

### Integration tests (Testcontainers)
- `SchedulerRecoverySpec` — create job, simulate restart (wipe ZIO state, re-initialize), assert
  job is still picked up and executed
- `RetrySpec` — job fails N times then succeeds; assert `retryCount` tracks correctly

---

## 11. Migration Plan

| Migration | Content |
|-----------|---------|
| V021 | `scheduler_extensions`: new columns on `schedulerJob`, FK to `user`, lease index |

Next migration after Phase 10 will be V022.

---

## 12. Open Questions (resolved)

| Question | Decision |
|----------|----------|
| Cron library | `cron4s-core 0.8.2` — standard cron syntax, Scala 3 support |
| userId for system jobs | Admin user (id resolved from `server_settings` or query by email after init) |
| `SchedulerSkill` wiring | Logic impl now; `SkillRegistry` wiring deferred to Phase 12 |
| Stream vs collect for job result | Collect to string via `SessionHub` subscription |
| Multi-instance locking | DB UPDATE optimistic claim (single-node for now; sufficient) |
