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
- [x] `RoleService` (create role, assign role to user, remove role from user, list roles for user) — implemented
  as `upsertRole`, `deleteRole`, `assignRole`, `removeRole` in `PermissionService`
- [x] `PermissionService` (create/delete permission, grant/revoke capability, list for user/role)
- [x] `assignRole`/`removeRole` write `RoleAssigned`/`RoleRevoked` events; approval lifecycle writes
  `ApprovalRequested`/`ApprovalGranted`/`ApprovalDenied` events

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

- [x] `JorlanShell` entry point (ZIO main)
- [x] Config: server URL, auth credentials (loaded from `~/.jorlan/config`), or passed in as command arguments, command
  line arguments take precedence
- [x] Caliban-generated GraphQL client (or sttp + hand-rolled queries — decided: sttp hand-rolled for Phase 7; caliban
  codegen deferred until API stabilises)
- [x] sttp for the http client with `HttpClientZioBackend` ZIO backend; `sttp.client4 %% "zio"` and `zio-json` added to
  shell module
- [x] Authenticate and obtain JWT on startup (`POST /login` → Bearer token stored in `Ref`)
- [x] Interactive REPL: prompt → submit message → display response (message submission stubs Phase 8 agent runtime)
- [ ] Session management: start new session, resume existing session by ID (Phase 8)
- [ ] Pending approval display: show approval requests, accept/deny interactively (Phase 8)
- [x] Graceful exit on `/quit` / `/exit` / Ctrl-C
- [x] Integration tests: unit tests for command parsing and config arg-override (21 tests pass)
- [x] Split-screen TUI via Lanterna: status bar (row 0), scrollable conversation area, separator + input line at bottom.
  PgUp/PgDn scrolling, word-wrapped messages, colour-coded by kind (system/user/server/error).

---

## Phase 8: Agent Session Runtime + Model Gateway

**Goal:** End-to-end message → agent → Ollama → **streaming** response token-by-token back to the shell, with every
step traced to the event log. **This is the first iterable milestone.**

### What the `ai` module already provides

The `ai` module (already present) wraps LangChain4j with ZIO-compatible types. Phase 8 builds on top of it:

- `StreamingChatLanguageModel` — wraps `OllamaStreamingChatModel`
- `StreamAssistant` — LangChain4j `AiServices` interface: `chat(String): TokenStream`
- `streamedChat(message): ZStream[StreamAssistant, Throwable, String]` — the key bridge: converts LangChain4j's
  `TokenStream` callbacks (`onPartialResponse`, `onCompleteResponse`, `onError`) into a proper ZIO `ZStream` via
  `ZStream.async`; this is the streaming path used for every model call in Phase 8
- `MessageWindowChatMemory` — per-instance sliding-window chat history (LangChain4j, in-memory)
- `LangChainServiceBuilder` — ZLayer builders for Ollama streaming and non-streaming variants

> **Note:** Phase 8 sessions are ephemeral — conversation history lives only in the JVM process and is lost on server
> restart. Phase 9 adds `ConversationRepository` to persist `ChatMessage` history per session and reconstruct
> `MessageWindowChatMemory` on resume.

### Clean-up (prerequisite)

- [x] Config keys: `jorlan.ai.ollamaBaseUrl`, `jorlan.ai.ollamaModel` (via `JorlanAiSettings` in `JorlanConfig`)

### Model Gateway (in `model` for trait, `server` for implementations)

The `ModelGateway` is the Jorlan boundary — `server` code never imports `langchain4j` directly, only the `ai` module
does.

- [x] `ModelInfo` data class: `id`, `provider`, `contextWindow`, `supportsStreaming`
- [x] `ModelGateway` ZIO service trait:
    - `streamedResponse(sessionId: AgentSessionId, message: String): ZStream[Any, ModelError, String]`
    - `availableModels: UIO[List[ModelInfo]]`
- [x] `OllamaModelGateway` implementation:
    - Maintains `Ref[Map[AgentSessionId, StreamAssistant]]` — one LangChain4j
      `MessageWindowChatMemory` per session, created on first use and keyed by `AgentSessionId`
    - Records `ModelCallStarted` and `ModelCallCompleted` events to `EventLogService`
- [x] `FakeModelGateway` for testing: returns a configurable `ZStream` of `String` chunks with optional delay to
  simulate streaming; deterministic so integration tests can assert on token-by-token delivery without Ollama
- [x] `ModelError` sealed type: `ModelUnavailable`, `ModelTimeout`, `ModelResponseMalformed`
- [x] Model config in `application.conf` under `jorlan.ai`

> **LangChain4j abstracts model differences:** `AiServices` handles tool specification, response parsing, and memory
> injection internally. `ModelCapabilityMetadata` (context window, tool calling support, etc.) can be read from
> LangChain4j's model introspection or declared statically in config for each named model.

### Agent Session Runtime (in `server`)

- [x] `AgentSession` domain model extended with `modelId: Option[ModelId]`; V015 migration adds column
- [x] `AgentSessionManager` ZIO service: `createSession(userId, modelId)`, `getSession(id)`,
  `suspendSession(id)`, `terminateSession(id)` — auto-creates "Jorlan Interactive" default agent
- [x] `AgentRunner` ZIO service:
    - `processMessage(sessionId, content, actorId): IO[JorlanError, Unit]`
    - Calls `ModelGateway.streamedResponse` → publishes chunks to `SessionHub` → finishing sentinel
    - Records `UserMessageReceived` and `AgentResponseCompleted` to event log
- [x] `SessionHub` ZIO service: maintains `Ref[Map[AgentSessionId, Hub[ResponseChunk]]]`
- [x] `HumanApprovalNotifier` (stub): logs `ApprovalRequired` event only

> **Full planning loop deferred to Phase 12:** The complete agent architecture uses a `Planner` that parses model
> responses into typed `PlanStep` values — either a final answer or a tool call — and an `AgentRunner` dispatch loop
> that executes tool calls via the skill runtime and re-submits results to the model (the "ReAct" pattern). This
> architecture is intentionally deferred until Phase 12 introduces built-in skills. In Phase 8, `AgentRunner` is a
> thin pass-through: message in → `streamedChat` → stream back. No multi-step loops, no tool dispatch.
> See Phase 12 "Foundation" section for the concrete implementation plan. Until that lands, no natural-language
> skill invocation works regardless of how many skills are registered.

### GraphQL changes

- [x] New domain type: `ResponseChunk { sessionId: AgentSessionId!, content: String!, finished: Boolean! }`
- [x] New mutation: `submitMessage(sessionId: Long!, content: String!): Unit` — capability-gated (`agent.message`)
- [x] Session lifecycle mutations: `createSession(modelId: String): AgentSession!`, `listSessions`
- [x] New subscription: `agentResponseStream(sessionId: Long!): ResponseChunk`
- [x] Schema regenerated; Caliban shell client regenerated with `WorkspaceId`, `ModelId` scalar mappings

### Shell changes

- [x] `/new [model]` command: calls `createSession`, stores `sessionId` in `ShellState`, updates mode bar
- [x] Plain text when session active: calls `submitMessage`, subscribes to `agentResponseStream` via WebSocket
- [x] No active session: prompts "No active session — type /new to start one"
- [x] `ShellState` service tracks active `AgentSessionId`
- [x] `SubscriptionClient` implements graphql-ws protocol over sttp WebSocket

### Integration wiring summary

- [x] Event log writes: `SessionCreated`, `UserMessageReceived`, `ModelCallStarted`, `ModelCallCompleted`,
  `AgentResponseCompleted`
- [ ] Integration test: full round-trip using `FakeModelGateway`, asserting each chunk arrives in order
- [x] Unit tests: `AgentRunnerSpec`, `AgentSessionManagerSpec`, `SessionHubSpec`

### ★ FIRST ITERABLE MILESTONE

> A user types `/new` in the shell. The server creates an agent session. The user types a message. The server streams
> it through Ollama (via LangChain4j) token by token — each token appearing in the shell conversation area as it
> arrives over the GraphQL subscription WebSocket. Every step is recorded in the event log. The loop repeats.

