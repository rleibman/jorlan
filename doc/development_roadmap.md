# Jorlan Development Roadmap

Version: 0.1  
Date: 2026-05-24

---

## How to Use

Check off items as they are completed. Each phase has a **Goal** statement describing what "done" means for that phase.
Sub-items break down larger tasks where needed. Tests are part of every phase — target >80% coverage throughout. The
first iterable milestone is marked explicitly.

---

## Phase 0: Project Foundation

**Goal:** The project compiles cleanly, modules are correctly structured, the server connects to MariaDB, and a bare
entry point starts up and shuts down gracefully.

- [x] `build.sbt` fully cleaned up (all `missMoneyPenny` references replaced, `ai` and `shell` modules added)
- [x] Source directory structure created for all modules (`model`, `db`, `ai`, `server`, `shell`, `analytics`,
  `integration`, `util`)
- [x] Application config loading (`zio-config` with HOCON) in `server`
- [x] DB connection pool setup (Quill `DataContext`) in `db`
- [x] Flyway baseline migration runs (empty schema, version table only)
- [x] `jorlan.Jorlan` main entry point: starts ZIO runtime, connects to DB, logs startup, handles shutdown signal
  cleanly
- [x] `logback.xml` and `application.conf` templates in `server/src/main/templates/`
- [x] `.env.example` documents all required environment variables
- [x] All modules compile with `-Werror` and no warnings

---

## Phase 1: Core Domain Model

**Goal:** All major domain types are defined in `model` as pure Scala — no persistence, no I/O, just the types and their
JSON codecs.

- [x] **Identity and access types**
    - `User` (id, displayName, createdAt, active)
    - `ChannelIdentity` (userId, channel type enum, channelUserId, verified)
    - `Role` (id, name, description)
    - `Permission` (id, roleId/userId, resource, action, scope)
    - `CapabilityGrant` (id, capability, scope, grantee, grantor, approvalMode, expiresAt)
    - `ApprovalMode` enum: `Denied | PerInvocation | Once | Session | Timed | Persistent`
    - `ApprovalRequest` / `ApprovalDecision` types
- [x] **Agent and session types**
    - `Agent` (id, name, description, defaultModel, trustLevel)
    - `AgentSession` (id, agentId, userId, workspaceId, status, createdAt)
    - `SessionStatus` enum: `Created | Active | Paused | Blocked | Completed | Failed | Cancelled`
    - `Conversation` (id, sessionId, startedAt)
    - `Message` (id, conversationId, role enum, content, timestamp)
- [x] **Skill types** *(note: SkillManifest folded into `SkillVersion.manifestJson` field)*
    - `SkillTier` enum (0–5 as per design)
    - `SkillVersion` (skillId, version as SemVer, manifestJson, status enum)
    - `SkillStatus` enum:
      `Draft | Validated | PermissionReviewed | SandboxTested | AwaitingApproval | Active | Deprecated | Revoked`
- [x] **Memory types** *(note: MemoryCheckpoint deferred — not yet implemented)*
    - `MemoryRecord` (id, userId|Null, scope: `User | Shared | Workspace | Private`, workspaceId|Null, content,
      embeddingId|Null, createdAt)
    - `MemoryEmbedding` (memoryRecordId, model, vector JSON)
- [x] **Scheduler types**
    - `SchedulerJob` (id, agentId, skillId, name, inputJson, status, scheduledAt, startedAt, finishedAt, resultJson)
    - `JobStatus` enum: `Pending | Running | Succeeded | Failed | Cancelled`
    - `SchedulerTrigger` (jobId, triggerType enum, expression)
    - `TriggerType` enum: `Cron | Interval | OneShot | Event`
- [x] **Event log types** *(note: flat `EventType` enum rather than sealed hierarchy)*
    - `EventLog` (id, eventType, actorId, agentId, sessionId, resourceType, resourceId, payloadJson, occurredAt)
