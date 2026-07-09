-- Tool-level embedding index for cosine-similarity tool selection in the ReAct loop.
-- Managed exclusively by LangChain4j MariaDbEmbeddingStore("jorlan_tools"). Quill does not touch this table.
-- Metadata JSON stores: toolName, skillName.
CREATE TABLE IF NOT EXISTS `jorlan_tools` (
  `id`        UUID        NOT NULL DEFAULT uuid() PRIMARY KEY,
  `embedding` VECTOR(768) NOT NULL,
  `content`   TEXT        NULL,
  `metadata`  JSON        NULL,
  VECTOR INDEX `jorlan_tools_embedding_idx` (`embedding`)
) ENGINE=InnoDB COLLATE uca1400_ai_cs;
