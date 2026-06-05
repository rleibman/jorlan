-- Make user.email NOT NULL. Backfill any existing NULL rows with a generated placeholder.
-- The format <displayName>-<id>@jorlan.internal ensures uniqueness.

UPDATE `user`
SET `email` = CONCAT(REPLACE(`displayName`, ' ', '.'), '-', `id`, '@jorlan.internal')
WHERE `email` IS NULL
   OR `email` = '';

ALTER TABLE `user`
    MODIFY COLUMN `email` VARCHAR(255) NOT NULL;
