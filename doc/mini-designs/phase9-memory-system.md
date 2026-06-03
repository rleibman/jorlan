<!--
 Copyright (c) 2026 Roberto Leibman - All Rights Reserved
-->

# Phase 9: Memory System Design

**Branch:** `phase-9/memory-system` (planned)  
**Status:** Design — 2026-06-02  
**Date:** 2026-06-02

---

## 1. Problem Statement

Agents currently have no memory beyond a single session. Two problems:

1. **Short-term:** `AgentRunner` builds a fresh `ChatMemory` on every call. When a session is
   resumed (e.g. shell reconnects), prior conversation history is lost.
2. **Long-term:** There is no mechanism to summarize what was learned across sessions into
   persistent facts that can be injected into future model calls.

Phase 9 solves both.

---

## 2. Three Memory Layers

### Layer 1 — Conversation History (`ConversationRepository`)

`ChatMessage` rows keyed by `AgentSessionId`. Written after every message exchange; read at
session resume to rebuild `ChatMemory`.

**Already modelled** in the domain (`ChatMessage`, `AgentSession`). Phase 9 wires the
persistence and reload into `AgentRunner`.

```
submitMessage(sessionId, content)
  → load prior ChatMessages for sessionId from ConversationRepository
  → build ChatMemory (MessageWindowChatMemory, last 50 messages)
  → call ModelGateway.streamedResponse(chatMemory, content)
  → persist new user message + assistant response to ConversationRepository
```

### Layer 2 — Long-term Episodic Memory (`MemoryService` / `MemoryRecord`)

Summarized facts stored in MariaDB. Injected as additional context into the system prompt
before every model call. Lifecycle:

```
CheckpointPolicy fires (session end / timer / user request)
  → CheckpointSummarizer calls ModelGateway to compress conversation
  → produces List[MemoryRecord]
  → MemoryClassifier assigns scope (User / Shared / Workspace / Private)
  → MemoryService.store(records)

AgentRunner.processMessage
  → MemoryService.query(scope=User, userId, recentTopics)
  → inject retrieved records into system prompt context block
```

### Layer 3 — Vector Semantic Search (Phase 16, deferred)

Qdrant index over `MemoryRecord` embeddings for semantic similarity retrieval. Phase 9 uses
keyword/structured MariaDB queries; Phase 16 upgrades this without changing the
`MemoryService` interface.

---

## 3. Domain Model

### `MemoryRecord`

```scala
case class MemoryRecord(
  id:        MemoryRecordId,           // UUID
  agentId:   AgentId,
  userId:    UserId,
  scope:     MemoryScope,              // User | Shared | Workspace | Private
  content:   String,                  // summarized text
  sourceSessionId: Option[AgentSessionId],
  createdAt: Instant,
  expiresAt: Option[Instant]
)

enum MemoryScope { case User, Shared, Workspace, Private }
```

### `ChatMessage` (already in model, needs persistence wiring)

```scala
case class ChatMessage(
  id:        ChatMessageId,
  sessionId: AgentSessionId,
  role:      ChatRole,                 // User | Assistant | System
  content:   String,
  createdAt: Instant
)
```

---

## 4. Service Interfaces

### `ConversationRepository`

```scala
trait ConversationRepository {
  def append(msg: ChatMessage): IO[RepositoryError, Unit]
  def loadHistory(sessionId: AgentSessionId, limit: Int = 50): IO[RepositoryError, List[ChatMessage]]
  def deleteSession(sessionId: AgentSessionId): IO[RepositoryError, Unit]
}
```

### `MemoryService`

```scala
trait MemoryService {
  def store(record: MemoryRecord):                              IO[MemoryError, Unit]
  def query(scope: MemoryScope, userId: UserId, text: String): IO[MemoryError, List[MemoryRecord]]
  def forget(id: MemoryRecordId):                              IO[MemoryError, Unit]
  def checkpoint(sessionId: AgentSessionId, trigger: CheckpointTrigger): IO[MemoryError, Unit]
}
```

### `CheckpointPolicy`

```scala
trait CheckpointPolicy {
  def shouldCheckpoint(trigger: CheckpointTrigger, session: AgentSession): UIO[Boolean]
}

enum CheckpointTrigger { case SessionEnd, TimedInterval, UserRequest, BeforeExternalEffect }
```

### `CheckpointSummarizer`

```scala
trait CheckpointSummarizer {
  def summarize(history: List[ChatMessage]): IO[SummarizerError, List[MemoryRecord]]
}
```

Uses `ModelGateway` with a fixed system prompt that instructs the model to extract facts,
preferences, and decisions as concise bullet points.

### `MemoryClassifier`

```scala
trait MemoryClassifier {
  def classify(record: MemoryRecord): UIO[MemoryScope]
}
```

Simple heuristic first pass: keyword patterns for PII → `Private`; explicit sharing language →
`Shared`; default → `User`.

### `MemoryAccessPolicy`

