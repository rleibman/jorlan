-- Fix uq_scheduler_job_name: was globally unique on name; should be unique per user.
-- Two different users may have jobs with the same name.

ALTER TABLE `schedulerJob`
    DROP INDEX `uq_scheduler_job_name`,
    ADD UNIQUE KEY `uq_scheduler_job_name_per_user` (`userId`, `name`);
