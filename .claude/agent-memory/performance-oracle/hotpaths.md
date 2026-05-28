---
name: hotpaths
description: Known hot paths in Jorlan and their DB index coverage
metadata:
  type: project
---

## GraphQL Resolver Hot Paths (per-request)

| Resolver | SQL Pattern | Index Coverage |
|---|---|---|
| `user(id)` | PK lookup on `user.id` | PRIMARY KEY — OK |
| `users` | Full `user` scan with optional active filter, LIMIT 20 | No index on `active`; acceptable at small scale |
| `role(id)` | PK lookup on `role.id` | PRIMARY KEY — OK |
| `roles(userId)` | JOIN `userRole(userId,roleId)` → `role(id)` | PK `(userId,roleId)` covers lookup — OK |
| `permissions(userId)` | Filter on `permission.userId` | `idx_perm_user_resource_action` prefix-usable — OK, monitor |

## Capability Evaluator Hot Path (per agent invocation)

| Query | Index |
|---|---|
| `getGrantsForCapability(userId, capability)` | `idx_cg_grantee_capability` — OK |
| `hasDirectPermission` | `idx_perm_user_resource_action` — OK |
| `hasRolePermission` | `idx_perm_role_resource_action` — OK |
| `findApprovedRequest` | `idx_ar_user_cap_status` (missing sessionId post-filter) |

## Scheduler Hot Path (periodic)

| Query | Index |
|---|---|
| `getPendingJobs` | `idx_sj_status_scheduled` — OK but unbounded result set |

## Event Log

| Query | Index |
|---|---|
| `replaySession(sessionId, limit)` | `idx_event_log_session` — OK |
| `search(filter)` | No composite index on (eventType, agentId, occurredAt) — potential full scan under filter combos |
