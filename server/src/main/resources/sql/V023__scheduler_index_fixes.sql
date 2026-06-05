-- P10-026: index on schedulerTrigger(jobId) — prevents full table scan in advanceTriggers on every tick.
CREATE INDEX `idx_st_job_id` ON `schedulerTrigger` (`jobId`);

-- P10-041: index on schedulerJob(agentId) — speeds up listJobs(Some(agentId)) filtered queries.
CREATE INDEX `idx_sj_agent_id` ON `schedulerJob` (`agentId`);

-- P10-040: drop redundant single-column status index — the composite idx_sj_status_scheduled covers all status-only queries.
DROP INDEX `idx_scheduler_job_status` ON `schedulerJob`;
