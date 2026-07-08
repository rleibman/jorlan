/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# Phase PUCI Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala Reviewer, Code Simplicity Reviewer, Pattern Recognition
Specialist, Performance Oracle, ScalaDoc Auditor, Test Coverage Tracker, UI Test Plan Writer, SRS/SDD Conformance
Reviewer)
**Date**: 2026-07-06
**Branch**: `Sprint4`
**Scope**: Phase PUCI — Use-Case Manifest Importer (`model/shared/src/main/scala/jorlan/usecase/UseCaseManifest.scala`,
`useCaseImporter/src/main/scala/jorlan/shell/UseCaseImporterApp.scala`,
`shellClient/src/main/scala/jorlan/shell/client/ZIOClientRepositories.scala` (new module extracted from `shell`),
`server/src/main/scala/jorlan/db/repository/QuillRepositories.scala`,
`server/src/main/scala/jorlan/graphql/JorlanAPI.scala`,
`server/src/main/scala/jorlan/service/llm/OllamaModelGateway.scala`, `ai/src/main/scala/ai/util.scala`,
`doc/use-cases/manifests/food_calendar.json`, `doc/mini-designs/use-case-manifest-importer.md`,
`doc/use-cases/manifest-schema.md`, `doc/use-cases/HOW-TO-IMPLEMENT-A-USE-CASE.md`)

---

## Executive Summary

This phase delivers a working end-to-end path from a declarative JSON use-case manifest to a fully provisioned role,
agent, memory seeds, MCP servers, declarative skills, and a scheduled pipeline job on a running Jorlan server, via a new
headless `useCaseImporter` CLI module built on a cleanly extracted `shellClient` module. Along the way the work surfaced
and fixed two real, pre-existing production bugs in `QuillRepositories.scala`: an `Agent.upsert` that silently
duplicated rows on every "update" due to a MariaDB `LAST_INSERT_ID()`/`ON DUPLICATE KEY UPDATE` interaction, and 16
search methods across nearly every domain type that applied pagination *before* sorting — the latter is what was making
the Event Log web page show almost no useful recent activity. Three issues raised by reviewers (wrong module path in
run-command docs across 4 locations, an incorrect per-agent uniqueness claim in the manifest schema doc, and opaque
runtime failures from bad pipeline template references) were fixed directly in this session after the agents reported
them and are recorded below as resolved.

**Update: all remaining items (PUCI-004 through PUCI-009) have since been resolved in this same session**, after the
initial multi-agent review. The two Critical items were addressed first: `jobTimeoutSeconds` was raised 300→900s and
wired into `application.conf` (`JORLAN_SCHEDULER_JOB_TIMEOUT_SECONDS`) so it comfortably exceeds `ai.timeout`; and
`AgentSearch`/`RoleSearch` gained a proper server-side exact-name filter (new `AgentsInput`/`RolesInput` GraphQL types,
schema + client regenerated), replacing the fetch-a-page-then-client-filter pattern in the importer. The Warning/
Suggestion items followed: 6 missing `logEvent` calls were added (4 new `EventType` cases), the 9 `provisionX`
functions were de-duplicated via a shared `reportFailure` helper, and both Quill bugs now have regression tests
(`RepositorySpec`, `SortingAndSortingSpec`). PUCI-007 (floating methods) was resolved in a scoped, safer form —
grouped and labeled in place rather than fully relocated — after discovering that `web`'s `AsyncCallbackRepositories`
implements the same shared `Repositories[F[_]]` trait, meaning a full move would ripple into `web` and `server` too;
that larger cross-module refactor is tracked as a separate follow-up, not forced into this cleanup pass.

**Overall health: Clean — ready to advance to the next phase.**

