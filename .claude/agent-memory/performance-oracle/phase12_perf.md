---
name: phase12-perf
description: Phase 12 Built-in Skills performance findings — ContactsSkill N+1, in-memory name filter, unindexed search, blocking I/O unwrapped, allToolSpecs double-traversal, connector linear scan, Instant.now() in pure code, token Vector append, loadPersonality uncached
metadata:
  type: project
---

# Phase 12 Built-in Skills Performance Findings

## Critical

- **ContactsSkill:117 N+1** — `contactFind` calls `getChannelIdentities` inside `ZIO.foreach` on every matched user. With N matches this is N+1 DB queries. No batch API exists; fix is a JOIN or parallel fetch + result limit.
- **ContactsSkill:115 full-table scan** — `repo.user.search(UserSearch(pageSize=200))` fetches 200 rows, then filters in-memory with `.contains`. There is no name-filter parameter in `UserSearch`. Fix: add a `nameSubstring` filter to `UserSearch` so the LIKE is pushed to SQL.
- **WorkspaceSkill:128-131,143-148,182-186 blocking I/O not wrapped** — `Files.readAllBytes`, `Files.write`, `Files.deleteIfExists`, `Files.walk` are called inside `ZIO.attempt` (not `ZIO.attemptBlocking`). On a shared fiber pool these block the executor thread.

## Warning

- **SkillRegistryLive:91 allToolSpecs double-traversal** — calls `allTools` (iterates map, flatMaps tools) then `.map` again to convert each ToolDescriptor. Single pass is straightforward.
- **NotificationRouterImpl:120 linear connector scan per notification** — `connectorManager.connectors.find(_.connectorType == connType)` is O(n) on a List. Negligible today (≤5 connectors) but should be a Map.
- **AgentRunnerImpl:368 Instant.now() in pure code** — `runCheckpoint` calls `java.time.Instant.now()` directly instead of `Clock.instant`. Untestable; leaks wall-clock calls from ZIO effect system.
- **AgentRunnerImpl:407 loadPersonality uncached** — DB round-trip on every `processMessage` call. Should be cached with a short TTL or invalidated on setting change (same issue noted in Phase 9).

## Suggestion

- **AgentRunnerImpl:142-143 Vector append in hot path** — `chunksRef.update(_ :+ chunk)` is O(n) amortized O(1) for Vector, acceptable, but the final `chunks.mkString` in `finaliseResponse` is O(total-chars). No structural fix needed, just awareness.
- **NotifySkill:113 ChannelType.values linear scan** — `ChannelType.values.find(_.toString.equalsIgnoreCase(s))` called per invocation. Acceptable for small enum; same pattern in ContactsSkill.
