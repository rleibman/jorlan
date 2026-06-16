-- Add prompt column to schedulerJob (the message sent to the LLM on each trigger)
-- and enforce uniqueness on name per user so jobs can be referenced by name.

ALTER TABLE `schedulerJob`
    ADD COLUMN `prompt` TEXT NOT NULL DEFAULT '' AFTER `name`;

ALTER TABLE `schedulerJob`
    ADD UNIQUE KEY `uq_scheduler_job_name` (`name`);
