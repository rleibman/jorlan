/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# Phase 6 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (SRS/SDD Conformance, Functional Scala, Code Simplicity, Performance, Architecture
& Patterns, Test Coverage)
**Date**: 2026-05-27
**Branch**: `phase-3/graphql-api`
**Scope**: Phase 6 — GraphQL API Skeleton (`JorlanAPI`, `JorlanRoutes`, `JorlanSession` wiring, `PermissionService`
extensions, `GraphQLApiSpec` integration tests)

---

## Executive Summary

The GraphQL API layer is structurally correct: Caliban schema derivation is applied cleanly, the queries/mutations/
subscriptions split follows the design spec, and the JWT authentication middleware from Phase 4 is correctly reused.
ZIO idioms are consistent — `Clock.instant` for time, no mutable state, no `null`, no thrown exceptions in domain code.

However, **two defects affect core security and architectural invariants**. The `JorlanSession` environment type is
threaded through every resolver but never read, meaning the Phase 5 capability kernel is completely bypassed — any
authenticated session can call `createUser`, `grantPermission`, or `assignRole` without restriction. Separately,
`JorlanApiEnv` directly names a `db`-module type (`UserZIORepository`) at the GraphQL layer, violating the stated
architecture contract and propagating the coupling into routes, the main entry point, and integration tests. These are
not edge cases — they are active architectural faults that must be resolved before Phase 7 begins.

A third cluster of bugs affects data integrity and audit correctness: `updateUser` silently overwrites `createdAt` on
every update, `createUser`/`updateUser` bypass the event log entirely, and `recordApprovalDecision` maps
`ApprovalStatus.Pending` to `EventType.ApprovalDenied` — a domain invariant violation.

