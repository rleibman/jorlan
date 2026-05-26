-- Migrate string columns that carry JSON payloads to the native JSON column type,
-- and consolidate eventLog.resourceType + resourceId into a single typed JSON column.

-- Permissions
ALTER TABLE `permission`
    MODIFY `scope` JSON NULL;

-- Messages
ALTER TABLE `message`
    MODIFY `metadataJson` JSON NULL;

-- Skill manifests
ALTER TABLE `skillVersion`
    MODIFY `manifestJson` JSON NOT NULL;

-- Connector config (sensitive — still stored, but JSON-typed for validation)
ALTER TABLE `connectorInstance`
    MODIFY `configJson` JSON NOT NULL;

-- Memory record value
ALTER TABLE `memoryRecord`
    MODIFY `value` JSON NOT NULL;

-- Embedding vector (stored as JSON float array)
ALTER TABLE `memoryEmbedding`
    MODIFY `vector` JSON NOT NULL;

-- Event log: replace separate resourceType + resourceId columns with a single typed JSON resource column
ALTER TABLE `eventLog`
    ADD COLUMN `resource` JSON NULL AFTER `sessionId`,
    MODIFY `payloadJson` JSON NULL;

UPDATE `eventLog`
SET `resource` = JSON_OBJECT(
    'type', `resourceType`,
    'id', `resourceId`
)
WHERE `resource` IS NULL
  AND (`resourceType` IS NOT NULL OR `resourceId` IS NOT NULL);

ALTER TABLE `eventLog`
    DROP COLUMN `resourceType`,
    DROP COLUMN `resourceId`;

-- Artifact metadata
ALTER TABLE `artifact`
    MODIFY `metadataJson` JSON NULL;
