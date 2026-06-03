---
name: phase85-findings
description: Phase 8.5 (session connection redesign, conversation logging, version check, shell improvements) conformance review (2026-06-02)
metadata:
  type: project
---

## Phase 8.5 Conformance Review (2026-06-02)

Branch: phase-8.5/manual-testing

### Critical Issues

None identified.

### Major Issues

**`AgentSessionManagerImpl` injects `SessionHub` but never uses it**
- `AgentSessionManagerImpl` constructor takes `sessionHub: SessionHub` and its `live` URLayer declares `SessionHub` as a required dependency
- No method in the class body calls `sessionHub.*` anywhere
- `SessionHub` was previously used to tear down session queues; under the redesign, queue lifecycle is fully managed by `subscribe`'s `ensuring` block — `AgentSessionManager` no longer needs to touch `SessionHub` at all
- Fix: remove `sessionHub` from the constructor, remove `SessionHub` from the `live` URLayer's dependency intersection

**`setPersonalityField` in `CommandHandler` fetches personality then patches it locally — no atomicity**
- When a user runs `/personality set <field> <value>`, the handler reads the current personality via `serverPersonality` query, patches one field locally, then calls `updatePersonality`. Between the read and the write, another admin could update the personality, causing a lost-update.
- This is not a Phase 8.5 requirement per se, but the design doc (Phase 8.3) describes a "field-level update" that implies atomicity. The implementation is a read-modify-write without any optimistic concurrency.
- The Phase 8.3 review marked the update path as missing; this branch adds it. The atomicity gap is a residual design issue worth flagging.

### Minor Issues

**`ConversationLogger` uses `scala.language.unsafeNulls` import**
- `ConversationLogger.scala` imports `scala.language.unsafeNulls` to handle `MDC.getCopyOfContextMap` returning nullable `java.util.Map`. This is correct practice under `-Yexplicit-nulls` — but the `Option(...)` wrapper around `MDC.getCopyOfContextMap` already handles null safely. The `unsafeNulls` import is broader than necessary; a narrower `Option(MDC.getCopyOfContextMap)` approach without the import would also work.
- Low risk; the current approach is safe.

**`VersionCheck.check` semver rule: client patch must be >= server patch**
- The version compatibility rule in `VersionCheck.check` requires `c.patch >= s.patch` (client patch ≥ server patch). This is stricter than typical SemVer compatibility rules (which require only major.minor equality).
- If a server is upgraded from patch 3 to patch 5, a shell at patch 4 is rejected even though both are the same minor version and both are typically backward-compatible.
- The roadmap does not specify the exact version-check rule, so this is a design choice; flag for discussion rather than a violation.

**`loadOrCreateSession` in `JorlanShell` does not interrupt old fiber on session replacement**
- `loadOrCreateSession` forks a subscription fiber but does not interrupt any previously active session's fiber before forking a new one (unlike `handleNewSession` which correctly calls `ls.subscriptionFiber.interrupt`)
- This is the startup path (first call) so in practice there is no prior fiber; the gap only matters if `loadOrCreateSession` were ever called twice.
- Low risk at current call sites, but worth aligning with the invariant stated in design doc section 5 point 1 ("A second /new tears down the previous fiber first").

**`fakeSubscriptionClient` in `CommandHandlerSpec` returns `ZStream.empty` for all sessions**
- Tests that cover the happy-path message drain (submitting a message and reading tokens from the queue) depend on the real queue in `ShellState` receiving items — but since `fakeSubscriptionClient` returns an empty stream, `handleMessage` tests that exercise the drain loop are not covered.
- `CommandHandlerSpec` has no test that asserts `handleMessage` actually delivers tokens to the screen when the queue has content.
- This is a test coverage gap, not a functional violation. The spec (roadmap Phase 8.5) does not prescribe `CommandHandlerSpec` content, but testing policy is >80% coverage.

### Missing Requirements (per roadmap/design-doc)

**`CommandHandlerSpec` missing: drain-from-queue test for `handleMessage`**
- `doc/session-connection-redesign.md` section 4 lists `shell/src/test/scala/jorlan/shell/CommandHandlerSpec.scala` as a file to update: "Update `handleMessage` tests for the new drain-from-queue pattern."
- The spec file acknowledges this in the files-to-change table. The test exists (`Message without active session prompts to start one`) but the case where a session IS active and tokens flow from queue is missing.

**No integration test for end-to-end session + streaming round-trip**
- The Phase 8 roadmap line (still open checkbox): "Integration test: full round-trip using `FakeModelGateway`, asserting each chunk arrives in order"
- This was not implemented in Phase 8 and remains open in Phase 8.5.

### Open Design Issues Encountered

- `JorlanSession.connectionId` field noted in design doc section 3.3 as "available for future use (admin 'who is connected' queries)" but not yet load-bearing. Implementation is consistent with this deferred stance.

### Conformant Aspects