**Overall health: Critical Issues — not ready to advance to Phase 7.** Four blockers (no auth enforcement, db-layer
coupling, event log bypass, invariant violation in approval decision recording) must be resolved first.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area                  | Issue                                                                                                              | File : Line                              | Recommended Action                                                                                                                                   |
|--------|------------|------------|-----------------------|--------------------------------------------------------------------------------------------------------------------|------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | P6-001     | Critical   | Security              | `JorlanSession` never read in any resolver; `CapabilityEvaluator` never called — all mutations are ungated         | `JorlanAPI.scala` (all resolvers)        | Wire `JorlanSession` into each resolver; gate mutations through `CapabilityEvaluator` before any repository call                                     |
| [x]    | P6-002     | Critical   | Architecture          | `JorlanApiEnv = UserZIORepository & PermissionService` — `db`-module type directly in GraphQL layer               | `JorlanAPI.scala:37`                     | Introduce `UserService` trait in `model`; replace `UserZIORepository` in `JorlanApiEnv` with `UserService`                                          |
| [x]    | P6-003     | Critical   | Observability         | `createUser` / `updateUser` call `UserZIORepository` directly — event log is never written for user mutations      | `JorlanAPI.scala:152–167`                | Route through `UserService` which writes `UserCreated`/`UserUpdated` events alongside the repository call                                           |
| [x]    | P6-004     | Critical   | Correctness           | `recordApprovalDecision`: `ApprovalStatus.Pending` maps to `EventType.ApprovalDenied` — domain invariant violated | `PermissionServiceImpl.scala:193–196`    | `Pending` must `ZIO.fail` with a descriptive error; it should never reach `recordApprovalDecision`                                                  |
| [x]    | P6-005     | Warning    | Data Integrity        | `updateUser` overwrites `createdAt` with current timestamp on every update                                         | `JorlanAPI.scala:161–167`                | Fetch existing user first; preserve `existing.createdAt`; pass `now` only for `updatedAt`                                                           |
| [x]    | P6-006     | Warning    | Correctness           | `GrantPermissionInput` allows `userId = None` and `roleId = None` simultaneously — orphaned permission rows       | `JorlanAPI.scala:63–68, 183–196`         | Validate in resolver before calling service; `ZIO.fail` if both are `None`                                                                          |
| [x]    | P6-007     | Warning    | Observability         | `assignRole` / `revokeRole` pass `actorId = None` — all GraphQL-originated role mutations are unattributable      | `JorlanAPI.scala:173–182`                | Extract acting user from `JorlanSession` and pass as `actorId` once P6-001 is resolved                                                              |
| [x]    | P6-008     | Warning    | Observability         | `assignRole` event records `roleId` but not `userId` — "which roles was user Y assigned?" unanswerable from log   | `PermissionServiceImpl.scala:39–51`      | Include `userId` in `payloadJson` of `RoleAssigned`/`RoleRevoked` events                                                                            |
| [x]    | P6-009     | Warning    | Performance           | `users`, `roles(userId)`, `permissions(userId)` have no pagination — silently truncate at 20 rows                 | `JorlanAPI.scala:74–78, 134–149`         | Add `page: Int` / `pageSize: Int` arguments to all list resolvers; document the default limit                                                        |
| [x]    | P6-010     | Warning    | Performance           | `findApprovedRequest` missing `.take(1)` in Quill query — scans entire table for first matching row                | `QuillRepositories.scala` (approval query) | Add `.take(1)` to the Quill query                                                                                                                    |
| [x]    | P6-011     | Warning    | Code Quality          | `.mapError(identity)` no-ops throughout permission resolvers; `.mapError(JorlanError(_))` double-wraps user calls | `JorlanAPI.scala:133, 136, 140, 144, 149` | Remove all `.mapError(identity)` calls; remove `JorlanError(_)` wraps (`RepositoryError extends JorlanError`)                                       |
| [x]    | P6-012     | Warning    | Code Quality          | `QuickAdapter(interp)` constructed twice in the same `routes` call                                                 | `JorlanRoutes.scala:34, 36`              | Extract `val adapter = QuickAdapter(interp)`; use `adapter.handlers.api` and `adapter.handlers.webSocket`                                           |
| [x]    | P6-013     | Warning    | Code Quality          | `private val interpreter` holds an unexecuted `ZIO` — naming implies an already-materialised interpreter           | `JorlanRoutes.scala:28`                  | Rename to `makeInterpreter` or inline directly in `routes`                                                                                           |
| [x]    | P6-014     | Warning    | Code Quality          | `ZIO.attempt(JorlanAPI.api.render)` wraps a pure expression — implies a side effect that does not exist            | `GraphQLApiSpec.scala:37`                | Change to `ZIO.succeed(JorlanAPI.api.render)`                                                                                                        |
| [x]    | P6-015     | Warning    | Observability         | `@@ printErrors` writes to `System.out`, bypassing the ZIO logging pipeline                                        | `JorlanAPI.scala:207`                    | Replace with `ZIO.logError` wrapper or remove; `printErrors` is invisible to structured logs                                                         |
| [x]    | P6-016     | Warning    | Code Quality          | `CalibanError` flattened to `new RuntimeException(e.toString)` — loses structured error information                | `Jorlan.scala:48`                        | Use `.orDie` for startup-time schema validation errors                                                                                               |
| [x]    | P6-017     | Suggestion | Performance           | Missing DB indexes: `userRole(userId, roleId)`, `permission(userId)`, `permission(roleId)`                         | New Flyway migration required            | Add `idx_user_role_user_role`, `idx_perm_user`, `idx_perm_role` in `V014__graphql_indexes.sql`                                                      |
| [x]    | P6-018     | Suggestion | Test                  | 7 missing integration test cases: `updateUser`, `revokeRole`, not-found paths (user/role), `grantPermission` with `roleId`, `revokePermission` return value | `GraphQLApiSpec.scala` | Add one test per gap using the existing suite structure                                                                                               |
| [x]    | P6-019     | Suggestion | Test                  | `extractLongField` returns `0L` on miss — causes silent false-positive test passes                                 | `GraphQLApiSpec.scala:160–167`           | Return `Option[Long]` or throw `AssertionError`; update callers to fail the test explicitly on miss                                                  |
| [x]    | P6-020     | Suggestion | Code Quality          | `interpreter.orDie` repeated 9× across all tests — interpreter created anew for every test                         | `GraphQLApiSpec.scala` (all tests)       | Lift interpreter construction to suite scope via `ZLayer`; inject once                                                                              |
| [x]    | P6-021     | Suggestion | API Design            | Bare `Long =>` resolver args expose as `value` in SDL — `user(value: Long)`, `roles(value: Long)` are ambiguous    | `JorlanAPI.scala:73–78`                  | Replace bare `Long` args with named single-field `case class` inputs (e.g. `UserIdInput(id: Long)`)                                                 |
| [x]    | P6-022     | Suggestion | Code Quality          | No `ArgBuilder` instances for opaque ID types — future gap if IDs are used directly as resolver arguments          | `JorlanAPI.scala:97–106`                 | Add `ArgBuilder[UserId]`, `ArgBuilder[RoleId]`, etc. alongside the existing `Schema` instances                                                       |
| [x]    | P6-023     | Suggestion | Test                  | `PermissionRepositorySpec.getRole` not-found case untested                                                         | `PermissionRepositorySpec.scala`         | Add test: `getRole` with a non-existent ID returns `None`                                                                                            |