- [x] **Other types**
    - `Artifact` (id, sessionId|Null, workspaceId|Null, name, mimeType, sizeBytes, storageUri, createdAt)
    - `Workspace` (id, ownerId, name, description)
    - `ConnectorInstance` (id, connectorType enum, config JSON, status)
    - `OrchestratorIdentity` (id, name, publicKeyPem, trustLevel)
- [x] `zio-json` codecs for all types (derive where possible, manual where needed)
- [x] Unit tests: construction, JSON round-trips, enum coverage

---

## Phase 2: Persistence Foundation

**Goal:** Full DB schema via Flyway, Quill repositories for all major entities, passing Testcontainers integration
tests.

- [x] Testcontainers MariaDB fixture shared across all repo tests
- [x] **Flyway migrations** (V001–V009 created)
    - `V001`: users, channel_identities
    - `V002`: roles, permissions, capability_grants, approval_requests, approval_decisions
    - `V003`: agents, agent_sessions, conversations, messages
    - `V004`: skills, skill_versions, connector_instances
    - `V005`: scheduler_jobs, scheduler_triggers
    - `V006`: event_log (append-only; no UPDATE/DELETE in application code)
    - `V007`: memory_records + memory_embeddings
    - `V008`: artifacts, workspaces
    - `V009`: orchestrator_identities
- [x] Quill `DataContext` ZIO layer (connection pool from config) — `MysqlZioJdbcContext` in `QuillRepositories`;
  `makeDataSource` factory extracted to `db` (HikariCP no longer in `model`)
- [x] Repository trait pattern: `trait XxxRepository[F[_]]` in `model`;
  `trait XxxZIORepository extends XxxRepository[RepositoryTask]` in `db`
- [x] `UserRepository` (users + channel identities) — `delete`/`deactivate` now return `F[Long]`
- [x] `PermissionRepository` (roles, permissions, capability grants, approval requests + decisions) — `revokeGrant`,
  `cancelApprovalRequest` added
- [x] `AgentRepository` (agents + sessions)
- [x] `ConversationRepository` (conversations + messages)
- [x] `SkillRepository` (skills, skill versions, connector instances)
- [x] `EventLogRepository` (insert only + search by type/actor/time range) — date filter bug fixed
- [x] `MemoryRepository` (upsert, search by scope/userId/workspaceId, purge expired)
- [x] `SchedulerRepository` (jobs + triggers) — `deleteJob`, `deleteTrigger` added; `getPendingJobs` filters
  `scheduledAt <= now`
- [x] `ArtifactRepository` (artifacts + workspaces)
- [x] `JorlanError` base class added; `ConfigurationError` and `RepositoryError` extend it
- [x] `PermissionId` and `ChannelIdentityId` opaque types added; all mistyped IDs corrected
- [x] `FlywayMigration.migrate` now returns `Task[Unit]` — startup aborts on migration failure
- [x] `FlywayConfig.target` changed from `String` to `Option[String]`
- [x] `ApprovalStatus.Denied` renamed to `ApprovalStatus.Rejected` (no longer shadows `ApprovalMode.Denied`)
- [x] `ConfigurationServiceImpl` file I/O wrapped in `ZIO.attempt`; dev-only fallback path removed
- [x] `testcontainers-scala-mariadb` scoped to `% Test` in `db` and `server` modules
- [x] Test container lifecycle uses `ZIO.acquireRelease` for proper cleanup
- [x] All `assertTrue(a) && assertTrue(b)` patterns fixed to `assertTrue(a, b)` in integration tests
- [ ] Integration tests for all repositories (>80% coverage) — partial (6 suites, 10 tests;
  Scheduler/Artifact/Permission suites missing)

---

## Phase 3: Event Logging Service

**Goal:** A working, append-only `EventLog` service usable from any other subsystem via a ZIO layer.

- [x] `EventLogService` ZIO layer: `log(event)`, `query(filter)`, `replay(sessionId)` — in `model` (trait) + `server` (
  impl)