**`SessionHub` full redesign matches `session-connection-redesign.md` exactly:**
- `Ref[Map[AgentSessionId, List[SubscriberEntry]]]` — exactly as specified in section 3.1
- `SubscriberEntry(connectionId, queue)` — exact field names from spec
- `subscribe(sessionId, connectionId): UIO[ZStream[...]]` — eagerly registers queue, returns stream; `ensuring` block removes only the specific `(sessionId, connectionId)` pair — matches spec invariant 4
- `Queue.sliding(1024)` — matches spec "Queue capacity" requirement exactly
- `publish(chunk)` broadcasts to all entries for `chunk.sessionId`, no-op if empty — matches spec
- Removed `getOrCreate(sessionId)` and `remove(sessionId)` — matches "Removed methods" in spec section 3.1

**`AgentRunner` trait updated correctly:**
- `subscribeToSession(sessionId, connectionId)` added with both parameters as specified in section 3.2
- `processMessage` no longer calls `getOrCreate` — matches spec requirement

**`JorlanAPI` subscription resolver mints fresh `ConnectionId` per call:**
- `ConnectionId.randomZIO` is called inside the subscription resolver — matches section 3.3 exactly
- Not threaded from `JorlanSession` — matches the design rationale

**`ShellState` `LiveSession` case class matches design exactly:**
- `sessionId: AgentSessionId`, `tokenQueue: Queue[Either[String, Option[ResponseChunk]]]`, `subscriptionFiber: Fiber[Nothing, Unit]` — exact field types from spec section 3.4
- `Ref[Option[LiveSession]]` with `getLiveSession`, `setLiveSession`, `clearLiveSession`, `getSessionId` accessors

**`handleNewSession` flow matches spec section 3.4:**
1. Calls `createSession` mutation
2. Interrupts existing `subscriptionFiber` if present
3. Creates `tokenQueue`
4. Forks `SubscriptionClient.agentResponseStream` as scoped fiber; pushes `Right(Some(chunk))`, `Right(None)`, or `Left(err)` to `tokenQueue`
5. Stores `LiveSession` in `ShellState`
All five steps implemented correctly.

**`handleMessage` drain loop matches spec section 3.4:**
- Drains `tokenQueue` until `Right(None)` (finished) or `Left(err)` — correct
- Per-message drain without re-opening WebSocket — correct

**`loadOrCreateSession` added to startup flow:**
- Resumes most-recent `Active` session if one exists, creates new one otherwise
- Forks subscription fiber for resumed session — correct

**`ConversationLogger` is a sound new component:**
- Uses SLF4J MDC with `sessionId` key for per-session log file routing via `SiftingAppender`
- MDC is set and restored atomically within a single `ZIO.succeed` block — no fiber-leak risk
- `logUserMessage` and `logAgentResponse` correctly distinguish error vs. normal agent output

**`VersionCheck` pure logic is correct and well-tested:**
- Semver path: major == major, minor == minor, client.patch >= server.patch — documented rule
- Non-semver fallback: epoch-millis comparison, client >= server — correct
- 14 tests cover all cases including mixed semver/non-semver, all pass

**`VersionCheck` integration in startup flow:**
- Called in `JorlanShell.initialisePostLogin` with `jorlan.BuildInfo.version` and `jorlan.BuildInfo.buildTime`
- Non-fatal: if `/api/status` is unreachable, version check is skipped (`.option`)
- Fatal on incompatibility: `mapError(msg => new RuntimeException(...))` propagates to `catchAll` display

**`ServerStatus` model extended with `buildTime: Long`:**
- `buildTime` added to `ServerStatus` case class for version check support
- `InitRoutes` returns `jorlan.BuildInfo.buildTime` in the status response

**`SessionHubSpec` fully rewritten for new API:**
- 7 tests covering: queue registration before publish, ordered emission, multi-message sessions, no-op publish, session isolation, multi-subscriber broadcast, cleanup on stream termination

**`AgentRunnerSpec` correctly passes `connectionId` to `subscribeToSession`:**
- All tests use `ConnectionId.randomZIO` and the updated `subscribeToSession(sessionId, connId)` signature

**`ShellStateSpec` covers all `LiveSession` accessors:**
- `getLiveSession`, `setLiveSession`, `getSessionId`, `clearLiveSession` all tested

**`SubscriptionClient` redesigned to per-layer backend:**
- Single `Backend` acquired at `live` layer construction time via `HttpClientZioBackend.scoped()` — eliminates per-call thread pool creation
- Correct fragmented WebSocket frame handling via `fragmentBuf`
- Ping/keep-alive loop every 15s using `WebSocketFrame.ping`
- Handles `connection_ack`, `data`, `complete`, `error`, `close` frame types
- `subscriptions-transport-ws` protocol (not the newer `graphql-ws` `subscribe` type) — correct for the Caliban server config

**Architectural compliance maintained:**
- Domain trait `AgentRunner` in `model/`; implementation in `server/` — unchanged
- `SessionHub` remains in `server/`; `AgentRunner` trait does not expose `SessionHub` directly
- `ConversationLogger` in `server/service/` — correct layer
- Shell module does not import any `server` or `db` code
