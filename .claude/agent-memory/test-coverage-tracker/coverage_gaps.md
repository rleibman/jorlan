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

## Patterns
- No error-path tests anywhere in integration layer (constraint violations, duplicate keys, etc.)
- `ChannelType` values Shell, Telegram, Slack, Email, WhatsApp, Sms, GraphQL never appear in `fromProvider` tests (they return None — currently untested)
- `OrchestratorIdentity` domain type has no tests whatsoever
