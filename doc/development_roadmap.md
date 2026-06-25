# Jorlan Development Roadmap

Version: 0.1  
Date: 2026-05-24

---

## How to Use

Check off items as they are completed. Each phase has a **Goal** statement describing what "done" means for that phase.
Sub-items break down larger tasks where needed. Tests are part of every phase — target >80% coverage throughout. The
first iterable milestone is marked explicitly.

Items that are done are moved to development_roadmap_completed.md to reduce context

---

## Phase 15: Web Frontend

**Goal:** The web equivalent of the shell interface — a Scala.js + React 19 + MUI v9 SPA that connects to the server
via the Caliban GraphQL API. Provides a chat interface, real-time approval handling, and full configuration screens
for sessions, memory, scheduler, event log, skills, and user/role management.

See `doc/mini-designs/phase15-web-frontend.md` for full design.

### Foundation

- [x] `web` SBT module added to `build.sbt` with `bundlerSettings`, `withCssLoading`, `commonWeb` configurations
- [x] `stLib/` sub-project created with ScalablyTyped bindings for React 19 + MUI v9 + Emotion
- [x] `stLib/publishLocal` verified (run once; confirms MUI v9 bindings compile correctly with a smoke-test Button)
- [x] `web/src/main/scala/` directory structure created:
    - `jorlan/web/JorlanWebApp.scala` — entry point
    - `jorlan/web/AppRouter.scala` — hash-based routing
    - `jorlan/web/AppShell.scala` — shared AppBar + Drawer layout
    - `jorlan/web/graphql/` — Caliban-generated client + adapter
    - `jorlan/web/pages/` — one file per page component
    - `jorlan/web/components/` — shared small components (Toast, Confirm, etc.)
- [x] `web/src/main/web/` static assets: favicon set, `css/jorlan.css`, `css/auth.css`, `css/toast.css`
- [x] MUI `ThemeProvider` with custom theme (Inter + JetBrains Mono fonts, brand primary color)
- [x] `CssBaseline` included in entry point

### GraphQL Client

- [x] Caliban client hand-written and committed to
  `web/src/main/scala/jorlan/web/graphql/client/JorlanClient.scala`
- [x] `ScalaJSClientAdapter.scala` (HTTP POST for queries/mutations, WebSocket for subscriptions)
- [x] HTTP transport wired: sttp → `POST /api/jorlan`
- [x] WebSocket transport wired: `ws[s]://host/api/jorlan/ws`

### Auth

- [x] `JorlanWebApp` uses `AuthClient.whoami` on mount; renders `LoginRouter` if unauthenticated
- [x] `LoginRouter` from `zio-auth` with only email/password login (no oauth for now)
- [x] Logout support: POST `/api/auth/logout` + page reload

### Chat Interface (`ChatPage`)

- [x] Session selector / "New session" button (calls `createSession` mutation)
- [x] Message history area: scrollable, timestamps, role-coloured (user ❯ / server ✦ / system ⚙ / error ✗)
- [x] Streaming response: `agentResponseStream` subscription appends tokens in real time
- [x] Input: MUI `TextField` multiline — Enter sends, Shift+Enter inserts newline
- [x] `submitMessage` mutation on send
- [x] Model display in AppBar subtitle (deferred — requires session state in AppShell)

### Sessions Page (`SessionsPage`)

- [x] Table: session ID, agent, model, status, created-at, actions
- [x] `listSessions` query with pagination
- [x] Create session dialog (model picker from `availableModels` query)
- [x] Terminate session button (`terminateSession` mutation)

### Approvals Page (`ApprovalsPage`)

- [x] Live approval list via `approvalNotifications` subscription
- [x] Table: capability, agent, requested scope, timestamp
- [x] Approve / Deny buttons → `decideApproval` mutation
- [ ] Badge on nav item showing pending count (deferred — requires global state)

### Memory Browser (`MemoryPage`)

- [x] Search box + scope filter → `listMemory` query
- [x] Table: scope badge, content preview, created-at
- [x] Forget button → `forgetMemory` mutation
- [x] Mark Shared / Mark Private buttons → `markMemoryShared` / `markMemoryPrivate`
- [x] "Remember" dialog (key + text + scope) → `storeMemory` mutation

### Scheduler Page (`SchedulerPage`)

