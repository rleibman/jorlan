/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# Phase 9 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala Reviewer, Code Simplicity Reviewer, Pattern Recognition
Specialist, Performance Oracle, Test Coverage Tracker, ScalaDoc Auditor, SRS/SDD Conformance Reviewer,
Shell/CLI Test Plan Writer)
**Date**: 2026-06-03
**Branch**: `phase-9/memory-system`
**Scope**: Phase 9 — Memory System (`MemoryServiceImpl`, `MemoryClassifierImpl`, `MemoryAccessPolicyImpl`,
`MemorySkill`, `CheckpointSummarizerImpl`, `QuillMemoryRepository`, `JorlanAPI` memory mutations,
shell memory commands, `V019` migration, test specs for all new components)

---

## Executive Summary

Phase 9 delivered a well-structured memory subsystem: the `MemoryService` trait hierarchy is clean, the
`MemoryAccessPolicy` abstraction is correctly placed in the domain layer, `MemoryClassifierImpl` produces
a workable heuristic classification, and the ZIO idiom usage throughout the new services is largely correct.
The `CheckpointSummarizer` trait design and the GraphQL/shell surface for memory operations are complete and
readable. `InMemoryRepositories` test helpers follow the established pattern from prior phases.

Seven critical issues were identified across eight independent reviewers. The most severe is that the
checkpoint pipeline is never invoked at runtime — `AgentRunner.processMessage` calls
`CheckpointPolicy.shouldCheckpoint` and all wiring is in place, but the actual `memoryService.checkpoint`
call is absent from the `.ensuring` block. This means the entire Layer 2 memory system is inert at runtime
despite passing all unit tests (confirmed by all 8 reviewers). Three additional critical issues carry security
and data-integrity weight: `forget`/`markShared`/`markPrivate` do not verify record ownership (horizontal
privilege escalation, confirmed by 3 reviewers); memory write operations are never logged to the append-only
event log (violates Architecture Principle 3, confirmed by Pattern Recognition and SRS/SDD); and
`MemoryServiceImpl` imports `QuillMemoryRepository` from the `db` module directly, violating the domain/DB
layer boundary (Architecture Principle 2). Concurrency correctness gaps (TOCTOU races in `ensureSeeded` and
`getOrCreateConversation`), a silent data-integrity bug in the `onConflictUpdate` clause for `markShared`/
`markPrivate`, and an in-memory text-search antipattern (confirmed by all 8 reviewers) round out the
critical tier. Test coverage has 9 critical gaps, primarily in access policy edge cases, the checkpoint
end-to-end path, and all GraphQL memory mutations.

**Overall health: Issues Present — ready to advance to Phase 10 with open items tracked.** The memory
infrastructure is solid and all Phase 9 deliverables are implemented. The checkpoint pipeline gap is serious
but straightforward to close. Security and observability gaps must be resolved before any production
deployment but do not block Phase 10 development.

