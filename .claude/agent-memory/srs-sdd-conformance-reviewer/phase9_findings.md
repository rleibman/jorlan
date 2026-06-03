---
name: phase9-findings
description: Phase 9 (Memory System) conformance review summary (2026-06-03)
metadata:
  type: project
---

## Phase 9 Conformance Review (2026-06-03)

Branch: phase-9/memory-system

### Critical Issues

None.

### Major Issues

**Checkpoint pipeline NOT wired into `AgentRunnerImpl`**
- `AgentRunnerImpl.processMessage` does not call `memoryService.checkpoint(...)` at any point.
- The design doc (section 6, step 8) and roadmap item 9.3 both specify: "after response, evaluate CheckpointPolicy → summarize → classify → store."
- `MemoryService` is injected and used for `buildMemoryContext` queries, but the `checkpoint` call is absent from the `.ensuring` block.
- This means no automatic checkpoint is ever triggered; the entire checkpoint pipeline (CheckpointSummarizer, MemoryClassifier, CheckpointPolicy) is wired into EnvironmentBuilder but dead at runtime.

**`buildMemoryContext` queries only `User` scope — `Shared` and `Workspace` records never injected**
- The design doc (section 4, service interface) states: query should retrieve records visible to the user via MemoryAccessPolicy.
- `buildMemoryContext` hard-codes `MemoryScope.User` and does not query `Shared` or `Workspace` scopes.
- Shared facts are stored but never injected into the model context — a significant functional gap.

**`MemorySearch.textSearch` applied in-process, not via FULLTEXT index**
- The design doc (section 5) adds a `FULLTEXT INDEX idx_memory_value_fulltext (value)` specifically for Phase 9 keyword search.
- `QuillMemoryRepository.search` fetches rows from DB filtered only on structured predicates, then applies `textSearch` as an in-process `String.contains` filter in Scala.
- The FULLTEXT index in V019 is never used. For large memory stores this will be slow and will bypass the database's relevance ranking.

**`CheckpointPolicy.shouldCheckpoint` signature diverges from the design doc**
- Design doc (section 4) specifies: `def shouldCheckpoint(trigger: CheckpointTrigger, session: AgentSession): UIO[Boolean]`
- Implementation in `MemoryService.scala`: `def shouldCheckpoint(trigger: CheckpointTrigger): UIO[Boolean]` (no `session` parameter)
- The session parameter is needed so the policy can inspect session metadata (e.g. message count, session age) before deciding whether to checkpoint.

**`CheckpointSummarizer.summarize` uses `AgentSessionId(0L)` as a fake session ID for model calls**
- `CheckpointSummarizerImpl.summarize` calls `modelGateway.streamedResponse(tempSessionId, ...)` with `AgentSessionId(0L)`.
- This pollutes the session hub and conversation history for session 0 (a sentinel/empty value), which may interfere with other operations.
- The design doc does not specify this; a better approach is to use a unique ephemeral session ID or a dedicated summarization method.

**`MemoryServiceImpl.checkpoint` classifies `record.value.toString` rather than `record.content`**
- In `checkpoint`: `classifier.classify(record.value.toString)` — `record.value` is a `Json` object (e.g. `{"text":"the fact"}`).
- `MemoryClassifierImpl.classify` does string matching on the serialized JSON, not on the extracted text content.
- The PII keyword match will fail on text like `{"text":"password"}` because it's looking for the bare word in the JSON representation (which will contain quote chars, `{`, `}` etc.). The match should be on the extracted `"text"` field.

### Minor Issues

**`listMemory` GraphQL query resolves `agentId` by scanning sessions — fragile**
- Both `listMemory` (query) and `storeMemory` (mutation) resolve `agentId` via `listSessions(actorId, 0, 1).headOption.map(_.agentId).getOrElse(AgentId.empty)`.
- If the user has no active sessions, `AgentId.empty` is used, which will match records with `agentId = None` but not records scoped to a real agent.
- The design doc does not mandate this resolver behavior; a more robust approach would accept an optional `agentId` parameter.

**`forgetMemory` mutation does not verify the record belongs to the calling user**
- `forgetMemory(id)` calls `memoryService.forget(id)` without first checking `record.userId == actorId`.
- Any authenticated user with `memory.write` capability can delete any other user's memory record by id.
- This violates the deny-by-default access model in SRS/SDD Section 3 (capability model).

**`markMemoryShared` / `markMemoryPrivate` mutations do not verify record ownership**
- Same issue as `forgetMemory`: no ownership check before scope promotion/demotion.

**Memory context query ignores the `text` parameter (passes `None`)**
- `buildMemoryContext` calls `memoryService.query(MemoryScope.User, userId, sess.agentId, None)` — passing `None` for the text parameter.
- The design doc (section 6, step 2) says: "query memory with `lastUserMessage`" as the text hint.
- No text relevance filtering is applied; all User-scoped records for the user are injected regardless of relevance.

**`persistMessages` uses `.ignore` on database errors**
- If `convRepo.addMessage` fails (e.g. DB transient error), the failure is silently swallowed via `.ignore`.
- Conversation history is not written, but no error is surfaced to the caller. The design intent for Layer 1 is reliable persistence.

### Missing Requirements (per mini-design and roadmap)

**9.1: Integration test for conversation persist + reload round-trip is not implemented** (roadmap checkbox open)

**9.2: Integration test for store → query → forget memory cycle is not implemented** (roadmap checkbox open)

**9.4: Integration test verifying injected memory appears in model call context is not implemented** (roadmap checkbox open)

**9.5: `MemorySkill` not registered in SkillRegistry** (deferred to Phase 12 per roadmap — acceptable)

**Design doc section 2, Layer 3 marker: FULLTEXT index created but not used in queries** — partially addressed by V019 migration but the query path does not leverage it.

### Conformant Aspects

- `CheckpointTrigger` enum matches design doc exactly (4 cases: SessionEnd, TimedInterval, UserRequest, BeforeExternalEffect)
- `CheckpointPolicy.onSessionEnd` default object present and correct
- `MemoryClassifierImpl` heuristic rules match design doc (PII → Private, share keywords → Shared, default → User)
- `MemoryAccessPolicyImpl` correctly implements all four scope rules from the design doc
- `MemoryServiceImpl` wraps access policy on `query` — correct
- `MemorySkill` exposes all five Tier 0 operations from design doc table (remember, search, forget, mark_shared, mark_private)
- GraphQL schema has all four required mutations (`storeMemory`, `forgetMemory`, `markMemoryShared`, `markMemoryPrivate`) and the `listMemory` query
- All memory GraphQL operations require `memory.read` / `memory.write` capability checks — conformant with deny-by-default model
- `MemoryService` interface has `markShared` / `markPrivate` operations matching design doc section 8
- `AgentRunnerImpl` loads conversation history on first call and seeds the model — Layer 1 conversation reload is implemented
- `AgentRunnerImpl` persists user + assistant messages after each exchange — Layer 1 persistence wired
- V019 migration adds indexes to `message` and `conversation` tables and FULLTEXT on `memoryRecord.value` — matches design intent
- `CheckpointSummarizerImpl` system prompt is well-constructed (extracts preferences, facts, decisions, persistent context)
- Layer wiring in `EnvironmentBuilder.live` is complete and correct for all new Phase 9 components
- `MemoryService` companion object provides accessor methods (`store`, `query`, `forget`, `markShared`, `markPrivate`, `checkpoint`) for ZIO environment injection
