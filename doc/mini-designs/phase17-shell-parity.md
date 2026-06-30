# Phase 17: Shell Client Feature Parity

## Context

The web client has been the primary focus of recent sprints (Phases 15–16). During that time the shell client fell behind: several entire feature areas added to the web (MCP management, full scheduler CRUD, role management, custom-skill lifecycle, event-log tail) were never wired into the shell. This phase closes those gaps so the shell remains a first-class interface for operators and power users who prefer it.

---

## Feature Parity Table

| Feature Area | Web Client | Shell Client | Gap |
|---|---|---|---|
| **Chat / Agent messaging** | ChatPage — streaming, queue, multiline | `/new`, bare text messages, token streaming | ✅ Parity |
| **Sessions** | SessionsPage — list, create (model picker), terminate, paginate | `/agents list`, `/new [model]`, `/agents stop <id>` | ✅ Parity |
| **Approvals** | ApprovalsPage — list, approve, deny; real-time WebSocket push | `/approvals list/approve/deny` | ✅ Parity (no live push, acceptable) |
| **Memory** | MemoryPage — list, search, remember, share, privatize, forget | `/memory list/search/remember/share/privatize/forget/checkpoint` | ✅ Parity |
| **Memory Policy** | ❌ No UI | `/memory policy` — full CRUD | Shell leads (no action needed) |
| **Personality / Settings** | SettingsPage — formality, languages, expertise, prompt | `/personality`, `/personality set <field> <value>` | ✅ Parity |
| **OAuth** | OAuthManagementPage — connect, disconnect | `/oauth list/status/connect/revoke` | ✅ Parity |
| **Users** | UsersPage — create, edit, deactivate, **reactivate**, capabilities, roles, identities | `/users` — full suite EXCEPT reactivate | ⚠️ Missing `/users reactivate` |
| **Roles** | RolesPage — create, **edit**, **delete**, capabilities grant/revoke | `/roles list`, `/roles create` | ❌ Missing edit, delete, capabilities |
| **Capabilities** | Inline dialogs on Users/Roles pages | `/capabilities`, `/users grant`, `/users revoke-grant` | ✅ Parity (role grants missing; see Roles row) |
| **Scheduler** | SchedulerPage — full CRUD: create, edit, delete, pause, resume, cancel, run-now, triggers sub-table | `/scheduler list`, `/scheduler result <id>` | ❌ Missing create/update/delete/pause/resume/cancel/run-now + trigger management |
| **MCP Servers** | McpServersPage — list, add, edit, delete, reload | ❌ No commands at all | ❌ Completely missing |
| **Skills — built-in** | SkillsPage — list, enable/disable toggle, docs dialog, validate, config UI | `/skills list/enable/disable/config get/config set` | ⚠️ Missing docs and validate |
| **Skills — custom** | CustomSkillsPage — 5-step wizard, lifecycle advance, approve, reject; full list + pending list | ❌ No commands at all | ❌ Completely missing |
| **Event Log** | EventLogPage — live WebSocket tail, expandable raw JSON | ❌ No commands at all | ❌ Completely missing |
| **Dashboard** | Charts + KPI cards + per-skill widgets | ❌ No equivalent | ⚠️ Charts infeasible; text KPIs worth adding |
| **Email/Calendar** | Dashboard widgets only (no dedicated page) | `/email list/read/search`, `/calendar today/list` | Shell leads (no action needed) |
| **Contacts** | ❌ No page | `/contacts find <name>` | Shell leads (no action needed) |
| **Server status** | ❌ No page | `/status` | Shell leads (no action needed) |
| **Trace / log level** | ❌ No UI | `/trace [level]` | Shell-specific; no action needed |

---

## Prioritised Implementation Plan

### Priority 1 — High-value, straightforward additions

#### 1a. MCP Server Management (`/mcp`)

New commands mirroring McpServersPage:

```
/mcp list
/mcp add <name> <transport> <command|url> [args] [--env KEY=VAL ...] [--keywords k1,k2]
/mcp edit <name> [--command ...] [--url ...] [--args ...] [--env ...] [--enable|--disable]
/mcp delete <name>
/mcp reload
/mcp enable <name>
/mcp disable <name>
```