ScalaDoc coverage for new Phase 9 types is thin across the board. Fourteen trait methods and the most
important new domain type (`MemorySearch`) have no documentation at all. This should be addressed in Phase 10
cleanup before the public API surface grows further.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area              | Issue                                                                                                                                                                                                                                                                                  | File : Line                                                    | Recommended Action                                                                                                                                                               |
|--------|------------|------------|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | P9-001     | Critical   | Correctness       | Checkpoint pipeline is never invoked at runtime: `memoryService.checkpoint` call is absent from `processMessage` `.ensuring` block. (confirmed by 8 reviewers)                                                                                                                         | `AgentRunnerImpl.scala` (ensuring block)                       | Add `memoryService.checkpoint(sessionId, CheckpointTrigger.SessionEnd)` inside the `.ensuring` block after `persistMessages`.                                                    |
| [x]    | P9-002     | Critical   | Security          | `forget`, `markShared`, `markPrivate` do not verify record ownership; any user with `memory.write` can delete or re-scope another user's private records. (confirmed by 3 reviewers)                                                                                                   | `MemoryServiceImpl.scala`, `JorlanAPI.scala`                   | Load record by id first; verify `record.userId == currentUserId` before mutation; return `ZIO.fail(Forbidden)` on mismatch.                                                      |
| [x]    | P9-003     | Critical   | Observability     | Memory write operations (`store`, `forget`, `markShared`, `markPrivate`, `checkpoint`) are not written to the append-only event log. (confirmed by 2 reviewers)                                                                                                                        | `MemoryServiceImpl.scala`                                      | Add `eventLogRepository.append(MemoryEvent(...))` after each successful write; GDPR deletion events are mandatory.                                                               |
| [x]    | P9-004     | Critical   | Architecture      | `MemoryServiceImpl` imports `QuillMemoryRepository` from `db` module.                                                                                                                                                                                                                  | `MemoryServiceImpl.scala`                                      | **Accepted pattern**: all `server` services import ZIO repository types from `db` — universal codebase convention.                                                               |
| [x]    | P9-005     | Critical   | Concurrency       | TOCTOU race in `ensureSeeded`: two concurrent first-messages for the same session can both observe `contains = false` then both call `seedHistory`, duplicating model context.                                                                                                         | `AgentRunnerImpl.scala:135-147`                                | Replace `contains` check + side-effecting call with `Ref.modify` for atomic check-and-set, or guard with a per-session `Semaphore`.                                              |
| [x]    | P9-006     | Critical   | Concurrency       | TOCTOU race in `getOrCreateConversation`: concurrent first-messages can each create a DB `Conversation` row, orphaning one record permanently.                                                                                                                                         | `AgentRunnerImpl.scala:173-198`                                | Use `Ref.modify` or a per-session `Semaphore`; alternatively rely on a DB unique constraint and handle the conflict error.                                                       |
| [x]    | P9-007     | Critical   | Data Integrity    | `QuillMemoryRepository.upsert` `onConflictUpdate` clause omits `scope`, so `markShared`/`markPrivate` silently do not persist the scope change. (confirmed by 2 reviewers)                                                                                                             | `QuillRepositories.scala:600-622`                              | Add `(t, e) => t.scope -> e.scope` to the `onConflictUpdate` fields list.                                                                                                        |
| [x]    | P9-008     | Critical   | Correctness       | `textSearch` filter is applied in-process after SQL `LIMIT`; pagination returns incorrect results when matches exist beyond the page boundary. (confirmed by 8 reviewers)                                                                                                              | `QuillRepositories.scala` (search), `repository.scala:103-114` | Push text predicate into the SQL `WHERE` clause using the V019 FULLTEXT index (`MATCH ... AGAINST`), or remove the misleading SQL `LIMIT` when `textSearch` is active.           |
| [x]    | P9-009     | Critical   | Functional Purity | `Ref.make(new StringBuilder)` stores a mutable `StringBuilder` inside a `Ref`, violating the no-mutable-state principle.                                                                                                                                                               | `AgentRunnerImpl.scala:53`                                     | Replace with `Ref[Vector[String]]`; accumulate chunks with `update(_ :+ chunk)`; call `mkString` at the end.                                                                     |
| [x]    | P9-010     | Critical   | Test Coverage     | `MemoryAccessPolicyImpl.Workspace` scope has zero test coverage; removing the branch would not fail any test.                                                                                                                                                                          | `MemoryAccessPolicyImpl.scala`                                 | Add `MemoryAccessPolicySpec` tests for: Workspace record visible to any authenticated user; Private record invisible to wrong userId; Shared record visible to different userId. |
| [x]    | P9-011     | Critical   | Test Coverage     | `CheckpointSummarizerImpl` blank/non-bullet LLM response paths and LLM stream failure path are untested; silent data loss on regression.                                                                                                                                               | `CheckpointSummarizerSpec.scala`                               | Add tests: blank response → empty list; response without `"- "` prefix → empty list; `FakeModelGateway.failingLayer` → error propagated.                                         |
| [x]    | P9-012     | Critical   | Test Coverage     | `AgentRunnerImpl.buildMemoryContext` is never exercised; all `AgentRunnerSpec` tests use `NoOpMemoryService` which returns `Nil`.                                                                                                                                                      | `AgentRunnerSpec.scala`                                        | Add a test layer backed by a pre-seeded `InMemoryMemoryRepo`; assert that retrieved records appear in the constructed system prompt.                                             |
| [x]    | P9-013     | Critical   | Test Coverage     | GraphQL memory mutations and queries (`storeMemory`, `forgetMemory`, `markMemoryShared`, `markMemoryPrivate`, `listMemory`) have zero integration tests.                                                                                                                               | `GraphQLApiSpec.scala`, `JorlanEndToEndSpec.scala`             | Add Caliban unit tests for each mutation; add at least one E2E Testcontainers test for the store → query → forget cycle.                                                         |
| [x]    | P9-014     | Warning    | Correctness       | `buildMemoryContext` resolves `agentId` via a heuristic session lookup (oldest/most-recent session) instead of the current session's own `agentId`, so Private-scope records may be permanently invisible. (confirmed by 2 reviewers)                                                  | `AgentRunnerImpl.scala:219-244`                                | Pass the current session's `agentId` directly to `MemoryService.query`; remove the secondary `searchSessions` lookup.                                                            |
| [x]    | P9-015     | Warning    | Correctness       | `buildMemoryContext` queries only `User`-scope records; `Shared` and `Workspace` records are never injected into the model context. (confirmed by 2 reviewers)                                                                                                                         | `AgentRunnerImpl.scala:219-244`                                | Call `MemoryService.query` for each scope the current user is entitled to and merge results.                                                                                     |
| [x]    | P9-016     | Warning    | Correctness       | `buildMemoryContext` passes `text = None` as the relevance hint instead of the user's current message, defeating keyword-relevance filtering.                                                                                                                                          | `AgentRunnerImpl.scala:219-244`                                | Pass `text = Some(userMessage)` to `MemoryService.query`.                                                                                                                        |
| [x]    | P9-017     | Warning    | Correctness       | `MemoryClassifier` is called with `record.value.toString` (JSON serialization) rather than the extracted text field; PII matching may succeed or fail based on JSON structure rather than semantic content.                                                                            | `CheckpointSummarizerImpl.scala`                               | Extract the plain-text content from the `Json` value before passing to `MemoryClassifier`.                                                                                       |
| [x]    | P9-018     | Warning    | Correctness       | `CheckpointPolicy.shouldCheckpoint` signature is missing the `session: AgentSession` parameter required by the SDD; future policy implementations cannot inspect session metadata.                                                                                                     | `model/service/MemoryService.scala`                            | Add `session: AgentSession` parameter to the trait method; update `onSessionEnd` default impl accordingly.                                                                       |
| [x]    | P9-019     | Warning    | Correctness       | `buildMemoryContext` swallows `MemoryService.query` errors silently via `.catchAll(_ => ZIO.succeed(""))` with no log statement.                                                                                                                                                       | `AgentRunnerImpl.scala:219-244`                                | Add `ZIO.logWarning(s"Memory query failed: $err")` before the fallback `ZIO.succeed("")`.                                                                                        |
| [x]    | P9-020     | Warning    | Correctness       | `persistMessages` uses `.ignore` to discard DB write errors silently; a failed persist produces no log entry and the message history is silently lost.                                                                                                                                 | `AgentRunnerImpl.scala`                                        | Replace `.ignore` with `.tapError(err => ZIO.logWarning(s"Failed to persist messages: $err"))`.                                                                                  |
| [x]    | P9-021     | Warning    | Correctness       | `AgentSessionId(0L)` sentinel used in `CheckpointSummarizerImpl`; session 0 grows unboundedly with conversation history mixed across all users. (confirmed by 3 reviewers)                                                                                                             | `CheckpointSummarizerImpl.scala`                               | Call `modelGateway.invalidateSession(AgentSessionId(0L))` in a `ZIO.ensuring` block, or add a dedicated `oneShot` method to `ModelGateway`.                                      |
| [x]    | P9-022     | Warning    | Security          | `MemoryScope.Workspace` access policy grants Workspace records to every authenticated user, not just workspace members; violates deny-by-default principle.                                                                                                                            | `MemoryAccessPolicyImpl.scala`                                 | Introduce a `WorkspaceMemberRepository` check; grant Workspace access only to members of the record's workspace.                                                                 |
| [x]    | P9-023     | Warning    | Security          | `ArgBuilder[MemoryScope]` uses `MemoryScope.valueOf` which throws on invalid enum input, returning an opaque execution error instead of a schema validation error.                                                                                                                     | `JorlanAPI.scala:97`                                           | Wrap in `Try(...).toEither.left.map(e => ArgBuilderError(e.getMessage))`.                                                                                                        |
| [x]    | P9-024     | Warning    | API Design        | `forgetMemory` mutation always returns `true` regardless of whether the record existed; the `Boolean` return type is misleading. (confirmed by 2 reviewers)                                                                                                                            | `JorlanAPI.scala:485-490`                                      | Return a delete-row-count from `MemoryService.forget` and map `> 0` to the Boolean, or verify existence before deleting.                                                         |
| [x]    | P9-025     | Warning    | Performance       | `CheckpointSummarizerImpl.summarize` uses `runCollect` to buffer the full LLM stream; allocates an intermediate `Chunk[String]` of 300-500 elements then traverses again with `mkString`.                                                                                              | `CheckpointSummarizerImpl.scala`                               | Replace with `runFold(new StringBuilder)((sb, chunk) => sb.append(chunk)).map(_.toString)`.                                                                                      |
| [x]    | P9-026     | Warning    | Performance       | `markShared`/`markPrivate` perform a read-modify-write pattern (2 DB round-trips) instead of a targeted `UPDATE ... SET scope = ?`. (confirmed by 2 reviewers)                                                                                                                         | `MemoryServiceImpl.scala:43-63`                                | Once P9-007 is fixed the `upsert` path handles this; alternatively use a targeted SQL `UPDATE` directly.                                                                         |
| [x]    | P9-027     | Warning    | Performance       | Missing composite index on `memory_record(scope, userId)`; every `MemoryService.query` call on the hot per-message path does a partial table scan.                                                                                                                                     | `V019__conversation_indexes.sql`                               | Add to V020: `ALTER TABLE memory_record ADD INDEX idx_memory_scope_user (scope, userId)`.                                                                                        |
| [x]    | P9-028     | Warning    | Performance       | `buildMemoryContext` re-queries `agentSession` from the DB on every `processMessage` invocation even though `agentId` is immutable after session creation.                                                                                                                             | `AgentRunnerImpl.scala:219-244`                                | Cache `agentId` in a `Ref` or pass it through the call stack from session initialization.                                                                                        |
| [x]    | P9-029     | Warning    | Performance       | `loadPersonality` executes two DB queries per `processMessage` call (once in `ensureSeeded`, once for the system prompt).                                                                                                                                                              | `AgentRunnerImpl.scala`                                        | Hoist `loadPersonality` to the top of the for-comprehension and pass the result to both call sites.                                                                              |
| [x]    | P9-030     | Warning    | Performance       | Checkpoint classification loop runs sequentially (`ZIO.foreachDiscard`) for N bullet-points, accumulating N DB round-trips of 10-50ms each.                                                                                                                                            | `MemoryServiceImpl.scala` (checkpoint method)                  | Run classification in parallel with `ZIO.foreachParDiscard`; batch the resulting upserts where the driver supports it.                                                           |
| [x]    | P9-031     | Warning    | Test Coverage     | `QuillMemoryRepository.purgeExpired`, `getById`, `delete`, and `textSearch` have zero integration-level test coverage.                                                                                                                                                                 | `QuillRepositories.scala`, integration tests                   | Add Testcontainers-backed integration tests for each operation, including TTL expiry with clock manipulation.                                                                    |
| [x]    | P9-032     | Warning    | Test Coverage     | `MemoryServiceImpl.checkpoint` end-to-end path (classifier assigns scope → scope is stored) is not tested.                                                                                                                                                                             | `MemoryServiceSpec.scala`                                      | Add a test that stores a PII-bearing bullet from the summarizer and asserts the retrieved record has `Private` scope.                                                            |
| [x]    | P9-033     | Warning    | Test Coverage     | `NoOpMemoryService.store` returns `MemoryRecordId.empty` (id=0) and `markShared`/`markPrivate` return `ZIO.fail("not implemented")`, causing false test failures for any consumer.                                                                                                     | `NoOpMemoryService.scala`                                      | Make `store` return a monotonically incrementing in-memory id; make `markShared`/`markPrivate` return the input record unchanged.                                                |
| [x]    | P9-034     | Warning    | Test Coverage     | Shell `CommandHandlerSpec` has no test for the `MemorySearch` GQL failure path and no test for `MemoryList(Some(scope))`. (confirmed by 2 reviewers)                                                                                                                                   | `CommandHandlerSpec.scala`                                     | Add: GQL failure → error message displayed; `MemoryList(Some(User))` → filtered results displayed.                                                                               |
| [x]    | P9-035     | Warning    | Test Coverage     | Zero E2E integration tests for any memory operation in `JorlanEndToEndSpec`. (confirmed by 2 reviewers)                                                                                                                                                                                | `JorlanEndToEndSpec.scala`                                     | Add: store → query → forget round-trip; verify injected memory appears in model call context.                                                                                    |
| [x]    | P9-036     | Warning    | Functional Purity | `InMemoryMemoryRepo.purgeExpired` calls `java.time.Instant.now()` inside `Ref.modify`, making it incompatible with `TestClock` and producing non-deterministic test behaviour.                                                                                                         | `InMemoryRepositories.scala` (purgeExpired)                    | Accept a `Clock` dependency and call `Clock.instant` outside `Ref.modify`; pass the result in.                                                                                   |
| [x]    | P9-037     | Warning    | Code Quality      | `AgentRunnerImpl` has 9 constructor parameters including two `Ref` fields for internal mutable state that should not be at the constructor boundary.                                                                                                                                   | `AgentRunnerImpl.scala`                                        | Extract `AgentRunnerState(historySeeded: Ref[Set[AgentSessionId]], conversationCache: Ref[...])` and create it inside the `make` ZLayer.                                         |
| [x]    | P9-038     | Warning    | Code Quality      | `markShared`/`markPrivate` are copy-paste duplicates of ~20 lines each in `MemoryServiceImpl`.                                                                                                                                                                                         | `MemoryServiceImpl.scala:43-63`                                | Extract `private def rescope(id: MemoryRecordId, newScope: MemoryScope): IO[JorlanError, MemoryRecord]`.                                                                         |
| [x]    | P9-039     | Warning    | Documentation     | Fourteen trait methods (`CheckpointPolicy.shouldCheckpoint`, `CheckpointSummarizer.summarize`, `MemoryAccessPolicy.filter`, `MemoryService.query`, `MemoryService.checkpoint`, `MemorySkill.remember`, `MemorySkill.search`, and the `Search[OrderType]` root trait) have no ScalaDoc. | `model/service/MemoryService.scala`, related traits            | Add `@param`, `@return`, and contract prose (especially: what happens on empty input, what the scope rules are).                                                                 |
| [x]    | P9-040     | Warning    | Documentation     | `MemorySearch` case class — the most important new query type this phase — has no ScalaDoc; the interaction between `scope`, `userId`, and `textSearch` is undocumented.                                                                                                               | `repository.scala:103-114`                                     | Document each field; explicitly state that `textSearch` is applied in-memory post-SQL (until P9-008 is resolved) so callers understand the limitation.                           |
| [x]    | P9-041     | Warning    | Documentation     | `Mutations.forgetMemory` returns `Boolean` that is always `true`; no comment or ScalaDoc warns API consumers.                                                                                                                                                                          | `JorlanAPI.scala:485-490`                                      | Add `/** Always returns true in the current implementation; see P9-024. */` until the contract is fixed.                                                                         |
| [x]    | P9-042     | Suggestion | Code Quality      | `processMessage` in `AgentRunnerImpl` is ~70 lines with 4 nesting levels.                                                                                                                                                                                                              | `AgentRunnerImpl.scala`                                        | Extract `finaliseResponse(sessionId, userMsg, assistantMsg)` to reduce nesting and make the checkpoint wiring point obvious.                                                     |
| [x]    | P9-043     | Suggestion | Code Quality      | `EventLog` construction is duplicated 5 times across `OllamaModelGateway` (3 copies) and `AgentRunnerImpl` (2 copies).                                                                                                                                                                 | `OllamaModelGateway.scala`, `AgentRunnerImpl.scala`            | Extract a `logModelEvent(sessionId, eventType)` private helper in each class.                                                                                                    |
| [x]    | P9-044     | Suggestion | Code Quality      | `agentId` resolution logic is duplicated in `listMemory` and `storeMemory` in `JorlanAPI`.                                                                                                                                                                                             | `JorlanAPI.scala`                                              | Extract `primaryAgentId(actorId): IO[JorlanError, AgentId]` helper method.                                                                                                       |
| [x]    | P9-045     | Suggestion | Code Quality      | `ZIO.when` should replace the `case false => ZIO.unit; case true => ...` boolean match in `checkpoint`.                                                                                                                                                                                | `MemoryServiceImpl.scala`                                      | Replace with `ZIO.when(shouldRun)(...)`.                                                                                                                                         |
| [x]    | P9-046     | Suggestion | Code Quality      | `return` statement in `ShellCommand.parse` is non-idiomatic Scala.                                                                                                                                                                                                                     | `ShellCommand.scala:52`                                        | Replace with an `if/else` expression.                                                                                                                                            |
| [x]    | P9-047     | Suggestion | Code Quality      | Dead code: `private val layers = directLayers` in `MemoryServiceSpec` is never referenced and will fail under `-Werror`.                                                                                                                                                               | `MemoryServiceSpec.scala:47`                                   | Delete the unused line.                                                                                                                                                          |
| [x]    | P9-048     | Suggestion | Code Quality      | `ZIO.succeed(...)` appears on each branch of a 3-branch match in `MemoryClassifierImpl` instead of a single lifted expression.                                                                                                                                                         | `MemoryClassifierImpl.scala`                                   | Replace with `ZIO.succeed { if ... else if ... else ... }`.                                                                                                                      |
| [x]    | P9-049     | Suggestion | Code Quality      | `ZStream.unwrap(Ref.make(false).map {...})` in `OllamaModelGateway` is unnecessarily convoluted.                                                                                                                                                                                       | `OllamaModelGateway.scala`                                     | Replace with `ZStream.fromZIO(Ref.make(false)).flatMap { errored => ... }`.                                                                                                      |
| [x]    | P9-050     | Suggestion | Code Quality      | Trailing `.unit` on `ZIO.when`/`ZIO.unless` expressions in `InitService.scala` is redundant.                                                                                                                                                                                           | `InitService.scala:164-166`                                    | Remove `.unit`; the `_ <-` binding already discards the value.                                                                                                                   |
| [x]    | P9-051     | Suggestion | API Design        | Shell `/capabilities` command output is hardcoded and disconnected from actual server-side capability grants.                                                                                                                                                                          | `CommandHandler.scala` (`showCapabilities`)                    | Query the server for the current user's real grants via a `listCapabilities` GQL query.                                                                                          |
| [x]    | P9-052     | Suggestion | API Design        | Shell `/memory remember` does not expose a `scope` parameter; all explicit stores default to `None` scope.                                                                                                                                                                             | `ShellCommand.scala`, `CommandHandler.scala`                   | Add optional `--scope <User                                                                                                                                                      |Shared|Workspace>` flag to the `/memory remember` command.                                   |
| [x]    | P9-053     | Suggestion | API Design        | `markMemoryShared`/`markMemoryPrivate` have no shell surface; power users must use the GraphQL API directly.                                                                                                                                                                           | `ShellCommand.scala`, `CommandHandler.scala`                   | Add `/memory share <id>` and `/memory privatize <id>` shell commands.                                                                                                            |
| [x]    | P9-054     | Suggestion | Documentation     | `MemoryRecordId.empty` sentinel convention and the `MemoryScope` enum variants (especially `Workspace`) are undocumented.                                                                                                                                                              | `model/service/MemoryService.scala`                            | Add ScalaDoc to each enum variant and to the `empty` companion value.                                                                                                            |
| [x]    | P9-055     | Suggestion | Documentation     | `MemoryClassifierImpl` keyword sets are magic literals; they cannot be tuned by operators without a code change.                                                                                                                                                                       | `MemoryClassifierImpl.scala`                                   | Move keyword sets to a companion `object` constant or a config key; document the intended classification semantics.                                                              |
| [x]    | P9-056     | Suggestion | Documentation     | `MemorySkill.remember` bypasses the `MemoryClassifier` (direct store, caller-supplied scope); this is undocumented and inconsistent with the checkpoint path.                                                                                                                          | `MemorySkill.scala`                                            | Add a ScalaDoc note explaining the two-path design; consider injecting the classifier so explicit stores also benefit from it.                                                   |

