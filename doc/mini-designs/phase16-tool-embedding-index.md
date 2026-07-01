# Phase 16 Mini-Design: Tool-Level Embedding Index

## Problem

`filteredToolSpecs` selects at the **skill level** using a MariaDB FULLTEXT index, then delivers **all
tools** from each selected skill to the LLM. With `topN = 4` skills and large skills like `LyrionSkill`
(19 tools) and `UserManagementSkill` (16 tools), the LLM can receive 30–50+ tool descriptors in a single
ReAct step — well past the ~15-tool accuracy threshold for local LLMs.

## Solution

Embed each tool descriptor at registration time into a native MariaDB `VECTOR(768)` table (`jorlan_tools`).
At query time, embed the prompt and retrieve the top-12 most similar individual tools via cosine similarity,
rather than expanding entire skill namespaces.

## Infrastructure Reused

- `EmbeddingStore.mariadb(tableName)` — `ai/src/main/scala/ai/EmbeddingStore.scala`
- `MemoryServiceImpl.semanticQuery` pattern — `server/.../service/memory/MemoryServiceImpl.scala`
- `nomic-embed-text` / 768 dims — `ai/src/main/scala/ai/LangChainConfig.scala`

## New Components

### `V031__tool_embedding_index.sql`

```sql
CREATE TABLE IF NOT EXISTS `jorlan_tools` (
  `id`        UUID        NOT NULL DEFAULT uuid() PRIMARY KEY,
  `embedding` VECTOR(768) NOT NULL,
  `content`   TEXT        NULL,
  `metadata`  JSON        NULL,
  VECTOR INDEX `jorlan_tools_embedding_idx` (`embedding`)
) ENGINE=InnoDB COLLATE uca1400_ai_cs;
```

### `ToolEmbeddingIndex` trait

`server/src/main/scala/jorlan/service/skills/ToolEmbeddingIndex.scala`

- `indexTool(toolName, skillName, text)` — embed + store as daemon fiber
- `purgeBySkillName(skillName)` — filter-delete on `skillName` metadata
- `searchTools(query, limit)` — embed query, return top-N `toolName` strings
- `live` ZLayer: creates its own `jorlan_tools` `MariaDbEmbeddingStore` internally (avoids type conflict
  with the `jorlan_memory` store used by `MemoryServiceImpl`)
- `noOp` ZLayer: all no-ops, used in all tests

### Embedding text per tool

```
"<toolName>: <description> <examplePrompts joined '. '> <keywords joined ' '>"
```

### `SkillRegistryLive` changes

New constructor params: `toolEmbeddingIndex: Option[ToolEmbeddingIndex] = None`, `topKTools: Int = 12`

- `register()`: calls `indexToolEmbeddings(skill)` after `indexSkill`
- `unregister()`: calls `toolEmbeddingIndex.purgeBySkillName(name)`
- `filteredToolSpecs()`: dispatches to `embeddingFilteredToolSpecs` when index available, else
  `fulltextFilteredToolSpecs` (existing logic, unchanged)

### `embeddingFilteredToolSpecs` algorithm

1. All tools from `prioritizedSkills` — always included, no limit
2. `toolEmbeddingIndex.searchTools(prompt + expertise, topKTools)` — filtered to enabled tools
3. `recentToolNames` — always included (already tool-level)
4. If embedding returns empty → fall back to `fulltextFilteredToolSpecs`
5. Resolve tool names → `ToolSpec`

### Wiring

`EnvironmentBuilder.scala`: add `ToolEmbeddingIndex.live` between `EmbeddingStore.mariadb("jorlan_memory")`
and `SkillRegistry.liveSecure`. The `liveSecure` layer now requires `ToolEmbeddingIndex` in its signature.

## Graceful Degradation

`indexTool` fires as `forkDaemon.ignore` — Ollama slowness or unavailability doesn't block registration.
`searchTools` returns `List.empty` on any error → falls back to FULLTEXT skill selection automatically.
No startup crash; no required Ollama dependency.

## Skill Splitting (Option B): Not Needed

Once tool-level embedding is in place, skill splitting adds no tool-selection benefit. The only residual
use of `prioritizedSkills` (always-include by skill name) is unaffected.