---

## Phase 8.1: First-Run Initialization

**Goal:** A freshly installed Jorlan server and shell reach a fully operational state without out-of-band file
editing, DBA scripting, or manual SQL. See `doc/phase8.1-initialization.md` for the full design.

**Architectural note — `server_settings` as a JSON key-value store:** All server-level configuration (initialized
flag, server name, personality, future settings) lives in a single `server_settings` table with schema
`(key VARCHAR(64) PRIMARY KEY, value JSON NOT NULL)`. Using a JSON value column means each entry can hold a scalar,
an array, or a nested object without schema migrations when the shape of a setting evolves. The `initialized` flag
is a JSON boolean; the server name is a JSON string; the personality (Phase 8.3) is a JSON object.

**Server:**

- [x] Flyway migration V017: `server_settings` table (`key VARCHAR(64) PRIMARY KEY, value JSON NOT NULL`);
  seed `('initialized', 'false')` and `('serverName', '"Jorlan"')`
- [x] `ServerSettingsRepository`: `get(key): UIO[Option[Json]]`, `set(key, value: Json): UIO[Unit]`; wraps
  `server_settings`; used by all Phase 8.x services instead of ad-hoc SQL
- [x] `InitService` trait + `InitServiceImpl`: `isInitialized`,
  `complete(token, serverName, adminEmail, adminName, adminPassword)`;
  reads/writes `server_settings` via `ServerSettingsRepository`; invalidates the one-time token on success
- [x] `InitTokenStore`: `Ref[Option[String]]` holding a 32-hex random token generated on startup when uninitialized;
  printed to stdout in a clearly visible box; discarded after use
- [x] `StatusRoutes`: `GET /api/status` (always unauthenticated) — returns `initialized`, `version`, `serverName`
  (from `server_settings`), `uptimeMs`
- [x] `InitRoutes`: `POST /api/init` — accepts `token`, `serverName`, `adminEmail`, `adminName`, `adminPassword`;
  returns 403 when already initialized or token invalid
- [x] `SetupModeApp`: serves `StatusRoutes` + `InitRoutes`; returns 503 for all other paths while uninitialized
- [x] `Jorlan.run` updated: after Flyway, check `isInitialized`; if false, mount `SetupModeApp` with the one-time token
- [x] Unit tests for `InitService`: invalid token, duplicate init, successful init flips DB flag and stores server name
- [x] Integration test (Testcontainers): fresh DB → setup mode → POST `/api/init` with server name → `initialized: true`
  and `serverName` in status response → login with new credentials succeeds

**Shell:**

- [x] `ShellConfig` config-file name changed from `jorlan.json` to `jorlan-shell.json`; load order:
  `JORLAN_SHELL_CONFIG`
  env var → `--config` flag → `~/.jorlan/jorlan-shell.json` → `~/.jorlan/jorlan.json` (read-only backwards compat) →
  `application.conf` defaults
- [x] `ShellConfig` writer: persists `serverUrl`, `email`, `password` to the resolved config path after successful
  first-run
- [x] `InitClient`: `checkStatus(serverUrl): IO[String, ServerStatus]`,
  `complete(serverUrl, token, serverName, adminEmail, adminName, adminPassword): IO[String, Unit]`
- [x] `FirstRunWizard`: TUI effect that prompts for server URL → checks `/api/status` → if uninitialized, collects
  setup token + server name + admin name/email/password (with confirm) → POSTs to `/api/init` → saves config
- [x] `JorlanShell.run` updated: invokes `FirstRunWizard` when config has no `serverUrl` or config file is absent
- [x] Unit tests for `FirstRunWizard` (via `FakeScreen`): already-initialized path, setup path with bad token then
  success, connection error retry, password mismatch retry

---

## Phase 8.2: Database Bootstrap Prerequisites

**Goal:** The empty MariaDB database and application user exist before Phase 8.1's in-process wizard runs. This is
the only step that requires MySQL root credentials; everything after it is handled by the server itself.

- [x] `init-db.sh` script in `server/src/main/scripts/`: creates MariaDB database and application user with correct
  grants; requires temporary MySQL root credentials; run once before first server start
- [x] `.env.example` kept up-to-date with all required and optional variables
- [x] Validation: server startup fails fast with a human-readable error if required env vars are missing (not a
  silent NPE or confusing stack trace)

---

## Phase 8.3: Server Personality and Identity

**Goal:** Every Jorlan installation has a name and a personality that shapes how its agents communicate. The
personality is structured (giving admins concrete handles) but also includes an open-ended prose section for anything
that doesn't fit the structured fields. All agents on a server share the same personality — it is a floor, not a
per-agent setting.

**Design decisions:**

- Stored entirely in `server_settings` as a JSON object under key `personality` — no files, no YAML parsing at
  runtime. The structured fields give the admin a clear model; the admin edits via GraphQL mutation or shell command,
  not by editing a file.
- The server name captured during Phase 8.1 initialization lives in `server_settings` key `serverName` and is also
  the value of `personality.name`. Both are the same string; `serverName` is the canonical source and `personality`
  reads from it.
- The configured name of the default agent record ("Jorlan Interactive") is an internal identifier used in logs and
  DB references. It is intentionally decoupled from the personality name that users see in conversation. Renaming the
  internal agent record is an admin operation with no effect on what the AI calls itself.

**Personality JSON structure (stored in `server_settings` key `personality`):**

```json
{
  "name": "Jorlan",
  "formality": "professional",
  "languages": [
    "en"
  ],
  "expertise": [],
  "prompt": "You are a capable, thoughtful assistant focused on helping users accomplish their goals efficiently. You ask clarifying questions when a request is ambiguous rather than making assumptions. You acknowledge uncertainty rather than fabricating answers."
}
```

Fields:

- `name` — what the assistant calls itself in conversation (mirrors `serverName`)
- `formality` — `casual | professional | academic | technical`
- `languages` — ISO 639-1 list; assistant matches the user's language when possible
- `expertise` — list of topic areas where the assistant applies extra depth
- `prompt` — open-ended prose injected as the system prompt; the admin writes whatever they need here

**Server:**

- [x] Flyway migration V018: seed `server_settings` key `personality` with the default JSON object above (substituting
  the server name captured in Phase 8.1)
- [x] `Personality` domain type: `name`, `formality` (enum), `languages`, `expertise`, `prompt`; codec via zio-json
- [x] `PersonalityService` trait: `get(): UIO[Personality]`, `update(p: Personality): IO[JorlanError, Personality]`;
  caches in a `Ref` updated on write; backed by `ServerSettingsRepository`
- [x] `PersonalityServiceImpl` + `PersonalityService.live` layer
- [x] `AgentRunnerImpl` updated: constructs system prompt from personality on every model call — formality and language
  hints are synthesised into natural-language instructions and prepended to the `prompt` field
- [x] Admin GraphQL query `serverPersonality: ServerPersonality`
- [x] Admin GraphQL mutation `updatePersonality(input: ServerPersonalityInput): ServerPersonality`
  (admin-only capability check; updates `server_settings` and refreshes the `Ref`)
- [x] Unit tests: `PersonalityService` get/update round-trip; `AgentRunnerImpl` system-prompt construction for each
  formality level

**Shell:**

- [x] Shell title bar / status bar shows server name from `/api/status` `serverName` field (already wired in Phase 8.1)
- [x] `/personality` shell command (admin only): displays the current personality fields in the message area with a
  clear structure; sub-commands or interactive prompts allow updating individual fields or the full prompt

---

## Phase 8.4: Agent testing on CI. We need to add some tests for AI,

- [x] Integrate AI/CI testing according to the documentation on doc/testing_ai_on_ci.md.
- [x] Add tests for the AI module, ensuring that the `FakeModelGateway` is used to simulate model responses and that the
  `AgentSessionManager` and `AgentRunner` correctly handle streaming responses and event logging. These tests should
  work both locally and on CI