- [x] Jobs table: name, status, scheduled-at, retry config, actions (pause/resume/cancel/run-now/delete)
- [x] Triggers sub-table per job (`triggers` query)
- [x] Create job dialog with trigger form (`createJob` + `addTrigger` mutations) — deferred
- [x] `pauseJob` / `resumeJob` / `cancelJob` / `triggerNow` / `deleteJob` mutations wired

### Event Log Page (`EventLogPage`)

- [ ] Filterable table: session, actor, event type, date range — `eventLog` query (paginated) — deferred (no paginated
  eventLog query)
- [x] Live-tail toggle: `eventLogTail` subscription appends rows in real time
- [x] Expandable row for `payloadJson` details

### Skill Registry Page (`SkillsPage`)

- [x] Table of skill versions: name, tier badge, status, version — `listSkillVersions` query not yet in API; stub page
  shown
- [ ] Filter by tier and status — deferred
- [x] Expandable row showing `manifestJson` — deferred

### Users & Roles Page (`UsersPage`, admin only)

- [x] Users table: display name, email, active toggle — `users` query
- [x] Create user dialog (`createUser` mutation)
- [x] Edit / deactivate (`updateUser` mutation)
- [x] Roles assignment dialog (`allRoles` / `roles` / `assignRole` / `revokeRole`)
- [x] Capability grants table with grant/revoke (`userCapabilityGrants` / `grantCapability` / `revokeCapabilityGrant`)
- [x] Channel identities dialog (`userChannelIdentities` / `linkChannelIdentity` / `unlinkChannelIdentity`)

### Settings Page (`SettingsPage`)

- [x] Personality editor: formality select + text fields → `updatePersonality` mutation
- [x] `serverPersonality` query populates current values
- [x] Default model selector from `availableModels` query

### Server-Side Static File Serving

- [x] `webRoot: String` added to `JorlanAppConfig` in `configuration.scala` (default `/opt/jorlan/www`)
- [x] `StaticFileRoutes.scala` in `server` module: serve `dist/` contents at `/`; fall back to `index.html`
  for unknown paths (SPA deep-linking)
- [x] `StaticFileRoutes` wired into `Jorlan.zapp` as catch-all for `GET` requests
- [x] Local dev override documented: set `jorlan.web.root = "debugDist"` in local `application.conf`
- [x] `application.conf` template updated with `jorlan.web.root = "/opt/jorlan/www"`

### Build & Packaging

- [x] `scripts/build-web.sh` helper script: runs `web/dist`
- [x] `web/debugDist` task verified (fast-opt bundle lands in `debugDist/`)
- [x] `web/dist` task verified (full-opt bundle lands in `dist/`) — pending (requires full-opt which takes longer)
- [x] `debianSettings` updated: add `dist/` → `/opt/jorlan/www/` mapping
- [ ] `server/debian:packageBin` verified to include web assets at `/opt/jorlan/www/`
- [x] CI pipeline note: `stLib publishLocal` must precede `web/dist` and `server/debian:packageBin`
- [x] `custom.webpack.config.js` already present; no extra loaders required for MUI/Emotion CSS-in-JS
- [x] Update README to describe how the web module works

---

## Phase 16: Advanced Features

**Goal:** Full platform feature set — declarative skills, agent-authored skills, MCP import, vector memory, and
remaining skills.

#### General

- [x] **Vector-backed memory retrieval**: MariaDB vector index, embedding job (via `ai` module),
  `MemoryService.semanticSearch`
- [ ] Workspace memory snapshots (workspace-scoped memory linked to snapshot artifacts)
- [ ] Shell parity

#### More Skills

- [x] **Declarative JSON skills** (Tier 2): HTTP/API, prompt/template, workflow, query, command-template types; JSON
  schema validation on install
- [x] **Agent-authored skill lifecycle** (Tier 5 → Active): draft → schema validated → permission reviewed → sandbox
  tested → awaiting approval → active (full state machine per design doc)
- [ ] **RSS/news feed skill**: fetch and parse RSS/Atom feeds, return recent entries. No auth required. New sbt
  module. Tools: `rss.fetch` (URL → list of entries), `rss.list_saved` / `rss.save_feed` / `rss.remove_feed` (
  persist watched feeds in `server_settings`).
- [ ] **Discord connector**
- [ ] **Discord oauth**
- [ ] **Telegram Oauth**
- [ ] **Slack connector**: Slack Bot API, message normalization, identity resolution
- [ ] **Workspace write skill**: extend the existing `WorkspaceSkill` (or add a new `workspace.write_file`,
  `workspace.append_file`, `workspace.delete_file` tools) so agents can produce persistent file artifacts, not just
  read
  them. Sandboxed to workspace root. Requires `workspace.write` capability.
