-- Agents, sessions, workspaces

CREATE TABLE `workspace`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `ownerId`     BIGINT       NOT NULL,
    `name`        VARCHAR(255) NOT NULL,
    `description` TEXT         NULL,
    `createdAt`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_workspace_owner` FOREIGN KEY (`ownerId`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `agent`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `name`         VARCHAR(255) NOT NULL,
    `description`  TEXT         NULL,
    `defaultModel` VARCHAR(128) NULL,
    `trustLevel`   INT          NOT NULL DEFAULT 0,
    `createdAt`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `agentSession`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `agentId`     BIGINT      NOT NULL,
    `userId`      BIGINT      NOT NULL,
    `workspaceId` BIGINT      NULL,
    `status`      VARCHAR(32) NOT NULL DEFAULT 'Created',
    `createdAt`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_agent_session_agent` FOREIGN KEY (`agentId`) REFERENCES `agent` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_agent_session_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_agent_session_workspace` FOREIGN KEY (`workspaceId`) REFERENCES `workspace` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