- [x] Correlation ID propagation via `ZIO.logAnnotate` / `CorrelationId` helper (`withNew`, `withId`, `get`) in `model`
- [ ] At least one event written for every significant action from Phase 2 onwards
- [x] Unit tests (mock repository, 7 tests in `server`) + integration tests (3 tests in `integration`)

---

## Phase 4: User, Identity, and Role Management

**Goal:** Full lifecycle management for users, their channel identities, roles, and direct permissions.

- [x] `JorlanAuthServer` (zio-auth): login, logout, changePassword, get by PK/email, create user via OAuth,
  link OAuth provider to existing user — covers the auth-facing slice of `UserService`
- [x] `ChannelIdentityService`: link identity to user, resolve channel ID → user — covered by `JorlanAuthServer`
  (`createOAuthUser`, `linkOAuthToUser`, `userByOAuthProvider`, `userByChannelIdentity` in repo)
- [x] `IdentityResolutionService`: OAuth/channel ingress resolution covered by `JorlanAuthServer` +
  `UserZIORepository.userByChannelIdentity`; connector-specific resolution delegated to each connector
- [ ] Admin user operations (deactivate, list users, update preferences) — exposed as GraphQL mutations, call
  `UserZIORepository` directly (no separate `UserService` layer needed)
- [x] `RoleService` (create role, assign role to user, remove role from user, list roles for user) — implemented
  as `upsertRole`, `deleteRole`, `assignRole`, `removeRole` in `PermissionService`
- [x] `PermissionService` (create/delete permission, grant/revoke capability, list for user/role)
- [x] `assignRole`/`removeRole` write `RoleAssigned`/`RoleRevoked` events; approval lifecycle writes
  `ApprovalRequested`/`ApprovalGranted`/`ApprovalDenied` events
- [ ] Unit tests >80%

---

## Phase 5: Permission and Capability Kernel

**Goal:** The deny-by-default capability evaluator — the core security primitive used by every subsequent subsystem.

- [x] `RiskClassifier`: classify any action request into a `RiskClass` (0–5)
- [x] `CapabilityEvaluator`: evaluates in order — explicit deny → resource permission → role → capability grant →
  connector policy → skill policy → default deny
- [x] `ApprovalPolicyEngine`: given risk class + evaluator result → `Allowed | PendingApproval(mode) | Denied`
- [x] `ApprovalService`: create approval request, notify (stub), record decision, check status, enforce expiry
- [x] All decisions written to `EventLogService` (`CapabilityAllowed`/`CapabilityDenied`; approval flow uses
  `ApprovalRequested`/`ApprovalGranted`/`ApprovalDenied`)
- [x] Unit tests: 37 tests covering every evaluation order step, all `RiskClass` mappings, all approval modes, and
  `PendingApproval` template fields — all pass

---

## Phase 6: GraphQL API Skeleton

**Goal:** A running HTTP server exposing an authenticated GraphQL API with basic CRUD for users, roles, and permissions.
GraphQL subscriptions wired (even if no events flow yet). Take a look at how DMscreen implements it's various GraphQL
APIs for inspiration on structure and best practices.

- [x] `zio-http` server setup with configurable port and TLS placeholder
- [x] Caliban schema derivation and wiring for domain types
- [x] JWT authentication middleware (issue token, validate on every request)
- [x] **Queries:** `user(id)`, `users`, `role(id)`, `roles`, `permissions(userId)`
- [x] **Mutations:** `createUser`, `updateUser`, `createRole`, `assignRole`, `grantPermission`, `revokePermission`
- [x] **Subscriptions:** We'll need this for message response streaming, approval notifications, and event log tailing —
  set up the infrastructure now
- [x] Health check endpoint (`GET /health`)
- [x] GraphQL subscription infrastructure (Caliban + WebSocket) — even if no active event sources yet
- [x] Integration tests for all endpoints (Testcontainers for DB, Caliban interpreter — `GraphQLApiSpec`)
- [x] Write scripts (in ./scripts directory) to generate the graphql schema and graphql client code (see dmscreen for
  example) and document in README

