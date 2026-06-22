# Mini-Design: Dynamic Tool Selection (Phase 15)

## Context

Local LLMs (small parameter count, constrained hardware) fail consistently when presented with 60+ tools at once — the model lacks capacity to reason over that many tool specs simultaneously. The fix is to filter the tool list before each LLM call, presenting only tools most relevant to the current user prompt, using MariaDB FULLTEXT search for quality ranking (stemming, TF-IDF, plurals, inflection variants).

## Problem Constraints

- Current tool count: ~60+ tools across ~15+ skills (grows with MCP servers)
- Tools are fetched via `skillRegistry.allToolSpecs` in `AgentRunnerImpl.processMessage` (~line 104)
- Filter must happen _before_ tools are passed to `modelGateway.chatStep(sessionId, messages, tools)`
- Must respect the existing enabled/disabled skill mechanism
- Must not break tool _invocation_ — `SkillRegistry.invoke` uses longest-prefix matching and is unchanged

## Resolved Design Decisions

1. **Granularity**: Skill-level. Search returns skill names; all tools from matched skills are included together. Related tools within a skill nearly always travel together. Skills with >10 tools get a startup warning (see below).
2. **Storage**: MariaDB FULLTEXT table (`skillIndex`, V028 migration). Better than in-memory keyword matching: NL mode handles plurals, tense variants, and common inflections via built-in TF-IDF stemming.
3. **Keyword weighting**: `SkillDescriptor.keywords` + `ToolDescriptor.keywords` go in a separate `keywords` column, weighted 3× over the general `searchText` column.
4. **Always-on skills**: None. Good keyword/description writing is the fix; a bypass flag adds complexity without solving the root cause.
5. **Recent-use boost**: Session-scoped, derived from in-memory message history already in `ReactLoopEnv` — zero extra DB query.
6. **Skill table as living store**: On `register()`, check `name + version` against `skillVersion` table; skip re-indexing if unchanged. Store descriptor JSON in `skillVersion.manifestJson`. Use `skillId` FK in `skillIndex`.

---

## Database Schema

### V028: `skillIndex` table

```sql
CREATE TABLE `skillIndex` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `skillId`     BIGINT       NOT NULL,
  `keywords`    TEXT         NOT NULL,    -- skill.keywords + all tool.keywords (high weight)
  `searchText`  MEDIUMTEXT   NOT NULL,    -- name + tool names/descriptions/examplePrompts
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_skill_id` (`skillId`),
  FULLTEXT INDEX `idx_skill_keywords` (`keywords`),
  FULLTEXT INDEX `idx_skill_search`   (`searchText`),
  CONSTRAINT `fk_skill_index_skill` FOREIGN KEY (`skillId`) REFERENCES `skill` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### V029: `agent.prioritizedSkills` column

```sql
ALTER TABLE `agent` ADD COLUMN `prioritizedSkills` JSON NULL;
```