ScalaDoc quality issues (wrong run-command module path in 4 places, incorrect uniqueness-scope wording) were caught by
the ScalaDoc Auditor and corrected directly in this session; no outstanding documentation defects remain from that pass.
`doc/development_roadmap.md` has no entry at all for this work, which should be added for traceability.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area                        | Issue                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | File : Line                                                                                                                                                                                                               | Recommended Action                                                                                                                                                                                                                                         |
|--------|------------|------------|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | PUCI-001   | Critical   | Documentation               | Run-command references said `shell/runMain jorlan.shell.UseCaseImporterApp` / cited `shell/src/main/scala/...` in 4 places, but the class lives in the new `useCaseImporter` module; `sbtn`'s detached-stdio server also cannot relay stdout for this forced `runMain`, making it appear to hang. **RESOLVED** — all 4 references corrected to `useCaseImporter/runMain` with corrected paths, and run commands switched from `sbtn` to plain `sbt`.                                                                                                                                                                                                                                                            | `useCaseImporter/src/main/scala/jorlan/shell/UseCaseImporterApp.scala` (scaladoc), `doc/mini-designs/use-case-manifest-importer.md`, `doc/use-cases/manifest-schema.md`, `doc/use-cases/HOW-TO-IMPLEMENT-A-USE-CASE.md`   | Fixed directly this session; no further action.                                                                                                                                                                                                            |
| [x]    | PUCI-002   | Warning    | Documentation               | `manifest-schema.md`'s `ManifestJob.name` description said the field "must be unique among jobs owned by this agent," contradicting the actual DB constraint (V037: `UNIQUE(userId, name)`, scoped per user) and the importer's own code comment. **RESOLVED** — doc corrected to "must be unique per user (not per agent — the DB enforces `UNIQUE(userId, name)`)".                                                                                                                                                                                                                                                                                                                                           | `doc/use-cases/manifest-schema.md`                                                                                                                                                                                        | Fixed directly this session; no further action.                                                                                                                                                                                                            |
| [x]    | PUCI-003   | Warning    | Test Coverage / Correctness | Manifest authoring errors (bad `{{steps.NAME.output}}` template references) were only detectable as an opaque runtime failure deep inside a live pipeline execution — exactly what happened this session with `food_calendar.json` referencing a step's `name` instead of its `outputVar`. **RESOLVED** — added `validatePipelineReferences` (`UseCaseImporterApp.scala:84`, invoked at line 355) which walks pipeline steps in order, extracts every `{{steps.NAME.output}}`/`{{steps.NAME.status}}` reference via regex, and fails fast with a full listing of bad references before any network call (before login). Verified against a deliberately-broken manifest that correctly caught 3 bad references. | `useCaseImporter/src/main/scala/jorlan/shell/UseCaseImporterApp.scala:84,355`                                                                                                                                             | Fixed directly this session; no further action.                                                                                                                                                                                                            |
| [x]    | PUCI-004   | Critical   | Performance                 | The `ai.timeout` bump from 5→10 minutes is very likely ineffective for the primary motivating scenario (scheduled pipelines): `TriggerEngine` wraps each agent turn in its own independent `jobTimeout`, default 300s, sourced from `configuration.scala:51` with no manifest-level or per-job override, which fires before the LLM client's 10-minute timeout ever could. **RESOLVED** — `jobTimeoutSeconds` default raised 300→900s (`configuration.scala`), wired into `application.conf` under `jorlan.scheduler.jobTimeoutSeconds` with a `JORLAN_SCHEDULER_JOB_TIMEOUT_SECONDS` env override.                                                                                                                                                                                                                                                                                                                                      | `server/src/main/scala/jorlan/configuration.scala:51`, `server/src/main/scala/jorlan/service/schedule/TriggerEngine.scala:64,196,510`                                                                                     | Raise `jorlan.scheduler.job-timeout-seconds` in `application.conf` to match (e.g. 12-15 min with headroom), or make `jobTimeoutSeconds` configurable per `SchedulerJob`/manifest.                                                                          |
| [x]    | PUCI-005   | Critical   | Correctness                 | `RoleSearch`/`AgentSearch` have no server-side exact name/email filter at all, so the importer's client-side `.find(_.name == ...)` lookups only scan a single page. `provisionRole`'s default `pageSize = 20` will silently miss existing roles past 20, causing a spurious `[FAIL]` on the `uq_role_name` unique constraint instead of the intended `[SKIP]`; `provisionAgent`'s `pageSize = 500` and `ZIOClientRepositories.userByEmail`'s `pageSize = 500` are the same latent bug at a larger threshold, not a fix. (confirmed by 2 reviewers: Performance Oracle, Functional Scala Reviewer) **RESOLVED** — added `name: Option[String]` exact-match filter to `AgentSearch`/`RoleSearch`, filtered server-side in `QuillRepositories.scala`, exposed via new `AgentsInput`/`RolesInput` GraphQL types (schema + client regenerated), and updated the importer's lookups accordingly. `userByEmail` left as a documented residual (separate `UserSearch`/`users` query surface, out of this ticket's scope).                                                                                                              | `useCaseImporter/src/main/scala/jorlan/shell/UseCaseImporterApp.scala:123` (`provisionRole`), `:179` (`provisionAgent`), `shellClient/src/main/scala/jorlan/shell/client/ZIOClientRepositories.scala:487` (`userByEmail`) | Add proper server-side exact-match lookup methods (e.g. `getByName` on Role/Agent repos, `getByEmail` on User repo) backed by an indexed `WHERE` clause, instead of widening page size.                                                                    |
| [x]    | PUCI-006   | Warning    | Observability               | 5 of the 11 GraphQL mutations the importer depends on write no event-log entry at all: `createRole`, `updateRole`, `upsertMcpServer`, `updateJobPipeline`, `upsertAgent`, `createSkillDraft`. Breaks Architecture Principle #3 (append-only audit trail). This is a recurring gap — a 3rd occurrence after Phase 8 and Sprint 1-3 reviews flagged the same pattern. **RESOLVED** — added `logEvent` calls to all 6 resolvers; added 4 new `EventType` cases (`RoleCreated`, `RoleUpdated`, `AgentDefinitionCreated`/`AgentDefinitionUpdated`, `McpServerUpserted`) and reused the existing unused `SkillDraftCreated`/`SchedulerJobUpdated` cases.                                                                                                                                                                                                                                                                                                                                             | `server/src/main/scala/jorlan/graphql/JorlanAPI.scala:1583` (`createRole`), `:1591` (`updateRole`), `:2016` (`upsertMcpServer`), `:2094` (`createSkillDraft`), `:2141` (`upsertAgent`), `:2174` (`updateJobPipeline`)     | Call `eventLogService.log(...)` with a typed event after each successful mutation, matching the pattern already used elsewhere in the same file. Treat as a standing item until the whole mutation surface is audited once, not per-phase.                 |
| [x]    | PUCI-007   | Warning    | Architecture                | The "floating methods on a repository facade" anti-pattern (previously identified and deliberately removed in an earlier repo refactor) has been reintroduced on the new `ZIOClientRepositories` in the `shellClient` module. **PARTIALLY RESOLVED** — grouped and labeled the floating methods by the sub-repo they conceptually extend, with a doc comment explaining why a full move isn't possible without a wider change: `web`'s `AsyncCallbackRepositories` also implements the same shared `Repositories[F[_]]` trait, so adding these methods to the model-level sub-repo interfaces would force `web`/`server` to implement them too. A proper cross-module `McpServerRepository`-style refactor is a separate, larger follow-up.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | `shellClient/src/main/scala/jorlan/shell/client/ZIOClientRepositories.scala`                                                                                                                                              | Move floating methods (e.g. `userByEmail`, ad hoc helpers) into the appropriate sub-repo trait under `Repositories[F[_]]`, matching the pattern established in the prior repo refactor (see `repo_refactor_floating_methods` project history).             |
| [x]    | PUCI-008   | Suggestion | Code Quality                | The 9 `provisionX` functions in `UseCaseImporterApp` (`provisionRole`, `provisionCapabilityGrants`, `provisionRoleAssignment`, `provisionAgent`, `provisionMemorySeeds`, `provisionMcpServers`, `provisionDeclarativeSkills`, `provisionJob`, `provisionTrigger`) share a near-identical "check-existing → skip-or-create → log" shape. **RESOLVED** — extracted the shared `.catchAll(err => logStep("FAIL", ...))` tail into a `reportFailure` helper, applied across all 9 functions. The search/create/update logic itself was left un-templatized per Code Simplicity Reviewer's own analysis (differs too much per entity to generalize without hurting readability).                                                                                                                                                                                                                                                                                                                                                                         | `useCaseImporter/src/main/scala/jorlan/shell/UseCaseImporterApp.scala:120-330`                                                                                                                                            | Extract a shared `provisionIfAbsent[A](lookup: ..., create: ..., describe: ...)` helper to remove the repeated skip/create/log boilerplate across all 9 functions.                                                                                         |
| [x]    | PUCI-009   | Warning    | Test Coverage               | The two production bugs fixed this session in `QuillRepositories.scala` — `Agent.upsert`'s duplicate-row-on-update bug, and the 16 search methods (User, Agent, AgentSession, Conversation, Message, Skill, SkillVersion, Connector, Memory, EventLog, Trigger, Artifact, Workspace, Role, Permission, Grant) that paginated before sorting — have no regression test guarding against reintroduction. **RESOLVED** — added an `Agent.upsert`-twice-no-duplicate test to `RepositorySpec.scala`, and a pageSize-smaller-than-row-count sort-order test to `SortingAndSortingSpec.scala` (as a representative case for the 16-method fix, not one test per method). Both verified failing against the old buggy code shape and passing against the fix.                                                                                                                                                                                                                                                                                                          | `server/src/main/scala/jorlan/db/repository/QuillRepositories.scala:369-386` (`Agent.upsert`), and each of the 16 `search*` methods (e.g. `:1251` `searchRoles`, `:347` `search` for Agent)                               | Add an integration test asserting `upsert` on an existing `Agent.id` does not increase row count; add one integration test per affected search method (or a shared parameterized test) asserting sort-then-paginate ordering with more rows than one page. |
| [ ]    | PUCI-010   | Suggestion | Documentation               | `doc/development_roadmap.md` has no entry at all for the Use-Case Manifest Importer work, despite it introducing two new sbt modules and fixing two production bugs.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `doc/development_roadmap.md`                                                                                                                                                                                              | Add a checklist entry (retroactively marked complete) for the importer, the `useCaseImporter`/`shellClient` module split, and the two Quill bug fixes, per the project's phase-tracking convention.                                                        |

