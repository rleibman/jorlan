-- Unify scheduled jobs: pipeline becomes mandatory, legacy prompt/inputJson columns removed.
-- Backfill every job that predates pipelines by wrapping its prompt/inputJson into a single-step pipeline.

UPDATE `schedulerJob`
SET `pipeline` = JSON_OBJECT(
    'steps', JSON_ARRAY(JSON_OBJECT(
        'name', 'legacy-prompt',
        'systemPrompt', '',
        'userPrompt', COALESCE(NULLIF(`prompt`, ''), `inputJson`, ''),
        'tools', JSON_ARRAY(),
        'mode', 'ReactLoop',
        'outputVar', 'result',
        'retryOnFail', 0
    )),
    'invariants', JSON_OBJECT()
)
WHERE `pipeline` IS NULL;

ALTER TABLE `schedulerJob`
    MODIFY COLUMN `pipeline` LONGTEXT NOT NULL;

ALTER TABLE `schedulerJob`
    DROP COLUMN `prompt`,
    DROP COLUMN `inputJson`;
