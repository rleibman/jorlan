CREATE TABLE IF NOT EXISTS `jorlan_memory` (
  `id`        UUID        NOT NULL DEFAULT uuid() PRIMARY KEY,
  `embedding` VECTOR(768) NOT NULL,
  `content`   TEXT        NULL,
  `metadata`  JSON        NULL,
  VECTOR INDEX `jorlan_memory_embedding_idx` (`embedding`)
) ENGINE=InnoDB COLLATE uca1400_ai_cs;
