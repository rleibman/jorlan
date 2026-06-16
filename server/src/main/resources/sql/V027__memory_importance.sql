-- Add importance score to memory records and a unique deduplication key index

ALTER TABLE `memoryRecord`
    ADD COLUMN `importance` TINYINT NOT NULL DEFAULT 5 AFTER `value`;

CREATE INDEX `idx_memory_importance` ON `memoryRecord` (`importance`);

-- Deduplicate existing rows before adding the unique index:
-- Keep only the most recent row per (recordKey, userId, agentId) triple.
DELETE m1
FROM `memoryRecord` m1
         INNER JOIN `memoryRecord` m2
                    ON m1.recordKey = m2.recordKey
                        AND m1.userId <=> m2.userId
                        AND m1.agentId <=> m2.agentId
                        AND m1.id < m2.id;

-- Unique constraint enables ON DUPLICATE KEY UPDATE deduplication for checkpoints.
-- NULL values in userId/agentId are treated as distinct by MariaDB, so shared records
-- without an owner do not conflict with each other.
ALTER TABLE `memoryRecord`
    ADD UNIQUE INDEX `uidx_memory_key_user_agent` (`recordKey`(200), `userId`, `agentId`);