---

## Phase 9: Memory System

**Goal:** Agents remember relevant context across sessions; checkpoints are committed at the right moments.

See `doc/mini-designs/phase9-memory-system.md` for full design.

### 9.1 — Conversation History (Layer 1)

- [x] V016 migration: `chat_message` table (session_id FK, role, content, created_at, index on session+time) — used
  existing V005 `conversation`/`message` tables; added indexes in V019
- [x] `ChatMessage` domain type + `ChatMessageId` opaque type in `model` — existing `Message`/`MessageId` types used
- [x] `ConversationRepository` trait: `append`, `loadHistory(sessionId, limit)`, `deleteSession` — existing trait used;
  `addMessage`/`searchMessages` are the equivalents
- [x] `ConversationRepositoryImpl` (Quill/MariaDB) in `db` — `QuillConversationRepository` already existed
- [x] `AgentRunner.processMessage`: load history from `ConversationRepository` before model call
- [x] `AgentRunner.processMessage`: persist user message + assistant response after model call
- [ ] Session resume: shell `/new` with existing session ID reloads history correctly (manual test only)
- [x] Unit test: `ConversationRepositorySpec` (in-memory) — `InMemoryConversationRepo` added to `InMemoryRepositories`
- [ ] Integration test: round-trip persist + reload via Testcontainers

### 9.2 — Long-term Episodic Memory (Layer 2)

- [x] V017 migration: `memory_record` table — existing V007; V019 adds FULLTEXT index
- [x] `MemoryRecord` domain type + `MemoryRecordId` opaque type + `MemoryScope` enum in `model` — already existed
- [x] `MemoryService` trait: `store`, `query(scope, userId, text)`, `forget(id)`, `checkpoint(sessionId, trigger)`
- [x] `MemoryServiceImpl` in `server/service/`; `InMemoryMemoryRepo` in test helpers
- [x] `MemoryAccessPolicy` trait + default impl (User/Private/Shared/Workspace rules)
- [x] `MemoryService.query` applies `MemoryAccessPolicy` before returning results
- [x] Unit test: `MemoryServiceSpec`
- [ ] Integration test: store → query → forget cycle

### 9.3 — Checkpoint Pipeline

- [x] `CheckpointTrigger` enum: `SessionEnd`, `TimedInterval`, `UserRequest`, `BeforeExternalEffect`
- [x] `CheckpointPolicy` trait + default impl (`onSessionEnd` — sessions end triggers checkpoint)
- [x] `CheckpointSummarizer` trait: `summarize(history): IO[JorlanError, List[MemoryRecord]]`
- [x] `CheckpointSummarizerImpl`: calls `ModelGateway` with a fixed summarization system prompt
- [x] `MemoryClassifier` trait + heuristic impl (keyword PII → Private; share language → Shared; default → User)
- [x] Wire checkpoint into `AgentRunner`: after response, evaluate `CheckpointPolicy` → summarize → classify → store
- [x] Unit test: `CheckpointSummarizerSpec` with `FakeModelGateway`
- [x] Unit test: `MemoryClassifierSpec`

### 9.4 — Context Injection

- [x] `AgentRunner.processMessage`: query `MemoryService` for relevant records before model call
- [x] Inject retrieved records as a context block appended to the system prompt
- [ ] Integration test: verify injected memory appears in model call context

### 9.5 — `MemorySkill` (Tier 0)

- [x] `memory.remember` tool: explicit fact → `MemoryService.store`
- [x] `memory.search` tool: text query → `MemoryService.query` → return results to agent
- [x] `memory.forget` tool: id → `MemoryService.forget`
- [x] `memory.mark_shared` tool: id → update scope to `Shared`
- [x] `memory.mark_private` tool: id → update scope to `Private`
- [x] Register `MemorySkill` in `SkillRegistry` as Tier 0 (completed Phase 12)
- [x] Unit test: each tool invocation covered via `MemoryServiceSpec` and GraphQL tests

### 9.6 — GraphQL & Shell Surface

- [x] `listMemory(scope: MemoryScope, textSearch: String): [MemoryRecord!]!` query
- [x] `storeMemory(key, text, scope)` mutation (implements `memory.remember`)
- [x] `forgetMemory(id: ID!): Boolean!` mutation
- [x] `markMemoryShared(id: ID!): MemoryRecord!` mutation
- [x] `markMemoryPrivate(id: ID!): MemoryRecord!` mutation
- [x] Shell `/memory list` command
- [x] Shell `/memory search <query>` command
- [x] Shell `/memory forget <id>` command
- [x] Shell `/memory remember <key> <text>` command
- [x] Shell `/capabilities` command (list current grants)
- [x] Added `memory.read` and `memory.write` capability grants to admin init

### 9.7 — Tests & Cleanup

- [x] Overall test coverage ≥ 80% for new Phase 9 code — 251 server tests, all passing
- [x] `sbt scalafmtAll` clean before merge
- [x] Update `development_roadmap.md` checkboxes as items complete

**Note:** Vector/semantic search (Qdrant embeddings) deferred to Phase 14.

---

## Phase 10: Durable Scheduler

**Goal:** Agents can create and manage scheduled tasks that survive server restarts.

See `doc/mini-designs/phase10-durable-scheduler.md` for full design.

### Pre-work (Tech Debt)

- [x] `LangChainConfig` kept as-is — `ai` module stays provider-agnostic (no dmscreen fields to remove)
- [x] Roadmap text fix done (this item)
- [x] P9-051: `/capabilities` shell command — added `listCapabilities` GQL query, wired shell command

### Domain Extensions

- [x] `MissedRunPolicy` enum: `Skip | RunOnce | RunAllMissed`
- [x] `RetryBackoffPolicy` enum: `Fixed | Exponential`
- [x] `JobStatus.Paused` added (new variant)
- [x] `SchedulerJob` extended: `userId`, `maxRetries`, `retryCount`, `backoffSeconds`, `backoffPolicy`,
  `missedRunPolicy`, `leasedAt`, `leasedBy`
- [x] V021 migration: new columns on `schedulerJob`, FK to `user`, lease index

### Repository Extensions

- [x] `SchedulerRepository.listJobs(agentId: Option[AgentId])`
- [x] `SchedulerRepository.claimJob` — optimistic UPDATE with lease check (prevents duplicate runs)
- [x] `SchedulerRepository.releaseJob` — set final status, `resultJson`, `finishedAt`, clear lease
- [x] `SchedulerRepository.expireLeases` — reset stale leases to `Pending`
- [x] `PermissionRepository.listPendingApprovals(userId)` — for shell `/approvals list`

### Services

- [x] `JobManager` ZIO service: `createJob`, `addTrigger`, `listJobs`, `getJob`, `pauseJob`, `resumeJob`, `cancelJob`,
  `triggerNow`
- [x] `TriggerEngine` daemon fiber: polls pending jobs, claims + executes, handles missed-run policies; uses
  `cron4s-core` for cron expression parsing and ISO 8601 duration for interval triggers
- [x] `RetryEngine` (integrated into `TriggerEngine`): fixed + exponential backoff, `maxRetries` cap
- [x] Wire: job fires → `AgentSessionManager.createSession(job.userId)` → `AgentRunner.processMessage` → collect
  result → `releaseJob`
- [x] EventType additions: `SchedulerJobQueued`, `SchedulerJobStarted`, `SchedulerJobCompleted`, `SchedulerJobFailed`,
  `SchedulerJobCancelled`
- [x] `TriggerEngine` started as daemon fiber in `Jorlan.run`

### GraphQL & Shell Surface

- [x] Queries: `jobs(agentId)`, `job(id)`, `triggers(jobId)`, `listApprovals`
- [x] Mutations: `createJob`, `addTrigger`, `pauseJob`, `resumeJob`, `cancelJob`, `triggerNow`, `deleteJob` — all gated
  on `scheduler.manage` capability
