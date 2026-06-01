---
name: phase8-perf
description: Phase 8 agent session runtime performance findings — OllamaModelGateway, SessionHub, AgentRunner, SubscriptionClient, JorlanAPI
metadata:
  type: project
---

## Findings (phase-8/agent-session-runtime, 2026-05-29)

### Critical: OllamaModelGateway sessions map never shrinks
- `OllamaModelGateway.sessions` (Ref[Map[AgentSessionId, StreamAssistant]]) grows without bound.
- `terminateSession` in `AgentSessionManagerImpl` calls `sessionHub.remove(id)` but does NOT call
  any eviction on `ModelGateway`. No `invalidateSession` method exists on `ModelGateway`.
- Each entry holds a `StreamAssistant` which wraps an `OllamaStreamingChatModel` AND an
  `InMemoryChatMemoryStore` (up to 1000 messages). Under long-running deployments this is an
  unbounded heap leak.
- Fix: add `invalidateSession(id)` to `ModelGateway` trait; call it from `terminateSession`
  alongside `sessionHub.remove(id)`.

### Critical: Non-atomic get+update in SessionHub and OllamaModelGateway
- Both `SessionHub.getOrCreate` and `OllamaModelGateway.getOrCreate` use a
  `Ref.get.flatMap { ... Ref.update(...) }` pattern. Under concurrent `submitMessage` calls for
  the same session, two fibers can both observe `None`, both create a new Hub/StreamAssistant,
  and the second write overwrites the first. For SessionHub this leaks an active Hub (subscribers
  on the first hub receive no further messages). For OllamaModelGateway this leaks a
  StreamAssistant and resets the chat memory window.
- Fix: use `Ref.modify` (returns old entry if present, creates+inserts otherwise, atomically).

