-- Add chat_ref to agentSession for connector-bound durable sessions (Phase 11)
-- Stores the channel-native chat identifier (e.g. Telegram chat id) so a session
-- can be resumed across server restarts for the same external chat.

ALTER TABLE `agentSession`
    ADD COLUMN `chatRef` VARCHAR(255) NULL AFTER `modelId`;

CREATE INDEX `idx_agent_session_chat_ref` ON `agentSession` (`userId`, `chatRef`);
