# Mini-Design: Use-Case Manifest + Importer Tool

## Motivation

`doc/use-cases/*.md` documents 16 personal-assistant use cases (food calendar, inbox management, project
manager, etc.), each with a fully-specified `## Implementation in Jorlan` section: a system prompt, the
exact Jorlan capabilities/skills required, memory facts to seed, sometimes MCP servers to register, and a
cron schedule. Provisioning one of these by hand means manually clicking through the web UI to create a
role, grant a dozen capabilities, create an agent, seed several memory records, and build a multi-field
pipeline job with a trigger — tedious and error-prone, and not something that scales to 16+ use cases.

The fix: a JSON schema (`UseCaseManifest`) that captures everything a use-case doc's `## Implementation in
Jorlan` section describes, plus a small standalone tool (`UseCaseImporterApp`) that reads a manifest file
and provisions it into a running Jorlan server via the existing GraphQL API. "Implementing a use case" now
means: translate the doc into a manifest JSON file, then run the importer against it. See
`doc/use-cases/HOW-TO-IMPLEMENT-A-USE-CASE.md` for the operational walkthrough.

---

## Decisions

- **Importer shape: standalone client-side script, not a server-side mutation.** A headless ZIOApp
  (`shell` module, modeled on the existing `EndToEndTestApp`) that calls the GraphQL API step by step is
  small, incremental, and testable against a running server today. A single `importUseCase(manifestJson)`
  server-side mutation would be more atomic and avoid N+1 round-trips, but is a bigger schema change —
  worth revisiting only if the round-trip approach proves too slow/fragile in practice.
- **Memory seed scoping: `MemoryScope.User`, not per-agent.** Seeds like "Sarah can't eat cruciferous
  vegetables" are household facts, not agent-private state — they should be visible to any agent the user
  talks to. This matches what `storeMemory` already supports; no API changes needed.
- **Declarative skill drafts are never auto-approved.** The importer always leaves a drafted declarative
  skill (musicbrainz, dictionary, etc.) in `Draft` state; a human reviews and approves it in the web UI.
- **The importer is additive/updating only.** It never deletes a capability grant or memory record that
  was removed from a manifest on a later run. Simpler, and reconciliation-by-deletion is rarely what you
  want for hand-curated household facts anyway.

---

## Two small pre-existing gaps, fixed as part of this work

Both were additive and low-risk; everything else needed already had a working GraphQL path.

1. **`CreateJobInput` had no `agentId` field.** The resolver hardcoded `JobManager.createJob(None, ...)`
   even though `JobManager.createJob` already accepted `agentId: Option[AgentId]`. Fixed in
   `server/src/main/scala/jorlan/graphql/JorlanAPI.scala`, regenerated via `scripts/capture-schema.sh` +
   `scripts/gen-client.sh`.
2. **`ZIOClientRepositoriesLive.agent.upsert` and `.search` were stubbed** (`ZIO.fail("not implemented")` /
   `ZIO.succeed(List.empty)`) even though the server-side `upsertAgent` mutation and `agents` query already
   existed and were already generated in `JorlanClient.scala`. Pure wiring, no schema change. Also wired
   `user.userByEmail` the same way, needed to resolve a manifest's `assignRoleToUser` email to a `UserId`
   (the `users` query has no server-side email filter, so this fetches all users and filters client-side —
   acceptable at the scale of a single household's user list).

A third small addition: `createJobWithPipeline` — a new method on `ZIOClientRepositories` alongside the
existing `createJob` (which only supports a 1-step prompt-only pipeline, used by the `/scheduler create`
shell command). The importer needs full multi-step pipelines with real per-step system prompts and an
`agentId`, so it gets its own method rather than overloading the shell command's simpler one.

---

## Confirmed idempotency semantics

Read directly from `QuillRepositories.scala` and the migration SQL — this is what makes re-running the
importer against an already-provisioned use case safe:

| Entity | Behavior on upsert | Importer's approach |
|---|---|---|
| `Agent` | Always inserts a new row if `id` empty; no unique name constraint | search by name first; reuse id if found |
| `Role` | Plain insert if `id` empty; `role.name` has `UNIQUE KEY uq_role_name` — re-inserting a colliding name throws | search by name first; only insert if absent |
| `CapabilityGrant` | `INSERT ... ON DUPLICATE KEY UPDATE` on `uq_capability_grant(capability, granteeId, granteeType)` (V031) | genuinely idempotent — call unconditionally every run |
| `MemoryRecord` (`storeMemory`) | No natural key; caller-scoped, no `importance`/`agentId` param exposed | search by key first; skip if a record with that key already exists |
| `SchedulerJob` | Insert if `id` empty; update path only touches runtime fields, never name/pipeline/agentId | search by name first; create if absent, else `updateJobPipeline` + `updateJob` |
| `SchedulerTrigger` | Always inserts, no natural key | search for a matching `(triggerType, expression)` on the job first; skip if found |
| MCP server (`upsertMcpServer`) | Read-modify-write on `server_settings` JSON blob keyed by `name` | already idempotent — call unconditionally |
| Declarative skill draft (`createSkillDraft`) | Always creates a new `SkillVersion` in `Draft` state | check existing versions by name+version first; skip if unchanged |

---

## Manifest schema

`model/shared/src/main/scala/jorlan/usecase/UseCaseManifest.scala`. Field-by-field documentation lives in
`doc/use-cases/manifest-schema.md`; the shape mirrors what a use-case doc's `## Implementation in Jorlan`
section already specifies:

