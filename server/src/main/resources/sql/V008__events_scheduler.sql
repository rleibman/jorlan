-- Event log and scheduler

CREATE TABLE `eventLog`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `eventType`    VARCHAR(64) NOT NULL,
    `actorId`      BIGINT      NULL,
    `agentId`      BIGINT      NULL,
    `sessionId`    BIGINT      NULL,
    `resourceType` VARCHAR(64) NULL,
    `resourceId`   BIGINT      NULL,
    `payloadJson`  TEXT        NULL,
    `occurredAt`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_event_log_type` (`eventType`),
    INDEX `idx_event_log_agent` (`agentId`),
    INDEX `idx_event_log_occurred` (`occurredAt`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `schedulerJob`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `agentId`     BIGINT      NOT NULL,
    `skillId`     BIGINT      NULL,
    `name`        VARCHAR(255) NOT NULL,
    `inputJson`   TEXT         NULL,
    `status`      VARCHAR(32) NOT NULL DEFAULT 'Pending',
    `scheduledAt` DATETIME    NOT NULL,
    `startedAt`   DATETIME    NULL,
    `finishedAt`  DATETIME    NULL,
    `resultJson`  TEXT        NULL,
    `createdAt`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_scheduler_job_status` (`status`),
    INDEX `idx_scheduler_job_scheduled` (`scheduledAt`),
    CONSTRAINT `fk_scheduler_job_agent` FOREIGN KEY (`agentId`) REFERENCES `agent` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_scheduler_job_skill` FOREIGN KEY (`skillId`) REFERENCES `skill` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `schedulerTrigger`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `jobId`       BIGINT       NOT NULL,
    `triggerType` VARCHAR(32)  NOT NULL,
    `expression`  VARCHAR(255) NOT NULL,
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
    `createdAt`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_scheduler_trigger_job` FOREIGN KEY (`jobId`) REFERENCES `schedulerJob` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
