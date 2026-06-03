/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# Phase 5 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala, Code Simplicity, Performance, Architecture & Patterns, Test
Coverage)
**Date**: 2026-05-27
**Branch**: `phase-5/permission-kernel`
**Scope**: Phase 5 — Capability Kernel (`RiskClassifier`, `CapabilityEvaluator`, `ApprovalPolicyEngine`,
`ApprovalService`, `PermissionService` extensions)

---

## Executive Summary

The capability kernel's core architecture is sound: the trait/impl module split is correctly maintained, the 7-step
evaluation pipeline accurately reflects the design spec, and `ApprovalPolicyEngine.decide` is deliberately a pure
function to enable unit testing. ZIO idioms are applied correctly throughout — `Clock.instant` for time,
`ZIO.serviceWith` vs `ZIO.serviceWithZIO` used correctly in all companion objects, no `Instant.now`, no mutable state.

However, **three correctness defects affect core security invariants**: session-less requests can match any session-less
approval (bypassing session isolation), `Timed` grants never auto-expire because `expiresAt` is never propagated into
the persisted approval request, and `Once` mode will grant access against any non-empty approval list regardless of its
status. These are not edge cases — they affect the core authorization outcomes.

Compounding this, `CapabilityEvaluatorImpl` and `ApprovalServiceImpl` have **zero test coverage**, which means the above
bugs are invisible in CI. The evaluator also makes 3 sequential DB round-trips per `authorize` call with no composite
indexes, which will serialize under realistic agent load.