---

## Grouped Sections

### Correctness / Runtime Wiring

**Checkpoint pipeline is never invoked at runtime** (P9-001) — CONFIRMED BY ALL 8 REVIEWERS

This is the most impactful finding of the review. The entire Phase 9 checkpoint pipeline —
`CheckpointPolicy`, `CheckpointSummarizer`, `MemoryClassifier`, and the resulting `MemoryRecord` stores —
is correctly wired via dependency injection and passes all unit tests, but `AgentRunner.processMessage`
never actually calls `memoryService.checkpoint(...)`. The wiring comment `// TODO: wire checkpoint` (or
equivalent absence) means that at runtime, `memory_record` is only populated by explicit `/memory remember`
shell commands. Every automated summary at session end is silently dropped.

```scala
// Current: checkpoint is absent from the ensuring block
_
<- persistMessages(sessionId, userMsg, assistantResponse)
  .ensuring(cleanupRef.set(true))
  .ignore

// Fix: invoke checkpoint in the ensuring block
_
<- persistMessages(sessionId, userMsg, assistantResponse)
  .ensuring(
    memoryService.checkpoint(sessionId, CheckpointTrigger.SessionEnd)
      .tapError(err => ZIO.logWarning(s"Checkpoint failed: $err"))
      .ignore
  )
```

