---
name: coverage-gaps
description: Known untested areas by package as of 2026-05-27 (~75% aggregate scoverage)
metadata:
  type: project
---

## Package Coverage Summary (2026-05-27)
- jorlan (model/domain): 34.72% — 265 statements
- jorlan.auth: 0% — 59 statements (JorlanAuthServer.scala entirely untested)
- jorlan.db: 37.90% — 248 statements
- jorlan.db.repository: 36.44% — 483 statements (QuillRepositories.scala)
- jorlan.domain: 25.23% — 214 statements
- jorlan.service: 100% — 45 statements

## Critical Untested Areas
1. `JorlanAuthServer` — entire auth layer at 0%: login, changePassword, createOAuthUser, linkOAuthToUser, userByPK, userByOAuthProvider, createUser (expected failure), sendEmail (noop), activateUser (noop)
2. `UserZIORepository.login` / `changePassword` — security-critical SQL logic never integration-tested
3. `RepositoryError.apply(Throwable)` — SQLTransient/SQLNonTransient branching untested

## High Priority Untested Areas
1. `jorlan.domain` enums JSON roundtrip — ApprovalMode, ApprovalStatus, SessionStatus, MessageRole, EventType, MemoryScope, SkillTier, SkillStatus, ConnectorType, JobStatus, TriggerType, ChannelType (non-fromProvider values) all lack codec tests
2. `MemorySearch` scoped by Workspace/Private/Agent — only User and Shared scopes tested
3. Search pagination (page > 0) — `page` parameter not exercised in any search test
4. `MemoryRepository.getById` — never called in tests
5. `AgentRepository` — session `getSession` only called once; `getById` for agent not tested standalone

## Medium Priority
1. `EventLogFilter` pagination (page > 0 in integration)
2. `SkillRepository` — `getConnector(id)` tested but standalone `getById(SkillId)` not tested in isolation
3. `quillUtil.scala` — Quill encodings for PublicKey, SemVer, URI, MediaType (exercised indirectly but coverage is low)
4. `configuration.scala` — AppConfig / DataSourceConfig parsing
5. `FlywayMigration.scala` — not directly tested (exercised implicitly by container startup)

## Phase 5 Capability Kernel — Unit Test Gaps (2026-05-27)

### RiskClassifierImpl — untested capabilities
- `permission.revoke` exact override (Privileged) — in exactOverrides but no test
- `filesystem.remove.*` prefix (Destructive) — only `filesystem.delete` tested
- `filesystem.list.*` prefix (ReadOnly) — untested
- `filesystem.*` fallback prefix (WorkspaceWrite) — untested
- `memory.delete.*` prefix (Destructive) — only `memory.forget` tested
- `memory.read.*` prefix (ReadOnly) — only `memory.search` tested
- `memory.*` fallback prefix (WorkspaceWrite) — untested
- `network.send.*` / `network.external.*` (ExternalEffect) — untested
- `network.*` fallback prefix (WorkspaceWrite) — untested
- `role.remove.*` (Privileged) — untested; `role.*` fallback (Privileged) untested
- `permission.*` prefix (Privileged) — `permission.grant` tested as exact override but not prefix fallback
- `capability.*` prefix (SecuritySensitive) fallback — untested (only `capability.grant` exact override tested)
- `skill.install`, `skill.approve` (ExternalEffect) — untested
- `skill.*` fallback (WorkspaceWrite) — untested
- `scheduler.*` prefix (WorkspaceWrite) — untested
- `agent.*` prefix (WorkspaceWrite) — untested
- `shell.sudo.*` prefix (SecuritySensitive) — `shell.sudo.execute` tested but `shell.sudo.anything` prefix fallback not
- Boundary: name exactly equal to prefix with no trailing dot (e.g. `"shell"` alone — no test)
- Boundary: empty string capability name — no test
- Boundary: name that is a strict prefix of a registered prefix (e.g. `"shel"`) — no test

### ApprovalPolicyEngineImpl — untested scenarios
- `Once` mode: existingApprovals present but all have `ApprovalStatus.Rejected` / `Pending` / `Expired` — currently the check is `.nonEmpty`, so a rejected approval incorrectly grants access; no test exposes this
- `Session` mode: request has `sessionId = None` — both sides of the match are untested with null session
- `Timed` mode: expiry instant exactly equal to `now` (boundary — `isAfter` is strict, so this falls to PendingApproval; no test for the boundary)
- `buildRequest` fields: `capability` in template uses `request.capability` not `grant.capability` — no test where they differ to prove the request field is copied correctly
- `buildRequest` fields: `scopeJson` comes from `grant.scopeJson` — not tested with a non-None scope
- `Denied` mode reason string content — test only checks `isInstanceOf`, not the reason text
- `DefaultDeny` reason string content — same issue

### CapabilityEvaluatorImpl — integration test gaps (no unit tests at all)
- Happy path: explicit deny grant present → returns ExplicitDeny
- Happy path: direct user permission present → returns ResourcePermissionAllows
- Happy path: role permission present (no direct) → returns RolePermissionAllows
- Happy path: non-denied grant present (no direct/role permission) → returns CapabilityGrantAllows
- Happy path: nothing matches → returns DefaultDeny
- Multiple grants including a Denied one — ordering check (Denied wins regardless of other grants)
- `splitCapability` for no-dot name — resource = full name, action = "use"
- `splitCapability` for multi-dot name — only first dot is the split point

### ApprovalServiceImpl — integration test gaps (no unit tests at all)
- Full `authorize` pipeline for all seven EvaluationResult paths
- PendingApproval result: persisted template has id replaced by saved record id
- Allowed result: CapabilityAllowed event is written to event log
- Denied result: CapabilityDenied event is written to event log
- `loadExistingApprovals` short-circuits to Nil for all non-Once/Session evaluation results (no DB call)
- `recordDecision` delegates to permissionService.recordApprovalDecision
- `expireStaleRequests` sums expired counts across multiple records
- `expireStaleRequests` with empty result set → returns 0

## Patterns
- No error-path tests anywhere in integration layer (constraint violations, duplicate keys, etc.)
- `ChannelType` values Shell, Telegram, Slack, Email, WhatsApp, Sms, GraphQL never appear in `fromProvider` tests (they return None — currently untested)
- `OrchestratorIdentity` domain type has no tests whatsoever