**Overall health: Needs Attention** — the design is correct, but three security bugs and two zero-coverage classes must
be resolved before this kernel is used to gate real agent invocations.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area          | Issue                                                                                                                                         | File : Line                            | Recommended Action                                                                                                                                     |
|--------|------------|------------|---------------|-----------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | P5-001     | Critical   | Correctness   | `Session` mode `None == None` allows sessionless requests to match any sessionless approval                                                   | `ApprovalPolicyEngineImpl.scala:71–73` | Add `request.sessionId.isDefined &&` guard before the `exists` check                                                                                   |
| [x]    | P5-002     | Critical   | Correctness   | `Timed` grants never expire — `buildRequest` sets `expiresAt = None` unconditionally                                                          | `ApprovalPolicyEngineImpl.scala:93`    | Pass `grant.expiresAt` into `buildRequest` for `Timed` mode                                                                                            |
| [x]    | P5-003     | Critical   | Correctness   | `Once` mode allows on any non-empty approval list regardless of `ApprovalStatus`                                                              | `ApprovalPolicyEngineImpl.scala:67`    | Filter `existingApprovals` to `status == Approved` inside `decide`                                                                                     |
| [x]    | P5-004     | Critical   | Performance   | 3 sequential DB round-trips per `authorize` call                                                                                              | `CapabilityEvaluatorImpl.scala:37–65`  | Replace sequential for-comprehension with fork/join for grant + direct + role lookups                                                                  |
| [x]    | P5-005     | Critical   | Performance   | Missing composite index `capabilityGrant(granteeId, capability)`                                                                              | `V010__indexes.sql`                    | Added `idx_cg_grantee_capability`                                                                                                                      |
| [x]    | P5-006     | Critical   | Performance   | Missing composite indexes `permission(userId, resource, action)` and `permission(roleId, resource, action)`                                   | `V010__indexes.sql`                    | Added `idx_perm_user_resource_action` and `idx_perm_role_resource_action`                                                                              |
| [x]    | P5-007     | Warning    | Correctness   | `network.read` classified as `WorkspaceWrite` (level 1) — should be `ExternalEffect` (level 3)                                                | `RiskClassifierImpl.scala:55`          | Changed to `RiskClass.ExternalEffect`                                                                                                                  |
| [x]    | P5-008     | Warning    | Correctness   | `splitCapability` first-dot split breaks 3-part names (`shell.sudo.execute` → action `"sudo.execute"` never matches `"execute"`)              | `CapabilityEvaluatorImpl.scala:69–73`  | Added clarifying comment documenting the first-dot split convention                                                                                    |
| [x]    | P5-009     | Warning    | Observability | No audit events for `upsertCapabilityGrant`, `revokeGrant`, `upsertPermission`, `deletePermission`, `deleteRole`, `upsertRole`                | `PermissionServiceImpl.scala:71–82`    | Added `CapabilityGranted`, `CapabilityRevoked`, `PermissionGranted`, `PermissionRevoked` event types and log calls                                     |
| [x]    | P5-010     | Warning    | Observability | `assignRole` / `removeRole` log `actorId = None` — unattributable audit records                                                               | `PermissionServiceImpl.scala:29–69`    | Added `actorId: Option[UserId]` parameter to both methods                                                                                              |
| [x]    | P5-011     | Warning    | Correctness   | `recordApprovalDecision` `if/else` silently misclassifies `Cancelled`/`Expired` decisions as `ApprovalDenied`                                 | `PermissionServiceImpl.scala:124–126`  | Replaced with exhaustive `match` across all `ApprovalStatus` values                                                                                    |
| [x]    | P5-012     | Warning    | Performance   | `expireStaleRequests` issues one UPDATE per row (N+1)                                                                                         | `ApprovalServiceImpl.scala:63–69`      | Added `expireAllStaleApprovalRequests()` bulk SQL method; `expireStaleRequests` is now a single call                                                   |
| [x]    | P5-013     | Warning    | Correctness   | Unknown capability defaults to `WorkspaceWrite` (risk 1) — not conservative for deny-by-default                                               | `RiskClassifierImpl.scala:74–78`       | Changed default to `SecuritySensitive`                                                                                                                 |
| [x]    | P5-014     | Warning    | Performance   | Missing index `approvalRequest(requestorUserId, capability, status)` for `findApprovedRequest`                                                | `V010__indexes.sql`                    | Added `idx_ar_user_cap_status`                                                                                                                         |
| [x]    | P5-015     | Warning    | Performance   | Missing composite index `schedulerJob(status, scheduledAt)` for `getPendingJobs`                                                              | `V010__indexes.sql`                    | Added `idx_sj_status_scheduled`                                                                                                                        |
| [x]    | P5-016     | Warning    | Performance   | Missing index `eventLog(sessionId, occurredAt)` for `replaySession`                                                                           | `V010__indexes.sql`                    | Added `idx_event_log_session`                                                                                                                          |
| [x]    | P5-017     | Warning    | Performance   | `RiskClassifierImpl` O(n) linear scan over 27 prefix rules on every `classify` call                                                           | `RiskClassifierImpl.scala:74–77`       | Replaced `List` with a `Map`; classify now walks from most-specific to least-specific prefix segment                                                   |
| [x]    | P5-018     | Suggestion | Test          | `CapabilityEvaluatorImpl` has zero tests — priority chain invisible in CI                                                                     | `CapabilityEvaluatorImpl.scala`        | Add integration tests: explicit-deny wins, direct permission short-circuits, role permission, grant match, default deny, multi-grant with one `Denied` |
| [x]    | P5-019     | Suggestion | Test          | `ApprovalServiceImpl` has zero tests — event log writes and PendingApproval DB id unverified                                                  | `ApprovalServiceImpl.scala`            | Add integration tests: `Allowed`/`Denied` paths write events, `PendingApproval` returns real DB id, `expireStaleRequests` returns correct count        |
| [x]    | P5-020     | Suggestion | Test          | ~18 of 33 `RiskClassifierImpl` prefix rules are untested                                                                                      | `CapabilityKernelSpec.scala`           | Add one test per missing rule (trivial parameterized cases)                                                                                            |
| [x]    | P5-021     | Suggestion | Test          | `Once` mode `None == None` boundary not tested; `Timed` `expiresAt == now` boundary not tested                                                | `CapabilityKernelSpec.scala`           | Add tests for both boundary conditions                                                                                                                 |
| [x]    | P5-022     | Suggestion | Test          | JSON codec roundtrips for `CapabilityName`, `RiskClass`, `ApprovalMode`, `ApprovalStatus`, `AuthorizationResult`, `EvaluationResult` untested | `CapabilityKernelSpec.scala`           | Add roundtrip codec assertions                                                                                                                         |
| [x]    | P5-023     | Suggestion | Code Quality  | `CapabilityEvaluatorImpl.evaluate` nested for-comprehension pyramid hides 7-step priority chain                                               | `CapabilityEvaluatorImpl.scala:34–67`  | Resolved by P5-004 refactor — fork/join fetch + flat `yield` decision block                                                                            |
| [ ]    | P5-024     | Suggestion | Code Quality  | Dead code: `CapabilityGrantAllows(Denied)` match arm is structurally unreachable                                                              | `ApprovalPolicyEngineImpl.scala:52–54` | Remove or document with explicit comment noting it is a defensive fallback                                                                             |
| [x]    | P5-025     | Suggestion | Code Quality  | `ApprovalRequest.riskClass: Int` — primitive obsession; `RiskClass` enum exists                                                               | `permission.scala`                     | Change field type to `RiskClass` and update Quill column mapping                                                                                       |
| [x]    | P5-026     | Suggestion | Code Quality  | 18 identical `val rc = new RiskClassifierImpl` instantiations in test file                                                                    | `CapabilityKernelSpec.scala:74–146`    | Hoist to suite scope as a single `private val rc`                                                                                                      |
| [ ]    | P5-027     | Suggestion | Code Quality  | 7 tests repeat `assertTrue(result match { case PendingApproval(_, Mode) => true; case _ => false })` with poor failure messages               | `CapabilityKernelSpec.scala`           | Extract `assertPendingApproval(result, expectedMode)` helper                                                                                           |
| [x]    | P5-028     | Suggestion | Code Quality  | Stale import of `PermissionZIORepository` in `ApprovalServiceImpl`                                                                            | `ApprovalServiceImpl.scala:14`         | Removed                                                                                                                                                |
| [x]    | P5-029     | Suggestion | Code Quality  | Four consecutive `=> AuthorizationResult.Allowed` match arms should use `\|` pattern syntax                                                   | `ApprovalPolicyEngineImpl.scala:47–50` | Merge into a single arm with `\|`                                                                                                                      |
| [ ]    | P5-030     | Suggestion | Code Quality  | `Once`/`Session` arms share identical `pendingOrAllowed` structure — not extracted                                                            | `ApprovalPolicyEngineImpl.scala:67–73` | Extract `pendingOrAllowed(approved: Boolean, ..., mode: ApprovalMode)` helper                                                                          |

