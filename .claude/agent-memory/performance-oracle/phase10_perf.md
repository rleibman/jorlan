---
name: phase10-perf
description: Phase 10 durable-scheduler performance findings — session/memory leak per job, runCollect on LLM stream, sequential trigger advance, missing schedulerTrigger index, redundant idx_scheduler_lease, double-query on pause/resume/cancel, InetAddress blocking init, math.pow float precision
metadata:
  type: project
---

## Findings (phase10/scheduler, 2026-06-04)

### Critical: Unbounded AgentRunnerState maps leak per scheduled job
- `TriggerEngine.executeJob` calls `sessionManager.createSession` for each job run, which inserts a new
  `AgentSessionId` into `AgentRunnerImpl.cachedAgentIds` and `activeConvs` (both `Ref[Map[...]]`).
  Neither map is ever evicted. Over time (dozens/hundreds of scheduled job executions) this map grows
  without bound, holding references to stale session IDs and conversation IDs.
  Fix: call `sessionManager.terminateSession` and evict from `cachedAgentIds`/`activeConvs` after
  `executeJob` completes (in an `ensuring` block).

### Critical: stream.takeUntil(_.finished).runCollect buffers entire LLM response in memory
- `TriggerEngine.executeJob` line 73: `stream.takeUntil(_.finished).runCollect` collects every token
  chunk into a `Chunk[ResponseChunk]` before concatenating. For a long agent response this holds all
  tokens in a Chunk simultaneously. Use `runFold("") { case (acc, c) => if (!c.finished) acc + c.content else acc }`
  to accumulate directly into a String without the intermediate Chunk allocation.
  (`takeUntil` also includes the sentinel chunk; the final empty-content chunk is harmlessly appended.)

### Warning: pauseJob/resumeJob/cancelJob/triggerNow each do getJob (SELECT) + upsertJob (UPDATE)
- `JobManagerImpl.pauseJob` (line 75), `resumeJob` (line 80), `cancelJob` (line 87), `triggerNow`
  (line 97) all call `getJob(id)` (SELECT by PK) then `upsertJob(job.copy(...))` (UPDATE).
  The full-row read is unnecessary; a targeted UPDATE — `UPDATE schedulerJob SET status=? WHERE id=?` —
  would halve the round-trips. Under high call rates (many jobs, aggressive triggerNow calls from the
  GraphQL API) this is noticeable. At current scale it's Warning only.

### Warning: executeJob creates a new AgentSession + event log entry for every job run without cleanup
- Every scheduled job creates a new `AgentSession` row (INSERT via `agentRepo.upsertSession`) and an
  `EventLog` row (`EventType.SessionCreated`). The session is never terminated, leaving it stuck in
  `Active` status in the DB permanently. This is both a functional bug and a storage/query pollution
  issue (session lists will grow, `SessionStatus.Active` filters will return stale entries).

### Warning: advanceTriggers uses sequential ZIO.foreachDiscard for per-trigger upsertJob calls
- `TriggerEngine.advanceTriggers` line 94: `ZIO.foreachDiscard(triggers.filter(_.enabled))` executes
  one DB upsert per trigger sequentially. Most jobs have 1 trigger; however if a job has many triggers
  (valid for multi-schedule patterns) this is O(n) sequential DB round-trips post-execution.
  Switch to `ZIO.foreachParDiscard` for safe parallelism (each trigger targets a different job row).

### Warning: Missing index on schedulerTrigger(jobId) — advanceTriggers query is an unindexed scan
- `schedulerTrigger` has no index on `jobId` (V008 only adds a FK constraint, no index).
  `searchTriggers(TriggerSearch(jobId = job.id, pageSize = 100))` issues
  `SELECT ... FROM schedulerTrigger WHERE jobId = ?` — this is a full table scan.
  At low trigger counts this is negligible; at scale (thousands of triggers) it becomes a bottleneck
  on every successful job execution. Add `INDEX idx_st_job_id (jobId)` via a new migration.

### Warning: Redundant idx_scheduler_lease index (status, leasedAt) duplicates coverage
- V021 adds `idx_scheduler_lease (status, leasedAt)` but the existing `idx_sj_status_scheduled
  (status, scheduledAt)` from V010 already narrows the `getPendingJobs` query effectively.
  `expireLeases` filters on `leasedAt < ? AND status = Running` — `idx_scheduler_lease` does help
  here. However `idx_scheduler_job_status (status)` from V008 is now fully dominated by the two
  composite indexes and can be dropped to reduce write amplification.

### Warning: InetAddress.getLocalHost in workerId is a blocking DNS call on construction
- `TriggerEngine.workerId` (line 40) is a `private val` initialized synchronously on class
  construction, which calls `InetAddress.getLocalHost` — a blocking DNS/network operation.
  While wrapped in `Try`, it still blocks the ZIO fiber that constructs the `TriggerEngine`
  (which happens inside a ZIO environment build). Wrap in `ZIO.attemptBlocking` or resolve
  lazily in `start` on the blocking executor.

### Info: math.pow(2, retryCount.toDouble).toLong loses precision for retryCount > 52
- `scheduleRetryOrFail` line 146: `math.pow(2, job.retryCount.toDouble).toLong` uses floating-point
  exponentiation. For `retryCount > 52` the double mantissa loses integer precision, yielding an
  incorrect backoff. Use `1L << job.retryCount` (bitshift) instead — exact, no FP conversion.
  With `maxRetries` typically <= 10 this is not a production risk, but it is a correctness issue.

### Info: listJobs without agentId filter fetches all schedulerJob rows with no LIMIT
- `QuillRepositories.listJobs(None)` (line 748) issues `SELECT * FROM schedulerJob ORDER BY createdAt DESC`
  with no LIMIT. The `listJobs` interface has no `pageSize` parameter. As the table grows, this
  becomes a full table scan. Add pagination or a default LIMIT before the table sees > 10k rows.

### Info: No index on schedulerJob(agentId) for listJobs(Some(agentId)) query
- `listJobs(Some(agentId))` filters on `agentId` which has only a FK constraint, not an index.
  At low job counts this is fine; add `INDEX idx_sj_agent_id (agentId)` when agents accumulate
  many jobs.