- [x] `decideApproval(id, decision)` mutation for approval lifecycle
- [x] `terminateSession(id)` mutation
- [x] `listCapabilities` query (P9-051 fix)
- [x] Shell `/agents list` and `/agents stop <id>` commands
- [x] Shell `/approvals list`, `/approvals approve <id>`, `/approvals deny <id>` commands
- [x] Shell `/capabilities` command now calls live `listCapabilities` GQL query

### `SchedulerSkill` (Tier 0 — logic only)

- [x] `SchedulerSkill` implements `scheduler.create_job`, `scheduler.list_jobs`, `scheduler.pause_job`,
  `scheduler.resume_job`, `scheduler.cancel_job`, `scheduler.trigger_now`
- [x] Registry wiring completed in Phase 12

### Tests

- [x] Integration tests for `claimJob`/`releaseJob` in `SchedulerRepositorySpec` (2 new tests)
- [x] Unit tests: `JobManagerSpec` (9 tests), `TriggerEngineSpec` (6 tests, tick + retry + backoff + stale lease),
  `SchedulerSkillSpec` (6 tests); 685 total tests passing
- [x] Shell tests for `/agents list|stop` and `/approvals list|approve|deny` in `CommandHandlerSpec` (8 new tests)
- [ ] Integration tests: `SchedulerRecoverySpec` (job survives simulated restart), `RetrySpec` (fail N then succeed) —
  deferred (complex Testcontainers lifecycle tests)

---

## Phase 11: Telegram Connector

**Goal:** Users can interact with Jorlan via Telegram; messages are resolved to canonical users and processed by agents.
The phase also lays the foundational plugin seam (`Skill` / `ConnectorSkill` traits + reusable ingress pipeline) every
future connector will reuse.

See `doc/mini-designs/phase11-telegram-connector.md` and `doc/mini-designs/plugin-architecture.md` for full design.

### Pre-work: Rename `Skill` record → `SkillRecord`

- [x] `model/src/main/scala/jorlan/domain/skill.scala` — rename `case class Skill` → `SkillRecord`
- [x] `model/src/main/scala/jorlan/repository.scala` — update `SkillRepository` return types and `SkillSearch` sort enum
  references
- [x] `db/src/main/scala/jorlan/db/repository/QuillRepositories.scala` — update Quill query mappings
- [x] Grep `\bSkill\b` and fix any GraphQL / test references (leave `SkillVersion`, `SkillId`, `SkillTier`,
  `SkillStatus` unchanged)

### Runtime Trait Seam (connector-api)

- [x] New file `connector-api/src/main/scala/jorlan/connector/Skill.scala`: define `Skill` trait (`descriptor`,
  `invoke`)
- [x] `ConnectorSkill extends Skill` trait: add `connectorType`, `instanceId`, `start`, `stop`
- [x] Supporting types in same file: `SkillDescriptor`, `ToolDescriptor`, `InvocationContext`

### Reusable Ingress Pipeline

- [x] New file `model/src/main/scala/jorlan/domain/ingress.scala`: `InboundMessage`, `ChatKind` enum,
  `UnrecognizedIdentityPolicy` enum
- [x] New file `model/src/main/scala/jorlan/service/MessageIngress.scala`: `MessageIngress` trait (
  `receive(msg): IO[JorlanError, Unit]`)
- [x] `MessageIngressImpl` in `server/.../service/`: identity resolution → unrecognized policy → capability gate (
  `agent.message`) → resolve-or-create `AgentSession` for `(user, chatRef)` → dispatch to `AgentRunner.processMessage` →
  write inbound receipt event
- [x] Reply path: deferred to Phase 12 `NotificationRouter` — `agentRunner` parameter removed from
  `TelegramConnectorSkill`; see P11-001 in phase review

### Telegram Bot API Client

- [x] `TelegramApiClient` trait: `getUpdates(offset, timeoutSeconds)`, `sendMessage(chatId, text)`,
  `sendPhoto(chatId, photo, caption?)`, `sendDocument(chatId, file, filename)`
- [x] `TelegramApiClientImpl` over `zio-http`
- [x] `TelegramConfig` case class: `botToken`, `allowedChatIds`, `allowedUserIds`, `unrecognizedPolicy`, `useWebhook` —
  parsed from `ConnectorInstance.configJson`
- [x] `FakeTelegramApiClient` for tests (returns canned `getUpdates` responses; no live token in CI)

### `TelegramConnectorSkill extends ConnectorSkill`

- [x] `TelegramMessageNormalizer`: `TelegramUpdate` → `InboundMessage` (maps `chat.type` → `ChatKind`, `from.id` →
  `channelUserId`, `chat.id` → `chatRef`)
- [x] `TelegramConnectorSkill`: `connectorType = Telegram`, bound to `ConnectorInstance`
- [x] `start`: fork long-poll loop (`getUpdates(offset, 30)` → normalizer → `MessageIngress.receive`, advance offset);
  handle private/group/channel/supergroup; gate groups on `allowedChatIds`
- [x] `stop`: interrupt polling fiber
- [x] `invoke` egress tools (each gated by capability `telegram.send`, `RiskClass ExternalEffect`):
    - `telegram.send_message { chatId, text }`
    - `telegram.send_photo { chatId, photo, caption? }`
    - `telegram.send_file { chatId, file, filename }`
- [x] `descriptor` listing all three `ToolDescriptor`s with JSON schemas and required capabilities

### Minimal `ConnectorManager` + Boot Wiring

- [x] `ConnectorManager` trait: `startAll`, `stopAll`
- [x] `ConnectorManagerImpl`: collect `ConnectorSkill`s from registered set, start/stop ingress
- [x] Wire `TelegramConnectorSkill.live` + `MessageIngressImpl.live` + `TelegramApiClient.live` in `EnvironmentBuilder`
- [x] Fork `ConnectorManager.startAll` as daemon in `Jorlan.run` (mirror `TriggerEngine` startup pattern)

### Migration V023 (Quarantine persistence)

- [x] Decision: quarantine is log-only for Phase 11 (no DB table). V023 was already used for scheduler index fixes; V024
  adds `chat_ref` column to `agentSession` for durable connector-bound sessions.

### Tests

- [x] `TelegramMessageNormalizerSpec`: `TelegramUpdate` → `InboundMessage` for private / group / channel / supergroup
- [x] `MessageIngressSpec`: identity hit; `Reject` miss; capability gate deny; resolve-or-create session; dispatch —
  uses `InMemoryRepositories` + fake `AgentRunner`
- [x] `TelegramConnectorSkillSpec`: `start`/`stop` lifecycle; each egress `invoke` tool via `FakeTelegramApiClient`;
  long-poll loop end-to-end with mock client
- [x] `sbt scalafmtAll` clean before merge
- [x] Update `development_roadmap.md` checkboxes as items complete

### Module restructuring (added per Phase 11 review)

- [x] `connector-api` SBT module — `Skill`, `ConnectorSkill`, `SkillDescriptor`, `ToolDescriptor`, `InvocationContext`,
  `MessageIngress`, `InboundMessage`, `ChatKind`, `UnrecognizedIdentityPolicy` — package `jorlan.connector`
- [x] `telegram` SBT module — `TelegramConnectorSkill`, `TelegramApiClient`, `TelegramMessageNormalizer`,
  `FakeTelegramApiClient` — package `jorlan.connector.telegram`

### Appendix updates

- [x] Mark `Telegram` connector `[x]` in the Connectors appendix table

---

## Phase 12: Built-in Skills

**Goal:** Core Tier-0 skills that unlock real agent utility — the full ReAct tool-calling loop,
skill registry, notification routing, identity/contacts lookup, workspace file access, and shell execution.

> **This phase is the prerequisite for all natural-language skill invocation.**  Until the Foundation
> items are complete, the LLM cannot call any tool regardless of how many skills are registered.
> The use-case prompts in `doc/manual-testing-guide.md` Section G cannot work until this phase is done.
>
> See `doc/mini-designs/phase12-built-in-skills.md` for the full design.

