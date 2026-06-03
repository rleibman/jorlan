---
name: phase9-perf
description: Phase 9 memory-system performance findings — in-process text search, checkpoint N+1 classify+upsert loop, missing index coverage, buildMemoryContext redundant session query, loadPersonality per-message DB hit, CheckpointSummarizer runCollect
metadata:
  type: project
---

## Findings (phase-9/memory-system, 2026-06-03)

### Critical: QuillMemoryRepository.search applies textSearch in-process after SQL fetch
- `QuillMemoryRepository.search` (line 592-597) fetches up to `pageSize` rows from the DB,
  then applies `s.textSearch` with `records.filter(r => r.value.toString.toLowerCase.contains(lower))`.
  The `value` column is a JSON blob; `.toString` on each record re-serializes the AST to a string on
  every search call. With a full page (20 records), this is 20 JSON serializations per query.
  Worse: the in-process filter runs AFTER the LIMIT clause, so a page of 20 returned by the DB may
  produce 0 or 1 actual results, forcing callers to issue more pages or silently miss records.
  V019 adds a FULLTEXT index on `memoryRecord.value` — use `MATCH(value) AGAINST (? IN BOOLEAN MODE)`
  via Quill `infix` to push the filter to the DB and eliminate the in-process scan.

### Critical: checkpoint pipeline classifies and upserts each record sequentially (O(n) sequential I/O)
- `MemoryServiceImpl.checkpoint` (line 76) uses `ZIO.foreachDiscard(records)` — one record at a time.
  Each iteration calls `classifier.classify` (pure, fast) then `memoryRepo.upsert` (DB round-trip).
  For N checkpoint bullet points this is N sequential DB round-trips. Use `ZIO.foreachParDiscard`
  to pipeline the upserts, or batch-insert via a single `insertAll` Quill query.
  The `classifier.classify` calls are also sequential but pure; they could be parallelised or
  pre-run before the upsert loop: `ZIO.foreach(records)(r => classifier.classify(r.value.toString).map(r -> _))`
  then a single parallel upsert pass.

### Warning: buildMemoryContext issues an agentSession DB query on every message
- `AgentRunnerImpl.buildMemoryContext` (line 224-244) calls `agentRepo.searchSessions(...)` on every
  call to `processMessage`, to retrieve the `agentId` for the session. The session's agentId is a
  stable property that does not change during a session's lifetime. This round-trip is unnecessary
  after the first call. The agentId should be resolved once (e.g. cached in the `activeConvs` Ref
  or passed as a parameter) and reused across messages in the same session.

### Warning: loadPersonality hits the DB on every message (not cached)
- `AgentRunnerImpl.loadPersonality` (line 246-256) is a `val` but returns `UIO[Personality]` that
  executes `settingsRepo.get(PersonalityKey)` on every invocation. In `processMessage` it is called
  twice: once in `ensureSeeded` and once directly. Each invocation is a DB query. Personality changes
  infrequently; this should be served from a ZIO `Cache` with a short TTL (e.g. 30 seconds) or at
  minimum memoised for the duration of a single `processMessage` call by sharing the result across
  the two call sites.

### Warning: CheckpointSummarizer.summarize calls runCollect on the LLM stream
- `CheckpointSummarizerImpl.summarize` (line 54-56) calls `modelGateway.streamedResponse(...).runCollect`
  which buffers the entire LLM response into a `Chunk[String]` before calling `.mkString`.
  For a long summary (many bullet points), this holds the full response in memory until complete.
  `runFold("") { case (acc, chunk) => acc + chunk }` would accumulate directly into a single String
  without allocating a Chunk. For a fake/test gateway the current approach is fine; for production
  where summaries can be 500+ tokens, the Chunk allocation is unnecessary.

### Warning: markShared / markPrivate do a read-then-upsert without a guard
- Both `MemoryServiceImpl.markShared` and `markPrivate` (lines 43-63) do `getById` followed by
  `upsert(copy(...))`. There is no optimistic lock or CAS on `updatedAt`. Under concurrent requests
  on the same record, two readers both see the original record and both write back with their own
  `updatedAt`. This is a correctness concern but also a performance concern: the upsert can silently
  overwrite a concurrent change. Use a targeted `UPDATE memoryRecord SET scope = ?, updatedAt = ? WHERE id = ?`
  instead of a full read-modify-write to eliminate the extra SELECT and the race.

### Warning: V019 FULLTEXT index on `memoryRecord.value` is unused by the current query code
- V019 adds `FULLTEXT INDEX idx_memory_value_fulltext (value)` but `QuillMemoryRepository.search`
  does not use it (see Critical item above). The index will consume write-amplification on every
  INSERT/UPDATE to `memoryRecord` without delivering any read benefit until the query is rewritten.
  This is not dangerous but represents index overhead for zero benefit in the current codebase.

### Info: MemoryClassifierImpl scans full content string twice (piiKeywords and sharedKeywords)
- `MemoryClassifierImpl.classify` (line 29-32) calls `content.toLowerCase` once, then iterates both
  keyword sets with `exists(lower.contains)`. With 7 PII keywords and 6 shared keywords, this is at
  most 13 substring searches on `lower`. For typical checkpoint bullet text (<200 chars) this is
  negligible. No action needed.

### Info: Missing index on `memoryRecord(scope, userId)` for the primary query pattern
- `MemorySearch` always filters on `scope` and optionally on `userId`. V019 only adds a FULLTEXT
  index on `value`. A composite index `(scope, userId)` would benefit the primary non-text query
  path. If the table is small now, add this before row counts grow.

### Resolved from Phase 8.5:
- `AgentRunnerImpl.tokensRef` is now a `StringBuilder` Ref (not `Vector[String]`) — the Phase 8.5
  token accumulation warning is resolved.

**How to apply in Phase 10+:**
- The in-process textSearch filter is the highest-priority fix before any public demo with real data.
- The loadPersonality double-hit and session query in buildMemoryContext compound on every message;
  cache both before multi-user load testing.