Transport values: `Stdio | Http | HttpSse`. For Stdio, `command` + optional positional args list. For Http/HttpSse, `url`. Env vars passed as repeated `--env KEY=VAL` flags. Display list as a table (name, transport, command/URL, enabled).

**GraphQL already available**: `mcpServers`, `upsertMcpServer`, `deleteMcpServer`, `reloadMcpServers` — all already in `JorlanClient`.

#### 1b. Full Scheduler CRUD (`/scheduler`)

Extend the existing `/scheduler` sub-command with the missing operations:

```
/scheduler list                         (exists)
/scheduler result <id>                  (exists)
/scheduler create <name>                (interactive: prompts for prompt, retries, backoff, policy, optional trigger)
/scheduler update <id> <field> <value>  (fields: name, prompt, maxRetries, backoffSeconds, backoffPolicy, missedRunPolicy)
/scheduler delete <id>
/scheduler pause <id>
/scheduler resume <id>
/scheduler cancel <id>
/scheduler run <id>                     (triggerNow)
/scheduler triggers <id>               (list triggers for a job)
/scheduler trigger add <id> <type> <expr>
/scheduler trigger delete <triggerId>
```

BackoffPolicy values: `Fixed | Exponential`. MissedRunPolicy: `Skip | RunOnce | RunAllMissed`. TriggerType: `Cron | Interval | OneShot | Event`.

**GraphQL already available**: `createJob`, `updateJob`, `deleteJob`, `pauseJob`, `resumeJob`, `cancelJob`, `triggerNow`, `addTrigger`, `deleteTrigger`, `triggers(jobId)` — all in `JorlanClient`.

#### 1c. Full Role Management (`/roles`)

Extend existing `/roles` sub-command:

```
/roles list                             (exists)
/roles create <name> [description]      (exists)
/roles update <id> name <value>
/roles update <id> description <value>
/roles delete <id>
/roles capabilities <id>               (list grants for a role)
/roles grant <id> <capability> <mode>  (grant to role; mode: Manual|AutoApprove|Deny)
/roles revoke-grant <grantId>
```

**GraphQL already available**: `updateRole`, `deleteRole`, `roleCapabilityGrants`, `grantCapabilityToRole`, `revokeCapabilityGrant` — all in `JorlanClient`.

#### 1d. Users — Reactivate

```
/users reactivate <id>
```

Calls `updateUser(id, active = true)` — already in `JorlanClient`.

### Priority 2 — Moderate effort

#### 2a. Skill Docs and Validate

```
/skills docs <name>        (print the skill's doc field as plain text, or "no docs available")
/skills validate <name>    (call skillValidate, show pass/fail + errors)
```

**GraphQL already available**: `skillValidate` in `JorlanClient`. The `skills` query already returns a `doc` field — confirm this is included in the selection builder; add if not.

#### 2b. Custom Skills Management (`/skills custom` or `/skills` sub-commands)

Rather than a 5-step interactive wizard (complex in a terminal), accept a JSON manifest file:

```
/skills list-custom                        (allCustomSkills)
/skills list-pending                       (pendingSkillVersions)
/skills versions <skillId>                 (skillVersions)
/skills create <manifest.json>             (createSkillDraft — reads file path)
/skills advance <versionId>                (advanceSkillLifecycle)
/skills approve <versionId>                (approveSkillVersion)
/skills reject <versionId> <reason>        (rejectSkillVersion)
```

For creation, users write the manifest JSON externally and pass the file path to the shell. This is idiomatic for terminal workflows and avoids a brittle multi-step wizard.

**GraphQL already available**: all in `JorlanClient`.

#### 2c. Event Log Tail (`/events`)

```
/events tail [--count <n>]    (live WebSocket subscription, Ctrl-C to stop)
/events list [--count <n>]    (one-shot: print the last N events from eventLogTail, then exit)
```

