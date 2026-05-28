---
name: graphql-perf-phase6
description: Phase 6 GraphQL API performance findings — resolvers, pagination, indexes, blocking
metadata:
  type: project
---

## Findings (phase-3/graphql-api branch, 2026-05-27)

### Missing GraphQL pagination arguments
- `users` query: calls `UserSearch()` with no caller-supplied page/pageSize args — hardcoded to page=0, pageSize=20
- `roles(userId)`: calls `RoleSearch(userId)` with no caller-supplied pagination args — hardcoded pageSize=20
- `permissions(userId)`: calls `PermissionSearch(userId=Some(...))` with no caller-supplied pagination args — hardcoded pageSize=20
- All three queries silently cap at 20 rows with no way for clients to paginate

### Missing indexes on permission table
- `searchPermissions` with only `userId` filter: uses `idx_perm_user_resource_action` (userId, resource, action) — a leading-column prefix scan is usable, but there is NO dedicated `idx_perm_user` single-column index. Under MySQL/MariaDB the composite index is usable for userId-only scans via prefix, so this is acceptable but worth monitoring.
- `searchRoles` JOIN on `userRole(userId, roleId)` then JOIN `role(id)` — the PK of `userRole` is `(userId, roleId)` which covers this query well.

### getRole — PK lookup, OK
- `getRole(id)` filters on `role.id` which is the PRIMARY KEY — O(1) index lookup, no issue.

### Unbounded getPendingJobs in SchedulerRepository
- `getPendingJobs` has no LIMIT — returns all pending+overdue jobs in one query. At high job volumes this becomes unbounded. Not in GraphQL API path but noted as a systemic pattern.

### findApprovedRequest missing LIMIT 1
- `findApprovalRequest` loads all matching rows and then calls `.headOption` in Scala rather than using `.take(1)` in the Quill query. MariaDB will scan and transfer all matching rows before the JVM discards them.

### approvalRequest table: missing `sessionId` index
- `findApprovedRequest` filters on `(capability, requestorUserId, status, sessionId)`. The V010 index covers `(requestorUserId, capability, status)` but not sessionId — when sessionId is non-null MariaDB must post-filter.

### No blocking operations in resolver chain
- All Quill calls go through `exec()` which uses the Hikari pool directly; ZIO-JDBC uses blocking I/O under the hood. Pool is configured from AppConfig (maximumPoolSize/minimumIdle are config-driven, not hardcoded) — acceptable.
- No `Unsafe.unsafe` or `ZIO.blocking` misuse found.

**How to apply:** When adding new GraphQL queries, always include `page: Int, pageSize: Int` arguments. When adding queries that filter on non-leading index columns, check V010__indexes.sql for coverage.
