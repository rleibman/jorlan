-- Users and channel identities

CREATE TABLE `user`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `displayName` VARCHAR(255) NOT NULL,
    `createdAt`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updatedAt`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `active`      TINYINT(1)   NOT NULL DEFAULT 1,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `channelIdentity`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `userId`        BIGINT       NOT NULL,
    `channelType`   VARCHAR(32)  NOT NULL,
    `channelUserId` VARCHAR(255) NOT NULL,
    `verified`      TINYINT(1)   NOT NULL DEFAULT 0,
    `createdAt`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_channel_identity` (`channelType`, `channelUserId`),
    CONSTRAINT `fk_channel_identity_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
