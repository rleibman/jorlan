-- Skills, skill versions, connector instances

CREATE TABLE `skill`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `name`           VARCHAR(255) NOT NULL,
    `currentVersion` VARCHAR(64)  NULL,
    `tier`           VARCHAR(32)  NOT NULL,
    `createdAt`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_skill_name` (`name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `skillVersion`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `skillId`      BIGINT      NOT NULL,
    `version`      VARCHAR(64) NOT NULL,
    `manifestJson` MEDIUMTEXT  NOT NULL,
    `status`       VARCHAR(32) NOT NULL DEFAULT 'Draft',
    `createdAt`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_skill_version` (`skillId`, `version`),
    CONSTRAINT `fk_skill_version_skill` FOREIGN KEY (`skillId`) REFERENCES `skill` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `connectorInstance`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `connectorType` VARCHAR(32)  NOT NULL,
    `name`          VARCHAR(255) NOT NULL,
    `configJson`    TEXT         NOT NULL,
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'active',
    `createdAt`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