---

## Grouped Sections

### Security / Authorization

**S1 — Authorization never enforced** [Critical — confirmed by 2 agents]

`JorlanSession` appears in every resolver's ZIO environment type but no resolver body ever calls
`ZIO.service[JorlanSession]`. The Phase 5 `CapabilityEvaluator`, `ApprovalPolicyEngine`, and all associated decision
machinery are completely bypassed at the GraphQL layer. Any request carrying a valid JWT can invoke
`grantPermission`, `assignRole`, or `createUser` without any capability check.

The fix requires two steps: (1) extract the calling user from `JorlanSession` in each resolver; (2) call
`CapabilityEvaluator.authorize` before any state-mutating repository call. This can be phased — extract the
session user first (which also unblocks P6-007), then add per-resolver gates.

---

### Architecture

**A1 — GraphQL layer directly depends on `db`-module type** [Critical — confirmed by 2 agents]

`JorlanApiEnv = UserZIORepository & PermissionService`. `UserZIORepository` extends
`UserRepository[RepositoryTask]` and lives in the `db` module. Its presence in the GraphQL layer type means
`JorlanRoutes.routes` return type, `Jorlan.scala`'s `JorlanEnvironment`, and `GraphQLApiSpec`'s layer
construction all acquire a direct dependency on a `db`-module concrete type. The stated architecture principle
requires the GraphQL layer to depend only on service-layer interfaces.

The fix is to introduce a `UserService` trait in `model/service/` alongside the existing `PermissionService`,
implement it in `server/service/`, and wire event-log writes there. This simultaneously resolves P6-003.

---

### Correctness / Data Integrity

**C1 — `updateUser` overwrites `createdAt`** [Warning — confirmed by 4 agents]

The `updateUser` resolver constructs `User(UserId(input.id), ..., now, now, input.active)`, passing `now`
for both `createdAt` and `updatedAt`. Every update silently destroys the original creation timestamp. Whether
the Quill `upsert` statement excludes `createdAt` from the `UPDATE` clause depends entirely on implementation
detail — the resolver should not construct a `createdAt` it does not own.

Fix: fetch the existing user with `getById`, fail with a not-found error if absent, then pass
`existing.createdAt` into the updated `User`.

**C2 — `GrantPermissionInput` permits an ownerless permission** [Warning]

Both `userId` and `roleId` are `Option[Long]`. A caller can submit `grantPermission(resource: "x", action: "y")`
with neither, producing a `Permission` row with `userId = NULL` and `roleId = NULL`. Depending on permission
lookup logic, this can accumulate as garbage data or (in a wider-match implementation) grant access to all
identities. Validate at the resolver boundary before calling the service.

**C3 — `recordApprovalDecision` domain invariant violation** [Critical]

`PermissionServiceImpl.scala:193–196` maps `ApprovalStatus.Pending` to `EventType.ApprovalDenied`. A pending
decision is not a denial; writing a `ApprovalDenied` event for an in-flight request corrupts the audit trail.
`recordApprovalDecision` should never be called with `Pending` — this branch should `ZIO.fail` with a
descriptive error to surface the caller contract violation.

---

### Observability / Audit

**O1 — User mutations produce no event log entries** [Critical]

`createUser` and `updateUser` call `UserZIORepository.upsert` directly, skipping any service layer. No
`UserCreated` or `UserUpdated` event is written. The design requirement states every significant action must
produce an audit event. This is resolved structurally by the A1 fix (introducing `UserService`).

**O2 — Role mutations unattributable** [Warning]

`assignRole` and `revokeRole` pass `actorId = None` to `PermissionService`. The event log records that a role
was assigned but not who did it. This is unblocked by the S1 fix — once `JorlanSession` is read, the acting
user's ID is available and can be passed as `actorId`.

**O3 — `assignRole` event missing subject** [Warning]

The `RoleAssigned` event records `resource = Some(roleId)` but stores no `userId`. The audit query "which
roles did user Y receive?" can only be answered by reading current DB state, not the append-only log. Include
`userId` in `payloadJson`.

---

### Performance

**P1 — Unbounded list queries** [Warning]

`users`, `roles(userId)`, and `permissions(userId)` expose no pagination arguments. Caliban's Quill integration
truncates at the repository's default fetch limit (20). Callers have no way to page through larger result sets,
and the silent truncation is invisible in the GraphQL schema. Add `page` / `pageSize` arguments.