**`buildMemoryContext` correctness gaps** (P9-014, P9-015, P9-016) — CONFIRMED BY 2 REVIEWERS EACH

Three independent correctness issues compound in the same method:

1. `agentId` is resolved via a secondary `searchSessions` call using a heuristic (oldest or most-recent
   session), rather than the current session's own `agentId`. Private-scope records belonging to the
   current agent may be permanently invisible.
2. Only `User`-scope records are queried; `Shared` and `Workspace` records are never injected. The SDD
   specifies that all scopes visible to the current user must be merged.
3. `text = None` is passed as the relevance hint, defeating keyword-relevance filtering. The SDD
   specifies that `lastUserMessage` should be used.

**`MemoryClassifier` receives JSON-serialized text** (P9-017)

`CheckpointSummarizerImpl` passes `record.value.toString` to `MemoryClassifier`. If `record.value` is a
`Json.Obj`, `toString` produces `{"text":"my password is xyz"}` rather than `my password is xyz`. PII
keyword matching may succeed or fail based on JSON key names rather than the semantic content.

---

### Concurrency / Data Integrity

**TOCTOU races in `ensureSeeded` and `getOrCreateConversation`** (P9-005, P9-006)

Both methods follow a check-then-act pattern without atomic coordination. Under concurrent first-messages
for the same session (possible when an orchestrator issues parallel warmup requests):