```scala
trait MemoryAccessPolicy {
  def visibleRecords(requestingUserId: UserId, records: List[MemoryRecord]): UIO[List[MemoryRecord]]
}
```

Rules:
- `User` scope: visible only to `record.userId == requestingUserId`
- `Private` scope: visible only to the originating agent+user pair
- `Shared` scope: visible to all users in the same workspace
- `Workspace` scope: visible to all agents in the workspace (no user restriction)

---

## 5. Database Migrations

### V016 — `chat_message` table

```sql
CREATE TABLE chat_message (
  id          CHAR(36)     NOT NULL PRIMARY KEY,
  session_id  CHAR(36)     NOT NULL,
  role        VARCHAR(16)  NOT NULL,
  content     MEDIUMTEXT   NOT NULL,
  created_at  DATETIME(3)  NOT NULL,
  FOREIGN KEY (session_id) REFERENCES agentSession(id) ON DELETE CASCADE,
  INDEX idx_chat_message_session (session_id, created_at)
);
```

### V017 — `memory_record` table

```sql
CREATE TABLE memory_record (
  id                CHAR(36)     NOT NULL PRIMARY KEY,
  agent_id          CHAR(36)     NOT NULL,
  user_id           CHAR(36)     NOT NULL,
  scope             VARCHAR(16)  NOT NULL,
  content           MEDIUMTEXT   NOT NULL,
  source_session_id CHAR(36)     NULL,
  created_at        DATETIME(3)  NOT NULL,
  expires_at        DATETIME(3)  NULL,
  INDEX idx_memory_user_scope (user_id, scope),
  FULLTEXT INDEX idx_memory_content (content)
);
```

The `FULLTEXT` index enables Phase 9's keyword search. Phase 16 adds a `content_embedding`
column alongside it.

---

## 6. `AgentRunner` Integration

`AgentRunner.processMessage` becomes:

```
1. ConversationRepository.loadHistory(sessionId, limit=50)      → chatHistory
2. MemoryService.query(User, userId, lastUserMessage)            → relevantMemory
3. Build ChatMemory: systemPrompt + memoryContext + chatHistory
4. ModelGateway.streamedResponse(chatMemory, content)            → stream tokens
5. Collect full assistant response
6. ConversationRepository.append(userMsg)
7. ConversationRepository.append(assistantMsg)
8. CheckpointPolicy.shouldCheckpoint(SessionEnd|TimedInterval)
   → if true: CheckpointSummarizer.summarize → MemoryClassifier → MemoryService.store
```

---

## 7. `MemorySkill` (Tier 0)

A built-in skill that agents can invoke directly. Operations:

| Tool name              | Description                                         |
|------------------------|-----------------------------------------------------|
| `memory.remember`      | Store an explicit fact as a `MemoryRecord`          |
| `memory.search`        | Query memory by text; returns matching records      |
| `memory.forget`        | Delete a record by id                               |
| `memory.mark_shared`   | Promote a record to `Shared` scope                  |
| `memory.mark_private`  | Demote a record to `Private` scope                  |

These bypass `CheckpointSummarizer` — they are explicit user/agent-directed memory writes.

---

## 8. GraphQL Additions

```graphql
type MemoryRecord {
  id: ID!
  scope: MemoryScope!
  content: String!
  createdAt: String!
  expiresAt: String
}

enum MemoryScope { USER SHARED WORKSPACE PRIVATE }

type Query {
  listMemory(scope: MemoryScope): [MemoryRecord!]!
}

type Mutation {
  forgetMemory(id: ID!): Boolean!
  markMemoryShared(id: ID!): MemoryRecord!
  markMemoryPrivate(id: ID!): MemoryRecord!
}
```

Shell commands: `/memory list`, `/memory search <q>`, `/memory forget <id>`

---

## 9. Open Questions

1. **ChatMemory window size:** 50 messages is a guess. Should be configurable per-agent in
   `AgentConfig`. Deferred — use 50 as a constant for Phase 9.
2. **Checkpoint prompt:** The summarizer system prompt needs careful tuning so it produces
   concise, factual records rather than rephrasing the conversation. Will iterate during
   testing.
3. **Expiry:** `expires_at` is modelled but no TTL enforcement mechanism is built in Phase 9.
   Deferred to Phase 10 (scheduler can run a cleanup job).
4. **MemoryClassifier accuracy:** Heuristic approach will mis-classify edge cases. Phase 16
   can replace it with an embedding-based classifier.

---

## 10. Implementation Order

1. `V016` migration + `ConversationRepository` + wire into `AgentRunner` (load & persist)
2. `V017` migration + `MemoryRecord` domain + `MemoryService` (store/query/forget)
3. `CheckpointPolicy` + `CheckpointSummarizer` + wire checkpoint into `AgentRunner`
4. `MemoryClassifier` + `MemoryAccessPolicy`
5. `MemorySkill` Tier 0 tools
6. GraphQL mutations/queries + shell `/memory` commands
7. Tests (unit: each service; integration: full checkpoint cycle)
