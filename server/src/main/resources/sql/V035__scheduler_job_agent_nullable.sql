-- Make agentId nullable on schedulerJob: jobs exist independently of agent sessions.
-- The TriggerEngine creates its own session at execution time; the stored agentId is
-- only used for event-log audit entries and is not required at job-creation time.
ALTER TABLE `schedulerJob`
  DROP FOREIGN KEY `fk_scheduler_job_agent`;

ALTER TABLE `schedulerJob`
  MODIFY COLUMN `agentId` BIGINT NULL;

ALTER TABLE `schedulerJob`
  ADD CONSTRAINT `fk_scheduler_job_agent`
    FOREIGN KEY (`agentId`) REFERENCES `agent` (`id`) ON DELETE SET NULL;