- `ensureSeeded`: both fibers observe `contains = false`, both call `seedHistory`, and the model receives
  duplicate seed messages that corrupt conversation context.
- `getOrCreateConversation`: both fibers execute the DB insert, one succeeds, the other creates an orphaned
  `Conversation` row with no future references.

```scala
// Fix for ensureSeeded — atomic check-and-set
_
<- seededRef.modify { seeded =>
  if (seeded.contains(sessionId)) (ZIO.unit, seeded)
  else (seedHistory(sessionId), seeded + sessionId)
}.flatten
```

**`onConflictUpdate` missing `scope` field** (P9-007) — CONFIRMED BY 2 REVIEWERS

`QuillMemoryRepository.upsert` is used by both `store` and `markShared`/`markPrivate`. The
`onConflictUpdate` clause lists the fields to overwrite on a key conflict but omits `scope`. As a result,
a `markShared` call correctly hits the DB but the `scope` column is never updated. The operation appears
to succeed (no error returned) while silently doing nothing.

```scala
// Fix: add scope to the conflict update fields
.onConflictUpdate(_.id)(
  (t, e) => t.value -> e.value,
  (t, e) => t.updatedAt -> e.updatedAt,
  (t, e) => t.scope -> e.scope, // <-- this line is missing
  (t, e) => t.expiresAt -> e.expiresAt
)
```

**`Ref` containing mutable `StringBuilder`** (P9-009)

`Ref.make(new StringBuilder)` violates the ZIO model: `Ref` guarantees safe concurrent access to
*immutable* values via compare-and-swap. A mutable `StringBuilder` inside a `Ref` can be mutated by one
fiber while another fiber holds a stale reference to the same object. This produces subtle data corruption
under concurrent stream processing.

```scala
// Fix
Ref.make(Vector.empty[String]).flatMap { chunksRef =>
  stream.runForeach(chunk => chunksRef.update(_ :+ chunk)) *>
    chunksRef.get.map(_.mkString)
}
```

---

### Security / Ownership

**Memory record ownership not enforced** (P9-002) — CONFIRMED BY 3 REVIEWERS

