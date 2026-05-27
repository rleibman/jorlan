-- Add password column for local authentication (separate from OAuth-only users).
-- NULL means the user authenticates via OAuth only and has no password set.

ALTER TABLE `user`
  ADD COLUMN `hashedPassword` VARCHAR(128) NULL AFTER `email`;
