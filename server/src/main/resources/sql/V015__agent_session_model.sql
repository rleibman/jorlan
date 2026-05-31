-- Add modelId to agentSession so Phase 8 can track which LLM model was selected at session creation.
ALTER TABLE `agentSession`
    ADD COLUMN `modelId` VARCHAR(128) NULL AFTER `workspaceId`;
