---
name: phase85-perf
description: Phase 8.5 manual-testing performance findings — SessionHub redesign, ConversationLogger MDC, AgentRunnerImpl token accumulation, SubscriptionClient, logRequests, appendToLastMessage
metadata:
  type: project
---

## Findings (phase-8.5/manual-testing, 2026-06-02)

### Critical: SessionHub.publish is sequential across all subscribers — sequential foreachDiscard
- `SessionHub.publish` uses `ZIO.foreachDiscard(entries)(_.queue.offer(chunk).unit)`.
  With N concurrent subscribers for the same session, offers are made one-by-one in sequence.
  If a subscriber's sliding queue is full and the offer itself yields, throughput to all later
  subscribers in the list is blocked for that cycle. For a single-shell deployment this is fine;
  for multi-subscriber scenarios (Phase 10+ with browser tabs), use `ZIO.foreachParDiscard`.

### Critical: appendToLastMessage calls messages.init on every token — O(n) Vector rebuild
- `LanternaScreen.appendToLastMessage` (JorlanScreen.scala ~line 175) calls `s.messages.init`
  on every token chunk. `Vector.init` is O(n) — it rebuilds the vector without the last element.
  With a long response (1000 tokens) and a message history of 2000 entries, each token call does
  an O(2000) allocation. Fix: store a separate `currentAgentBuffer: StringBuilder` in ScreenState,
  flushed to `messages` only on a kind-change or explicit flush, avoiding the repeated init call.

### Warning: AgentRunnerImpl accumulates all response tokens in a Vector[String] in-memory
- `AgentRunnerImpl.processMessage` (line 40) allocates `Ref.make(Vector.empty[String])` and
  appends every LLM token with `tokensRef.update(_ :+ chunk)`. At the end it calls
  `tokens.mkString` to produce the full response for `ConversationLogger`. For a long response
  (thousands of short tokens), this is an unbounded in-memory accumulation of small String
  objects whose combined size matches the full response. The `Ref.update(_ :+ chunk)` pattern
  also creates a new Vector on each update (O(1) amortised but under GC pressure at high token
  rates). Fix: use a `StringBuilder`-based Ref or accumulate directly in the ensuring block's
  scan, keeping only a rolling buffer.

### Warning: ConversationLogger copies the full MDC map on every log call — unnecessary allocation
- `ConversationLogger.withMdc` calls `MDC.getCopyOfContextMap` (returns a `java.util.HashMap`)
  then `MDC.setContextMap(prev)` in the finally block on every `logUserMessage` /
  `logAgentResponse` call. MDC.getCopyOfContextMap allocates a new HashMap copy each time.
  Because this runs inside `ZIO.succeed`, it executes on the ZIO thread pool without blocking,
  but each call still triggers a HashMap allocation. In a high-throughput multi-session scenario
  this adds GC pressure.
  Fix: since the ZIO fiber model does not propagate MDC across fiber hops anyway (MDC is
  thread-local, not fiber-local), the save-and-restore is only needed if this code runs on a
  shared thread that might already have an MDC. A simpler fix is to use ZIO's structured logging
  annotations (`ZIO.logAnnotate("sessionId", ...)`) instead of SLF4J MDC entirely, which is
  fiber-safe and allocation-free.

### Warning: logRequests wrapper logs the full GraphQL query body at DEBUG level on every request
- `JorlanAPI.logRequests` (JorlanAPI.scala ~line 76) logs `request.query.getOrElse("")` on
  every single GraphQL request including subscriptions. Subscription queries can be long; mutation
  bodies for updatePersonality include full prompt text. At DEBUG level in production this is fine,
  but the string interpolation `s"GraphQL ...: ${request.query.getOrElse("")}"` always constructs
  the full message string regardless of whether the logger is enabled. In ZIO's `ZIO.logDebug`
  the message is a by-name parameter so it IS lazily evaluated — no issue here. This is safe.

### Warning: SessionHub.subscribe — second Ref.get after Ref.update to log subscriber count
- `SessionHub.subscribe` (line 53-55) calls `subs.get.map(_.getOrElse(...).size)` immediately
  after `subs.update(...)` to log the subscriber count. This is a redundant second lock
  acquisition on the Ref. Fix: `Ref.updateAndGet` returns the new value, then read `.size` from
  the returned map entry without a second Ref access.

### Info: drain in CommandHandler is stack-safe via ZIO's trampolining
- The recursive `def drain` pattern in `CommandHandler.handleMessage` (line 74-83) is a
  tail-recursive ZIO composition. ZIO trampolines effect chains so this does not blow the stack
  regardless of token count. No issue.

### Info: loadOrCreateSession scans full listSessions response on every login
- `JorlanShell.loadOrCreateSession` calls `listSessions()` (page=0, pageSize=20 default) and
  then does `.find(_.status == SessionStatus.Active)` in memory. At 20 sessions this is trivial.
  If the active session happens to be outside the first 20 (sorted by some other order), the
  client silently creates a new session instead of resuming. Same root cause as the Phase 8
  ensureDefaultAgent issue — filter should be pushed to the server query.

### Resolved from Phase 8/8.3:
- SubscriptionClient backend is now a shared `val` constructed at layer time — P7-001 fixed.
- `SessionHub` has moved from Hub to per-connection Queue with sliding strategy — subscriber
  isolation is correct.
- `OllamaModelGateway.sharedModel` singleton — confirmed fixed in 8.3, no regression here.

**How to apply:** When reviewing Phase 9+:
- Check whether `appendToLastMessage` gets a StringBuilder-based optimisation before the
  token-streaming path becomes a hot path.
- Verify `ZIO.foreachParDiscard` is used in `SessionHub.publish` once multi-subscriber support
  is added.
- Track whether `ConversationLogger` migrates to ZIO structured logging annotations.
