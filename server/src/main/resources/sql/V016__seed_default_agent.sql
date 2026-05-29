-- Seed the "Jorlan Interactive" default agent used for interactive shell sessions.
-- The INSERT IGNORE ensures idempotency: running this migration twice has no effect.
INSERT IGNORE INTO `agent` (`name`, `description`, `trustLevel`, `createdAt`)
VALUES ('Jorlan Interactive', 'Default interactive agent for shell sessions', 0, NOW());