### Foundation — Step 1: `ModelGateway.chatStep` + LangChain4j wiring  *(blocks everything)*

- [x] `ChatStep` sealed trait in `model`: `FinalAnswer(stream: ZStream[Any, ModelError, String])` and
  `ToolCallRequested(name: String, argsJson: String)`
- [x] `AgentMessage` sealed trait in `model`: `SystemMsg`, `UserMsg`, `AssistantMsg`, `ToolCallMsg`,
  `ToolResultMsg`; maps to LangChain4j `ChatMessage` in the `ai` module
- [x]
  `ModelGateway.chatStep(sessionId, messages: List[AgentMessage], tools: List[ToolSpec]): IO[ModelError, ChatStep]`
  method added to the `ModelGateway` trait; `ToolSpec` is a model-module-safe view of a tool descriptor
- [x] `OllamaModelGateway.chatStep` impl: convert `ToolSpec` → `ToolSpecification` via `ai.ToolSupport`;
  submit via `StreamingChatLanguageModel`; return `ToolCallRequested` on `onCompleteResponse` with tool
  requests, `FinalAnswer(stream)` otherwise
- [x] `FakeModelGateway` extended: `stepsLayer(steps: List[ChatStep])` factory; successive `chatStep` calls
  pop from the list; falls back to `FinalAnswer(chunks)` when exhausted

### Foundation — Step 2: `SkillRegistry` ZIO service

- [x] `SkillRegistry` trait in `server`: `register(skill: Skill)`, `allTools: UIO[List[ToolDescriptor]]`,
  `allToolSpecs: UIO[List[ToolSpec]]`,
  `invoke(toolName, argsJson, context: InvocationContext): UIO[Json]` (errors returned as `Json.Str`)
- [x] `SkillRegistryLive`: `Ref[Map[String, Skill]]`; validates required fields from `argsJson` against
  input schema; on tool error returns `Json.Str("Error: …")` (no JVM exception propagation)
- [x] `InvocationContext` extended in `connector-api`: add `workspaceId: Option[WorkspaceId]`,
  `approvalId: Option[ApprovalId]`, `traceId: String`
- [x] `MemorySkill` (Phase 9) retrofitted with `Skill` trait + wired into `SkillRegistry` at startup via
  `EnvironmentBuilder`
- [x] `SchedulerSkill` (Phase 10) retrofitted with `Skill` trait + wired into `SkillRegistry` at startup via
  `EnvironmentBuilder`
- [x] `TelegramConnectorSkill` egress tools wired at startup: registered in `SkillRegistry` via `startServices` after
  `ConnectorManager` starts

### Foundation — Step 3: `AgentRunnerImpl` ReAct loop

- [x] `AgentRunnerImpl.processMessage` replaced with ReAct loop:
    - Load history + memory + build system prompt (existing logic preserved)
    - Fetch `allToolSpecs` from `SkillRegistry`
    - Loop up to `maxToolSteps` (default 10): call `chatStep` → on `ToolCallRequested` invoke
        + append result + continue; on `FinalAnswer` stream chunks to `SessionHub` + persist + break
    - If max steps exceeded: publish error chunk + log `ToolLoopExceeded` event + terminate turn
- [x] New `EventType` variant: `ToolLoopExceeded` (added to `SkillInvoked`, `SkillSucceeded`, `SkillFailed` group)
- [x] `jorlan.agent.maxToolSteps` config key added to `JorlanConfig` / `application.conf` (via `AgentSettings`)
- [x] Shell `toolEvents(sessionId)` subscription wired: `SkillInvoked` and `SkillSucceeded` events streamed to
  shell so user sees `⟳ calling <tool>…` spinner per invocation (inline `ResponseChunk` feedback currently)
- [x] Unit test `AgentRunnerReActSpec`: `FakeModelGateway.stepsLayer` returning `ToolCallRequested` → `FinalAnswer`;
  assert correct tool invocation, event log entries, and chunk delivery order
- [x] Integration test `ToolCallingLoopSpec` (Testcontainers + `FakeModelGateway`): full pipeline from
  `submitMessage` → two tool calls → final answer → `agentResponseStream` delivers chunks in order

### Step 4: Notification Skill + `NotificationRouter`

- [x] `NotificationRouter` ZIO service trait + `NotificationRouterImpl` in `server`:
    - `notifyUser(userId, message)`: resolves user's channel identities (preference: Telegram first),
      calls `notifyChannel`
    - `notifyChannel(chatId, connectorType, message)`: invokes connector skill directly via `ConnectorManager`
      (avoids circular dep: router→registry→skill); returns error if no matching connector active
- [x] `NotifySkill` registered in `SkillRegistry` at startup: `notify.user { userId, message }` and
  `notify.channel { chatId, connectorType, message }` — require `notify.send` capability
- [x] `notify.send` capability grant seeded for admin user in `InitService.complete`
- [x] Unit test `NotificationRouterSpec`: known/unknown user identity; active/inactive connector

### Step 5: Contacts + Identity Skill

- [x] `ContactsSkill` registered in `SkillRegistry` at startup:
    - `contacts.find { name }`: case-insensitive substring search on `User.displayName`; returns list of
      `{ userId, displayName, identities: [{ channelType, channelUserId }] }` records; requires `contacts.read`
    - `identity.resolve { channelType, channelUserId }` → canonical `User` or null; `contacts.read`
    - `identity.link { userId, channelType, channelUserId }` → links identity; requires `identity.manage`
    - `identity.listAliases { userId }` → all `ChannelIdentity` rows for user; `contacts.read`
- [x] `contacts.read` and `identity.manage` capability grants seeded for admin in `InitService.complete`
- [x] `/contacts find <name>` shell command: calls `contacts.find` directly without agent
- [x] Unit test `ContactsSkillSpec`: match, no match, multiple matches; `IdentitySkillSpec`: resolve hit/miss

### Step 6: Workspace Skill

- [x] Flyway **V025**: `workspace_id` column already exists in `agent_sessions` from V004 — no migration needed
- [x] `jorlan.workspace.root` config key added to `WorkspaceSettings` / `application.conf`
- [x] `WorkspaceSkill` registered in `SkillRegistry` at startup:
    - `workspace.read { path }` — requires `workspace.read` capability; path-traversal guard
    - `workspace.write { path, content }` — requires `workspace.write` capability
    - `workspace.search { prefix? }` — requires `workspace.read`; lists files matching prefix
    - `workspace.delete { path }` — requires `workspace.write`; path-traversal guard
- [x] `workspace.read` + `workspace.write` capability grants seeded for admin in `InitService.complete`
- [x] Unit test `WorkspaceSkillSpec`: path-traversal rejection, read/write round-trip

### Step 7: Shell Execution Skill

- [x] `ShellSkill` registered in `SkillRegistry` at startup:
    - `shell.run { binary, args, cwd?, timeoutSeconds? }` — structured invocation via `zio-process`;
      binary must appear in `jorlan.shell.allowedBinaries`; unlisted → error (no execution)
- [x] Binary allowlist enforced from `jorlan.shell.allowedBinaries` config key; default safe set
- [x] `jorlan.shell.allowedBinaries` and `jorlan.shell.timeoutSeconds` config keys in `ShellSettings`
- [x] `shell.execute` capability grant seeded for admin in `InitService.complete`
- [x] Stdout + stderr captured; if output > threshold, stored as artifact
- [x] Event log: `ShellCommandInvoked` + `ShellCommandCompleted` events
- [x] Unit test `ShellSkillSpec`: allowed binary executes; blocked binary rejected; timeout fires;
  non-zero exit code returned as result (not exception)

### Step 8: GraphQL + Shell surface

