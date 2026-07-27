# Use-Case Manifest Schema

A machine-readable translation of a use-case doc's `## Implementation in Jorlan` section, consumed by
`UseCaseImporterApp` (`useCaseImporter/src/main/scala/jorlan/shell/UseCaseImporterApp.scala`) to provision a role,
agent, memory seeds, MCP servers, declarative skills, and a scheduled pipeline job into a running Jorlan
server.

Source of truth: `model/shared/src/main/scala/jorlan/usecase/UseCaseManifest.scala`. Worked example:
`doc/use-cases/manifests/food_calendar.json`. See `doc/mini-designs/use-case-manifest-importer.md` for the
full design rationale, and `HOW-TO-IMPLEMENT-A-USE-CASE.md` for the step-by-step process of turning a
use-case doc into a manifest.

## Top-level fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `useCaseName` | string | yes | Short identifier, matches the use-case doc's filename stem (e.g. `food_calendar`) |
| `sourceDoc` | string | no | Path to the source `.md` doc, for traceability |
| `role` | `ManifestRoleSpec` | yes | The role that will hold this use case's capability grants |
| `assignRoleToUser` | string (email) | no | If set, the importer assigns `role` to this user |
| `agent` | `ManifestAgentSpec` | yes | The agent persona the scheduled job runs as |
| `memorySeeds` | `ManifestMemorySeed[]` | no | Facts to seed once, idempotently, by key |
| `mcpServers` | `ManifestMcpServer[]` | no | MCP servers this use case depends on |
| `declarativeSkills` | `ManifestDeclarativeSkill[]` | no | Raw declarative-skill manifest JSON to draft |
| `job` | `ManifestJob` | no | The scheduled pipeline job (most use cases have exactly one) |

## `ManifestRoleSpec`

| Field | Type | Default | Meaning |
|---|---|---|---|
| `name` | string | — | Role name; must be unique across the server |
| `description` | string? | none | |
| `capabilities` | `ManifestCapabilitySpec[]` | `[]` | See below |

`ManifestCapabilitySpec`: `capability` (dot-namespaced string, e.g. `calendar.listEvents`) + `approvalMode`
(`Denied | PerInvocation | Once | Session | Timed | Persistent`, default `Persistent`).

## `ManifestAgentSpec`

| Field | Type | Default | Meaning |
|---|---|---|---|
| `name` | string | — | Agent name; must be unique |
| `description` | string? | none | |
| `defaultModel` | string? | none | Plain model identifier (e.g. `llama3`); wrapped as `ModelId` on import |
| `trustLevel` | int | 0 | 0 = untrusted; higher unlocks capabilities without approval |
| `prioritizedSkills` | string[] | `[]` | Skill namespaces to prioritize in tool selection |
| `invariants` | map<string,string> | `{}` | Key-value facts injected into every pipeline step via `{{invariants.KEY}}` |

## `ManifestMemorySeed`

| Field | Type | Default | Meaning |
|---|---|---|---|
| `key` | string | — | Unique key; the importer skips seeding if a record with this key already exists |
| `text` | string | — | The memory content |
| `scope` | `User \| Shared \| Workspace \| Private` | `User` | Memory scope — `User` means visible to any agent this user talks to |

## `ManifestMcpServer`

Mirrors `UpsertMcpServerInput` exactly: `name`, `transport` (must exactly match the `McpTransport` enum case
name — `Stdio` / `Http` / `HttpSse`, capitalized; the server parses it via `.valueOf`, so `"stdio"` fails
silently — see the "Failure modes" section below), `command`, `args`,
`env` (list of `{key, value}`), `url`, `enabled` (default `true`), `keywords`.

## `ManifestDeclarativeSkill`

Just `{ "manifestJson": "<raw DeclarativeSkillManifest JSON as a string>" }`. See
`server/src/main/scala/jorlan/service/skills/declarative/DeclarativeSkillManifest.scala` for that format.
Always lands in `Draft` state — never auto-approved by the importer.

## `ManifestJob`

| Field | Type | Default | Meaning |
|---|---|---|---|
| `name` | string | — | Job name; must be unique per user (not per agent — the DB enforces `UNIQUE(userId, name)`) |
| `steps` | `ManifestPipelineStep[]` | — | See below; at least one step |
| `invariants` | map<string,string> | `{}` | Pipeline-level invariants (override agent-level ones with the same key) |
| `personality` | string? | none | Named personality; `None` = accuracy mode |
| `maxRetries` | int | 0 | |
| `backoffSeconds` | int | 60 | |
| `backoffPolicy` | `Fixed \| Exponential` | `Fixed` | |
| `missedRunPolicy` | `Skip \| RunOnce \| RunAllMissed` | `Skip` | |
| `trigger` | `ManifestTrigger?` | none | See below |

