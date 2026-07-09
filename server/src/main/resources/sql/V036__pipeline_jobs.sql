-- Pipeline jobs: structured multi-step job execution
-- Adds pipeline_json to scheduler jobs, invariants to agents, and a pipeline_runs table.

ALTER TABLE `schedulerJob`
    ADD COLUMN `pipeline` LONGTEXT NULL AFTER `inputJson`;

ALTER TABLE `agent`
    ADD COLUMN `invariants` JSON NULL AFTER `prioritizedSkills`;

CREATE TABLE `pipelineRun` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `jobId`       BIGINT       NOT NULL,
    `status`      VARCHAR(32)  NOT NULL DEFAULT 'Running',
    `runContext`  TEXT         NULL,
    `contextJson` LONGTEXT     NULL,
    `failedStep`  VARCHAR(255) NULL,
    `startedAt`   DATETIME     NOT NULL,
    `finishedAt`  DATETIME     NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_pipeline_run_jobId` (`jobId`),
    CONSTRAINT `fk_pipeline_run_jobId`
        FOREIGN KEY (`jobId`) REFERENCES `schedulerJob` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
