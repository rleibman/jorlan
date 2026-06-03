-- Add indexes to conversation and message tables for history queries,
-- and a FULLTEXT index on memoryRecord.value for Phase 9 keyword search.

ALTER TABLE `message`
    ADD INDEX `idx_message_conversation_created` (`conversationId`, `createdAt`);

ALTER TABLE `conversation`
    ADD INDEX `idx_conversation_session` (`sessionId`, `startedAt`);

ALTER TABLE `memoryRecord`
    ADD FULLTEXT INDEX `idx_memory_value_fulltext` (`value`);