`ManifestPipelineStep` mirrors `PipelineStep` exactly: `name`, `systemPrompt`, `userPrompt`,
`tools` (namespace allowlist, e.g. `["calendar", "weather"]`), `mode` (`ReactLoop | SingleCall`,
default `ReactLoop`), `outputVar`, `retryOnFail` (default 0).

**`tools` is enforced at execution time**: a ReactLoop step's model sees *only* the tools whose names
match one of the declared namespace prefixes (e.g. `"calendar"` exposes `calendar.listEvents`,
`calendar.createEvent`, …). Keep the list minimal — a small local model given three relevant tools is
dramatically faster and more accurate than one given the agent's full tool catalog. An empty list means
no tools (fine for pure-reasoning steps; consider `SingleCall` mode for those instead).

`ManifestTrigger`: `triggerType` (`Cron | Interval | OneShot | Event`) + `expression`.

### The cron4s 6-field gotcha

Use-case docs write cron expressions in standard 5-field form (`0 9 * * 3` = 09:00 every Wednesday). The
server parses triggers with **cron4s**, which uses 6 fields with `?` for whichever of day-of-month /
day-of-week is unused. Two traps:

1. **Day-of-week is `0=Monday .. 6=Sunday`** — NOT the standard cron `0=Sunday` or Quartz `1=Sunday`.
   `7` is invalid and makes the whole expression fail to parse (the trigger is silently never created).
2. **Expressions are evaluated in the server's local timezone** — write the time you mean locally, no
   UTC conversion.

Translate every 5-field expression by inserting a leading `0` (seconds), using `?` for day-of-month when
specifying day-of-week, and shifting day-of-week down by one from standard-cron's Monday=1:

| 5-field (standard) | 6-field (cron4s manifest) | Meaning |
|---|---|---|
| `0 9 * * 3` | `0 0 9 ? * 2` | 09:00 every Wednesday |
| `0 9 * * 1-5` | `0 0 9 ? * 0-4` | 09:00 every weekday (Mon-Fri) |
| `0 9 * * 0` | `0 0 9 ? * 6` | 09:00 every Sunday |
| `0 9 1 * *` | `0 0 9 1 * ?` | 09:00 on the 1st of every month |

## Idempotency

All fields are safe to re-import. See the table in `doc/mini-designs/use-case-manifest-importer.md` for
exactly how each entity is matched (by name/key) before deciding create vs. skip vs. update. Re-running the
importer against an unchanged manifest reports `[SKIP]` for every step.

## Failure modes and prerequisites

**GraphQL mutation failures show up as `[FAIL] ... — <mutation> returned nothing`, with no further
detail.** Every mutation the importer calls returns a nullable GraphQL field (`Agent`, not `Agent!`), and
the caliban-client `.body` decode only inspects `data`, not the response's `errors[]` array — so a
resolver-level failure (bad capability, invalid enum string, etc.) decodes as a clean `None`/`null`
rather than surfacing the actual server-side error message. When you see this, don't assume the manifest
is malformed — check the two most likely causes first:

1. **The operator account is missing an admin capability the mutation requires.** `upsertAgent` requires
   `admin.agent.manage`; `upsertMcpServer`/`reloadMcpServers`/`deleteMcpServer` require `admin.settings`;
   `createSkillDraft` requires `skill.create`. These are **not** use-case-specific capabilities and are
   deliberately **not** part of the manifest's `role.capabilities` — they gate admin-level operations
   (creating a reusable agent persona, registering an MCP server) and must already be granted directly to
   the user account running the importer, separately from any use-case role. Check what's actually granted
   with `SELECT capability FROM capabilityGrant WHERE granteeType='User' AND granteeId=(SELECT id FROM
   user WHERE email='...')`, and grant anything missing via the web UI's user capability editor (or
   `/users grant` in the shell) before re-running.
2. **An enum-shaped string field doesn't exactly match the server's enum case name.** `ManifestMcpServer.
   transport` is the sharpest edge here — the resolver parses it via `McpTransport.valueOf(input.transport)`,
   which requires an exact, case-sensitive match (`Stdio`, `Http`, `HttpSse`), not the lowercase forms you
   might expect from typical JSON conventions.

If a `[FAIL]` doesn't match either of these, check the server's own logs for the actual exception (the
importer's own log won't have it, for the reason above).
