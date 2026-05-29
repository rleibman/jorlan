---
name: shell-perf-phase7
description: Phase 7 shell module performance findings — HTTP client lifecycle, render loop, Ref batching, scroll reset
metadata:
  type: project
---

## Findings (phase-7/shell-interface branch, 2026-05-28)

### Critical: HTTP backend recreated per request
- `AuthClientImpl` and `GraphQLClientImpl` both declare `private def backend = HttpClientZioBackend()`
  as a `def` not a `val`. Every call to `login`, `whoAmI`, and `execute` allocates a new
  `HttpClient` (Java 11 HttpClient), spinning up a new thread pool and connection pool.
  Used inside `ZIO.scoped` so the resource is released after each request — correct lifecycle
  management, but with huge overhead. Fix: share a single `HttpClientZioBackend` created once
  at layer construction time and passed in as a `ZLayer.scoped` resource.

### Warning: per-frame full message re-expansion
- `expandMessages` in `LanternaScreen.drawFrame` (JorlanScreen.scala line 257) is called every
  33ms on the full `Vector[MessageEntry]`. Includes word-wrap (`wordWrap`) for every message.
  Worst case: 2000 messages × word-wrap allocations × 30fps = high GC pressure.
  Fix: cache `(msgs.size, width)` → `expandedLines` in a Ref; recompute only when either changes.

### Warning: scroll reset on every addMessage
- `addMessage` calls `scrollOffset.set(0)` unconditionally (JorlanScreen.scala line 142).
  If messages arrive while user is scrolled up, the view jumps to bottom on every new message.
  Fix: only reset scroll when `scrollOffset` is already 0 (user is at bottom).

### Info: 6 sequential Ref reads per frame
- `oneFrame` reads messages, inputBuf, statusText, inputPrompt, modeText, scrollOffset in sequence.
  ZIO Refs are already in-memory and cheap, but 6 separate fiber checkpoints per frame is
  unnecessary. Fix: consolidate into a single `Ref[ScreenState]` and do one `get`.

### Info: ZIO.blocking per frame
- `oneFrame` calls `ZIO.blocking { ... }` every 33ms. The ZIO blocking thread pool is fixed —
  `ZIO.blocking` itself only submits to a different executor and is cheap. Not a meaningful issue.

### Info: heartbeat connection accumulation
- `connectionHeartbeat` calls `AuthClient.whoAmI` every 30s. With the `def backend` bug, each
  heartbeat also creates and destroys a new HttpClient. Once the HTTP backend is fixed to a
  shared val, the 30s interval is safe — 2 connections/minute is negligible.

### Info: showStatus query leaks all user IDs
- `CommandHandler.showStatus` executes `{ users { id } }` to test connectivity (line 89-91).
  No pagination — will return all user IDs. Acceptable for a status ping but should use a
  lighter probe (e.g., an introspection query or a dedicated ping field) in production.

**How to apply:** When reviewing future HTTP client code in this project, always verify that
backends are `val`s constructed at layer time, not `def`s called per request.