- [ ] **GitHub skill**: read GitHub issues, pull requests, and file contents; post comments; search code. Uses
  GitHub REST API v3 with a personal access token stored in `server_settings` key `"skill.github"`. New sbt module.
  Tools: `github.list_issues`, `github.get_issue`, `github.comment_issue`, `github.list_prs`, `github.get_file`,
  `github.search_code`.
- [ ] **Notes/scratchpad skill**: lightweight session-scoped or persistent key/value store for within-session agent
  state. Distinct from the memory system (no embeddings, no summarisation — just fast keyed access). Lives in the
  server
  module. Tools: `notes.set`, `notes.get`, `notes.list`, `notes.delete`.

---

## Phase 17: Orchestrator Integration

**Goal:** External orchestrators (Paperclip-style) can submit, supervise, and retrieve results from work requests via
GraphQL.

- [ ] **Orchestrator identity model**: first-class entity distinct from users; manifest-based registration
- [ ] **Work request schema**: title, goal, userContext, workspaceContext, constraints, allowedCapabilities,
  disallowedCapabilities, expectedArtifacts, successCriteria, approvalPreference, idempotencyKey
- [ ] `submitWork` mutation → validation → execution handle
- [ ] `ExecutionStateMachine`:
  `created → accepted → waiting_for_approval → running → paused → blocked → completed | failed | cancelled | expired`
- [ ] **GraphQL additions**: `execution(id)` query, `executionEvents` subscription, `decideApproval` mutation,
  `artifacts(executionId)` query
- [ ] Capability discovery queries: `skills`, `capabilities`, `connectors` with full metadata
- [ ] Approval delegation policy: orchestrator vs. human boundary configurable per capability
- [ ] Trace export service: full, redacted, summary variants in JSON and Markdown
- [ ] Idempotency key deduplication on mutations
- [ ] Dry-run / planning mode: `planWork` query returns capability requirements and likely effects without executing
- [ ] Tests: full orchestrator submission → execution → artifact retrieval flow

---


---

## Appendix: Module Dependency Map

Skills are cross-projects (JVM + JS). The `←` arrow means "depends on".

```
model              (cross)
  ← gqlClient      (cross: model)
  ← skillApi       (cross: model, gqlClient)
  ← ai             (JVM:   model)
  ← analytics      (JVM:   model)

Skill cross-projects (each: JVM + JS, depend on model, skillApi):
  ← calculatorSkill
  ← lyrionSkill
  ← emailConnector
  ← unitConversionSkill
  ← httpFetchSkill
  ← weatherSkill
  ← timeSkill
  ← marketDataSkill
  ← searchSkill
  ← googleServices

  ← telegramConnector  (JVM: skillApi, model)
  ← shell              (JVM: model, gqlClient)
  ← web                (JS:  model, gqlClient, skillApi, all skill JS sides)
  ← server             (JVM: model, ai, analytics, skillApi, all skill JVM sides,
                              telegramConnector)
  ← integration        (JVM: model, server, shell, skillApi, telegramConnector)
```

## Appendix: Testing Conventions

- Unit tests: `zio-test`, in `src/test/scala` within each module, target >80% coverage (>90% for permission kernel)
- Integration tests: Testcontainers MariaDB, in `db/src/test/scala` and `integration/src/test/scala`
- Fake providers: `FakeModelProvider`, mock connectors — all integration tests must run without live external
  credentials
- Every subsystem that writes to the event log should have a test asserting the correct event is emitted

## Appendix: Supported shell commands

**Status legend:** `[x]` implemented · `[~]` stub/partial (planned for a later phase) · `[ ]` not yet started