---

## Phase 7: Shell Interface

**Goal:** A runnable CLI (`jorlan-shell`) that connects to the server, lets a user send messages and see responses, and
handles approvals interactively. First deployable artifact beyond the server.

- [ ] `JorlanShell` entry point (ZIO main)
- [ ] Config: server URL, auth credentials (loaded from `~/.jorlan/config`), or passed in as command arguments, command
  line arguments take precedence
- [ ] Caliban-generated GraphQL client (or sttp + hand-rolled queries — decide at implementation time).
- [ ] I want to use sttp for the http client, with a zio back end (sttp.client4.httpclient.zio.HttpClientZioBackend)
  you'll need to add those dependencies to the project
- [ ] Authenticate and obtain JWT on startup
- [ ] Interactive REPL: prompt → submit message → display streamed or complete response
- [ ] Session management: start new session, resume existing session by ID
- [ ] Pending approval display: show approval requests, accept/deny interactively
- [ ] Graceful exit on `/quit` / `/exit` / Ctrl-C
- [ ] Integration tests (mock server responses)
- [ ] The shell shoud look similar to claude or openclaw (no surprises). The screen is split in half (I'm not quite sure
  how that's done, you'll have to research a bit). The top section streams responses from the server, the bottom section
  takes input from the user. You can use /commands to do various things we'll allow plugins to have their own. I'll let
  you take a first pass at which commands are important.
  commands, but some will be built in.

/new  archives the current session and starts a fresh one, will ask a bunch of questions about model, etc
/configure [x]  Interactively allows configuration of skill/function x
/exit leaves the shell
/quit alias for exit
/model Lets you chose the model and configure various things about the model
/trace [none, error, warning, debug] Sets trace(log) level
/models Lists available models
/help



---

## Phase 8: Agent Session Runtime + Model Gateway

**Goal:** End-to-end message → agent → Ollama model → response, with everything traced to the event log. **This is the
first iterable milestone.**

**Agent Session Runtime (in `server`):**

- [ ] `AgentSessionManager`: create, restore, suspend, terminate sessions; persist state via `AgentSessionRepository`
- [ ] `ConversationContextManager`: maintain in-memory context window; truncate when needed; flush to
  `ConversationRepository`
- [ ] `Orchestrator`: top-level per-session loop — receive message, invoke `Planner`, dispatch result
- [ ] `Planner`: build prompt from context + memory, call model, parse response into `PlanStep` (tool call or final
  answer)
- [ ] `ExecutionEngine`: dispatch `PlanStep` to skill runtime (stub for now) or return answer; handle multi-step loops
- [ ] `HumanApprovalManager`: pause execution on `NeedsApproval`, notify via `NotificationRouter`, resume on decision
- [ ] `NotificationRouter`: stub — logs to console and writes event; real delivery added in Phase 12

**Model Gateway (in `ai` module):**

- [ ] `ModelProvider` ZIO service trait: `complete(prompt, options) => ZIO[..., ModelError, ModelResponse]`
- [ ] `FakeModelProvider`: deterministic canned responses, configurable per test
- [ ] `OllamaProvider`: wraps LangChain4j `OllamaChatModel` in ZIO (enable LangChain4j deps in `build.sbt`)
- [ ] `ModelCapabilityMetadata`: context window, tool calling support, structured output reliability, cost/latency
  profile
- [ ] `ModelGateway` ZIO layer: routes to configured provider, records `ModelCallEvent` to event log
- [ ] Model config in `application.conf` (provider type, model name, endpoint URL, timeout)

**Integration:**

- [ ] Wire: shell REPL → GraphQL mutation `submitMessage` → identity resolution → session manager → orchestrator → model
  gateway → response → subscription event → shell displays response
- [ ] All steps write to event log
- [ ] Unit tests for each component; integration test for full round-trip using `FakeModelProvider`

### ★ FIRST ITERABLE MILESTONE

