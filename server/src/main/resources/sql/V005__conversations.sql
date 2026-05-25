-- Conversations and messages

CREATE TABLE `conversation`
(
    `id`        BIGINT   NOT NULL AUTO_INCREMENT,
    `sessionId` BIGINT   NOT NULL,
    `startedAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_conversation_session` FOREIGN KEY (`sessionId`) REFERENCES `agentSession` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `message`
(
    `id`             BIGINT      NOT NULL AUTO_INCREMENT,
    `conversationId` BIGINT      NOT NULL,
    `role`           VARCHAR(32) NOT NULL,
    `content`        MEDIUMTEXT  NOT NULL,
    `metadataJson`   TEXT        NULL,
    `createdAt`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_message_conversation` FOREIGN KEY (`conversationId`) REFERENCES `conversation` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
