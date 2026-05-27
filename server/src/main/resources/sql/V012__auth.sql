-- Phase 4: authentication columns and system user

ALTER TABLE `user`
  ADD COLUMN `email` VARCHAR(255) NULL UNIQUE AFTER `displayName`;

ALTER TABLE `channelIdentity`
  ADD COLUMN `providerData` JSON NULL AFTER `verified`;

-- System/server user (id=1, reserved for background operations).
-- hashedPassword is intentionally NULL — the server user never logs in via password.
INSERT INTO `user` (`id`, `displayName`, `email`, `active`)
VALUES (1, 'server', 'server@jorlan.internal', 1)
ON DUPLICATE KEY UPDATE `displayName` = 'server', `email` = 'server@jorlan.internal';