```scala
case class UseCaseManifest(
  useCaseName:       String,
  sourceDoc:         Option[String] = None,
  role:              ManifestRoleSpec,           // name + capabilities to grant
  assignRoleToUser:  Option[String] = None,       // email of the user who should hold this role
  agent:             ManifestAgentSpec,           // name/description/model/trustLevel/prioritizedSkills/invariants
  memorySeeds:       List[ManifestMemorySeed] = List.empty,
  mcpServers:        List[ManifestMcpServer] = List.empty,
  declarativeSkills: List[ManifestDeclarativeSkill] = List.empty,  // raw JSON passthrough
  job:               Option[ManifestJob] = None,  // full multi-step Pipeline + optional trigger
)
```

`ManifestJob.steps` mirrors `PipelineStep` exactly (`name`, `systemPrompt`, `userPrompt`, `tools`, `mode`,
`outputVar`, `retryOnFail`) — the manifest's job section is essentially a `Pipeline` literal plus the
scheduler metadata (`maxRetries`, `backoffSeconds`, `backoffPolicy`, `missedRunPolicy`, `trigger`).

`defaultModel` is a plain `String` in the manifest (not `ModelId`) for human-editability; the importer
wraps it with `ModelId(_)` when constructing the `Agent`.

---

## Importer tool

`useCaseImporter/src/main/scala/jorlan/shell/UseCaseImporterApp.scala`, modeled on `EndToEndTestApp.scala`: same
environment stack minus `SubscriptionClient`/`LiveSession`/`ShellState` (no chat session needed —
`ShellConfig & AuthClient & GraphQLClient & ZIOClientRepositories`).

Run with:
```
sbt "useCaseImporter/runMain jorlan.shell.UseCaseImporterApp doc/use-cases/manifests/food_calendar.json \
  --server-url http://localhost:8080 --email roberto@leibman.net --password ..."
```
(or configure `~/.jorlan/jorlan-shell.json` and omit the flags — `ShellConfig.applyArgs` folds in CLI
overrides on top of the file-based config, same as the interactive shell).

Provisioning order, each step logging `[OK]` / `[SKIP]` / `[FAIL]` and continuing past non-fatal failures
so a partial import is visible in one run:

1. **Role** — create or reuse by name.
2. **Capability grants** — one `upsertCapabilityGrant` per capability (always safe).
3. **Assign role to user** — if `assignRoleToUser` is set.
4. **Agent** — create or update by name.
5. **Memory seeds** — one per seed, skipped if the key already exists.
6. **MCP servers** — one `upsertMcpServer` per server (always safe).
7. **Declarative skills** — one `createSkillDraft` per skill, skipped if already drafted, always left as
   `Draft`.
8. **Job** — create (with `agentId`) or update-in-place by name.
9. **Trigger** — add if the job doesn't already have one matching `(triggerType, expression)`.

---

## Worked example

`doc/use-cases/manifests/food_calendar.json` translates `doc/use-cases/food_calendar.md`'s
`## Implementation in Jorlan` section end to end: role + 11 capabilities, agent persona, 4 memory seeds
(dietary rules, preferred sites, shopping schedule, weekend cooking), the `ourgroceries` MCP server
(registered `enabled: false` with placeholder credentials, since the MCP wrapper doesn't exist yet —
matches the doc's documented blocker), and a 6-step weekly pipeline job wired to a Wednesday-09:00 cron
trigger.

The job is deliberately **not** a single mega-step. An early draft put the whole multi-constraint chef
prompt (dietary rules, schedule rules, seasonal hints, calendar/weather/memory/search/recipe-fetch/
calendar-write/shopping-list/reminders/telegram, all in one `ReactLoop`) into one step — exactly the
"context saturation" and "multi-objective drift" failure modes `doc/mini-designs/pipeline-jobs.md` warns
about, and it choked a cheap/local model in practice. The final version splits it into six
single-responsibility steps (`gather-context` → `plan-meals` (`SingleCall`, pure reasoning) →
`research-recipes` → `update-calendar` → `shopping-list` → `notify-and-schedule`), chaining each step's
output into the next via `{{steps.NAME.output}}`, with the recurring household rules
(`dietary_rules`/`schedule_rules`/`seasonal_hints`/`preferred_sites`) hoisted into pipeline-level
`invariants` so they're injected into every step without needing to repeat the full prose each time.

**Cron gotcha**: use-case docs specify 5-field standard cron (`0 9 * * 3`). The server uses cron4s 6-field
syntax with `?` for the unused day-of-month/day-of-week field (per `JorlanAPI.scala`'s own error-message
example, `'0 0 9 ? * 1-5'` = 09:00 weekdays). Wednesday → `0 0 9 ? * 3`. Every future manifest needs this
same translation.

---

## Verification performed

- `sbtn --error compile test:compile` — model/server/gql-client/shell all compile together after the
  schema regeneration and the new files.
- `sbtn --error test` — full suite passes.
- Decoded `food_calendar.json` through `UseCaseManifest`'s zio-json codec directly (temporary throwaway
  main, removed after confirming a clean parse) before wiring it into the importer, to catch field-name or
  enum-value mismatches early.
- Manual end-to-end run against a live local server, followed by a second run to confirm every step
  reports `[SKIP]` (idempotent, no duplicates).
