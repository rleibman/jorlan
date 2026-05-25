-- Artifacts and orchestrator identity

CREATE TABLE `artifact`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `workspaceId`  BIGINT       NULL,
    `sessionId`    BIGINT       NULL,
    `name`         VARCHAR(255) NOT NULL,
    `mimeType`     VARCHAR(128) NOT NULL,
    `sizeBytes`    BIGINT       NOT NULL DEFAULT 0,
    `storageUri`   TEXT         NOT NULL,
    `metadataJson` TEXT         NULL,
    `createdAt`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_artifact_workspace` FOREIGN KEY (`workspaceId`) REFERENCES `workspace` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_artifact_session` FOREIGN KEY (`sessionId`) REFERENCES `agentSession` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `orchestratorIdentity`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `name`         VARCHAR(255) NOT NULL,
    `description`  TEXT         NULL,
    `publicKeyPem` TEXT         NULL,
    `trustLevel`   INT          NOT NULL DEFAULT 0,
    `createdAt`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_orchestrator_name` (`name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