> A user sends a message via the shell CLI. The server creates an agent session, sends the message to Ollama, returns
> the response, and every step is recorded in the event log. The shell displays the response. The loop can repeat.

---

## Phase 8b: Installer and Distribution

**Goal:** Jorlan can be installed cleanly on Ubuntu (deb package) and macOS, with a first-run wizard that sets up the
database and configuration.

**Linux (Ubuntu .deb):**

- [ ] `sbt-native-packager` configured for Debian packaging: `debianPackageMaintainer`, `debianPackageSummary`,
  `packageDescription`, `linuxPackageMappings`
- [ ] Systemd service unit file (`jorlan.service`): `Type=notify`, `Restart=on-failure`, environment file at
  `/etc/jorlan/jorlan.env`
- [ ] Package layout:
    - `/usr/lib/jorlan/` — JARs and classpath
    - `/usr/bin/jorlan` — launch script (generated by `sbt-native-packager`)
    - `/etc/jorlan/jorlan.env` — environment variables file (installed as config, not overwritten on upgrade)
    - `/var/log/jorlan/` — log directory (owned by `jorlan` system user)
    - `/usr/lib/jorlan/scripts/init-db.sh` — database initialization helper
- [ ] Debian pre/post install scripts: create `jorlan` system user and group if absent; set log directory permissions
- [ ] Debian pre-remove script: stop service if running
- [ ] `sbt debian:packageBin` produces a valid `.deb` installable via `dpkg -i` or `apt install ./jorlan_*.deb`
- [ ] Smoke test: install on a fresh Ubuntu 22.04 LTS container (Testcontainers or GitHub Actions), verify service
  starts and `/health` responds

**macOS:**

- [ ] `sbt-native-packager` configured for universal tarball (`universal:packageZipTarball`) as primary macOS
  distribution format (no App Bundle required for a server daemon)
- [ ] Homebrew formula stub (`Formula/jorlan.rb`): `url`, `sha256`, `depends_on :java => "21"`, `service` block for
  `brew services start jorlan`
- [ ] LaunchDaemon plist template (`io.jorlan.server.plist`) for `launchctl load` based installs
- [ ] `install-macos.sh` script: extracts tarball, installs plist to `/Library/LaunchDaemons/`, creates log dir,
  prompts for config

**First-run Setup:**

- [ ] `init-db.sh` script in `shell/src/main/scripts/`: creates MariaDB database + application user with correct
  grants; documents required root credentials (already written in Phase 0)
- [ ] `.env.example` kept up-to-date with all required and optional variables (already in Phase 0)
- [ ] Validation: server startup fails fast with a human-readable error if required env vars are missing (not a silent
  NPE or confusing stack trace)

**CI/CD:**

- [ ] GitHub Actions job `build-deb`: runs `sbt debian:packageBin`, uploads `.deb` as workflow artifact
- [ ] GitHub Actions release workflow: on `v*` tag, build `.deb` and macOS tarball, attach to GitHub Release

---

## Phase 9: Memory System

**Goal:** Agents remember relevant context across sessions; checkpoints are committed at the right moments.

- [ ] `MemoryService` ZIO layer: `store(record)`, `query(scope, userId, text)`, `forget(id)`,
  `checkpoint(sessionId, trigger)`
- [ ] `CheckpointPolicy`: defines when to checkpoint (configurable: before/after external effect, timed interval,
  session end, user request)
- [ ] `CheckpointSummarizer`: uses `ModelGateway` to summarize conversation context into a `MemoryRecord`
- [ ] `MemoryClassifier`: assigns `scope` (User / Shared / Workspace / Private) to summarized chunk based on content
  heuristics
- [ ] `MemoryAccessPolicy`: governs which memory records are visible to which users/agents (prevents cross-user leakage)
- [ ] Wire memory retrieval into `Planner` context building (inject relevant records before model call)
- [ ] `MemorySkill` (Tier 0): `memory.remember`, `memory.search`, `memory.forget`, `memory.mark_shared`,
  `memory.mark_private`
