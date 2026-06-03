<!--
 Copyright (c) 2026 Roberto Leibman - All Rights Reserved
-->

# Session / Connection Redesign Spec

**Branch:** `phase-8.5/manual-testing`  
**Status:** Implemented in Phase 8.5 — 2026-06-02  
**Date:** 2026-06-01

---

## 1. Problem Statement

The current implementation has two related defects:

### 1a. `SessionHub` is single-subscriber per session

`SessionHub` stores `Map[AgentSessionId, Queue[ResponseChunk]]` — one queue per session.
This breaks in two ways:

- **Multiple subscribers race**: if two WebSocket connections subscribe to the same
  `AgentSessionId` (e.g. two browser tabs), they share a single queue and tokens are
  delivered non-deterministically to one or the other.
- **No per-connection cleanup**: when a subscriber disconnects there is no mechanism to
  remove just that subscriber's queue; the only operation is `remove(sessionId)` which
  tears down the session entirely.

### 1b. Shell opens a new WebSocket per message

`CommandHandler.handleMessage` opens a fresh WebSocket subscription for every user
message, waits for the stream to finish, then closes the connection. This adds ~36 ms
of handshake overhead per message and means any tokens published between the mutation
returning and the WS start frame being processed are potentially missed.

---

## 2. Concept Clarification

Two identifiers exist and both are needed — they represent different things:

| Identifier       | Type                               | Scope          | Lifetime                                                                            | Analogous to (chuti)                   |
|------------------|------------------------------------|----------------|-------------------------------------------------------------------------------------|----------------------------------------|
| `AgentSessionId` | `Long` (MariaDB auto-increment PK) | Server-global  | Durable — survives reconnects, stored in DB                                         | `ChannelId` (the game channel)         |
| `ConnectionId`   | `UUID`                             | Per-connection | Transient — lasts for the lifetime of one WebSocket connection or one shell process | `ConnectionId` (the per-tab queue key) |

> **Mental model:** `AgentSessionId` is the conversation thread (the "what").
> `ConnectionId` is the physical pipe delivering tokens to a specific client (the "where").
> Multiple connections can subscribe to the same conversation, each receiving their own
> independent copy of the token stream.

Neither identifier is renamed. The confusion is in naming and comments, not in the
types themselves.

---

## 3. Target Architecture

### 3.1 `SessionHub` — per-connection queues (chuti pattern)

Replace the current `Map[AgentSessionId, Queue[ResponseChunk]]` with:

```
Ref[Map[AgentSessionId, List[SubscriberEntry]]]

case class SubscriberEntry(connectionId: ConnectionId, queue: Queue[ResponseChunk])
```

**API:**

| Method      | Signature                                                                                       | Behaviour                                                                                                                                                                                     |
|-------------|-------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `subscribe` | `(sessionId: AgentSessionId, connectionId: ConnectionId): UIO[ZStream[Any, Nothing, ResponseChunk]]` | Atomically creates a bounded queue, registers it under `(sessionId, connectionId)`, returns a `UIO` that resolves to a `ZStream.fromQueue` with an `ensuring` block that removes just this entry on stream termination. The two-step return (`UIO` then `ZStream`) guarantees no tokens are lost between subscription and publishing. |
| `publish`   | `(chunk: ResponseChunk): UIO[Unit]`                                                             | Broadcasts `chunk` to **all** entries whose `sessionId` matches `chunk.sessionId`. No-op if no subscribers exist.                                                                             |

**Removed methods:** `getOrCreate(sessionId)` and `remove(sessionId)`. Queue lifecycle
is entirely managed by the `ensuring` block inside `subscribe`.

**Queue capacity:** `Queue.sliding(1024)` per subscriber. A sliding queue drops the
oldest token rather than back-pressuring the publisher — appropriate because LLM tokens
are high-frequency and a slow subscriber should see recent output, not stall the model.

### 3.2 `AgentRunner` trait — add `connectionId` parameter

```scala
def subscribeToSession(
                        sessionId: AgentSessionId,
                        connectionId: ConnectionId,
                      ): ZStream[Any, Nothing, ResponseChunk]
```

`processMessage` no longer calls `sessionHub.getOrCreate(sessionId)` — the queue is
created on first `subscribe`, not on `processMessage`.

### 3.3 `JorlanAPI` — generate a fresh `ConnectionId` per subscription call

In the `Subscriptions` resolver:

```scala
agentResponseStream = sessionId =>
  ZStream.unwrap(
    ConnectionId.randomZIO.flatMap { connId =>
      ZIO.serviceWith[AgentRunner](_.subscribeToSession(sessionId, connId))
    }
  )
```

A new UUID is minted for each WebSocket subscription call — not threaded from
`JorlanSession`. This is correct because:

- Each WS connection is physically independent regardless of whether the same user
  re-subscribes from the same tab.
- It is simpler than encoding `connectionId` in the JWT and threading it through the
  auth layer.
