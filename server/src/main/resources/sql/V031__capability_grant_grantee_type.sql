-- Add granteeType discriminator to capabilityGrant so capabilities can be granted to roles as well as users.
-- Drop the FK on granteeId (it can now reference either user.id or role.id), clean up duplicates,
-- and add a unique constraint on (capability, granteeId, granteeType).

-- 1. Add granteeType column — all existing rows are user grants.
ALTER TABLE `capabilityGrant`
  ADD COLUMN `granteeType` ENUM('User', 'Role') NOT NULL DEFAULT 'User' AFTER `granteeId`;

-- 2. Drop the foreign key that constrains granteeId to user.id only.
ALTER TABLE `capabilityGrant` DROP FOREIGN KEY `fk_capability_grant_grantee`;

-- 3. Remove duplicate rows — keep the one with the lowest id for each (capability, granteeId, granteeType).
DELETE c1
FROM `capabilityGrant` c1
INNER JOIN `capabilityGrant` c2
  ON  c1.capability   = c2.capability
  AND c1.granteeId    = c2.granteeId
  AND c1.granteeType  = c2.granteeType
  AND c1.id           > c2.id;

-- 4. Unique constraint prevents future duplicates.
ALTER TABLE `capabilityGrant`
  ADD UNIQUE KEY `uq_capability_grant` (`capability`, `granteeId`, `granteeType`);