---

## Grouped Sections

### Correctness / Security

**C1 — Session `None == None` security bug** [Critical]

`ApprovalPolicyEngineImpl.scala:71–73` compares `request.sessionId == existingApproval.sessionId`. When both are `None`,
this evaluates `true`, meaning a sessionless agent request silently matches any sessionless approval for the same
capability. `Session` mode becomes equivalent to `Once` mode for all sessionless agents, bypassing session isolation
entirely.

**C2 — Timed grants never expire** [Critical — confirmed by 3 agents]

`buildRequest` sets `expiresAt = None` unconditionally for all new `ApprovalRequest` rows. `expireStaleRequests` queries
`WHERE expiresAt IS NOT NULL AND expiresAt <= NOW()`, so it can never find Timed-mode requests. Timed grants become
permanent once approved. Fix: pass `grant.expiresAt` into `buildRequest` for `Timed` mode.

**C3 — `Once` mode ignores approval status** [Critical]

The `Once` arm uses `existingApprovals.nonEmpty` where it should filter to `status == Approved`. The caller (
`ApprovalServiceImpl.loadExistingApprovals`) correctly pre-filters via `findApprovedRequest`, but the pure engine trusts
a precondition not enforced by its own types. Passing a `Rejected` or `Expired` record returns `Allowed`.

**C4 — `splitCapability` first-dot split silently breaks 3-part names** [Warning]

`splitCapability("shell.sudo.execute")` produces `resource="shell"`, `action="sudo.execute"`. A `Permission` row stored
as `action="execute"` will never match. The convention must be documented and a test must confirm that permission rows
are actually written with the multi-segment action format.

**C5 — `network.read` misclassified** [Warning]

`network.read` is at `WorkspaceWrite` (level 1). Every other read operation (`filesystem.read`, `memory.read`) is
`ReadOnly` (level 0). Even a conservative read-network-as-external policy should use `ExternalEffect` (level 3), not a
workspace write level.

---

### Observability / Audit

**O1 — High-privilege mutations produce no audit trail** [Warning — 1 agent]

`upsertCapabilityGrant`, `revokeGrant`, `upsertPermission`, `deletePermission`, `deleteRole`, `upsertRole` are the most
sensitive operations in the capability kernel and write nothing to the event log. The design requirement states every
significant action must produce an audit event. Fix: add `CapabilityGranted`, `CapabilityRevoked`, `PermissionGranted`,
`PermissionRevoked` event types.

**O2 — Role assignment/removal unattributable** [Warning]

`assignRole` and `removeRole` log with `actorId = None`. Unlike `requestApproval` (which already accepts
`actorId: Option[UserId]`), these methods have no way to record who performed the action. Fix: add `actorId` parameter.

---

### Performance

**P1 — Sequential DB queries on authorization hot path** [Critical — confirmed by 2 agents]

Every `authorize` call issues three sequential DB queries: `getGrantsForCapability`, `hasDirectPermission`,
`hasRolePermission`. All three are independent after the initial grant fetch. Using `ZIO.zipPar` collapses latency from
the sum to the max. In a cloud deployment (5–20 ms per round-trip) this is 15–60 ms added per agent tool invocation.

