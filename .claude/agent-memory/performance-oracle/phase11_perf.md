---
name: phase11-perf
description: Phase 11 Telegram connector performance findings — resolveOrCreateSession in-memory filter, chatRef not in AgentSessionSearch, sequential update dispatch, ZLayer.succeed per-call, multipart copy, Instant.now() in pure fn, startAll sequential
metadata:
  type: project
---

## Findings (phase-11/telegram, 2026-06-07)

### Critical: resolveOrCreateSession filters chatRef in memory after full user-session scan
- `MessageIngressImpl.resolveOrCreateSession` calls `searchSessions(AgentSessionSearch(userId=Some(uid), pageSize=100))`
  then calls `.find(s => s.chatRef.contains(chatRef) && ...)` in Scala memory.
  `AgentSessionSearch` has no `chatRef` field so the DB never filters it. If a user has > 100 sessions the
  correct session may not even appear in the result window. A composite DB filter on `(userId, chatRef, status)` is needed.
  Fix: add `chatRef: Option[String]` to `AgentSessionSearch` and push it into the Quill query;
  the V024 index `idx_agent_session_chat_ref(userId, chatRef)` already exists to cover this.

### Warning: ZIO.foreach (sequential) used to dispatch updates in poll loop
- `TelegramConnectorSkill.pollLoop` line 166: `ZIO.foreach(filtered) { update => ... }` dispatches each
  update to `ingress.receive` sequentially. A batch of 10 updates (Telegram long-poll default) processes
  one after another, including the full agent round-trip for each. Switch to `ZIO.foreachPar` with a
  reasonable parallelism bound (`ZIO.foreachParN(4)(...)`) so concurrent updates from different chats
  do not serialize on a single fiber.

### Warning: ZLayer.succeed(client) allocated on every HTTP call
- `TelegramApiClientLive.run` (line 93): `effect.provide(ZLayer.succeed(client))` constructs a new
  `ZLayer` (with associated allocation) on every `getUpdates`, `sendMessage`, `sendPhoto`, `sendDocument` call.
  The `client` is already a field; use `effect.provideEnvironment(ZEnvironment(client))` which is
  allocation-free, or restructure to avoid the layer wrapping entirely.

### Warning: ConnectorManager.startAll starts connectors sequentially
- `ConnectorManagerImpl.startAll` (line 36): `ZIO.foreachDiscard(connectors)` starts each connector
  sequentially. Each `c.start` forks its own daemon and returns quickly, so this is a minor concern at
  current scale (one connector). Once Phase 12 adds many connectors, use `ZIO.foreachParDiscard` to
  start them concurrently.

### Info: Multipart body built via Array concatenation (triple allocation)
- `TelegramApiClientLive.sendPhoto` (line 152) and `sendDocument` (line 176) construct the multipart body
  as `headerStr.getBytes("UTF-8") ++ photo ++ footerStr.getBytes("UTF-8")`. Each `++` copies the entire
  accumulated array. For large photos/documents this means three array allocations (header copy, photo copy,
  footer copy) instead of one. Use a `ByteArrayOutputStream` or `Chunk` concatenation to avoid the
  intermediate copies.

### Info: java.time.Instant.now() called inside a pure function
- `TelegramMessageNormalizer.normalizeMessage` (line 52): `receivedAt = java.time.Instant.now()` is called
  inside what is presented as a pure `Option`-returning function. This bypasses ZIO's clock abstraction,
  making the timestamp non-deterministic in tests and preventing TestClock usage. Pass `receivedAt` as a
  parameter or thread `Clock.instant` through the caller.

### Info: pageSize=100 hard-coded in resolveOrCreateSession is brittle
- `MessageIngressImpl.resolveOrCreateSession` line 95: `pageSize = 100` is a magic constant. A user with
  > 100 sessions will silently fail to find their existing chatRef session and create a duplicate instead.
  Even with the chatRef filter pushed to the DB the pageSize should be 1 (fetching only the matching row).
