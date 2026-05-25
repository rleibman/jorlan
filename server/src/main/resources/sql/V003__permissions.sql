-- Roles, permissions, capability grants, and approval workflow

CREATE TABLE `role`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(128) NOT NULL,
    `description` TEXT         NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_role_name` (`name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `userRole`
(
    `userId` BIGINT NOT NULL,
    `roleId` BIGINT NOT NULL,
    PRIMARY KEY (`userId`, `roleId`),
    CONSTRAINT `fk_user_role_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_user_role_role` FOREIGN KEY (`roleId`) REFERENCES `role` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `permission`
(
    `id`       BIGINT       NOT NULL AUTO_INCREMENT,
    `roleId`   BIGINT       NULL,
    `userId`   BIGINT       NULL,
    `resource` VARCHAR(255) NOT NULL,
    `action`   VARCHAR(128) NOT NULL,
    `scope`    VARCHAR(255) NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_permission_role` FOREIGN KEY (`roleId`) REFERENCES `role` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_permission_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `capabilityGrant`
(
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `capability`          VARCHAR(255) NOT NULL,
    `scopeJson`           TEXT         NULL,
    `granteeId`           BIGINT       NOT NULL,
    `grantorId`           BIGINT       NULL,
    `approvalMode`        VARCHAR(32)  NOT NULL,
    `expiresAt`           DATETIME     NULL,
    `resourceConstraints` TEXT         NULL,
    `createdAt`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_capability_grant_grantee` FOREIGN KEY (`granteeId`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_capability_grant_grantor` FOREIGN KEY (`grantorId`) REFERENCES `user` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `approvalRequest`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `capability`      VARCHAR(255) NOT NULL,
    `scopeJson`       TEXT         NULL,
    `agentId`         BIGINT       NULL,
    `requestorUserId` BIGINT       NOT NULL,
    `sessionId`       BIGINT       NULL,
    `riskClass`       INT          NOT NULL DEFAULT 0,
    `status`          VARCHAR(32)  NOT NULL DEFAULT 'Pending',
    `createdAt`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expiresAt`       DATETIME     NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_approval_request_user` FOREIGN KEY (`requestorUserId`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `approvalDecision`
(
    `id`                BIGINT      NOT NULL AUTO_INCREMENT,
    `approvalRequestId` BIGINT      NOT NULL,
    `decidedBy`         BIGINT      NOT NULL,
    `decision`          VARCHAR(32) NOT NULL,
    `scopeOverride`     TEXT        NULL,
    `decidedAt`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_approval_decision_request` FOREIGN KEY (`approvalRequestId`) REFERENCES `approvalRequest` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_approval_decision_user` FOREIGN KEY (`decidedBy`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
