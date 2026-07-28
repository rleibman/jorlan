# Mini-Design: meal-o-rama MCP Integration for the Food-Calendar Job

**Status**: Design approved, Jorlan-side implementation in progress
**Date**: 2026-07-11
**Related**: `doc/use-cases/manifests/food_calendar.json`, `doc/use-cases/food_calendar.md`, `MCP.md`

---

## 1. Context and motivation

The `food-calendar-weekly-plan` job plans a week of dinners: it searches the web for a recipe per dish,
writes Google Calendar events, builds a shopping list, and sends a summary. Two things are wrong with it:

1. **Recipes are never captured.** Each week the agent finds a URL, drops it into a calendar event, and
   forgets it. Nothing accumulates, nothing is reusable, and nothing is ever *scaled* — most published
   recipes serve four or more people; the household is two.
2. **Recipe sites block scraping.** The agent can find a URL, but nothing downstream can reliably read what
   is behind it.

**meal-o-rama** (a separate project; a recipe manager) already solves the recipe-domain half of this: a
multi-stage parser chain, a grocery-item/aisle dictionary, an ingredient NLP parser, and a recipe library.
It will expose an **MCP server**, and the food-calendar agent will use it as its recipe backend.

**Jorlan remains the source of record and the orchestrator.** meal-o-rama is a recipe library and parsing
service, nothing more. Google Calendar stays the human-facing view of the plan; OurGroceries stays the
shopping list.

The intended outcome: a configurable share of each week's dinners comes from the meal-o-rama library
(already parsed, already scalable); the rest come from new web sources — and *those get imported into
meal-o-rama*, so the library compounds week over week and every recipe the household actually cooks is
sized for two people.

The meal-o-rama side of this work is specified in that project's own
`doc/mcp-server-and-recipe-import.md`. This document is the Jorlan side, plus the wire contract between
the two.

---

## 2. Findings: how Jorlan's MCP support actually works

These are the constraints the design has to live inside. All of them were verified against the code on
`sprint5`.

**Jorlan is an MCP client only.** It consumes external MCP servers and re-exposes their tools as ordinary
skills. It does not host an MCP server, and this integration does not change that: meal-o-rama hosts, Jorlan
connects. Three transports exist (`McpTransport` in `model/shared/src/main/scala/jorlan/mcp.scala`): `Stdio`,
`Http` (streamable HTTP, MCP 2025-03-26), and `HttpSse` (the older 2024-11-05 SSE flavour). The client is
hand-rolled JSON-RPC 2.0 in `server/src/main/scala/jorlan/service/mcp/McpClient.scala` — there is no MCP SDK
dependency.

**Tools are namespaced `mcp.<server>.<tool>`.** `McpSkillAdapter` (`service/mcp/McpSkillAdapter.scala:41`)
sanitises the server name and prefixes every tool with `mcp.<name>.`. So meal-o-rama's `search_recipes`
becomes `mcp.mealorama.search_recipes`. `SkillRegistry.invoke` routes by longest-prefix match, which is what
makes the dotted skill name safe.

**`PipelineStep.tools` is a prefix allowlist, not a tool list.** `AgentRunnerImpl.scala:136` filters with
`t.name == p || t.name.startsWith(p + ".")`. This is the source of a live bug in the current manifest — see
§5.2.

**Every MCP tool requires exactly one capability: `mcp.call`.** `McpSkillAdapter` sets
`requiredCapabilities = List(CapabilityName("mcp.call"))` on *every* tool of *every* MCP server. There is no
per-server or per-tool capability today. Granting `mcp.call` grants the agent every tool on every registered
MCP server. This is coarse, and it is already tracked as a cross-cutting item in `doc/use-cases/TODO.md`
("Approval-gate verification for MCP tools"). It is out of scope here, but it means the food-calendar agent's
`mcp.call` grant is not as narrow as it looks.

**Results over 4 KB are spilled to the workspace.** `McpSkillAdapter` writes any oversized tool result to
`mcp-spill/mcp_<server>_<tool>_<millis>.json` under the session/user-scoped workspace root and hands the model
a pointer instead of the payload. This is a correctness feature (it protects the context window), but it costs
an extra `workspace.read` round-trip. **The meal-o-rama tools must therefore return lean results** — see §4.

**A failing MCP server is skipped, not fatal.** `McpManager.doLoad` registers servers in parallel on a forked
fiber with a 45-second per-server budget; a server that fails to initialise is logged and skipped, and the rest
of Jorlan still starts. This is why the Jorlan changes here can land safely *before* meal-o-rama's endpoint
exists.

