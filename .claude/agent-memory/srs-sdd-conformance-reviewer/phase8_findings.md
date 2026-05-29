---
name: phase8-findings
description: Phase 8 agent session runtime conformance review summary (2026-05-29)
metadata:
  type: project
---

## Phase 8 Conformance Review (2026-05-29)

### Critical Issues

**listSessions resolver is broken** (`JorlanAPI.scala` lines 226-233)
- Calls `AgentSessionManager.getSession(AgentSessionId.empty).map(_.toList)` — looks up a single session by zero-ID, which returns at most one result and is semantically wrong
- Should call a proper multi-session search; `AgentSessionManager` doesn't even expose a `listSessions` / `searchSessions` method
- The correct approach: add `listSessions(userId: UserId, pagination): IO[JorlanError, List[AgentSession]]` to `AgentSessionManager` / delegate to `AgentService.searchSessions`

**`/new [model]` does not pass the model argument** (ShellCommand.scala line 52, CommandHandler.scala line 40)
- `ShellCommand.parse` discards the model token: `case "new" :: _ => NewSession` (singleton variant, no payload)
- `CommandHandler` always calls `handleNewSession(None)` regardless of input
- `handleNewSession` is correctly wired to send the `modelId` to the server — the breakage is in the enum variant and parser
- Fix: add `case NewSession(model: Option[String])` to the enum, pass the model in the `parse` case, and route in the handler

### Major Issues

**`ModelCallFailed` event never recorded** (`OllamaModelGateway.scala`)
- The roadmap requires: "Records ModelCallFailed on error"
- `OllamaModelGateway` writes `ModelCallStarted` and `ModelCallCompleted` (via `.ensuring`) but nothing on failure
- `ModelCallCompleted` in `.ensuring` fires even if the stream fails — this partially masks the gap but is semantically wrong
- Fix: use `ZStream.onError` or `ZIO.onError` to write `ModelCallFailed`, and change `.ensuring` to only fire `ModelCallCompleted` on success

**`AgentResponseCompleted` event not written on model error** (`AgentRunner.scala`)
- The sequential for-comprehension means `AgentResponseCompleted` only logs when the entire stream succeeds
- If `ModelGateway.streamedResponse` fails, the hub still gets the finished sentinel (via `.ensuring`) but no event is logged
- Roadmap says every significant step must be recorded; a model error is a significant step

### Minor Issues

- `SessionHub` is a concrete class exposed directly (not via a trait) — fine for Phase 8, could be refactored later
- `HumanApprovalNotifier` is not a trait (no interface separation) — Phase 11 concern but worth flagging
- `listModels` shell handler always returns "Model listing requires a running Phase 8 server" stub — this is Phase 8 code speaking of itself; should probably invoke `availableModels` from the GraphQL API

### Missing Requirements (per roadmap checkbox list)

- [ ] Integration test: full round-trip using `FakeModelGateway`, asserting each chunk arrives in order (roadmap explicitly marks this with `[ ]`)

### Conformant Items

- `ModelGateway` trait correctly placed in `model/` (trait) and `server/` (implementations) — spec-compliant module placement
- `OllamaModelGateway` writes `ModelCallStarted` and `ModelCallCompleted` events
- `AgentRunner` writes `UserMessageReceived` and `AgentResponseCompleted` events
- `AgentSessionManager` writes `SessionCreated` event on session creation
- `AgentSessionManager` is a trait in `server/` (acceptable per the roadmap note "in server")
- `SessionHub` uses `Ref[Map[AgentSessionId, Hub[ResponseChunk]]]` as specified
- V015 migration correctly adds `modelId` column to `agentSession`
- `FakeModelGateway` implements configurable chunks with optional delay
- `ResponseChunk`, `AgentSession`, `SessionStatus` all present in generated Caliban shell client (`JorlanClient.scala`)
- `WorkspaceId` and `ModelId` scalar mappings present in `JorlanClientDecoders`
- `createSession` capability-gated on `agent.session.create`; `submitMessage` on `agent.message`
- `listSessions` capability-gated on `agent.session.list` (even though the resolver body is broken)
- `SubscriptionClient` implements graphql-ws protocol correctly
- `ShellState` tracks `AgentSessionId`
- "No active session" message present in shell
- `★ FIRST ITERABLE MILESTONE`: session creation and WebSocket subscription paths are structurally present; milestone is reachable once the model arg and listSessions bugs are fixed