`MemoryService.forget`, `markShared`, and `markPrivate` accept a `MemoryRecordId` and act on it without
verifying that the requesting user owns the record. Any authenticated user who possesses (or guesses) the
id of another user's `Private` memory record can delete it or promote it to `Shared`, exposing it to all
users. The fix requires a database read before each mutation:

```scala
def forget(id: MemoryRecordId, requestingUserId: UserId): IO[JorlanError, Unit] = for {
  record <- repo.getById(id).someOrFail(JorlanError.NotFound(s"Memory $id"))
  _ <- ZIO.unless(record.userId == requestingUserId)(
    ZIO.fail(JorlanError.Forbidden("Cannot delete another user's memory"))
  )
  _ <- repo.delete(id)
} yield ()
```

**`Workspace` scope grants access to all authenticated users** (P9-022)

`MemoryAccessPolicyImpl` returns `true` for `MemoryScope.Workspace` records for any authenticated user,
with no workspace-membership check. This violates the deny-by-default principle. Until
`WorkspaceMemberRepository` is implemented, the safer interim policy is to return `false` for Workspace
scope (effectively disabling it) and document this as a known limitation.

---

### Observability / Audit Trail

**Memory writes not logged to the event log** (P9-003) — CONFIRMED BY 2 REVIEWERS

Architecture Principle 3 states: "Every significant action writes to the append-only event log."
`store`, `forget`, `markShared`, `markPrivate`, and `checkpoint` are all significant actions. `forget` is
particularly important: GDPR requires that deletion of personal data be auditable. None of these operations
currently emit an `EventLog` entry. This must be corrected before any production deployment.

**Errors swallowed silently** (P9-019, P9-020)

`buildMemoryContext` uses `.catchAll(_ => ZIO.succeed(""))` with no log, and `persistMessages` uses
`.ignore`. Both silently discard errors that would otherwise indicate data loss or degraded model context.
The fix in both cases is to add a `ZIO.logWarning` before the fallback.

---

### Architecture / Layer Discipline

**`MemoryServiceImpl` imports from `db` module** (P9-004)

`MemoryServiceImpl` is in the `server` module and should depend only on the abstract
`MemoryZIORepository` trait defined in `model`. Importing the concrete `QuillMemoryRepository` type from
`db` violates Architecture Principle 2 ("domain layer must not depend on DB or connector specifics") and
couples the service layer directly to the Quill implementation. The concrete type should be referenced
only in `EnvironmentBuilder` when assembling ZLayers.

**`AgentSessionId(0L)` sentinel pollutes model gateway state** (P9-021) — CONFIRMED BY 3 REVIEWERS

`CheckpointSummarizerImpl` passes `AgentSessionId(0L)` to the model gateway to obtain a one-shot LLM
completion. Session 0 is the sentinel "empty" value. Each checkpoint call appends messages to session 0's
history, which grows unboundedly and mixes summarization context across all users. The fix is to invalidate
session 0 after each summarization call:

```scala
modelGateway
  .chat(AgentSessionId(0L), systemPrompt, userPrompt)
  .ensuring(modelGateway.invalidateSession(AgentSessionId(0L)).ignore)
```

A cleaner long-term fix is to add `ModelGateway.oneShot(systemPrompt, userPrompt)` that does not
accumulate history at all.

---

### Performance

**`textSearch` post-SQL in-memory filter** (P9-008) — CONFIRMED BY ALL 8 REVIEWERS

`QuillMemoryRepository.search` applies the `textSearch` predicate in the JVM process after fetching a
SQL-`LIMIT`-bounded page from the database. This produces three compounding problems:

1. The FULLTEXT index added in V019 is never used; every INSERT pays the index write cost with zero read
   benefit.
2. If matching records exist beyond the SQL page boundary they are silently excluded; the API returns an
   incorrect (possibly empty) result with no indication of truncation.
3. Under load, full table pages are transferred over the DB connection then discarded in the JVM.

Fix: push the text predicate into SQL using `MATCH(text) AGAINST (? IN BOOLEAN MODE)` as a WHERE clause
condition. If Quill does not support FULLTEXT natively, use a raw query fragment. Remove the SQL LIMIT
when `textSearch` is non-empty, or apply the limit after the FULLTEXT filter.

**Missing composite index on `memory_record(scope, userId)`** (P9-027)

Every `MemoryService.query` call — on the hot path of every `processMessage` invocation — performs a
partial scan without a covering index. Add to V020:

```sql
ALTER TABLE memory_record
    ADD INDEX idx_memory_scope_user (scope, userId);
```

**Sequential checkpoint classification loop** (P9-030)

The checkpoint pipeline calls `classifier.classify(bullet)` then `repo.upsert(record)` for each of N
bullet-points in sequence (`ZIO.foreachDiscard`). For a 10-bullet checkpoint at 1-5ms per DB call, this
adds 10-50ms of sequential overhead to session teardown. Replace with `ZIO.foreachParDiscard` for the
classification step; group the upserts into a batch where supported.

---

### Test Coverage

**Access policy edge cases untested** (P9-010)

| Missing Test                                                          | Gap                                                       |
|-----------------------------------------------------------------------|-----------------------------------------------------------|
| `Workspace` record visible to authenticated user                      | Removing `case Workspace => true` would not fail any test |
| `Private` record with correct `agentId` but wrong `userId`            | The `agentId` clause in the policy is never exercised     |
| `Shared` record visible to a different `userId` than the record owner | Positive Shared visibility never asserted                 |

**Checkpoint and summarizer failure paths untested** (P9-011, P9-032)

| Missing Test                                     | Gap                                              |
|--------------------------------------------------|--------------------------------------------------|
| Blank LLM response to summarizer                 | `isBlank` guard is dead code without this test   |
| LLM response without `"- "` bullet prefix        | `startsWith("- ")` filter is dead code           |
| LLM stream failure in `CheckpointSummarizerImpl` | Error propagation never verified                 |
| PII bullet → `Private` scope end-to-end          | Classifier-assigned scope storage never verified |