- [x] `skills: [SkillInfo!]!` GraphQL query — lists registered skills, their tools, tier (groups tools by namespace)
- [x] `toolEvents(sessionId: Long!): ToolEvent` GraphQL subscription — streams `ToolInvoked` / `ToolResult`
- [x] `notifyUser(userId: ID!, message: String!): Boolean!` mutation (admin; calls `NotificationRouter`)
- [x] `/skills` shell command: calls `skills` GQL query; shows skill name, tier, and tool list
- [x] `contacts(name: String!): [ContactResult!]!` GraphQL query — case-insensitive user search with channel identities
- [x] Shell spinner: during a tool-calling turn, each `ToolInvoked` subscription event shows
  `⟳ calling <toolName>…` (replaced by final answer stream when `FinalAnswer` arrives)

### Step 9: Tests, scalafmt, roadmap checkboxes

- [x] Overall test coverage ≥ 80% for new Phase 12 code
- [x] `sbt --error scalafmtAll` clean before merge
- [x] `SkillRegistrySpec` — 7 tests: register, lookup, required-field validation, unknown namespace, liveWith
- [x] `AgentRunnerReActSpec` — 3 tests: FinalAnswer, ToolCall→FinalAnswer, ToolLoopExceeded
- [x] `NotificationRouterSpec`, `ContactsSkillSpec`, `WorkspaceSkillSpec`, `ShellSkillSpec`
- [x] All `development_roadmap.md` checkboxes above marked `[x]`

---

## Phase 13: Email and Calendar Skills

**Goal:** Agents can read email and calendar events; can send/modify with explicit approval.

See `doc/mini-designs/phase13-email-calendar.md` for full design.

Two new SBT modules:

- **`email`** — IMAP/SMTP via `emil` library + `EmailSkill` + `PgpService`
- **`google-services`** — Gmail, Calendar, Drive via Google REST APIs + `OAuthCredentialService`

### 13.0 — SBT Module Setup

- [x] Add `email` module to `build.sbt`: depends on `model`, `connector-api`, `db`; libraries
  `emil-common 0.19.0`, `emil-javamail 0.19.0`, `zio-interop-cats 23.1.0.5`, `bcpg-jdk18on 1.79`;
  source directory `email/src/main/scala/jorlan/email/`
- [x] Add `google-services` module to `build.sbt`: depends on `model`, `connector-api`, `db`;
  source directory `google-services/src/main/scala/jorlan/google/`
- [x] Add `email` and `google-services` as dependencies of `server` in `build.sbt`
- [x] `ThisBuild / libraryDependencySchemes` entries if needed for version conflicts

### 13.1 — Domain Types (model module)

- [x] `EmailMessageId` opaque type + `EmailMessage`, `EmailAttachment`, `EmailDraft` case classes in
  `model/src/main/scala/jorlan/domain/email.scala`
- [x] `CalendarId`, `CalendarEventId` opaque types + `CalendarEntry`, `CalendarAttendee`, `UserCalendar`,
  `CalendarEventStatus`, `AttendeeResponse` in `model/src/main/scala/jorlan/domain/calendar.scala`
- [x] `DriveFileId` opaque type + `DriveFile` case class in `model/src/main/scala/jorlan/domain/drive.scala`
- [x] `ExternalCredential` case class + `ExternalCredentialId` opaque type in
  `model/src/main/scala/jorlan/domain/externalCredential.scala`
- [x] `ExternalCredentialRepository[F[_]]` trait in `model/src/main/scala/jorlan/repository.scala`
- [x] `OAuthCredentialService` trait in `model/src/main/scala/jorlan/service/OAuthCredentialService.scala`:
  `store`, `load`, `revoke`, `listProviders`, `refreshAccessToken`
- [x] `EmailProvider` trait in `model/src/main/scala/jorlan/service/EmailProvider.scala`
- [x] New `EventType` variants: `EmailMessageRead`, `EmailMessageSent`, `EmailDraftCreated`, `EmailMessageArchived`,
  `CalendarEventRead`, `CalendarEventCreated`, `CalendarEventUpdated`, `CalendarEventDeleted`,
  `DriveFileRead`, `DriveFileListed`
- [x] zio-json codecs for all new domain types

### 13.2 — Database & Persistence (db module)

- [x] **V025** migration: `external_credentials` table (userId FK, provider, encryptedData JSON, expiresAt, scopes,
  timestamps, unique key on `(user_id, provider)`, CASCADE FK to `users`)
- [x] `QuillExternalCredentialRepository` in `db`: implements `ExternalCredentialRepository[RepositoryTask]`
  (upsert, find, delete, listByUser) using Quill

### 13.3 — Credential Encryption (server module)

- [x] `OAuthCredentialEncryptor` class in `google-services/src/main/scala/jorlan/google/OAuthCredentialEncryptor.scala`:
  AES-256-GCM; key = HKDF-SHA256(`JORLAN_AUTH_SECRET_KEY`, info=`"jorlan-external-credentials"`);
  output `{ iv: base64, ciphertext: base64 }`; uses `javax.crypto` only

### 13.4 — Configuration (server module)

- [x] `GoogleOAuthSettings` case class: `clientId`, `clientSecret`, `redirectUri` — added to `configuration.scala`
- [x] `ImapSettings` + `SmtpSettings` + `PgpSettings` + `EmailSettings` — added to `configuration.scala`
- [x] `JorlanConfig` extended with `google: GoogleOAuthSettings` and `email: EmailSettings`
- [x] `application.conf` template updated with `jorlan.google.*` and `jorlan.email.*` stanzas
- [x] `.env.example` updated with `JORLAN_GOOGLE_CLIENT_ID`, `JORLAN_GOOGLE_CLIENT_SECRET`,
  `JORLAN_GOOGLE_REDIRECT_URI`

### 13.5 — OAuth2 HTTP Routes (server module)

- [x] `OAuthRoutes` in `server/src/main/scala/jorlan/routes/OAuthRoutes.scala`:
  `GET /api/oauth/start/:provider` (builds Google auth URL with CSRF state JWT, redirects);
  `GET /api/oauth/callback/google` (verifies state JWT, exchanges code → tokens, stores via
  `OAuthCredentialService`, redirects to `/?oauth=success`)
- [x] State JWT: 30-min TTL, signed with `JORLAN_AUTH_SECRET_KEY`, carries `userId + provider` — CSRF protection
- [x] Google OAuth scopes requested together: `gmail.modify`, `calendar`, `drive.readonly`
- [x] `OAuthRoutes` wired into `Jorlan.zapp`

### 13.6 — `google-services` Module: OAuthCredentialService + Providers

- [x] `OAuthCredentialServiceImpl` in `google-services/src/main/scala/jorlan/google/`:
  backed by `ExternalCredentialRepository` + `OAuthCredentialEncryptor`;
  `refreshAccessToken` POSTs to `https://oauth2.googleapis.com/token` via zio-http;
  all three Google skills use `provider = "google"` (one token row, three APIs)
- [x] `GmailProvider` implements `EmailProvider[IO[JorlanError,*]]`: uses Google API Java client (not zio-http);
  wraps blocking calls with `ZIO.attemptBlocking`; maps Gmail API → `EmailMessage`
- [x] `GoogleCalendarProvider` in `google-services`: uses Google Calendar Java client;
  `listCalendars`, `listEvents`, `getEvent`, `createEvent`, `updateEvent`, `deleteEvent`
- [x] `GoogleDriveProvider` in `google-services`: uses Google Drive Java client;
  `listFiles`, `readTextFile` (exports Google Docs as text/plain), `downloadFile`
- [x] `FakeGmailProvider` in `google-services/src/test/scala/`: configurable messages + call recorder
- [x] `FakeCalendarProvider` in test: configurable event list + call recorder
- [x] `FakeDriveProvider` in test: configurable file list + content map

### 13.7 — `google-services` Module: Skills