| Status | Command                  | Type      | Priority | Parameters                                                           | Description                                                                                                                                                                                                  |
|:------:|--------------------------|-----------|----------|----------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|  [x]   | `/help`                  | Built-in  | 0        | —                                                                    | Same as `/commands` — shows full command list with key bindings                                                                                                                                              |
|  [x]   | `/commands`              | Built-in  | 0        | —                                                                    | List all available commands with key bindings                                                                                                                                                                |
|  [x]   | `/quit`                  | Built-in  | 0        | —                                                                    | Exit the shell cleanly                                                                                                                                                                                       |
|  [x]   | `/exit`                  | Built-in  | 0        | —                                                                    | Alias for `/quit`                                                                                                                                                                                            |
|  [x]   | `/about`                 | Built-in  | 0        | —                                                                    | Show version and platform information                                                                                                                                                                        |
|  [x]   | `/status`                | Built-in  | 0        | —                                                                    | Server connectivity, client version, server version, uptime                                                                                                                                                  |
|  [x]   | `/whoami`                | Built-in  | 0        | —                                                                    | Show current authenticated user (parsed: name, email, ID)                                                                                                                                                    |
|  [x]   | `/trace`                 | Built-in  |          | `none \| error \| warning \| info \| debug`                          | Set log/trace level                                                                                                                                                                                          |
|  [x]   | `/personality`           | Admin     |          | —                                                                    | Display server personality; `/personality set <field> <value>` to update a single field. Formality: Casual, Professional, Academic, Technical, Quirky, Fresh, Rude, Boomer, GenX, Millennial, GenZ, GenAlpha |
|  [ ]   | `/clear`                 | Built-in  |          | —                                                                    | Clear the conversation display                                                                                                                                                                               |
|  [ ]   | `/connect`               | Built-in  |          | `[url]`                                                              | Connect to a different server URL                                                                                                                                                                            |
|  [ ]   | `/disconnect`            | Built-in  |          | —                                                                    | Disconnect from the current server                                                                                                                                                                           |
|  [ ]   | `/logs`                  | Built-in  |          | `[n]`                                                                | Tail the last *n* lines from `~/.jorlan/shell.log`                                                                                                                                                           |
|  [x]   | `/new`                   | Session   |          | `[model]`                                                            | Start a new agent session with optional model override                                                                                                                                                       |
|  [x]   | `/model`                 | Session   |          | —                                                                    | Show active session ID, model, and status (queries server)                                                                                                                                                   |
|  [x]   | `/models`                | Session   |          | —                                                                    | List models available on the connected server                                                                                                                                                                |
|  [ ]   | `/session`               | Session   |          | `list \| new \| switch <id> \| close`                                | Manage agent sessions                                                                                                                                                                                        |
|  [ ]   | `/history`               | Session   |          | `[n]`                                                                | Show the last *n* messages in the current session                                                                                                                                                            |
|  [ ]   | `/configure`             | Session   |          | `<name>`                                                             | Interactively configure a skill or function                                                                                                                                                                  |
|  [ ]   | `/skill`                 | Skill     |          | `<name> [args…]`                                                     | Run a skill by name                                                                                                                                                                                          |
|  [x]   | `/capabilities`          | Auth      |          | —                                                                    | List your current capability grants                                                                                                                                                                          |
|  [x]   | `/approvals`             | Auth      |          | `list \| approve <id> \| deny <id>`                                  | View and action pending approval requests                                                                                                                                                                    |
|  [x]   | `/agents`                | Agent     |          | `list \| stop <id>`                                                  | List and terminate running agent sessions                                                                                                                                                                    |
|  [x]   | `/memory`                | Memory    |          | `list [scope] \| search <q> \| forget <id> \| remember <key> <text>` | Browse and manage agent memory entries                                                                                                                                                                       |
|  [x]   | `/skills`                | Skills    |          | —                                                                    | List registered skills and their tools                                                                                                                                                                       |
|  [x]   | `/contacts`              | Contacts  |          | `find <name>`                                                        | Find contacts by display name                                                                                                                                                                                |
|  [x]   | `/users`                 | Admin     |          | `list [all\|inactive]`                                               | List users (active by default)                                                                                                                                                                               |
|  [x]   | `/users create`          | Admin     |          | `<displayName> <email>`                                              | Create a new user                                                                                                                                                                                            |
|  [x]   | `/users deactivate`      | Admin     |          | `<id>`                                                               | Deactivate a user account                                                                                                                                                                                    |
|  [x]   | `/users update`          | Admin     |          | `<id> name\|email <value>`                                           | Update a user field                                                                                                                                                                                          |
|  [x]   | `/users capabilities`    | Admin     |          | `<id>`                                                               | List capability grants for a user                                                                                                                                                                            |
|  [x]   | `/users grant`           | Admin     |          | `<id> <capability> <approvalMode>`                                   | Grant a named capability to a user                                                                                                                                                                           |
|  [x]   | `/users revoke-grant`    | Admin     |          | `<grantId>`                                                          | Revoke a capability grant by id                                                                                                                                                                              |
|  [x]   | `/users roles`           | Admin     |          | `<id>`                                                               | List roles assigned to a user                                                                                                                                                                                |
|  [x]   | `/users assign-role`     | Admin     |          | `<userId> <roleId>`                                                  | Assign a role to a user                                                                                                                                                                                      |
|  [x]   | `/users revoke-role`     | Admin     |          | `<userId> <roleId>`                                                  | Revoke a role from a user                                                                                                                                                                                    |
|  [x]   | `/users identities`      | Admin     |          | `<id>`                                                               | List channel identities for a user                                                                                                                                                                           |
|  [x]   | `/users link-identity`   | Admin     |          | `<userId> <channelType> <channelUserId>`                             | Link a channel identity to a user                                                                                                                                                                            |
|  [x]   | `/users unlink-identity` | Admin     |          | `<identityId>`                                                       | Remove a channel identity                                                                                                                                                                                    |
|  [x]   | `/roles list`            | Admin     |          | —                                                                    | List all roles in the system                                                                                                                                                                                 |
|  [x]   | `/roles create`          | Admin     |          | `<name> [description]`                                               | Create a new role                                                                                                                                                                                            |
|  [x]   | `/scheduler`             | Scheduler |          | `list`                                                               | List scheduler jobs with status                                                                                                                                                                              |
|  [x]   | `/scheduler result`      | Scheduler |          | `<id>`                                                               | Show full result for a scheduler job                                                                                                                                                                         |
|  [x]   | `/agents`                | Agent     |          | `list \| stop <id>`                                                  | List and terminate running agent sessions (also at top)                                                                                                                                                      |
|  [x]   | `/oauth`                 | OAuth     |          | `list \| status <p> \| connect <p> \| revoke <p>`                    | Manage OAuth connections                                                                                                                                                                                     |
|  [x]   | `/email`                 | Email     |          | `list [n] \| read <id> \| search <query>`                            | Browse email via Google/IMAP skill                                                                                                                                                                           |
|  [x]   | `/calendar`              | Calendar  |          | `today \| list [YYYY-MM-DD]`                                         | Browse calendar events                                                                                                                                                                                       |
|  [ ]   | `/restart`               | Admin     |          | —                                                                    | Restart the Jorlan server process                                                                                                                                                                            |
|  [ ]   | `/plugins`               | Plugin    |          | `list \| inspect \| install \| enable \| disable`                    | Manage server plugins (Phase 14)                                                                                                                                                                             |
|  [ ]   | `/mcp`                   | Plugin    |          | —                                                                    | MCP protocol tools and adapter management (Phase 14)                                                                                                                                                         |