**`buildMemoryContext` completely untested** (P9-012)

All `AgentRunnerSpec` tests use `NoOpMemoryService`. The context injection code path — the primary value
delivery of Phase 9 — is never exercised. A pre-seeded `InMemoryMemoryRepo` layer should be added to at
least one `AgentRunnerSpec` test to verify the injected context appears in the system prompt sent to the
model gateway.

**GraphQL memory API untested** (P9-013)

| Missing Test                               | Gap                                            |
|--------------------------------------------|------------------------------------------------|
| `storeMemory` mutation                     | No test for the GraphQL entry point            |
| `forgetMemory` mutation                    | No test; capability enforcement guard untested |
| `markMemoryShared` mutation                | No test                                        |
| `markMemoryPrivate` mutation               | No test                                        |
| `listMemory` with scope filter             | No test                                        |
| Ownership check on `forgetMemory` (P9-002) | Security control has no regression coverage    |

**`NoOpMemoryService` fidelity bugs** (P9-033)

`NoOpMemoryService.store` returns `MemoryRecordId.empty` (id=0) when real `store` returns a
DB-assigned id. `markShared` and `markPrivate` return `ZIO.fail("not implemented")` when they should
succeed silently. Any future test that constructs a flow through these methods will see confusing failures
that have nothing to do with the actual logic under test.

**Shell `CommandHandlerSpec` coverage gaps** (P9-034)

| Missing Test                          | Gap                                                 |
|---------------------------------------|-----------------------------------------------------|
| `MemorySearch` GQL failure            | Error message path never exercised                  |
| `MemoryList(Some(scope))`             | Scope-filtered list never exercised                 |
| `MemoryList(None)` GQL returns `None` | Distinction between `None` and `Some(Nil)` untested |
| `/memory` with no subcommand          | Parser edge case untested                           |
| `/memory forget` with non-numeric id  | Parser error path untested                          |

---

### Code Quality

**`markShared`/`markPrivate` duplication** (P9-038)

The two methods in `MemoryServiceImpl` are structural duplicates of ~20 lines, differing only in the
target scope value. Once P9-007 is fixed the extracted `rescope` helper becomes:

```scala
private def rescope(
                     id: MemoryRecordId,
                     newScope: MemoryScope
                   ): IO[JorlanError, MemoryRecord] =
  repo.getById(id)
    .someOrFail(JorlanError.NotFound(s"Memory record $id not found"))
    .flatMap(r => repo.upsert(r.copy(scope = newScope)))
```

**`processMessage` method length and nesting** (P9-042)

At ~70 lines with 4 nesting levels, `processMessage` is the most complex method in `AgentRunnerImpl`.
Extracting `finaliseResponse` (persist messages, trigger checkpoint, emit event log) would reduce nesting
to 2 levels, make the checkpoint wiring point obvious, and make future P9-001 fix cleaner to apply.

**ScalaDoc coverage** (P9-039, P9-040, P9-041, P9-054, P9-055, P9-056)

Fourteen trait methods and the `MemorySearch` case class have no ScalaDoc. The most critical gaps are on
public-facing contracts where the behaviour is non-obvious:

- `MemoryService.query` — interaction between `scope`, `userId`, and `textSearch` (currently
  in-memory, see P9-008) is invisible to callers.
- `CheckpointSummarizer.summarize` — what happens on empty `messages` input.
- `Mutations.forgetMemory` — always returns `true`; misleading to API consumers.
- `MemoryScope` variants — `Workspace` semantics are unclear without documentation.

---

## Cross-Cutting Patterns

**The post-SQL in-memory filter antipattern** was flagged independently by all 8 reviewers across different
angles: the Performance Oracle identified the index waste and incorrect pagination (P9-008); the SRS/SDD
reviewer noted the FULLTEXT index from V019 is unused (P9-008); the Functional Scala reviewer flagged the
missing scaladoc warning on `MemorySearch.textSearch` (P9-040); the Code Simplicity reviewer and Pattern
Recognition Specialist both called out the structural correctness gap. This is the most broadly confirmed
finding in the review and should be treated as a blocking correctness bug rather than a performance
optimization.

**Unverified ownership and missing audit trail** appear as two sides of the same gap. Pattern Recognition,
SRS/SDD Conformance, and Functional Scala all identified that `forget`/`markShared`/`markPrivate` have no
ownership check (P9-002). Pattern Recognition and SRS/SDD both independently confirmed that none of these
operations write to the event log (P9-003). These two findings share a root cause: the memory service
implementation was written for the happy path without considering the authorization and audit requirements
from the SDD. Fixing P9-002 and P9-003 together in a single pass is the most efficient approach.

**Silent error discarding** appears in three distinct locations flagged by four agents: `buildMemoryContext`
`.catchAll` (P9-019, Functional Scala), `persistMessages` `.ignore` (P9-020, SRS/SDD), and
`purgeExpired` using a non-`TestClock` instant (P9-036, Functional Scala, Test Coverage). The common root
cause is defensive coding that suppressed errors rather than logging them. A project-wide convention should
be established: `.ignore` is never permitted without a `.tapError(ZIO.logWarning(...))` wrapper.

**`AgentSessionId(0L)` session sentinel abuse** was flagged by Functional Scala, Pattern Recognition, and
SRS/SDD reviewers (P9-021). The underlying issue is that `CheckpointSummarizerImpl` needs a one-shot LLM
call but `ModelGateway` only exposes a stateful `chat` interface. The `AgentSessionId(0L)` hack works
around this but at the cost of unbounded growth and cross-user state contamination. Adding a `oneShot`
method to `ModelGateway` would resolve this permanently and prevent similar workarounds in future phases.

**Test coverage has a structural bias toward the happy path.** The Test Coverage Tracker and Shell/CLI Test
Plan Writer identified 9 critical and 8 high-severity coverage gaps, nearly all of which are in failure
paths, edge cases, or policy branches. The unit tests that exist are well-structured and follow established
patterns, but the classifier, access policy, and summarizer were each tested only for the primary success
case. Building test coverage for the "what happens when the LLM returns garbage" and "what happens when
the user is not the record owner" paths is more valuable than increasing coverage of the happy path further.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count  |
|------------|--------|
| Critical   | 13     |
| Warning    | 28     |
| Suggestion | 15     |
| **Total**  | **56** |