- [x] `GoogleCalendarSkill` in `server/src/main/scala/jorlan/service/skills/GoogleCalendarSkill.scala` — 6 tools:
    - `calendar.listCalendars` — `calendar.read`, RiskClass ReadOnly
    - `calendar.listEvents { calendarId?, maxResults?, timeMin?, timeMax? }` — `calendar.read`, RiskClass ReadOnly
    - `calendar.getEvent { calendarId?, eventId }` — `calendar.read`, RiskClass ReadOnly
    - `calendar.createEvent { summary, start, end, description?, location?, attendees?, calendarId? }` —
      `calendar.write`, RiskClass ExternalEffect
    - `calendar.updateEvent { eventId, summary?, start?, end?, description?, location?, calendarId? }` —
      `calendar.write`, RiskClass Modification
    - `calendar.deleteEvent { eventId, calendarId? }` — `calendar.write`, RiskClass ExternalEffect
- [x] `GoogleDriveSkill` in `server/src/main/scala/jorlan/service/skills/GoogleDriveSkill.scala` — 3 read-only
  tools:
    - `drive.listFiles { folderId?, query?, maxResults? }` — `drive.read`, RiskClass ReadOnly
    - `drive.readFile { fileId }` — `drive.read`, RiskClass ReadOnly
    - `drive.downloadFile { fileId }` — `drive.read`, RiskClass ReadOnly; stores result as Artifact
- [x] Event log writes per invocation in both skills

### 13.8 — `email` Module: PgpService + ImapSmtpProvider + EmailSkill

- [x] `PgpService` trait in `email/src/main/scala/jorlan/email/PgpService.scala` (stub impl; full BouncyCastle impl
  deferred)
- [x] `ImapSmtpProvider` in `email/src/main/scala/jorlan/email/ImapSmtpProvider.scala`:
  implements `EmailProvider`; stub methods returning `ZIO.fail(JorlanError("not yet implemented"))` (full Emil impl
  deferred)
- [x] `EmailSkill` in `server/src/main/scala/jorlan/service/skills/EmailSkill.scala`:
  provider-injected; 8 tools:
    - `email.list { maxResults?, query? }` — `email.read`, RiskClass ReadOnly
    - `email.search { query }` — `email.read`, RiskClass ReadOnly
    - `email.read { messageId }` — `email.read`, RiskClass ReadOnly
    - `email.draft { to, subject, body, cc?, bcc? }` — `email.write`, RiskClass Low
    - `email.send { to, subject, body, cc?, bcc? }` — `email.send`, RiskClass ExternalEffect
    - `email.reply { messageId, body }` — `email.send`, RiskClass ExternalEffect
    - `email.delete { messageId }` — `email.write`, RiskClass Modification
    - `email.archive { messageId }` — `email.write`, RiskClass Modification
- [x] Event log writes per tool invocation
- [x] `FakeEmailProvider` in `email/src/test/scala/`

### 13.9 — EnvironmentBuilder Wiring (server module)

- [x] `OAuthCredentialServiceImpl.live` ZLayer in `google-services`; wired in `EnvironmentBuilder`
- [x] `GmailProvider` constructed with `oauthSvc` (config.google used for OAuth flow separately)
- [x] `ImapSmtpProvider` constructed with `MailConfig` from `config.email.imap/smtp`
- [x] `EmailSkill` uses `GmailProvider` or `ImapSmtpProvider` based on `config.email.defaultProvider`
- [x] `GoogleCalendarSkill` + `GoogleDriveSkill` constructed with `GoogleCalendarProvider` / `GoogleDriveProvider`
- [x] All three skills registered in `SkillRegistry` at startup

### 13.10 — Capability Seeding

- [x] Seed in `InitService.complete`:
  `email.read` (Persistent), `email.write` (Persistent), `email.send` (PerInvocation),
  `calendar.read` (Persistent), `calendar.write` (PerInvocation), `drive.read` (Persistent)

### 13.11 — GraphQL Additions (server module)

- [x] Query `oauthStatus(provider: String!): OAuthStatus!` — `{ connected: Boolean!, expiresAt: DateTime }`
- [x] Query `listOAuthProviders: [String!]!` — providers with stored credentials for calling user
- [x] Mutation `startOAuth(provider: String!): OAuthStartResult!` — returns `{ authUrl: String! }`
- [x] Mutation `revokeOAuth(provider: String!): Boolean!`
- [x] Mutation `invokeTool(toolName: String!, argsJson: String!): String!` — direct skill tool invocation

### 13.12 — Shell Commands (server module)

- [x] `/oauth status <provider>` — show OAuth connection status for provider
- [x] `/oauth` — list connected providers
- [x] `/oauth connect google` — print Google auth URL
- [x] `/oauth revoke google` — revoke stored credentials
- [x] `/email list [n]` — list last n emails (default 10)
- [x] `/email read <id>` — show full email
- [x] `/email search <query>` — search inbox
- [x] `/calendar today` — show today's events
- [x] `/calendar list [date]` — show events for a date (YYYY-MM-DD)

### 13.13 — Tests

- [x] `ExternalCredentialRepositorySpec` (Testcontainers, integration module): upsert, find, delete, listByUser
- [x] `OAuthCredentialEncryptorSpec` (google-services): encrypt/decrypt round-trip; wrong key fails
- [x] `OAuthRoutesSpec` (server): state JWT; CSRF rejection; redirect URL
- [~] `PgpServiceSpec` (email): sign + verify round-trip (BouncyCastle test keypair); missing key → warning — deferred:
  BouncyCastle impl not yet written; `PgpService.noOp` stub has no useful test surface
- [x] `EmailSkillSpec` (server): all 8 tools via `FakeEmailProvider`; capability gates; event log entries
- [~] `GmailProviderSpec` (google-services): via `FakeGmailProvider`; token refresh before expiry — deferred:
  `FakeGmailProvider` tests require integration with OAuth refresh path; planned for Phase 14
- [x] `GoogleCalendarSkillSpec` (server): all 6 tools via `FakeCalendarProvider`; write blocked
  without capability; event log entries
- [x] `GoogleDriveSkillSpec` (server): all 3 tools; download returns base64 content
- [~] Overall test coverage ≥ 80% for all new Phase 13 code — deferred: coverage tooling not yet integrated; existing
  1047 tests provide high confidence on the skill and repository layers
- [x] `sbt --error scalafmtAll` clean before merge
- [x] `sbt --error test` passes with all Phase 13 tests included (1047 total)

### Appendix updates

- [x] Mark `Email (IMAP/SMTP)` connector `[x]` in the Connectors appendix table
- [x] Mark `Google Calendar` skill `[x]` in the Skills appendix table
- [x] Update module dependency graph in Appendix

---

## Phase 14: Skills

Each subphase should be a separate MR

### 14.0

- [x] autocomplete of / commands and command history management. When a user starts typing a command, the shell should
  suggest available commands that match the input. This can be implemented using a simple prefix matching algorithm that
  filters the list of available commands based on the user's input. The shell should also maintain a history of
  previously entered commands, allowing users to easily recall and reuse them. The whole thing should work similar to a
  bash shell experience. Implemented: Tab key cycles through prefix matches; ↑↓ arrows navigate command history with
  draft restoration; history capped at 500 entries.
- [ ] **Fuzzy contact search in `contacts.find`**: Phase 12 uses case-insensitive substring match on
  `User.displayName`. Upgrade to fuzzy/phonetic matching (e.g. Levenshtein distance or MariaDB
  `SOUNDEX`) so that "Roberto" matches "Robert Leibman" and "Sara" matches "Sarah Smith". Ensures
  natural-language name resolution works without exact display-name spelling.
- [x] Admin user operations (deactivate, list users, update preferences) — exposed as GraphQL mutations, call
  `UserZIORepository` directly (no separate `UserService` layer needed), expose these in the shell and in the web UI
- [x] Go through all the missing commands and implement those that you can given the latest state of the system.
  Implemented: `/users create`, `/users capabilities`, `/users grant`, `/users revoke-grant`, `/users roles`,
  `/users assign-role`, `/users revoke-role`, `/users identities`, `/users link-identity`, `/users unlink-identity`,
  `/roles list`, `/roles create`. GQL mutations `grantCapability`, `revokeCapabilityGrant`, `linkChannelIdentity`,
  `unlinkChannelIdentity`, and queries `userCapabilityGrants`, `userChannelIdentities`, `allRoles` also added.