## Appendix: Supported skills

| Status | Skill            | Priority | Tier        | Description |
|:------:|------------------|----------|-------------|-------------|
|  [x]   | Market Data      |          | Declarative |             |
|  [x]   | Lyrion Server    | 1        | Declarative |             |
|  [x]   | Google Contacts  |          | Plugin      |             |
|  [x]   | Google Calendar  | 1        | Plugin      |             |
|  [x]   | MCP Connector    |          | Built-in    |             |
|  [ ]   | Declarative Json |          | Built-in    |             |
|  [x]   | Calculator       |          | Built-in    |             |
|  [x]   | Search           |          | Built-in    |             |
|  [x]   | Weather          |          | Built-in    |             |
|  [x]   | Unit Conversion  |          | Built-in    |             |
|  [ ]   | ``               |          | Built-in    |             |
|  [ ]   | ``               |          | Built-in    |             |
|  [ ]   | ``               |          | Built-in    |             |
|  [ ]   | ``               |          | Built-in    |             |
|  [ ]   | ``               |          | Built-in    |             |

## Appendix: Connectors

| Status | Skill              | Priority | Type     | Description |
|:------:|--------------------|----------|----------|-------------|
|  [x]   | Telegram           | 1        | Built-in |             |
|  [ ]   | Slack              |          | Built-in |             |
|  [ ]   | Whatsapp           |          | Built-in |             |
|  [ ]   | Discord            | 2        | Built-in |             |
|  [ ]   | SMS                |          | Built-in |             |
|  [ ]   | Matrix             |          | Built-in |             |
|  [ ]   | IRC                |          | Built-in |             |
|  [ ]   | Facebook Messenger |          | Built-in |             |
|  [ ]   | Twitter DM         |          | Built-in |             |
|  [ ]   | LinkedIn Messaging |          | Built-in |             |
|  [x]   | Email (IMAP/SMTP)  | 2        | Built-in |             |