- [ ] Tests >80%

**Note:** Vector/semantic search deferred to Phase 16.

---

## Phase 10: Durable Scheduler

**Goal:** Agents can create and manage scheduled tasks that survive server restarts.

- [ ] `JobManager` ZIO service: `createJob`, `listJobs`, `pauseJob`, `resumeJob`, `cancelJob`, `triggerNow`
- [ ] `TriggerEngine`: time-based and cron-like triggers initially (use ZIO `Schedule` internals where applicable)
- [ ] DB-backed job locking/leasing (`SchedulerRepository.claimJob` / `releaseJob`) to prevent duplicate runs
- [ ] `RetryEngine`: configurable retry count + backoff policy per job
- [ ] Missed-run handling: configurable policy per trigger (`skip`, `run_once`, `run_all_missed`)
- [ ] `SchedulerSkill` (Tier 0): exposes all `JobManager` operations to agents
- [ ] Wire: scheduled job fires → `AgentSessionManager` creates new session → `Orchestrator` runs job payload
- [ ] Tests: scheduler recovery after simulated restart (Testcontainers MariaDB), retry/backoff correctness

---

## Phase 11: Telegram Connector

**Goal:** Users can interact with Jorlan via Telegram; messages are resolved to canonical users and processed by agents.

- [ ] Telegram Bot API integration (start with long-polling; webhook switchable via config)
- [ ] `TelegramConnector` ZIO service: start polling, stop, send message, send photo/file
- [ ] `MessageNormalizer`: Telegram message → internal `InboundMessage`
- [ ] Identity resolution for Telegram users (Telegram user ID → `ChannelIdentity` → `User`)
- [ ] Unrecognized Telegram identity policy: configurable — reject or quarantine
- [ ] Outbound delivery for `NotificationRouter`
- [ ] Channel-specific config: bot token, allowed chat IDs, allowed users
- [ ] Integration tests using a mock Telegram API (no live bot token required for CI)

---

## Phase 12: Built-in Skills

**Goal:** Core Tier-0 skills that unlock real agent utility — file access, shell, notifications, identity, and
scheduling.

- [ ] **Skill registry infrastructure** (`SkillRegistry` ZIO service): register, look up by id/tier, validate manifest
  JSON schema
- [ ] **Workspace/Filesystem skill** (`workspace.*`)
    - `workspace.read` (read file by path within scoped workspace)
    - `workspace.write` (write file; creates path if needed)
    - `workspace.search` (find files by glob or text)
    - `workspace.snapshot` (tar/zip of workspace)
    - `workspace.delete` (requires explicit approval)
- [ ] **Shell execution skill** (`shell.*`)
    - `RiskClassifier` for shell commands (Class 0–5)
    - Structured command execution (`binary + args + cwd + timeout`; raw `bash -c` disabled by default)
    - Capture stdout/stderr, exit code → write to `ArtifactRepository`
    - Full trace: user, agent, workspace, binary, args, timing, exit code, artifact refs, approval ID
- [ ] **Identity and Contacts skill** (`identity.*`, `contacts.*`): resolve, link, verify, list aliases, search contacts
- [ ] **Notification skill** (`notify.*`): `notify.user`, `notify.channel` — delivers via `NotificationRouter` →
  Telegram, or console fallback
- [ ] Tests for each skill including permission enforcement

---

## Phase 13: Email and Calendar Skills

**Goal:** Agents can read email and calendar events; can send/modify with explicit approval.

- [ ] **Email skill abstraction** (provider-independent interface)
    - Operations: `list`, `search`, `read`, `draft`, `send`, `reply`, `forward`, `archive`
    - Send/forward/delete require approval
- [ ] Gmail API provider (OAuth2 per-user credentials, stored securely)
- [ ] IMAP/SMTP provider
- [ ] PGP support: verify inbound sender signature, sign outbound messages (configurable per user)
- [ ] **Google Calendar skill**: `listEvents`, `getEvent`, `createEvent`, `updateEvent`, `deleteEvent` (write ops
  require approval)