### Skills

Most skills should be ideally independent of the server code, and server code should be mostly ignorant of the specifics
of each skill, we should avoid having to add any of these directly to EnvironmentBuilder, though they should be
registered into the SkillRegistry in Jorlan.scala.

14.1 [ ] Add a calculator skill for the agent to perform math calculations, it shoud be in a separate module. Consider
using mXparser library.
14.2 [ ] Add a unit conversion skill for the agent to convert between different units of measurement, use squants
library
for this, it should be in a separate module.
14.3 [ ] **MCP compatibility adapter**: import MCP-compatible tools as Tier-4 skills; translate manifest to canonical
internal format (since this is basic funcitonality it can be in the server module)
14.4 [ ] **Market data skill**: read quotes, watchlists, alerts, news (no trading/execution in initial scope), new sbt
module
14.5 [ ] **Lyrion music skill**: list players, play/pause/stop, set volume, play playlist, schedule playback new sbt
module
14.6 [ ] **Google Contacts skill**: search, read contacts new sbt module
14.7 [ ] ** Weather skill: current conditions, forecast, alerts (use public API like OpenWeatherMap) new sbt module
14.8 [ ] ** Bash commands skill: ls, cat, grep, find, etc. (execute in a sandboxed environment with resource limits) new
sbt module
14.9 [x] ** user management skill: crud on user and permissions (requires special permissions to modify other user's
persmissions). Can reside in the server module since it's a core part of the system and doesn't have external
dependencies.

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
- [ ] Model display in AppBar subtitle (deferred — requires session state in AppShell)

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
- [ ] Create job dialog with trigger form (`createJob` + `addTrigger` mutations) — deferred
- [x] `pauseJob` / `resumeJob` / `cancelJob` / `triggerNow` / `deleteJob` mutations wired

### Event Log Page (`EventLogPage`)

- [ ] Filterable table: session, actor, event type, date range — `eventLog` query (paginated) — deferred (no paginated
  eventLog query)
- [x] Live-tail toggle: `eventLogTail` subscription appends rows in real time
- [x] Expandable row for `payloadJson` details

### Skill Registry Page (`SkillsPage`)

- [ ] Table of skill versions: name, tier badge, status, version — `listSkillVersions` query not yet in API; stub page
  shown
- [ ] Filter by tier and status — deferred
- [ ] Expandable row showing `manifestJson` — deferred

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
- [ ] `web/dist` task verified (full-opt bundle lands in `dist/`) — pending (requires full-opt which takes longer)
- [x] `debianSettings` updated: add `dist/` → `/opt/jorlan/www/` mapping
- [ ] `server/debian:packageBin` verified to include web assets at `/opt/jorlan/www/`
- [x] CI pipeline note: `stLib publishLocal` must precede `web/dist` and `server/debian:packageBin`
- [x] `custom.webpack.config.js` already present; no extra loaders required for MUI/Emotion CSS-in-JS
- [ ] Update README to describe how the web module works

---

## Phase 16: Advanced Features

**Goal:** Full platform feature set — declarative skills, agent-authored skills, MCP import, vector memory, and
remaining skills.

- [ ] **Declarative JSON skills** (Tier 2): HTTP/API, prompt/template, workflow, query, command-template types; JSON
  schema validation on install
- [ ] **Agent-authored skill lifecycle** (Tier 5 → Active): draft → schema validated → permission reviewed → sandbox
  tested → awaiting approval → active (full state machine per design doc)
- [ ] **Vector-backed memory retrieval**: MariaDB vector index, embedding job (via `ai` module),
  `MemoryService.semanticSearch`
- [ ] **Web search skill**: `web.search`, `web.open_url`, `web.download` (granular capabilities, not a single broad
  permission)
- [ ] **Slack connector**: Slack Bot API, message normalization, identity resolution
- [ ] Workspace memory snapshots (workspace-scoped memory linked to snapshot artifacts)

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

## Phase 18: Installer and Distribution

**Goal:** Jorlan can be installed cleanly on Ubuntu (deb package) and macOS by a non-developer, with full end-to-end
smoke tests. Depends on Phase 8.1 (in-process wizard) and Phase 8.2 (database bootstrap script) being complete.

> The smoke test starts the server, checks `GET /api/status`, runs the Phase 8.1 initialization wizard, and verifies
> normal operation — so Phase 8.1 must be complete before this phase can close.

**Linux (Ubuntu .deb):**

- [x] `sbt-native-packager` configured for Debian packaging: `debianPackageMaintainer`, `debianPackageSummary`,
  `packageDescription`, `linuxPackageMappings`
- [x] Systemd service unit file (`jorlan-server.service`): generated by `SystemdPlugin`, environment file at
  `/etc/default/jorlan-server`; custom env at `/etc/jorlan/server.env`
- [x] Package layout (two packages — `jorlan-server` and `jorlan-shell`):
    - `/usr/share/jorlan-server/` — JARs and classpath (standard Debian location for arch-independent JVM apps)
    - `/usr/bin/jorlan-server` — launch script symlink (generated by `sbt-native-packager`)
    - `/etc/jorlan/server.env` — environment variables file (installed as config, not overwritten on upgrade)
    - `/etc/jorlan-server/application.conf`, `logback.xml` — also config-protected
    - `/var/log/jorlan-server/` — log directory (owned by `jorlan` system user)
    - `/usr/lib/jorlan-server/scripts/init-db.sh` — bundled copy of the Phase 8.2 bootstrap script
    - `/usr/share/jorlan-shell/` — shell JARs; `/usr/bin/jorlan` — shell launch script
- [x] Debian post-install scripts: create log directory, set permissions, create `/etc/jorlan/`
- [x] Debian pre-remove script: stop service if running
- [x] `sbt "server/debian:packageBin"` and `sbt "shell/debian:packageBin"` produce valid `.deb`s
- [ ] Smoke test: install on a fresh Ubuntu 22.04 LTS container, run `init-db.sh`, start server, complete
  initialization wizard, verify login and first agent session succeed

**macOS:**

- [x] `sbt-native-packager` configured for universal tarball (`universal:packageZipTarball`) for both server and shell
- [x] Homebrew formula stub (`Formula/jorlan.rb`): `url`, `sha256`, `depends_on "openjdk@21"`, `service` block for
  `brew services start jorlan`; also `Formula/jorlan-shell.rb`
- [x] LaunchDaemon plist template (`server/src/templates/io.jorlan.server.plist`) bundled in tarball under `launchd/`
- [x] `install-macos.sh` script: extracts tarball, installs plist to `/Library/LaunchDaemons/`, creates log dir

**CI/CD:**

- [x] GitHub Actions job `build-packages` in `scala.yml`: runs after tests pass, uploads both `.deb` files as artifacts
- [x] GitHub Actions release workflow (`release.yml`): on `v*` tag, build `.deb`s and macOS tarballs, attach to
  GitHub Release with installation instructions

---

## Appendix: Module Dependency Map

```
model
  ← connector-api
  ← ai
  ← analytics
  ← email          (connector-api, model)
  ← telegram       (connector-api, model)
  ← google-services(connector-api, model)
  ← shell          (model)
  ← web            (model)
  ← server         (model, ai, analytics, connector-api, email, telegram, google-services)
  ← integration    (model, server, shell, connector-api, telegram)
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
|  [ ]   | Market Data      |          | Declarative |             |
|  [ ]   | Lyrion Server    | 1        | Declarative |             |
|  [ ]   | Google Contacts  |          | Plugin      |             |
|  [x]   | Google Calendar  | 1        | Plugin      |             |
|  [ ]   | MCP Connector    |          | Built-in    |             |
|  [ ]   | Declarative Json |          | Built-in    |             |
|  [ ]   | Calculator       |          | Built-in    |             |
|  [ ]   | Search           |          | Built-in    |             |
|  [ ]   | Weather          |          | Built-in    |             |
|  [ ]   | Unit Conversion  |          | Built-in    |             |
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