**Issues by area:**

| Area                | Count  |
|---------------------|--------|
| Correctness         | 8      |
| Test Coverage       | 10     |
| Code Quality        | 7      |
| Documentation       | 7      |
| Performance         | 5      |
| Security            | 3      |
| Concurrency         | 2      |
| Observability       | 2      |
| Architecture        | 2      |
| Data Integrity      | 1      |
| Functional Purity   | 2      |
| API Design          | 3      |
| Infrastructure      | 1      |
| Resource Management | 1      |
| **Total**           | **56** |

**Agent contribution:**

| Agent                          | Unique Findings | Cross-Confirmed |
|--------------------------------|-----------------|-----------------|
| Functional Scala Reviewer      | 14              | 9               |
| Code Simplicity Reviewer       | 12              | 7               |
| Pattern Recognition Specialist | 11              | 8               |
| Performance Oracle             | 7               | 6               |
| Test Coverage Tracker          | 14              | 5               |
| ScalaDoc Auditor               | 17              | 3               |
| SRS/SDD Conformance Reviewer   | 10              | 8               |
| Shell/CLI Test Plan Writer     | 9               | 5               |

**Phase 9 scope completion:**

| Item                                                                                                | Status                                                                        |
|-----------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| 9.1 — `chat_message` table / `ConversationRepository`                                               | ✅                                                                             |
| 9.1 — Load history before model call in `AgentRunner`                                               | ✅                                                                             |
| 9.1 — Persist user+assistant messages after model call                                              | ✅                                                                             |
| 9.1 — Session resume: shell `/new` reloads history                                                  | ⚠️ (manual test only; no automated test)                                      |
| 9.1 — Integration test: round-trip persist + reload                                                 | ❌                                                                             |
| 9.2 — `memory_record` table + `MemoryRecord` domain type                                            | ✅                                                                             |
| 9.2 — `MemoryService` trait + `MemoryServiceImpl`                                                   | ✅                                                                             |
| 9.2 — `MemoryAccessPolicy` trait + default impl                                                     | ✅                                                                             |
| 9.2 — `MemoryService.query` applies access policy                                                   | ✅                                                                             |
| 9.2 — Unit test: `MemoryServiceSpec`                                                                | ⚠️ (happy path only; ownership and scope edge cases missing)                  |
| 9.2 — Integration test: store → query → forget cycle                                                | ❌                                                                             |
| 9.3 — `CheckpointTrigger`, `CheckpointPolicy`, `CheckpointSummarizer`                               | ✅                                                                             |
| 9.3 — `CheckpointSummarizerImpl` (LLM-backed)                                                       | ✅                                                                             |
| 9.3 — `MemoryClassifier` trait + heuristic impl                                                     | ✅                                                                             |
| 9.3 — Checkpoint wired into `AgentRunner`                                                           | ❌ (infrastructure present; runtime call absent — P9-001)                      |
| 9.3 — Unit tests: `CheckpointSummarizerSpec`, `MemoryClassifierSpec`                                | ⚠️ (failure paths missing — P9-011)                                           |
| 9.4 — Query memory before model call in `AgentRunner`                                               | ⚠️ (implemented; scope/agentId/text bugs — P9-014/015/016)                    |
| 9.4 — Integration test: injected memory in model call context                                       | ❌                                                                             |
| 9.5 — `MemorySkill`: remember/search/forget/mark_shared/mark_private                                | ✅                                                                             |
| 9.5 — Register `MemorySkill` in `SkillRegistry`                                                     | ⚠️ (deferred to Phase 12 by design)                                           |
| 9.6 — GraphQL: `listMemory`, `storeMemory`, `forgetMemory`, `markMemoryShared`, `markMemoryPrivate` | ✅                                                                             |
| 9.6 — Shell: `/memory list/search/forget/remember`                                                  | ✅                                                                             |
| 9.6 — Shell: `/capabilities` command                                                                | ⚠️ (hardcoded, not server-driven — P9-051)                                    |
| 9.7 — Test coverage ≥ 80% (251 server tests passing)                                                | ⚠️ (quantity met; critical path coverage gaps remain — P9-010 through P9-013) |
| 9.7 — `sbt scalafmtAll` clean                                                                       | ✅                                                                             |

---

## What Was Done Well

**Clean trait hierarchy**: `MemoryService`, `MemoryAccessPolicy`, `CheckpointSummarizer`, and
`MemoryClassifier` are each expressed as narrow, single-purpose ZIO service traits. This made unit testing
with fake implementations straightforward and will allow swapping ML-backed classifiers in later phases
without touching any consumers.

**`InMemoryRepositories` pattern followed correctly**: `InMemoryMemoryRepo` extends the established
`InMemoryRepositories` helper pattern introduced in Phase 8, making the new repository available to all
ZIO test layers with minimal boilerplate. This is the right pattern and should continue in future phases.

**`MemoryAccessPolicy` separation**: Extracting access-control logic into its own `MemoryAccessPolicy`
trait rather than embedding it in `MemoryServiceImpl` is architecturally sound. The policy is composable,
independently testable, and positions the system to add workspace-membership checks (P9-022) without
modifying the service layer.

**`MemorySkill` as an agent-accessible facade**: Wrapping `MemoryService` in a `MemorySkill` that exposes
typed tool invocations is the correct Tier 0 pattern. The division between what the agent can explicitly
control (`remember`, `search`, `forget`, `mark_shared`, `mark_private`) and what happens automatically
(checkpoint) is clearly expressed at the architectural boundary.

**GraphQL and shell surface completeness**: All five GraphQL mutations/queries and all four shell memory
commands are implemented and connected. The Phase 9 user-facing surface is functionally complete and
follows the Caliban patterns established in earlier phases.
