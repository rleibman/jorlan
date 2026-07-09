---
name: sprint4-uc-importer
description: Sprint4 use-case manifest importer (headless CLI) and its downstream effects on Roles/Agents/Memory/MCP/Scheduler/Event Log pages; two bugs fixed this session
metadata:
  type: project
---

## Feature

`UseCaseImporterApp` (shell module, headless ZIOApp) reads a `UseCaseManifest` JSON
(`doc/use-cases/manifests/*.json`, worked example `food_calendar.json`) and provisions via GraphQL: a Role
+ capability grants, optional role→user assignment, an Agent, memory seeds, MCP server registrations,
declarative skill drafts, and a SchedulerJob + Trigger. No new web UI — verification is entirely via
existing admin pages. Design doc: `doc/mini-designs/use-case-manifest-importer.md`. Schema doc:
`doc/use-cases/manifest-schema.md`.

Idempotent by design (see mini-design's table): Role/Agent/Job matched by name, MemoryRecord by key,
CapabilityGrant/MCP server always upsert, Trigger matched by (type, expression). Re-running should report
`[SKIP]` for every step and create zero duplicate rows.

## Two bugs fixed this session (both directly checkable in web UI)

1. **Agent upsert duplicate-row bug** — `QuillRepositories.scala` `AgentRepository.upsert`, ~line 369.
   Root cause: `insertValue(...).onConflictUpdate(...).returningGenerated(_.id)` — MariaDB's `INSERT ...
   ON DUPLICATE KEY UPDATE` still advances the auto-increment counter on the UPDATE branch, so
   `returningGenerated` (LAST_INSERT_ID()-backed) returned a bogus new id even when updating, silently
   creating a second row. Fixed by branching explicitly: plain insert when `agent.id == AgentId.empty`,
   else a plain `.update(...)` by id. **QA check**: Agents page (`#/agents`) must show exactly ONE row
   per agent name after re-running the importer.
2. **Event Log pagination bug** — now fixed; current `EventLogPage.scala` is a pure live WebSocket tail
   (`subscribeToEventLog`), not a paginated historical query. New events prepend
   (`batch.reverse ::: state.value.events`) and the list is capped at the most recent 200 via `.take(200)`
   — inherently newest-first, no stale/arbitrary slice possible. **QA implication**: to see importer
   activity on this page you must have it open (or reconnect) while the importer runs — it will NOT
   backfill historical events on load. Page is "always mounted" (like Chat) per `AppRouter.scala` — nav
   away just toggles CSS display, so the WS subscription and the 200-event buffer persist across
   navigation within the same session.

## Page routes relevant to importer verification (confirmed in AppRouter.scala)

- `#/roles` — RolesPage: Name/Description/Actions table, "+ New Role", "Capabilities" dialog
  (capability/mode/revoke table + grant-toggle list), no pagination.
- `#/agents` — AgentsPage: Name/Description/Model/Invariants(chip count)/Edit-Invariants table. **No
  Trust Level or Prioritized Skills columns in the UI** — those manifest fields aren't independently
  checkable on this page, only via GraphQL/DB. No pagination.
- `#/memory` — MemoryPage: "+ Remember", search text field (textSearch), table Key/Scope/Value(truncated
  60 chars)/Created/Actions(Share-Privatize toggle, Forget), MuiTablePagination (5/10/25/50).
- `#/mcp` — McpServersPage: "Reload" (reloadMcpServers + toast), "+ Add Server", table
  Name/Transport/Command-or-URL/Status(Enabled chip)/Actions. Add/Edit dialog: Name disabled on edit,
  Transport select, Command+Args or URL, env var rows, Enabled switch, Keywords. No pagination.
- `#/scheduler` — SchedulerPage: "+ New Job", "Refresh", jobs table
  Name/Status-chip/Scheduled/Retries/Actions(Pause-Resume/Cancel/Run Now/Edit/Delete)/expand-toggle.
  Expand row shows Result-or-Error block, Triggers sub-table (Type/Expression/Enabled/Delete + "+ Add
  Trigger"), Pipeline Runs sub-table (Run ID/Status/Started/Finished/**Failed Step**).
  **Important gap: the page never displays pipeline step names/order directly** — the only place a step
  name surfaces is the "Failed Step" column of a Pipeline Run after a run, or inside the Edit dialog
  (`CreateSchedulerJobWizard`, not yet read in detail — check there if a test needs to see the full step
  list before running). MuiTablePagination (5/10/25).
- `#/users` — role-to-user assignment is verified here, not on `#/roles`: per-user "Roles" button opens a
  dialog listing assigned roles + an "assign role" select, calls `permission.searchRoles(RoleSearch(userId
  = Some(user.id)))`.

## Cron gotcha (worth a dedicated test case)

Server uses cron4s 6-field syntax (`?` for unused dom/dow), but use-case docs write 5-field cron. Manifest
schema doc has a conversion table. `food_calendar` job trigger is `0 0 9 ? * 3` (Wed 09:00). Wrong field
count/character is the single easiest manifest authoring mistake per the schema doc's own "Failure modes"
section — worth a negative test case (malformed cron does what? rejected at import time vs. accepted then
fails at trigger-fire time).

## Worked example: food_calendar.json 6-step pipeline

`gather-context` (ReactLoop) → `plan-meals` (SingleCall) → `research-recipes` (ReactLoop) →
`update-calendar` (ReactLoop) → `shopping-list` (ReactLoop) → `notify-and-schedule` (ReactLoop). Each
step's `outputVar` (context/meal_plan/recipes/calendar_result/shopping_list/summary) is what downstream
steps reference via `{{steps.<outputVar>.output}}` — this was the bug fixed this session (steps were
previously cross-referenced by `name` instead of `outputVar`). Pipeline-level `invariants` (dietary_rules/
schedule_rules/seasonal_hints/preferred_sites) are injected into every step via `{{invariants.KEY}}`
rather than repeated in each systemPrompt.