`/events tail` opens the `eventLogTail` subscription via `SubscriptionClient` (same pattern as `agentResponseStream`), prints each event as a table row (timestamp, type, actorId, sessionId), and streams until the user presses Ctrl-C (or `/quit`). The subscription fiber is tracked in `ShellState` like existing fibers.

**GraphQL already available**: `eventLogTail` subscription in `JorlanClient`.

### Priority 3 — Nice-to-have

#### 3a. Dashboard Stats (`/dashboard` or `/stats`)

Print text-based summary of `dashboardStats` — active session count, events today, skill invocations, job success rate. Skip the charts entirely — they are not feasible in a terminal.

```
/dashboard
```

No subscription needed; single query.

---

## Architecture Notes

All shell commands follow the same pattern:

1. **ShellCommand.scala** — add new `case class` or `case object` variants for each new command group.
2. **CommandHandler.scala** (or per-group handler file) — match on the new command, call `GraphQLClient.query/mutation` or `SubscriptionClient.subscribe`, format output as a table using the existing `AnsiTable` / ANSI helpers.
3. **HelpCommand** — add new entries to the help list.

For `/events tail`, a new fiber must be started and tracked in `ShellState` (alongside the existing session fiber and tool-event fiber), with clean teardown on `/quit` or the next `/events` invocation.

For `/mcp add`/`edit`, the argument parsing must handle the variadic `--env` list. The existing parser in `ShellCommand.scala` uses a recursive descent approach; extend it to collect repeated flags into a `List[(String, String)]`.

No changes to the GraphQL schema or `JorlanAPI.scala` are required — every needed query/mutation is already present and available in `JorlanClient.scala`.

---

## Files to Modify

| File | Change |
|---|---|
| `shell/.../commands/ShellCommand.scala` | Add `McpCommand`, extend `SchedulerCommand`, extend `RolesCommand`, `EventsCommand`, optional `DashboardCommand` |
| `shell/.../commands/CommandHandler.scala` | Dispatch new commands; or split into `McpCommandHandler`, etc. |
| `shell/.../ShellState.scala` | Add optional event-log fiber ref |
| `shell/.../JorlanShell.scala` | Wire new state; teardown event-log fiber on exit |
| `shell/.../help/HelpCommand.scala` | Add new entries |
| `gql-client/.../JorlanClient.scala` | **Read-only verification** that `doc` field is in the `skills` selection; add if missing. No schema changes. |

---

## What to Skip / Exclude

| Feature | Reason |
|---|---|
| Dashboard charts | Graphical; not feasible in a terminal. Text KPIs in `/dashboard` are sufficient. |
| Custom skill config JS component | Browser-only; skill config JSON is already editable via `/skills config set`. |
| Skill docs rendered as Markdown | Plain-text dump is sufficient; no Markdown renderer needed in the shell. |
| Real-time approval push notifications | The shell's polling via `/approvals list` is sufficient; adding a background notification fiber would be noisy for a CLI. |
| Per-skill dashboard widgets (market/weather/etc.) | These are already accessible via the email/calendar/etc. skill commands. |

---

## Verification

1. **Compile**: `sbtn --error compile test:compile`
2. **Unit tests** (none to add — shell has no unit tests; integration tests cover the API layer): `sbtn --error test`
3. **Manual smoke tests** (against a running server):
   - `/mcp list` → table of servers; `/mcp add test Stdio echo --env FOO=bar` → appears in list; `/mcp reload`; `/mcp delete test`
   - `/scheduler create "Test Job"` → prompts; `/scheduler list` shows it; `/scheduler run <id>`; `/scheduler result <id>`; `/scheduler delete <id>`
   - `/roles create "TestRole" "A test"` → listed; `/roles update <id> description "Updated"`; `/roles grant <id> admin.settings AutoApprove`; `/roles capabilities <id>`; `/roles delete <id>`
   - `/users reactivate <id>` → user shows as active again
   - `/skills docs weather`; `/skills validate weather`
   - `/skills list-custom`; `/skills create /path/to/manifest.json`; `/skills list-pending`; `/skills approve <id>`
   - `/events tail` → live stream of events; Ctrl-C returns to prompt
   - `/dashboard` → KPI summary text
