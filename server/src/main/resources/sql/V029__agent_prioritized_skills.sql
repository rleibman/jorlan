-- Per-agent list of skill names that are always included in the LLM tool list
-- regardless of the FULLTEXT relevance filter result.
ALTER TABLE `agent`
    ADD COLUMN `prioritizedSkills` JSON NULL AFTER `trustLevel`;