---

## Grouped Sections

### Performance / Scheduled-Pipeline Timeouts

**`ai.timeout` bump does not address the scheduled-pipeline case** (PUCI-004)

The live incident this session ("Jorlan is unusable, timeouts") was root-caused to the `qwen3:4b` model's unavoidable
chain-of-thought reasoning overhead (confirmed via direct Ollama API testing), not to either of the two config changes
made in response (`ai.timeout` 5→10 min in `ai/src/main/scala/ai/util.scala`, and a new `keepAlive=1800s` Ollama
parameter in `OllamaModelGateway.scala`). Independently of that root cause, the Performance Oracle flagged that the
`ai.timeout` change specifically cannot help the primary motivating use case — a scheduled pipeline job — because
`TriggerEngine` (`TriggerEngine.scala:64`, default `Duration.ofSeconds(300)`, sourced from `configuration.scala:51`'s
`jobTimeoutSeconds: Int = 300`) wraps each agent turn in its own timeout that will fire at 5 minutes regardless of what
the LLM client's own timeout is set to. A pipeline step that legitimately needs 6-10 minutes (plausible with a reasoning
model) will be killed by `TriggerEngine` and burn a retry cycle before the 10-minute LLM timeout is ever reached. The
two knobs are not coupled today, and nothing surfaces that disconnect to an operator.