**P2 — `findApprovedRequest` full-table scan** [Warning]

The Quill query for `findApprovedRequest` lacks a `.take(1)`. The database evaluates the full predicate across
all matching rows before returning. Add `.take(1)` to short-circuit on the first match.

**P3 — Missing indexes for GraphQL-driven queries** [Suggestion]

The `userRole` and `permission` tables have no indexes on the join columns exercised by the new GraphQL
queries. Under realistic agent load each `roles(userId)` or `permissions(userId)` call scans the full table.
Add a `V011__graphql_indexes.sql` migration with `idx_user_role_user_role`, `idx_perm_user`, `idx_perm_role`.

---

### Test Coverage

**T1 — Seven missing integration test cases** [Suggestion]

| Missing Test | Gap |
|---|---|
| `updateUser` mutation | Completely untested — including the `createdAt` overwrite bug (P6-005) |
| `revokeRole` mutation | Completely untested |
| `user(id)` not-found path | Returns `null`; never asserted |
| `role(id)` not-found path | Returns `null`; never asserted |
| `grantPermission` with `roleId` | Only `userId` target is tested |
| `revokePermission` return value | Result is ignored in the current test |
| `PermissionRepositorySpec.getRole` | Not-found case untested |

**T2 — `extractLongField` silent false-positives** [Suggestion — confirmed by 4 agents]

`extractLongField` returns `0L` when the regex fails to match. Subsequent queries then use `id: 0`, which
silently returns `None` from the repository. Tests assert `result.errors.isEmpty` and pass, but prove nothing
about the entity that was supposed to be created. Replace the `getOrElse(0L)` fallback with a thrown
`AssertionError` or return `Either[String, Long]`.

---

### Code Quality / API Design

**Q1 — `.mapError` inconsistency** [Warning — confirmed by 3 agents]

User-path resolvers call `.mapError(JorlanError(_))` on `UserZIORepository` results. This double-wraps a
`RepositoryError` (which already extends `JorlanError`) inside a new generic `JorlanError`, discarding the
original subtype. Permission-path resolvers call `.mapError(identity)`, which is a no-op. Both forms add noise
without adding safety. Remove all `.mapError(identity)` calls and the `JorlanError(_)` wraps on user calls.

**Q2 — `QuickAdapter` constructed twice** [Warning — confirmed by 3 agents]

`JorlanRoutes` constructs `QuickAdapter(interp)` twice in the same expression — once for `handlers.api` and
once for `handlers.webSocket`. Extract to `val adapter = QuickAdapter(interp)`.

**Q3 — API argument naming** [Suggestion]

Bare `Long =>` resolver functions expose as `value` in the generated SDL: `user(value: Long!)`,
`roles(value: Long!)`, `permissions(value: Long!)`. A GraphQL consumer reading the schema has no indication
what `value` represents. Replace with named single-field input case classes.

---

## Cross-Cutting Patterns

**P6-005 (`updateUser` overwrites `createdAt`)** was independently flagged by all 4 non-coverage agents —
highest-confidence finding in this phase.

**P6-019 (`extractLongField` 0L sentinel)** was also flagged by 4 agents — equally high confidence; the test
utility undermines the correctness of all 6 dependent tests.

**P6-011 (`.mapError` inconsistency)** and **P6-012 (`QuickAdapter` duplication)** were each flagged by 3
agents from complementary angles.

**P6-001 (no authorization) and P6-002 (db-layer coupling) share a common root**: the absence of a
`UserService` service-layer interface. Introducing `UserService` resolves P6-002 structurally, unblocks P6-003,
and provides the natural extension point for wiring P6-001 authorization checks.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count  |
|------------|--------|
| Critical   | 4      |
| Warning    | 12     |
| Suggestion | 7      |
| **Total**  | **23** |

**Issues by area:**

| Area                    | Count  |
|-------------------------|--------|
| Security / Authorization | 1     |
| Architecture             | 1     |
| Correctness / Data       | 3     |
| Observability / Audit    | 3     |
| Performance              | 3     |
| Test Coverage            | 3     |
| Code Quality / API       | 9     |
| **Total**               | **23** |

**Agent contribution:**

| Agent                      | Unique Findings | Cross-Confirmed |
|----------------------------|-----------------|-----------------|
| SRS/SDD Conformance        | 8               | 5               |
| Functional Scala Reviewer  | 6               | 8               |
| Pattern Recognition        | 4               | 7               |
| Code Simplicity Reviewer   | 3               | 5               |
| Performance Oracle         | 3               | 2               |
| Test Coverage Tracker      | 2               | 4               |
