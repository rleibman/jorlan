-- Extend schedulerJob with retry, missed-run, and distributed-lease columns.

ALTER TABLE `schedulerJob`
    ADD COLUMN `userId`          BIGINT       NOT NULL DEFAULT 1      AFTER `agentId`,
    ADD COLUMN `maxRetries`      INT          NOT NULL DEFAULT 0      AFTER `resultJson`,
    ADD COLUMN `retryCount`      INT          NOT NULL DEFAULT 0      AFTER `maxRetries`,
    ADD COLUMN `backoffSeconds`  INT          NOT NULL DEFAULT 60     AFTER `retryCount`,
    ADD COLUMN `backoffPolicy`   VARCHAR(32)  NOT NULL DEFAULT 'Fixed'       AFTER `backoffSeconds`,
    ADD COLUMN `missedRunPolicy` VARCHAR(32)  NOT NULL DEFAULT 'Skip'        AFTER `backoffPolicy`,
    ADD COLUMN `leasedAt`        DATETIME     NULL                           AFTER `missedRunPolicy`,
    ADD COLUMN `leasedBy`        VARCHAR(255) NULL                           AFTER `leasedAt`,
    ADD CONSTRAINT `fk_scheduler_job_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`);

CREATE INDEX `idx_scheduler_lease` ON `schedulerJob` (`status`, `leasedAt`);