Recommended fix: either raise `jorlan.scheduler.job-timeout-seconds` in `application.conf` to a value with headroom
above the LLM timeout (e.g. 12-15 minutes), or thread `jobTimeoutSeconds` through as a per-`SchedulerJob`/manifest
override so pipeline authors can tune it per step without a server-wide change. The actual recommended long-term fix for
the timeout symptom itself is switching to a non-reasoning model (e.g. `llama3.2:3b`) for latency-sensitive scheduled
work — the config bumps alone do not solve the underlying overhead.

---

### Correctness / Data Lookup

**Unbounded client-side lookups over a single page** (PUCI-005) — CONFIRMED BY 2 REVIEWERS

Neither `RoleSearch` nor `AgentSearch` expose a server-side name filter. `UseCaseImporterApp.provisionRole` (line 123)
calls `repo.permission.searchRoles(RoleSearch())` — which defaults to `pageSize = 20` — and then does
`.find(_.name == m.role.name)` on the client side. Once a server has more than 20 roles, an existing role beyond the
first page is invisible to this lookup: the importer will attempt to re-create it, hit the `uq_role_name` unique
constraint, and report a spurious `[FAIL]` instead of the intended `[SKIP]`. `provisionAgent` (line 179) works around
the same underlying problem today by hardcoding `pageSize = 500`, which is just a larger version of the identical bug (
it breaks again past 500 agents), and `ZIOClientRepositories.userByEmail` (line 487) has the exact same `pageSize = 500`
pattern. All three call sites are symptomatic of the same root cause: there is no indexed, server-side exact-match
lookup for name/email on these repositories.

Recommended fix: add `getByName`/`getByEmail`-style methods to the relevant repositories, backed by an indexed `WHERE`
clause, and have the importer (and any other caller doing existence checks) call those instead of paginating and
filtering client-side.

---

### Observability / Audit Trail

**Missing event log entries on importer-dependent mutations** (PUCI-006)