**MCP servers live in the DB, not in config.** They are rows in the `mcpServer` table (`V039__mcp_servers.sql`),
managed through GraphQL / the web MCP page / the shell, and seeded by the use-case importer. Nothing about MCP
is in `configuration.scala`.

**MCP tools have weak semantic retrieval.** `SkillRegistry` builds each tool's embedding text from
`name: description <keywords>`, and MCP tools carry `examplePrompts = List.empty`. The `keywords` field on
`McpServerConfig` is server-wide and exists to compensate. This only matters on the non-pipeline path; our
job's steps declare explicit `tools` prefixes, so retrieval is bypassed. Set good keywords anyway, for
interactive chat use.

### 2.1 The blocker: HTTP MCP servers cannot be authenticated

`HttpMcpClient` sends exactly two headers — `Content-Type` and `mcp-session-id` (`McpClient.scala:62`). The
`McpServerConfig.env` map is passed **only** to stdio subprocesses (`McpClient.scala:392`). There is no field
anywhere in the MCP config that reaches an HTTP request as a header.

**Consequence: today it is impossible for Jorlan to authenticate to an HTTP MCP server.** Since meal-o-rama's
API is bearer-JWT protected and multi-tenant (every repository call is scoped to the session's account), this
blocks the entire integration. Fixing it is §5.1, and it is the first thing to build.

---

## 3. The verification rule

This is the load-bearing design decision, and it comes from a question worth restating:

> *If a recipe we find on the web cannot be parsed, how do we know it matches what the planner asked for?*

You don't — and that is precisely why an unparsed recipe must be a last resort rather than a normal outcome.
The distinction is not cosmetic:

- **Parsed recipe** → structured ingredients, servings, and times. The agent can check it *mechanically*
  against the household rules (no cruciferous vegetables, red meat at most once a week, weeknight dinners
  under 45 minutes) and can scale it to two servings.
- **Unparsed recipe** → nothing but a search-result title and a snippet. Ingredients, serving count and prep
  time are all unknown. It cannot be checked against a single household rule, and it cannot be scaled.

So each day's dish resolves through three tiers, in order:

| Tier | Source | Outcome |
|---|---|---|
| 1 | meal-o-rama library hit | Verified, scaled, linked to its meal-o-rama permalink |
| 2 | Web recipe, import succeeded | Verified, scaled, **added to the library**, linked to its meal-o-rama permalink (not the source URL) |
| 3 | Import failed | Retry the next search result; then fall back to a **library** recipe for that day |

Only when tier 2 *and* the tier-3 fallback both fail does the calendar event link the raw source URL — and
then it carries an explicit caveat (the `unverified_note` job invariant), in the same spirit as the existing
"No suitable recipe found" note:

> ⚠️ Unverified — this recipe could not be imported, so its ingredients, serving size and prep time were
> **not** checked against our dietary rules, and it is **not** scaled to 2 servings.

Tier 3's raw-URL escape hatch is not the happy path, and it is always visibly labelled.

---

## 4. Wire contract: what meal-o-rama must expose

meal-o-rama implements this; it is reproduced here because it is the interface Jorlan codes against.

- **Endpoint**: `POST http://localhost:8077/mcp`, streamable HTTP (`McpTransport.Http`).
- **Auth**: `Authorization: Bearer <JWT>`, a long-lived token. meal-o-rama derives the account scope from it.
- **Protocol**: JSON-RPC 2.0 — `initialize`, `notifications/initialized`, `tools/list`, `tools/call`.

Tools, as the agent sees them (`mcp.mealorama.*`):

| Tool | Purpose |
|---|---|
| `search_recipes(query?, cuisine?, course?, maxTotalMinutes?, excludeIngredients?, limit)` | Library lookup — tier 1. `excludeIngredients` is what enforces the cruciferous-vegetable rule. |
| `get_recipe(id, servings?)` | Full recipe, **scaled** when `servings` is given. Returns the permalink. |
| `import_recipe(url, servings?)` | Tier 2. Fetch → render if blocked → parse → persist → return the scaled recipe and permalink, or a structured failure reason. |
| `mark_recipe_unparsable(url)` | Remember a bad URL so next week does not retry it. |
| `recent_meals(startDate, endDate)` | What we ate lately, from meal-o-rama's own scheduled meals — more reliable than re-reading calendar event titles. |
| `schedule_meal(date, mealType, recipeId \| recipeTitle, servings)` | Mirror the plan into meal-o-rama. |
| `build_shopping_list(recipeIds[], servings)` | Consolidate the week's ingredients: merge duplicate quantities, resolve to grocery items, group by aisle. Output feeds OurGroceries. |
| `parse_ingredients(text)` | Free-text → structured ingredients. |

**Result size is part of the contract.** Jorlan spills anything over 4 KB (§2), so `search_recipes` must
return a compact summary per recipe (id, title, cuisine, course, servings, total time, permalink, ingredient
*names*), not full recipe bodies. `get_recipe` is the tool that returns detail, one recipe at a time.

**Rendered HTML never crosses this boundary.** The anti-scraping fix (a headless browser) lives *behind*
`import_recipe`, inside meal-o-rama. A rendered page is 200 KB–2 MB; passing it through the agent would blow
the context window and spill on every single import. The agent calls `import_recipe(url)` and the whole
fetch-render-parse problem stays server-side. This is why the browser is not a Jorlan skill.

---

## 5. Jorlan changes

### 5.1 Add `headers` to the MCP server config *(required; blocks everything else)*

Per §2.1, thread a `headers` field through every MCP surface:

- `model/shared/src/main/scala/jorlan/mcp.scala` — `McpServerConfig.headers: Map[String, String] = Map.empty`
- `server/src/main/resources/sql/V040__mcp_server_headers.sql` — `headers LONGTEXT NOT NULL` holding a JSON
  object, exactly as `env` / `args` / `keywords` are stored in `V039__mcp_servers.sql`
- `server/src/main/scala/jorlan/db/repository/QuillRepositories.scala` — `McpServerRow`, `fromConfig`, `toConfig`
- `model/shared/src/main/scala/jorlan/api.scala` — the wire type reuses the existing `McpEnvVarInfo` key/value
  pair shape, because GraphQL has no map type and `env` already does exactly this
- `server/src/main/scala/jorlan/graphql/JorlanAPI.scala` — `McpServerView`, `UpsertMcpServerInput`.
  **Regenerate `jorlan.gql` and `JorlanClient.scala` with the scripts; never hand-edit them.**
- `server/src/main/scala/jorlan/service/mcp/McpClient.scala` — `HttpMcpClient` and `HttpSseMcpClient` add each
  configured header to every request (including the initial SSE GET)
- `model/shared/src/main/scala/jorlan/usecase/UseCaseManifest.scala` —
  `ManifestMcpServer.headers: List[ManifestMcpEnvVar] = List.empty`
- `web/.../pages/McpServersPage.scala` and the `ShellCommand.McpAdd` / `McpEdit` surfaces

Headers are secrets (they carry the bearer token) and, like `env`, are stored in plaintext and readable by any
holder of `admin.settings`. That is a pre-existing property of the MCP config, not a new one, but it is worth
knowing.

### 5.2 Fix the MCP tool-prefix bug in `food_calendar.json`

The shopping-list step declares `"tools": ["ourgroceries"]`. Because `PipelineStep.tools` is a **prefix
allowlist** (§2) and MCP tools are named `mcp.ourgroceries.*`, that prefix matches nothing:
`"mcp.ourgroceries.add_item".startsWith("ourgroceries.")` is `false`. **The step currently runs with an empty
tool list.** It degrades quietly only because the prompt says "if not, just output the list" and the server is
disabled anyway.

The correct prefix is `mcp.ourgroceries`. Check the other manifests in `doc/use-cases/manifests/` for the same
mistake.

Note that `validatePipelineReferences` in the use-case importer validates `{{steps.*}}` references but **not**
`tools` entries or `{{invariants.*}}` references, so a bad tool prefix passes import silently. That is why this
bug survived.

### 5.3 Grant `mcp.call`

`food_calendar.json`'s role does not grant `mcp.call`, so even with the prefix fixed, every MCP tool call
would be denied at the capability check. Add it to `role.capabilities`.

### 5.4 Remove committed credentials

`food_calendar.json` contains a real-looking `OURGROCERIES_PASSWORD`. Replace with `CHANGE_ME`, matching every
other manifest.

---

## 6. The rewritten `food_calendar.json`

**Role** — existing capabilities, plus `mcp.call` (§5.3).

**Agent** — `prioritizedSkills` gains `mcp.mealorama` and `mcp.ourgroceries`.

**MCP servers** — the `mealorama` entry is registered `enabled: false` until meal-o-rama's endpoint exists:

```json
{
  "name": "mealorama",
  "transport": "Http",
  "url": "http://localhost:8077/mcp",
  "headers": [{ "key": "Authorization", "value": "Bearer CHANGE_ME" }],
  "enabled": false,
  "keywords": ["recipe", "recipes", "meal", "cooking", "ingredients", "shopping list", "scale", "servings"]
}
```

`transport` must match the enum case name exactly — `Http`, not `http`. `McpTransport.valueOf` is what parses
it, and a bad value surfaces only as an opaque `upsertMcpServer returned nothing` at import time.

**New job invariants**:

- `new_recipe_percent` — `"40"`: the target share of the week's dinners drawn from new web sources; the rest
  come from the meal-o-rama library.
- `household_servings` — `"2"`.
- `unverified_note` — the ⚠️ caveat text from §3.

**Steps** — seven, replacing the current six:

1. **`gather-context`** — `calendar.listEvents`, `weather.forecast`, `mcp.mealorama.recent_meals`. Recent
   meals now come from meal-o-rama rather than being reverse-engineered out of calendar event titles.
2. **`plan-meals`** *(SingleCall, no tools)* — as today, plus: target `{{invariants.new_recipe_percent}}` of
   the week as new dishes, and mark each day `NEW` or `LIBRARY`.
3. **`source-recipes`** *(ReactLoop; tools `mcp.mealorama`, `search.web`)* — implements the three-tier rule
   from §3. `LIBRARY` days call `search_recipes` (passing the cruciferous list as `excludeIngredients` and the
   day's time budget as `maxTotalMinutes`). `NEW` days do one `search.web`, then
   `import_recipe(url, servings: 2)`; on failure they try the next result, then `mark_recipe_unparsable`, then
   fall back to `search_recipes`. Emits one line per day carrying the link, a `VERIFIED` / `UNVERIFIED` flag,
   ingredients, total time, and an optional `LONG PREP` marker.
4. **`schedule-meals`** — `mcp.mealorama.schedule_meal` per day, mirroring the plan into meal-o-rama at
   `servings: 2`.
5. **`update-calendar`** — `calendar.createEvent` per day. The description links the **meal-o-rama permalink**
   for `VERIFIED` days, and the raw source URL plus `{{invariants.unverified_note}}` for `UNVERIFIED` ones.
6. **`shopping-list`** — `mcp.mealorama.build_shopping_list` (quantities merged, grouped by aisle), then push
   to `mcp.ourgroceries` — note the corrected prefix (§5.2).
7. **`notify-and-schedule`** — as today: a `notify.user` summary, then `scheduler.create_job` reminders for
   `LONG PREP` days.

The trigger is unchanged: `0 0 9 ? * 2` — 09:00 on Wednesday under cron4s's Monday=0 numbering.

---

## 7. Rollout

The Jorlan changes are safe to land before meal-o-rama's endpoint exists: an unreachable MCP server is logged
and skipped at startup (§2), and the `mealorama` entry ships `enabled: false` regardless.

1. Land §5 (Jorlan): the `headers` field end-to-end, and the rewritten manifest.
2. meal-o-rama implements its side (see that repo's `doc/mcp-server-and-recipe-import.md`). **Its recipe import
   is currently non-functional** — three `???` sit on the hot path — so that work is a prerequisite, not a
   nice-to-have.
3. Mint a long-lived JWT in meal-o-rama, put it in the manifest's `Authorization` header, flip
   `enabled: true`, re-import the manifest.
4. Verify the tools register as `mcp.mealorama.*` (web MCP page or shell `mcp list`), then trigger
   `food-calendar-weekly-plan` by hand.

### Verification

- `sbtn --error test` — extend `McpServerRepositorySpec` (round-trip `headers`) and the `McpClient` tests
  (assert configured headers are sent on both HTTP transports).
- Re-import the manifest with the use-case importer and watch for `[FAIL]` lines. Note that importer failures
  are opaque: a bad `transport` enum and a missing `admin.settings` capability both surface only as
  `<mutation> returned nothing`.
- End-to-end: trigger the job and confirm the library/new split roughly matches `new_recipe_percent`, web
  recipes land in meal-o-rama, calendar events link meal-o-rama permalinks and are scaled to two servings, any
  `UNVERIFIED` day carries the caveat, and the shopping list reaches OurGroceries.

## 8. Out of scope

- Per-server or per-tool MCP capabilities (today everything is `mcp.call`; tracked separately in
  `doc/use-cases/TODO.md`).
- A Jorlan-hosted MCP server. Jorlan is a client.
- Everything inside meal-o-rama.
