# How to Implement a Use Case

This is an operational guide for a Claude session asked to "implement use case X" (e.g. "implement the
food calendar use case", "set up the inbox management agent"). It assumes no prior context from any other
conversation — everything needed is in this repo.

Background: `doc/use-cases/*.md` documents personal-assistant use cases. Each has an
`## Implementation in Jorlan` section specifying a system prompt, required capabilities, memory seeds, MCP
servers, and a cron schedule. Rather than clicking through the web UI by hand, these get provisioned via a
JSON manifest (schema: `doc/use-cases/manifest-schema.md`) and a standalone importer tool
(`UseCaseImporterApp`). Design rationale: `doc/mini-designs/use-case-manifest-importer.md`.

## Steps

1. **Read the target use-case doc.** Open `doc/use-cases/<name>.md` and read its `## Implementation in
   Jorlan` section in full — that's where the capabilities table, MCP setup instructions, step-by-step
   agent workflow, agent configuration (system prompt + capabilities + schedule), and memory seeding block
   already live. Also check `doc/use-cases/README.md`'s status table for `<name>.md` to see its
   ✅/🔧/🔨/⚠️ gap status before starting.

2. **Check for missing capabilities.** Cross-reference every capability the doc lists against
   `doc/use-cases/README.md`'s "Current Jorlan Skill Inventory" section. If a capability doesn't map to an
   existing built-in skill, external skill module, Google service, or connector — **stop and flag it**
   rather than inventing a fictitious capability string in the manifest. A missing capability means:
   - an MCP needs to be configured (see the README's "Must-add MCPs" table) — this can go in the
     manifest's `mcpServers` list, `enabled: false` with placeholder credentials, exactly like
     `food_calendar.json`'s `ourgroceries` entry, so the human fills in real credentials later; or
   - a declarative HTTP skill needs to be authored (see "Must-define declarative HTTP skills") — write
     the `DeclarativeSkillManifest` JSON and put it in the manifest's `declarativeSkills` list; or
   - a native skill needs real Scala code (see "Must-build new native skills") — this is a separate,
     larger piece of work, not something the importer can paper over. Report it to the user rather than
     working around it with an unsupported capability name.

3. **Translate the doc into a manifest.** Following `doc/use-cases/manifest-schema.md` field by field:
   - `role.capabilities` — one entry per capability in the doc's "Required capabilities" list.
   - `agent` — name it `<use-case-name>-agent`; put any persona framing ("You are an expert executive
     chef...") into `agent.invariants` if it's short, or leave it to the job's `systemPrompt` if it's the
     bulk of the prompt (most use cases put the whole prompt in the single pipeline step's
     `systemPrompt`, matching `food_calendar.json`'s example — an agent-level invariant is more useful for
     small recurring facts than for the full instruction text).
   - `memorySeeds` — one entry per fact in the doc's "Seeding memory" block, each with a distinct,
     namespaced key (`<use-case-name>.<short-topic>`, e.g. `food_calendar.dietary_rules`).
   - `job.steps` — usually a single step is enough (start there; only split into multiple `PipelineStep`s
     if the doc's step-by-step workflow clearly separates into distinct reasoning phases with different
     tool needs — see `doc/mini-designs/pipeline-jobs.md` for when that's worth it). `tools` should list
     the skill *namespaces* the step needs (e.g. `"calendar"`, not `"calendar.listEvents"`).
   - `job.trigger` — convert the doc's cron expression from 5-field to cron4s 6-field syntax; see the
     conversion table in `manifest-schema.md`. Double check by hand — this is the easiest field to get
     subtly wrong.
   - Save the result to `doc/use-cases/manifests/<name>.json`.

4. **Sanity-check the manifest decodes.** Before running the importer, verify the JSON is at least
   syntactically valid (`jq empty doc/use-cases/manifests/<name>.json`). If you want to verify it actually
   decodes into `UseCaseManifest` without running a live import, you can temporarily drop a one-off
   `@main` into `model/shared/src/main/scala/jorlan/` that reads the file and calls
   `.fromJson[jorlan.usecase.UseCaseManifest]`, run it via `sbtn "modelJVM/runMain <name>"`, then delete
   the temporary file — this was how `food_calendar.json` was validated during development and catches
   field-name or enum-value typos before touching a live server.

5. **Run the importer against a live server.**
   ```
   sbtn "shell/runMain jorlan.shell.UseCaseImporterApp doc/use-cases/manifests/<name>.json \
     --server-url http://localhost:8080 --email <user-email> --password <password>"
   ```
   (or omit the flags if `~/.jorlan/jorlan-shell.json` already has valid credentials for the target
   server). Read the `[OK]` / `[SKIP]` / `[FAIL]` line for every step. Any `[FAIL]` needs investigation
   before declaring the use case implemented — don't just report success because the process exited.

6. **Re-run once to confirm idempotency.** Every step should report `[SKIP]` the second time (see the
   idempotency table in `doc/mini-designs/use-case-manifest-importer.md` for why each entity type is safe
   to re-import). If a second run creates duplicates instead, that's a bug in the importer, not something
   to work around by hand-editing the database.

7. **Report back to the user.** Summarize what was provisioned (role name + capability count, agent name,
   number of memory seeds, MCP servers registered and their `enabled` state, job name, trigger schedule in
   human-readable form). Explicitly call out anything still needing manual follow-up:
   - MCP servers registered `enabled: false` with placeholder credentials — the user needs to supply real
     ones and flip `enabled: true` (either by hand in the web UI, or by editing the manifest and
     re-running the importer).
   - Declarative skills left in `Draft` state — the user needs to review and approve them in the web UI
     before they're usable.
   - Any capability gaps flagged in step 2 that still need a real MCP/skill built.

## What NOT to do

- Don't hand-edit the GraphQL schema or `JorlanClient.scala` to add a shortcut for this — if the importer
  is missing a capability (e.g. a new repository method needs wiring), fix it the same way
  `agent.upsert`/`user.userByEmail` were wired: find the existing server-side mutation, wire the client
  stub to call it, following the pattern of neighboring methods in `ZIOClientRepositories.scala`.
- Don't invent capability strings that don't correspond to a real skill's tool — the agent will simply
  fail to find the tool at runtime, and it's much harder to debug than catching the gap up front in step 2.
- Don't skip step 6 (idempotency re-run) — it's cheap and it's the whole point of building this as a
  repeatable importer rather than a one-off script.
- Don't commit or push any changes without the user's explicit go-ahead, per this repo's standing git
  discipline rule (see `CLAUDE.md`).