`createRole` (`JorlanAPI.scala:1583`), `updateRole` (`:1591`), `upsertMcpServer` (`:2016`), `createSkillDraft` (
`:2094`), `upsertAgent` (`:2141`), and `updateJobPipeline` (`:2174`) — six of the mutations the importer calls directly
to provision a manifest — write no event-log entry. This violates Architecture Principle #3 (every significant action
writes to the append-only event log) and means none of these provisioning actions are visible in the audit trail: there
is no way to answer "who created this role, and when" purely from the log. This is the third time this exact pattern has
been flagged (Phase 8 review, Sprint 1-3 review, now this review), suggesting new mutations are being added faster than
the event-log convention is being enforced for them. Given the recurrence, this may warrant a structural fix (e.g. a
lint/test that fails when a new mutation handler is added without a corresponding event-log call) rather than another
one-off fix.

---

### Architecture / Layer Discipline

**Floating-methods anti-pattern reintroduced** (PUCI-007)

The project previously did a deliberate refactor to move floating methods off repository facades and into proper
sub-repo traits (see prior `repo_refactor_floating_methods` work). The new `ZIOClientRepositories` in the `shellClient`
module reintroduces the same shape that refactor was meant to eliminate — ad hoc methods like `userByEmail` sitting
directly on the facade rather than on a `User`-scoped sub-repository. Because `shellClient` is a brand-new module
extracted from `shell` this phase, this is a good moment to apply the established convention before more callers
accumulate against the facade shape.

---

### Code Quality

**Repeated provisioning shape across 9 functions** (PUCI-008)

`provisionRole`, `provisionCapabilityGrants`, `provisionRoleAssignment`, `provisionAgent`, `provisionMemorySeeds`,
`provisionMcpServers`, `provisionDeclarativeSkills`, `provisionJob`, and `provisionTrigger` in
`UseCaseImporterApp.scala` (lines 120-330) all follow the same "look up existing → skip-with-log or create-with-log"
shape. This is non-blocking today given the module is small and new, but as more manifest sections are added the
duplication will make each new section a copy-paste of the last. A shared `provisionIfAbsent` helper parameterized on
lookup/create/describe would collapse this to one implementation.

---

### Test Coverage

**No regression tests for the two Quill bugs fixed this session** (PUCI-009)

