-- Add composite index on memoryRecord(scope, userId) to support the hot per-message
-- MemoryService.query path without a full table scan (P9-027).

ALTER TABLE `memoryRecord`
    ADD INDEX `idx_memory_scope_user` (`scope`, `userId`);