### Warning: AgentRunner ensuring block fires on error — event log says Completed when run failed
- `AgentRunnerImpl.processMessage` logs `AgentResponseCompleted` only on the happy path (lines 77-89
  after the model stream). But the `.ensuring { sessionHub.publish(finished sentinel) }` fires
  unconditionally — so the downstream subscriber always receives the sentinel, even if the model
  call failed mid-stream. The `AgentResponseCompleted` event log entry is NOT written on error
  (it's outside the ensuring block), which is actually correct for the event log. The only
  problem is that the sentinel being published on error could mask the error from subscribers.
- Fix: acceptable for Phase 8 if error handling is deferred to Phase 12, but document clearly.
  For production, publish a `ResponseChunk(sessionId, errMsg, finished=true, error=true)` on
  the error path.

### Warning: forkDaemon swallows errors in submitMessage
- `JorlanAPI.scala:322` — `processMessage(...).forkDaemon` detaches the fiber from the request.
  Any `JorlanError` from `AgentRunner.processMessage` is lost: it surfaces neither to the HTTP
  client nor to any structured error log. The GraphQL mutation always returns `Unit` success to
  the caller even if the runner immediately fails.
- Fix: wrap in `forkDaemon` but attach a `.tapError(e => ZIO.logError(...))` before forking, so
  errors surface in structured logs. Consider a separate error field in the subscription stream
  for client-visible failure.

### Warning: SubscriptionClient creates a new HttpClientZioBackend per subscription call
- `SubscriptionClient.agentResponseStream` calls `HttpClientZioBackend.scoped()` inside
  `ZStream.unwrapScoped` on every invocation. This is the same P7-001 pattern found in Phase 7:
  a new Java 11 HttpClient is allocated per call, spinning up a new thread pool.
  For WebSocket subscriptions (long-lived) the one-time allocation cost is less severe than
  short-lived HTTP calls, but if the shell client opens and closes sessions repeatedly the
  churn adds up.
- Fix: pass a shared `SttpBackend` constructed at shell startup and injected as a dependency,
  following the same fix recommended for Phase 7.

### Warning: ensureDefaultAgent does a full paginated table scan on every createSession call
- `AgentSessionManagerImpl.ensureDefaultAgent` calls `agentRepo.search(AgentSearch())` which
  issues `SELECT … LIMIT 20` with no name filter. The result is a List[Agent] which is then
  searched in-memory with `.find(_.name == defaultAgentName)`.
- At 20 agents this is fine; above 20 (or if the default agent happens to sort after position 20)
  the method silently creates a duplicate default agent on every session creation.
- Fix: add a `name: Option[String]` filter to `AgentSearch` and the Quill query, or cache the
  default agent ID in a `Ref[Option[AgentId]]` after the first successful lookup.

### Info: OllamaStreamingChatModel instantiated per session, not shared
- `OllamaModelGateway.getOrCreate` builds a NEW `OllamaStreamingChatModel` for every session.
  Each model instance has its own HTTP client and connection pool toward the Ollama server.
  With many concurrent sessions this creates N parallel HTTP connection pools.
- The model itself is stateless (memory is in ChatMemory); a single shared model instance is safe.
- Fix: construct `OllamaStreamingChatModel` once in `OllamaModelGateway.live` and share it.
  Only `StreamAssistant` (which carries per-session memory) needs to be per-session.

### Info: java.time.Instant.now() in ensuring block — purity issue
- `AgentRunner.processMessage` reads `Clock.instant` before and after the model call, which is
  correct. `OllamaModelGateway.streamedResponse` also uses `Clock.instant` inside its ensuring
  block — this is fine, ZIO Clock is pure. No bare `java.time.Instant.now()` calls were found
  in Phase 8 code.

### Info: listSessions resolver calls getSession(AgentSessionId.empty)
- `JorlanAPI.scala:231` — the `listSessions` resolver calls
  `getSession(AgentSessionId.empty).map(_.toList)` which retrieves at most one session with the
  empty/zero ID, not a list of sessions for the authenticated user. This is almost certainly a
  placeholder bug rather than a performance issue, but it means listSessions always returns
  an empty list or a single session.

### Info: Queue.unbounded in SubscriptionClient
- `SubscriptionClient.agentResponseStream` creates a `Queue.unbounded` for WS frames. If the
  consumer is slow and the server sends many tokens quickly, the queue grows without bound in
  the shell process. Under normal token-streaming throughput this is fine, but could be an issue
  if the consumer blocks (e.g., slow Lanterna rendering). A `Queue.bounded(4096)` with back-
  pressure would be safer.

**How to apply:** When reviewing Phase 9+, check that ModelGateway exposes eviction hooks
called from terminateSession. Verify Ref.modify is used for all concurrent get-or-create
patterns. Confirm shared model instances are not duplicated per-session.

---

## Phase 8.3/server-personality findings (branch phase-8.3/server-personality, 2026-06-01)

### Resolved from Phase 8: OllamaModelGateway shared model
- `sharedModel: StreamingChatLanguageModel` is now constructed once in `live` and shared across all sessions.
- `sessions` map now stores `(StreamAssistant, String)` (assistant + systemPrompt) to support prompt-change invalidation.

### Regression introduced: non-atomic get+update in OllamaModelGateway.getOrCreate
- The Phase 8 fix used `Ref.modify` (atomic). Phase 8.3 reverted to `sessions.get.flatMap { ... sessions.update(...) }`.
- The class-level ScalaDoc still says "all reads/writes use Ref.modify" — this comment is now wrong.
- Under concurrent `submitMessage` calls for the same session with the same systemPrompt, two fibers can
  both observe `Some(existing, samePrompt)` on the first branch and return the same assistant (which is
  fine). But under the _rebuild_ branch (prompt changed or missing), both fibers see the `_` case, both
  call `buildAssistant`, and the second `sessions.update` silently overwrites the first — leaking the
  first `StreamAssistant` and resetting that session's chat memory.
- Fix: use `Ref.modify` to atomically check-and-insert; only call `buildAssistant` inside the modify
  if the entry is absent or stale.

### SessionHub.getOrCreate: correctly uses Ref.modify — no regression.

### New: Personality.buildSystemPrompt called on every processMessage — string allocation in hot path
- `AgentRunnerImpl.processMessage` calls `personalityService.get()` (Ref.get — cheap) then
  `Personality.buildSystemPrompt(personality)` on every message.
- `buildSystemPrompt` allocates a new String on every call via `List(...).mkString`. The personality
  object is server-wide and changes rarely (admin action only).
- The same `systemPrompt` String is then passed into `OllamaModelGateway.getOrCreate`, which stores it
  in the sessions map and compares it with `storedPrompt == systemPrompt`. This means `String.==` is
  called on the full prompt text on every message — not just reference equality.
- At the default prompt length (~300 chars) this is negligible per call. At scale (100+ concurrent
  sessions, high message throughput) the allocations and comparisons accumulate. Low severity at
  current expected load.
- Fix: cache the built prompt in a second Ref[String] inside PersonalityServiceImpl, invalidated on
  `update()`. `processMessage` would read the cached String directly (no rebuild).

### New: InitClient.checkStatus — no timeout configured, blocks login on slow server
- `initialisePostLogin` (called once at login and on reconnect in `connectionHeartbeat`) calls
  `InitClient.checkStatus(serverUrl)`. The underlying `basicRequest` has no explicit read timeout.
  sttp's `HttpClientZioBackend` defaults to no read timeout (infinite).
- If the server is accepting TCP connections but is slow to respond (e.g., still starting up),
  the shell hangs at the connected/connecting status bar indefinitely. The `.orElse(ZIO.succeed(serverUrl))`
  fallback only fires on a hard connection error, not on a slow-response hang.
- `connectionHeartbeat` is NOT affected (it calls `AuthClient.whoAmI`, not `InitClient.checkStatus`).
  `initialisePostLogin` is called only once per login sequence, making this a startup concern rather
  than a per-message hot path.
- Fix: add `.readTimeout(5.seconds)` to the `basicRequest` in `InitClientImpl.checkStatus`.

### Resolved from Phase 8: invalidateSession
- `ModelGateway.invalidateSession` now exists and is called from `AgentSessionManagerImpl.terminateSession`.
  The sessions map leak from Phase 8 is resolved (pending confirming AgentSessionManagerImpl wiring).