| Missing Test                                                      | Gap                                                                                                                                                                                                                                                 |
|-------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Agent.upsert` on an existing `id` does not duplicate the row     | Regression could silently reappear; the MariaDB `LAST_INSERT_ID()`/`ON DUPLICATE KEY UPDATE` interaction that caused it is subtle and easy to reintroduce via a future refactor of the branch in `QuillRepositories.scala:369-386`                  |
| Each of the 16 affected `search*` methods sorts before paginating | Without a test asserting correct ordering across a multi-page result set, a future edit could silently reorder the `drop`/`take`/`sortBy` chain back to the broken order — exactly what caused the Event Log page to show almost no recent activity |

Both fixes are currently only verified by manual testing this session. A shared integration-test helper that inserts N >
pageSize rows with distinct timestamps and asserts page 1 contains the newest rows would cover all 16 search methods
with one parameterized test.

---

### Documentation

**Roadmap has no entry for this work** (PUCI-010)

`doc/development_roadmap.md` tracks phase-by-phase completion via checkbox items, but this phase — which added two new
sbt modules (`useCaseImporter`, `shellClient`) and fixed two production bugs — has no corresponding entry. This breaks
the project convention of using the roadmap as the canonical phase-completion record.

---

## Cross-Cutting Patterns

**Unbounded/single-page client-side existence checks** were independently flagged by the Performance Oracle and the
Functional Scala Reviewer across three call sites (`provisionRole`, `provisionAgent`, `userByEmail` — PUCI-005). All
three "solve" the problem of no server-side exact-match filter by widening the client-side page size, which only raises
the threshold at which the bug reappears rather than fixing it. This is the same shape of issue as the previously-fixed
pagination-before-sort bug in `QuillRepositories.scala` (PUCI-009's second row): both are cases where "just fetch more
rows and filter locally" was chosen over a proper indexed, server-side query.

**Missing event-log entries on new mutations** was flagged by the SRS/SDD Conformance Reviewer and is now a confirmed
3rd occurrence of a pattern first noted in the Phase 8 review and repeated in the Sprint 1-3 review (PUCI-006). Given
the repeat rate, this points to a process gap rather than an isolated oversight — new GraphQL mutation handlers are
consistently shipped without the audit-log call that Architecture Principle #3 requires, and no automated check
currently catches this at review time.

**Anti-patterns previously fixed are being reintroduced in new modules.** The `shellClient` module — extracted fresh
this phase — already reintroduces the floating-methods-on-a-facade shape (PUCI-007) that an earlier repo refactor
deliberately eliminated from `ZIOClientRepositories`/`AsyncCallbackRepositories`. Combined with the recurring
missing-event-log pattern above, this suggests that conventions fixed in one refactor pass are not yet being carried
forward automatically into newly created modules — worth a brief mention in `CLAUDE.md` or a lint rule if this happens
again.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count  |
|------------|--------|
| Critical   | 3      |
| Warning    | 5      |
| Suggestion | 2      |
| **Total**  | **10** |

**Issues by area:**

| Area          | Count  |
|---------------|--------|
| Documentation | 3      |
| Performance   | 1      |
| Correctness   | 1      |
| Observability | 1      |
| Architecture  | 1      |
| Code Quality  | 1      |
| Test Coverage | 2      |
| **Total**     | **10** |

**Agent contribution:**

| Agent                          | Unique Findings | Cross-Confirmed |
|--------------------------------|-----------------|-----------------|
| Performance Oracle             | 2               | 1               |
| Functional Scala Reviewer      | 1               | 1               |
| ScalaDoc Auditor               | 2               | 0               |
| Test Coverage Tracker          | 2               | 0               |
| SRS/SDD Conformance Reviewer   | 1               | 0               |
| Pattern Recognition Specialist | 1               | 0               |
| Code Simplicity Reviewer       | 1               | 0               |
| UI Test Plan Writer            | 0               | 0               |

**Phase PUCI scope completion:**

| Item                                                                           | Status |
|--------------------------------------------------------------------------------|--------|
| `UseCaseManifest` domain model (role, agent, memory, MCP, skills, job/trigger) | ✅      |
| `useCaseImporter` CLI module (`UseCaseImporterApp`)                            | ✅      |
| `shellClient` module extracted from `shell`                                    | ✅      |
| `food_calendar.json` example manifest + supporting docs                        | ✅      |
| Pipeline template-reference validation (`validatePipelineReferences`)          | ✅      |
| Production bug fix: `Agent.upsert` duplicate-row-on-update                     | ✅      |
| Production bug fix: 16 search methods paginate-before-sort                     | ✅      |
| LLM/scheduler timeout alignment for pipeline steps                             | ❌      |
| Server-side exact-match name/email lookups (Role/Agent/User)                   | ❌      |
| Event-log coverage for importer-dependent mutations                            | ⚠️     |
| Regression tests for the two Quill bug fixes                                   | ❌      |
| `development_roadmap.md` entry for this work                                   | ❌      |

---

## What Was Done Well

**Fail-fast manifest validation before any network call**: `validatePipelineReferences` catches bad
`{{steps.NAME.output}}` references by walking the pipeline in order and checking against strictly-earlier steps'
`outputVar`s, before login even happens. This is exactly the right place to catch author error — at the boundary of the
tool, not deep inside a live scheduled execution — and is a pattern worth reusing for other manifest sections (e.g. MCP
server references, skill references) as they grow.

**Root-causing the timeout incident instead of stopping at the first plausible fix**: rather than accepting the
`ai.timeout`/`keepAlive` config bumps as "the fix," the session went further and confirmed via direct Ollama API testing
that the real cause was the `qwen3:4b` model's reasoning overhead — and identified the actually-correct fix (switching
to a non-reasoning model). Documenting that config bumps alone are insufficient (PUCI-004) instead of quietly closing
the incident is the right call.

**Fixing real production bugs discovered incidentally**: the `Agent.upsert` duplicate-row bug and the
pagination-before-sort bug across 16 search methods were both pre-existing production defects unrelated to the
importer's own code, found because the importer's provisioning flow exercised upsert-by-id and list/search paths that
hadn't been stressed this way before. Fixing them in place, with a clear comment explaining the MariaDB
`LAST_INSERT_ID()` quirk, is exactly the right response — the only gap is the missing regression coverage (PUCI-009).

**Clean module extraction**: pulling `shellClient` out of `shell` as its own module so that `useCaseImporter` can depend
on the GraphQL client surface without pulling in the full interactive shell is a sound structural choice and matches the
project's existing pattern of narrow, purpose-built sbt modules.