**Search query** (raw SQL — Quill doesn't support FULLTEXT natively; use `qc.ctx.executeQuery`):
```sql
SELECT s.id, s.name,
  (MATCH(si.keywords)    AGAINST(? IN NATURAL LANGUAGE MODE) * 3.0
   + MATCH(si.searchText) AGAINST(? IN NATURAL LANGUAGE MODE)) AS score
FROM skillIndex si
JOIN skill s ON s.id = si.skillId
WHERE si.skillId NOT IN (/* disabled skillIds */)
  AND (MATCH(si.keywords)    AGAINST(? IN NATURAL LANGUAGE MODE) > 0
       OR MATCH(si.searchText) AGAINST(? IN NATURAL LANGUAGE MODE) > 0)
ORDER BY score DESC
LIMIT ?
```

---

## Implementation

### 1. `SkillIndexRepository` (new, in `db` module)

```scala
trait SkillIndexRepository {
  def upsert(skillId: SkillId, keywords: String, searchText: String): RepositoryTask[Unit]
  def search(query: String, disabledSkillIds: Set[SkillId], limit: Int): RepositoryTask[List[(SkillId, String)]]
  def remove(skillId: SkillId): RepositoryTask[Unit]
  def removeWhere(skillIds: Set[SkillId]): RepositoryTask[Unit]
}
```

Follows `QuillRepoBase(qc)` pattern (same as all other repositories in `QuillRepositories.scala`). Upsert uses standard Quill `insertValue(...).onConflictUpdate(...)`; search uses raw SQL for FULLTEXT.

### 2. `SkillRegistryLive` changes

Add `SkillIndexRepository` as a constructor dependency (wired via ZLayer).

**On `register(skill)`:**
1. Emit startup warning if `skill.descriptor.tools.size > 10`:
   ```scala
   _ <- ZIO.when(skill.descriptor.tools.size > 10)(
          ZIO.logWarning(s"Skill '${skill.descriptor.name}' exposes ${skill.descriptor.tools.size} tools — consider splitting it to stay under the 10-tool guideline for local LLMs.")
        )
   ```
2. Look up `skill` row by name; compare `currentVersion` to `skill.descriptor.skillVersion`
3. If version unchanged → skip DB upsert and `skillIndex` upsert (no re-indexing needed)
4. If new or version changed → upsert `skill` row + insert `skillVersion` row (with descriptor JSON in `manifestJson`) + upsert `skillIndex` row

**Index text construction:**
- `keywords`: `(skill.descriptor.keywords ++ skill.descriptor.tools.flatMap(_.keywords)).mkString(" ")`
- `searchText`: skill name + all tool names + all tool descriptions + all tool examplePrompts, joined with spaces

**On `unregister(name)` / `unregisterWhere(pred)`:**
- Resolve skill ID(s) then call `skillIndexRepo.remove(skillId)` / `removeWhere(skillIds)`

### 3. New `SkillRegistry` method

```scala
// Trait addition:
def filteredToolSpecs(
  prompt:            String,
  expertise:         String,
  recentMessages:    List[Message],
  prioritizedSkills: List[String] = List.empty
): UIO[List[ToolSpec]]
```

**Implementation in `SkillRegistryLive`:**
1. Build query string: `prompt + " " + expertise`
2. Get disabled skill IDs from the disabled name set (resolve via `skills` map)
3. Call `skillIndexRepo.search(queryStr, disabledIds, topN)` → returns `List[(SkillId, skillName)]`
4. Extract recently-invoked skill names from `recentMessages` (scan for tool-result messages, extract skill name via prefix matching — same longest-prefix logic already in `invoke`)
5. Apply recency boost to scores, re-sort, take `topN`
6. Collect `ToolSpec`s from matched skills via the in-memory `skills` Ref

**Fallback when FULLTEXT returns zero results**: never fall back to all tools (the LLM chokes). Instead:
1. Use recently-invoked skills from `recentMessages` (already computed for the boost step)
2. If recent is also empty (first message of a session with a generic prompt): return the first `topN` enabled skills sorted alphabetically — deterministic, never empty, avoids the all-tools flood

**Per-agent skill prioritization**: the `filteredToolSpecs` call accepts `prioritizedSkillNames: List[String]` derived from the agent's configuration. Prioritized skills are always included in the result (prepended before the FULLTEXT results). This supports specialized agents (e.g., a calendaring assistant always gets `googleServices` regardless of prompt). Stored as `prioritizedSkills: List[String] = List.empty` on the `Agent` domain model (V029 migration column), exposed via existing agent update mutations in the GQL API.

### 4. `AgentRunnerImpl.processMessage`

```scala
// Before:
tools <- skillRegistry.allToolSpecs

// After (messages and agent are already in scope in the ReAct loop):
tools <- skillRegistry.filteredToolSpecs(
           userMessage,
           agent.expertise.getOrElse(""),
           currentMessages,
           agent.prioritizedSkills
         )
```

### 5. Configuration (server_settings keys, loaded on startup like `skill.disabled`)

- `skill.topN` — number of top skills to present (default `5`)
- `skill.recentBoost` — additive score boost for in-session recently-used skills (default `1.0`)

---

## What Is NOT Changing

- `SkillRegistry.invoke()` — all 60+ skills remain registered; routing by longest-prefix is unchanged
- Disabled skill mechanism — `filteredToolSpecs` respects the existing disabled set
- Capability evaluator / authorization — unchanged
- GraphQL API — no new mutations needed for core feature; `topN` and `recentBoost` settable via existing server_settings mutations

---

## Keyword Population: All Existing Skills

As part of this phase, go through every `SkillDescriptor` and every `ToolDescriptor` in the codebase and populate their `keywords` lists with relevant search terms. The goal is to ensure FULLTEXT search finds the right skill even when the user's prompt uses synonyms or related terms.

**Skills to update** (representative keywords shown; expand liberally):

| Skill | Descriptor keywords | Example tool keyword additions |
|-------|---------------------|-------------------------------|
| `CalculatorSkill` | `math, arithmetic, calculate, compute, formula, equation, number, sum, product` | tool: `add, subtract, multiply, divide, expression, evaluate` |
| `TimeSkill` | `time, clock, timezone, datetime, schedule, when, date, hours, minutes` | tool.now: `current time, local time, UTC`; tool.convert: `timezone conversion, DST` |
| `UnitConversionSkill` | `convert, units, measurement, length, weight, temperature, distance, metric, imperial` | `celsius, fahrenheit, kilometers, miles, kilograms, pounds` |
| `WeatherSkill` | `weather, forecast, temperature, rain, humidity, wind, climate, conditions, outdoor` | `precipitation, sunny, cloudy, storm, UV index` |
| `MarketDataSkill` | `stocks, market, finance, trading, price, portfolio, investment, shares, equity, ticker` | `quote, dividend, P/E, NASDAQ, NYSE` |
| `SearchSkill` | `search, web, internet, find, lookup, browse, query, information, news, research` | `Tavily, results, links, articles` |
| `HttpFetchSkill` | `fetch, HTTP, URL, web page, download, scrape, request, content, REST, API` | `GET, JSON, HTML, response` |
| `LyrionSkill` | `music, audio, Lyrion, LMS, play, playlist, album, artist, song, track, media` | `Squeezebox, streaming, volume` |
| `EmailSkill` / `emailConnector` | `email, mail, message, send, inbox, Gmail, SMTP, IMAP, compose, reply, forward` | `attachment, cc, bcc, subject` |
| `googleServices` (Calendar) | `calendar, event, meeting, appointment, schedule, invite, reminder, Google Calendar` | `RSVP, recurring, availability` |
| `googleServices` (Contacts) | `contacts, address book, people, phone number, Google Contacts, vCard` | `name, email address, organization` |
| `googleServices` (Drive) | `Drive, file, document, Google Drive, storage, upload, download, share, folder` | `Docs, Sheets, Slides, PDF` |
| `ShellSkill` | `shell, bash, command, terminal, execute, script, system, process, file system, ls, grep` | `run, chmod, find, pipe` |
| `UserManagementSkill` | `user, account, admin, permission, role, authentication, password, profile, settings` | `create user, delete user, grant, revoke` |
| `SchedulerSkill` | `schedule, cron, job, timer, recurring, automation, trigger, task, reminder, periodic` | `interval, delay, at, every` |
| MCP skills | (no static descriptor — keywords come from the MCP server's tool descriptions; ensure `McpSkillAdapter` concatenates tool descriptions richly into `searchText`) | — |

Keywords should be lowercase, comma-separated terms. Prioritize terms a user would naturally type, not technical jargon.

---

## Files to Modify / Create

| File | Change |
|------|--------|
| `server/src/main/resources/sql/V028__skill_index.sql` | New migration: `skillIndex` table |
| `server/src/main/resources/sql/V029__agent_prioritized_skills.sql` | `ALTER TABLE agent ADD COLUMN prioritizedSkills JSON NULL` |
| `server/src/main/scala/jorlan/db/repository/QuillRepositories.scala` | Add `QuillSkillIndexRepository` + wire into `AllRepositories` |
| `model/shared/src/main/scala/jorlan/service/SkillIndex.scala` (new) | `SkillIndexRepository` trait |
| `model/shared/src/main/scala/jorlan/agent.scala` (or wherever `Agent` is defined) | Add `prioritizedSkills: List[String] = List.empty` field |
| `server/src/main/scala/jorlan/service/skills/SkillRegistry.scala` | Add `filteredToolSpecs` to trait; in `SkillRegistryLive`: add `SkillIndexRepository` dep, version-aware upsert on register, recency boost logic, startup warning, alphabetical fallback |
| `server/src/main/scala/jorlan/service/AgentRunnerImpl.scala` | Replace `allToolSpecs` with `filteredToolSpecs(msg, expertise, messages, agent.prioritizedSkills)` |
| `server/src/main/scala/jorlan/Jorlan.scala` | Load `skill.topN` and `skill.recentBoost` from server_settings on startup; pass to `SkillRegistryLive` |
| All `*Skill.scala` files with a `SkillDescriptor` | Populate `keywords` list (see keyword table above); also populate `ToolDescriptor.keywords` and `examplePrompts` where missing |

---

## Verification

### Automated
1. `sbtn --error "compile; test:compile"` — clean
2. `sbtn --error test` — all previously passing tests still pass
3. New unit tests for `SkillRegistryLive.filteredToolSpecs`:
   - Prompt that clearly matches one skill → only that skill's tools returned
   - Prompt that matches nothing → returns topN alphabetically-sorted enabled skills (not all tools)
   - Recently used skill with unrelated prompt → skill still appears (recency boost)
   - Disabled skill with matching prompt → excluded from results
   - Agent with `prioritizedSkills = List("weather")` → weather always in results regardless of prompt

### Manual Testing Checklist

**Setup**: Start the server with at least 5 skills enabled. Enable debug logging for `SkillRegistry` so selected skill names are visible in output.

| # | Scenario | Steps | Expected |
|---|----------|-------|----------|
| 1 | Weather query | Send "What's the weather in Chicago?" | Only `weatherSkill` tools in LLM call; no email, calendar, etc. |
| 2 | Stock query | Send "What's the current price of AAPL?" | Only `marketDataSkill` tools |
| 3 | Multi-turn recency | (1) Ask about weather, (2) then ask "compare that to New York" | Turn 2: weather tools still present (recency boost), even though "compare" is ambiguous |
| 4 | Ambiguous first message | Send "hi" or "help me" as first message | Returns first `topN` enabled skills alphabetically — agent is not frozen; no all-tools flood |
| 5 | Specialized agent | Configure agent with `prioritizedSkills = ["googleServices"]`; send "tell me a joke" | Google Calendar/Contacts/Drive tools still appear alongside whatever matched |
| 6 | Disabled skill | Disable `searchSkill`; send "search the web for ZIO tutorials" | `searchSkill` tools absent |
| 7 | MCP skill with >10 tools | Register an MCP server exposing 12 tools | Startup log: `"Skill 'mcp.myserver' exposes 12 tools — consider splitting..."` |
| 8 | Keyword synonym | Send "how many kilometers in 50 miles?" without using word "convert" | `unitConversionSkill` is selected (keyword `distance`/`measurement` matched) |
| 9 | topN config | Set `skill.topN = 2` in server_settings; send a generic prompt | At most 2 skills' tools presented (plus prioritized, if any) |
| 10 | Version-stable restart | Restart server without changing any skill version | No "re-indexing" log messages; `skillIndex` table row counts unchanged |
