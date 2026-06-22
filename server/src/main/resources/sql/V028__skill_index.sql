-- Derived FULLTEXT cache for dynamic tool selection: one row per registered skill.
-- Populated by SkillRegistryLive.register(); cleared on unregister().
-- keywords column (skill + tool keywords) is weighted 3x higher than searchText in scoring.
CREATE TABLE `skillIndex` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `skillId`     BIGINT       NOT NULL,
  `keywords`    TEXT         NOT NULL,
  `searchText`  MEDIUMTEXT   NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_skill_index_skill_id` (`skillId`),
  FULLTEXT INDEX `idx_skill_keywords` (`keywords`),
  FULLTEXT INDEX `idx_skill_search`   (`searchText`),
  CONSTRAINT `fk_skill_index_skill` FOREIGN KEY (`skillId`) REFERENCES `skill` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