- The `JorlanSession.connectionId` field remains available for future use (admin "who
  is connected" queries, etc.) but is not yet load-bearing in the subscription path.

### 3.4 Shell — one long-lived WebSocket subscription per `AgentSession`

#### `ShellState` additions

```scala
// Holds the live session state.  None until the first /new is run.
case class LiveSession(
                        sessionId: AgentSessionId,
                        tokenQueue: Queue[Either[String, Option[ResponseChunk]]],
                        subscriptionFiber: Fiber[Nothing, Unit],
                      )
```

`ShellState` gains a `Ref[Option[LiveSession]]` (or equivalent accessors).

#### `/new` flow (`handleNewSession`)

1. Call `createSession` mutation → receive `AgentSession`.
2. If a `LiveSession` already exists, interrupt its `subscriptionFiber`.
3. Create a new `tokenQueue`.
4. Fork `SubscriptionClient.agentResponseStream(sessionId)` as a scoped fiber; on each
   chunk push `Right(Some(chunk))` to `tokenQueue`; on finish push `Right(None)`; on
   error push `Left(errorMessage)`.
5. Store the new `LiveSession` in `ShellState`.

#### Message-send flow (`handleMessage`)

1. Get `LiveSession` from `ShellState`; if absent, show "no active session" prompt.
2. Submit the `submitMessage` mutation.
3. Drain `tokenQueue` until `Right(None)` (finished sentinel) or `Left(err)`.
   Append tokens to the current message in `JorlanScreen` with `appendToLastMessage`.

This eliminates the per-message WS handshake. The subscription is always hot.

#### Reconnect / error handling

If the subscription fiber exits with an error (network drop, server restart), push a
`Left("Connection lost — reconnect with /new")` to the `tokenQueue` and clear the
`LiveSession`. The user must run `/new` to re-establish the session.

---

## 4. Files to Change

| File                                                              | Change                                                                                               |
|-------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| `server/src/main/scala/jorlan/service/SessionHub.scala`           | Full redesign — per-connection queue map, `subscribe(sessionId, connectionId)`, `publish(chunk)`.    |
| `model/src/main/scala/jorlan/service/AgentRunner.scala`           | Add `connectionId` param to `subscribeToSession`.                                                    |
| `server/src/main/scala/jorlan/service/AgentRunnerImpl.scala`      | Update `subscribeToSession` to pass `connectionId`; remove `getOrCreate` call from `processMessage`. |
| `server/src/main/scala/jorlan/service/FakeModelGateway.scala`     | Update `subscribeToSession` signature if it implements `AgentRunner`.                                |
| `server/src/main/scala/jorlan/graphql/JorlanAPI.scala`            | Subscription resolver: mint `ConnectionId.randomZIO`, call `subscribeToSession(sessionId, connId)`.  |
| `shell/src/main/scala/jorlan/shell/ShellState.scala`              | Add `LiveSession` case class and `Ref[Option[LiveSession]]`.                                         |
| `shell/src/main/scala/jorlan/shell/commands/CommandHandler.scala` | `handleNewSession`: fork subscription fiber. `handleMessage`: drain from session token queue.        |
| `server/src/test/scala/jorlan/service/SessionHubSpec.scala`       | Rewrite tests for new API.                                                                           |
| `server/src/test/scala/jorlan/service/AgentRunnerSpec.scala`      | Update `subscribeToSession` calls to pass `connectionId`.                                            |
| `shell/src/test/scala/jorlan/shell/CommandHandlerSpec.scala`      | Update `handleMessage` tests for the new drain-from-queue pattern.                                   |
| `shell/src/test/scala/jorlan/shell/ShellStateSpec.scala`          | Add tests for `LiveSession` accessors.                                                               |

---

## 5. Invariants & Constraints

1. **One WS per shell session**: the shell MUST NOT open more than one WebSocket
   connection per `AgentSessionId`. A second `/new` tears down the previous fiber first.

2. **Publish before subscribe is safe**: `AgentRunner.processMessage` is forked as a
   daemon; it may start publishing before the subscription stream is open. Because each
   subscriber's queue is created in `subscribe` (called before `submitMessage` in the
   message flow), tokens published before subscription are **lost**. This is acceptable
   because the shell subscribes before submitting.

3. **Sliding queue, not blocking**: the publisher (`SessionHub.publish`) must never
   block waiting for a slow consumer.  `Queue.sliding` ensures this.

4. **Cleanup is per-connection, not per-session**: `SessionHub` only removes the entry
   for the specific `(sessionId, connectionId)` pair when a stream terminates. Other
   subscribers for the same session are unaffected.

5. **`AgentSessionId` remains the DB key**: it is never replaced by `ConnectionId`.
   The DB schema, Quill repositories, and event log references are unchanged.

---

## 6. Out of Scope (future work)

- Exposing `ConnectionId` as a subscription argument in the GraphQL schema (would allow
  the client to specify a stable connection identity across reconnects).
- Admin query: "list all active subscribers per session".
- Reconnect with session resume (shell automatically re-subscribes on disconnect).
- Browser/web client implementation (only shell is in scope here).