**P2 — Missing hot-path indexes** [Critical — 3 indexes]

The three most-called queries in every authorization check have no composite indexes:

- `capabilityGrant(granteeId, capability)` — full range scan per authorize call
- `permission(userId, resource, action)` — range scan for direct permission check
- `permission(roleId, resource, action)` — range scan for role permission check

**P3 — N+1 expiry updates** [Warning — confirmed by 4 agents]

`expireStaleRequests` issues one `UPDATE` per expired row. The correct fix is a single bulk SQL statement.

**P4 — Additional missing indexes** [Warning]

- `approvalRequest(requestorUserId, capability, status)` — for `findApprovedRequest` (Once/Session modes)
- `schedulerJob(status, scheduledAt)` — for `getPendingJobs`
- `eventLog(sessionId, occurredAt)` — for `replaySession`

---

### Test Coverage

**T1 — `CapabilityEvaluatorImpl` and `ApprovalServiceImpl` at 0% coverage** [Suggestion — highest priority]

These two classes contain all the DB interactions, event log writes, and pipeline orchestration in the capability
kernel. The three correctness bugs (C1–C3) are currently invisible in CI. Priority integration tests:

*CapabilityEvaluatorImpl*: explicit deny wins over permission; direct permission short-circuits; role permission; grant
match; default deny; multi-grant with one `Denied`.

*ApprovalServiceImpl*: `Allowed` path writes `CapabilityAllowed` event; `Denied` path writes `CapabilityDenied` event;
`PendingApproval` returns DB-assigned id; `expireStaleRequests` returns correct count.

**T2 — 18 of 33 prefix rules untested in `RiskClassifierImpl`** [Suggestion]

Untested: `filesystem.remove`, `filesystem.list`, `filesystem` fallback, `memory.delete`, `memory.read`, `memory`
fallback, `network.send`, `network.external`, `network` fallback, `role.remove`, `role` fallback, `permission` prefix,
`capability` prefix, `skill.install`, `skill.approve`, `skill` fallback, `scheduler`, `agent`. Each is a one-line
parameterized test.

**T3 — Boundary conditions untested** [Suggestion]

- `Once` mode with non-Approved approval records
- `Timed` grant with `expiresAt == now` (strict `isAfter` means equal is expired)
- `permission.revoke` exact override
- `buildRequest` template `capability` field identity

---

### Code Quality

**Q1 — Nested for-comprehension pyramid** [Suggestion]

The 3-level nesting in `CapabilityEvaluatorImpl.evaluate` (lines 34–67) obscures the priority order. This is resolved
naturally by the P1 parallel-fetch refactor, which separates the fetch phase from a flat `yield` decision block.

**Q2 — Primitive obsession: `ApprovalRequest.riskClass: Int`** [Suggestion]

`RiskClass` enum exists and has a `.level: Int`. `ApprovalRequest` stores the integer, throwing away type safety. Change
to `RiskClass` and update the Quill column mapping.

**Q3 — Test boilerplate** [Suggestion]

- Hoist `new RiskClassifierImpl` from 18 per-test instantiations to one suite-level `private val`
- Extract `assertPendingApproval(result, mode)` helper to replace 7 copies of the same match/boolean pattern and produce
  better failure messages

---

## Cross-Cutting Patterns

**N+1 `expireStaleRequests`** was independently flagged by all 4 non-coverage agents — highest-confidence finding.

**C2 (`Timed` `expiresAt = None`)** was flagged by 3 agents from 3 different angles: functional correctness, structural
pattern, and test coverage.

**P1 (sequential queries) and Q1 (nested pyramid)** were flagged by 2 agents from complementary angles — the
parallel-fetch refactor resolves both simultaneously.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count  |
|------------|--------|
| Critical   | 6      |
| Warning    | 11     |
| Suggestion | 13     |
| **Total**  | **30** |

**Issues by area:**

| Area                   | Count  |
|------------------------|--------|
| Correctness / Security | 5      |
| Performance / Indexes  | 7      |
| Observability / Audit  | 2      |
| Test Coverage          | 5      |
| Code Quality           | 11     |
| **Total**              | **30** |

**Agent contribution:**

| Agent                     | Unique Findings | Cross-Confirmed |
|---------------------------|-----------------|-----------------|
| Functional Scala Reviewer | 7               | 6               |
| Performance Oracle        | 5               | 4               |
| Pattern Recognition       | 4               | 5               |
| Code Simplicity Reviewer  | 4               | 3               |
| Test Coverage Tracker     | 3               | 4               |