- [ ] **Google Drive skill**: `listFiles`, `readFile`, `downloadFile` (write/delete deferred or require approval)
- [ ] OAuth2 credential management ZIO service (per-user, encrypted at rest)
- [ ] Tests using mock/stub providers; no live credentials in CI

---

## Phase 14: Orchestrator Integration

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

## Phase 15: Web Frontend

**Goal:** The web equivalent of the shell interface - a simple scala.js react app that connects to the server, lets a
user send messages and see responses, and handles approvals interactively. We'll also have pages for A browser UI for
managing sessions, reviewing approvals, inspecting execution history, and browsing skills.

- [ ] We'll be using the same technologies as dmscreen, scalajs, react, but instead of semantic ui we'll be using
  elemental UI, so we'll need to set up the project structure and dependencies for that.
- [ ] We'll use the generated graphql client to connect to the server, take a look at how dmscreen does this for
  reference.
- [ ] If you see dmscreen, you'll notice that the scalablytyped generated scala wrappers are generated by a separate
  project, this is to reduce the amount of dependencies we need in our main server and shell projects, so we'll need to
  set up a similar project for our web interface, but if you have an alrenative I'm all ears. If we use the same
  approach as dmscreen, we need to figure out how to make it work in CI
- [ ] Decide frontend approach (Scala.js + Laminar / TypeScript + React / other — decide at implementation time)
- [ ] Agent session list and detail view
- [ ] Approval queue: view pending requests, approve/deny with optional scope override
- [ ] Execution history and event log browser (filterable by session, actor, event type)
- [ ] Skill registry browser (list, filter by tier, view manifest)
- [ ] Scheduler management UI (list jobs, pause/resume/cancel, trigger manually)
- [ ] User and role management UI (admin only)
- [ ] Memory browser (search, view, mark private/shared, forget)

---

## Phase 16: Advanced Features

**Goal:** Full platform feature set — declarative skills, agent-authored skills, MCP import, vector memory, and
remaining skills.

- [ ] **Declarative JSON skills** (Tier 2): HTTP/API, prompt/template, workflow, query, command-template types; JSON
  schema validation on install
- [ ] **Agent-authored skill lifecycle** (Tier 5 → Active): draft → schema validated → permission reviewed → sandbox
  tested → awaiting approval → active (full state machine per design doc)
- [ ] **MCP compatibility adapter**: import MCP-compatible tools as Tier-4 skills; translate manifest to canonical
  internal format
- [ ] **Vector-backed memory retrieval**: MariaDB vector index, embedding job (via `ai` module),
  `MemoryService.semanticSearch`
- [ ] **Web search skill**: `web.search`, `web.open_url`, `web.download` (granular capabilities, not a single broad
  permission)
- [ ] **Market data skill**: read quotes, watchlists, alerts, news (no trading/execution in initial scope)
- [ ] **Lyrion music skill**: list players, play/pause/stop, set volume, play playlist, schedule playback
- [ ] **Google Contacts skill**: search, read contacts
- [ ] **Slack connector**: Slack Bot API, message normalization, identity resolution
- [ ] Workspace memory snapshots (workspace-scoped memory linked to snapshot artifacts)

---

## Appendix: Module Dependency Map

```
model
  ← db
  ← ai
  ← db ← server ← (ai, analytics)
  ← model ← shell
  ← model, server ← integration
```

## Appendix: Testing Conventions

- Unit tests: `zio-test`, in `src/test/scala` within each module, target >80% coverage (>90% for permission kernel)
- Integration tests: Testcontainers MariaDB, in `db/src/test/scala` and `integration/src/test/scala`
- Fake providers: `FakeModelProvider`, mock connectors — all integration tests must run without live external
  credentials
- Every subsystem that writes to the event log should have a test asserting the correct event is emitted
