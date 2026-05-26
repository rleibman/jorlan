-- Memory records and embeddings

CREATE TABLE `memoryRecord`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `scope`       VARCHAR(32)  NOT NULL,
    `userId`      BIGINT       NULL,
    `workspaceId` BIGINT       NULL,
    `agentId`     BIGINT       NULL,
    `recordKey`   VARCHAR(255) NOT NULL,
    `value`       MEDIUMTEXT   NOT NULL,
    `ttl`         DATETIME     NULL,
    `createdAt`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_memory_scope_key` (`scope`, `recordKey`),
    INDEX `idx_memory_user` (`userId`),
    INDEX `idx_memory_workspace` (`workspaceId`),
    INDEX `idx_memory_agent` (`agentId`),
    CONSTRAINT `fk_memory_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_memory_workspace` FOREIGN KEY (`workspaceId`) REFERENCES `workspace` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_memory_agent` FOREIGN KEY (`agentId`) REFERENCES `agent` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `memoryEmbedding`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `memoryRecordId` BIGINT       NOT NULL,
    `model`          VARCHAR(128) NOT NULL,
    `vector`         MEDIUMTEXT   NOT NULL,
    `createdAt`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_memory_embedding_record` FOREIGN KEY (`memoryRecordId`) REFERENCES `memoryRecord` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
