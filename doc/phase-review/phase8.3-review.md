/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law. All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders. If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

# Phase 8.3/8.4 Tech Debt Report — Jorlan

**Reviewed by**: Functional Scala Reviewer, ScalaDoc Auditor, Performance Oracle, Test Coverage Tracker, Code Simplicity Reviewer, Pattern Recognition Specialist, SRS/SDD Conformance Reviewer
**Date**: 2026-06-01
**Branch**: `phase-8.3/server-personality`
**Scope**: Phase 8.3 (Server Personality & Identity) + Phase 8.4 (Agent Testing on CI) — `server/src/main/scala/jorlan/service/PersonalityServiceImpl.scala`, `server/src/main/scala/jorlan/service/OllamaModelGateway.scala`, `server/src/main/scala/jorlan/graphql/JorlanAPI.scala`, `shell/src/main/scala/jorlan/shell/commands/CommandHandler.scala`, `shell/src/main/scala/jorlan/shell/client/InitClient.scala`, `server/src/main/resources/sql/V018__personality.sql`, `server/src/test/scala/jorlan/graphql/JorlanAPISpec.scala`, `server/src/test/scala/jorlan/service/PersonalityServiceSpec.scala`, `.github/workflows/scala.yml`

---

## Executive Summary

The Phase 8.3 personality subsystem is structurally sound. `PersonalityServiceImpl` is correctly implemented with `Ref`-cached state, effectful startup loading, and proper ZIO layer placement. `Personality.buildSystemPrompt` is pure, exhaustive, and thoroughly tested across all formality values and language edge cases. The `PersonalityService` trait and companion follow the exact established pattern of `EventLogService` and `UserService` — domain type and trait in `model/`, implementation in `server/`. The Phase 8.4 CI groundwork (Ollama installation, `FakeModelGateway` usage in `AgentRunnerSpec` and `AgentSessionManagerSpec`) is in place and CI-safe.

Two critical defects require attention before these features can be relied upon in any production install. First, the Flyway V018 seed row encodes `"formality":"professional"` in lowercase, but the zio-json derived codec for `Formality` expects PascalCase (`"Professional"`); the decode silently fails and `PersonalityServiceImpl` falls back to `Personality.default`, rendering the seeded custom prompt dead on arrival on every new installation. This was independently confirmed by four reviewers. Second, the `serverPersonality` GraphQL query carries no authentication guard despite every other compound query in the API gating on a capability; the admin-authored system prompt — which may contain sensitive operational instructions — is readable by any unauthenticated caller. This was also confirmed by four reviewers.

**Overall health: Clean — all 20 actionable items resolved. Two suggestions (P8.3-021 CI cache key digest, P8.3-022 live gateway smoke test) intentionally deferred to Phase 9. Ready to advance.**

Documentation coverage is incomplete. The `serverPersonality` and `updatePersonality` GraphQL fields lack ScalaDoc, the `PersonalityKey` constant is undocumented, and the `OllamaModelGateway` class doc describes `Ref.modify` usage that is no longer present in the implementation and omits the behavioral consequence (conversation history loss) of a personality update.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area             | Issue                                                                                                                                                                    | File : Line                                        | Recommended Action                                                                                                              |
|--------|------------|------------|------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| [x]    | P8.3-001   | Critical   | Data Integrity   | V018 seeds `"formality":"professional"` (lowercase); zio-json derived codec expects PascalCase; decode fails silently and every new install falls back to `Personality.default`. (confirmed by 4 reviewers) | `V018__personality.sql:7`                          | Change seed value to `"formality":"Professional"`, OR add a case-insensitive custom `JsonDecoder[Formality]`.                   |
| [x]    | P8.3-002   | Critical   | Security         | `serverPersonality` GraphQL query has no authentication guard; admin system prompt is readable by any unauthenticated caller. (confirmed by 4 reviewers)                  | `JorlanAPI.scala:291`                              | Add `actorIdFromSession` + `requireCapability("admin.personality.read", actorId)`, or add an explicit comment documenting the intentional public-read decision. |
| [x]    | P8.3-003   | Warning    | Concurrency      | `OllamaModelGateway.getOrCreate` uses a two-step `sessions.get` then `sessions.update`; concurrent fibers can both miss an entry, build duplicate `StreamAssistant` instances, and the second overwrites the first, leaking the first assistant's memory. (confirmed by 4 reviewers) | `OllamaModelGateway.scala:65-78`                   | Replace the two-step read/write with a single `Ref.modify` for a conditional atomic insert; discard the redundant assistant if another fiber won the race. |
| [x]    | P8.3-004   | Warning    | Observability    | `PersonalityServiceImpl.live` silently discards a corrupt stored personality (`getOrElse(Personality.default)`) with no log output; operators have no diagnostic signal. (confirmed by 2 reviewers) | `PersonalityServiceImpl.scala:44-46`               | Replace with `ZIO.logWarning(s"Corrupt personality in settings: $err").as(Personality.default)` in the `Left` case.            |
| [x]    | P8.3-005   | Warning    | Performance      | `InitClient.checkStatus` has no read timeout; if the server accepts the TCP connection but is slow to respond, the shell hangs at startup indefinitely with no recovery path. | `InitClient.scala` (called from `JorlanShell.scala:119`) | Add `.readTimeout(5.seconds)` to the sttp request.                                                                              |
| [x]    | P8.3-006   | Warning    | Test Coverage    | No GraphQL-level tests for `serverPersonality` query or `updatePersonality` mutation; `fakePersonality` is wired into the test layer but no test exercises the Caliban interpreter for these fields. (confirmed by 3 reviewers) | `JorlanAPISpec.scala`                              | Add `personalitySuite` with at least three cases: query returns default, mutation succeeds with `allowAll` evaluator, mutation is rejected with `denyAll`. |
| [x]    | P8.3-007   | Warning    | API Design       | Shell `/personality` command shows current personality (display path) but the update path — explicitly specified in the Phase 8.3 roadmap — is not implemented; `updatePersonality` mutation is unreachable from the shell. (confirmed by 2 reviewers) | `CommandHandler.scala`                             | Implement update sub-commands or interactive prompts for `/personality` that call the `updatePersonality` mutation.             |
| [x]    | P8.3-008   | Warning    | Documentation    | `OllamaModelGateway` class doc claims all reads/writes use `Ref.modify` (now incorrect) and does not mention conversation-history loss on personality update.             | `OllamaModelGateway.scala` (class-level doc)       | Correct the docstring to reflect the current two-step pattern (or the fixed `Ref.modify` pattern after P8.3-003 is resolved), and document the history-loss contract. |
| [x]    | P8.3-009   | Suggestion | Code Quality     | `fakePersonality` anonymous `PersonalityService` implementation is copy-pasted across three test files; any new method on the trait requires three independent edits. (confirmed by 2 reviewers) | `AgentRunnerSpec.scala:25`, `JorlanAPISpec.scala:85`, `GraphQLApiSpec.scala:35` | Extract `FakePersonalityService.layer` alongside `FakeModelGateway.scala` in the server test module.                            |
| [x]    | P8.3-010   | Suggestion | Test Coverage    | `PersonalityServiceImpl` `Some(json)` deserialization branch is never reached in unit tests; all tests start with an empty `InMemoryServerSettingsRepo`, so a corrupt stored value silently falling to default is undetectable. | `PersonalityServiceSpec.scala`                     | Add a test that pre-seeds the repo with a corrupt JSON value and asserts that the service starts with `Personality.default`.    |
| [x]    | P8.3-011   | Suggestion | Documentation    | `PersonalityKey` constant has no ScalaDoc; `AgentRunnerImpl` class and its `personalityService` constructor parameter are undocumented.                                   | `ServerSettingsRepository.scala:38`, `AgentRunnerImpl.scala:18` | Add one-sentence ScalaDoc to each.                                                                                              |
| [x]    | P8.3-012   | Suggestion | Documentation    | `serverPersonality` and `updatePersonality` GraphQL fields lack ScalaDoc; the required capability string for `updatePersonality` is not surfaced in field-level documentation. | `JorlanAPI.scala`                                  | Add ScalaDoc to both fields noting purpose and, for the mutation, the required capability `admin.personality.update`.           |
| [x]    | P8.3-013   | Suggestion | API Design       | `ServerPersonalityInput` does not follow the `VerbNounInput` naming convention used by every other mutation input type in the API (`CreateUserInput`, `UpdateUserInput`, etc.). (confirmed by 2 reviewers) | `JorlanAPI.scala:131`                              | Rename to `UpdatePersonalityInput`.                                                                                             |
| [x]    | P8.3-014   | Suggestion | Code Quality     | `showPersonality` in `CommandHandler` reaches 5 levels of nesting (JSON parsing `for` comprehension inside a lambda inside `foldZIO` success branch); not independently testable. (confirmed by Code Simplicity Reviewer) | `CommandHandler.scala:188-213`                     | Extract `parsePersonalityText(json: Json): Option[String]` as a pure function; make `foldZIO` branches symmetric.              |
| [x]    | P8.3-015   | Suggestion | Infrastructure   | CI workflow does not pass `--error` to sbt, contradicting the `CLAUDE.md` project mandate for terse, token-efficient output. (confirmed by 2 reviewers)                   | `.github/workflows/scala.yml:66`                   | Change `sbt test` to `sbt --error test`.                                                                                        |
| [x]    | P8.3-016   | Suggestion | Code Quality     | `import scala.language.unsafeNulls` is scoped inside lambdas at lines 195 and 233 of `CommandHandler.scala` rather than at file scope.                                    | `CommandHandler.scala:195,233`                     | Move to top-level import.                                                                                                       |
| [x]    | P8.3-017   | Suggestion | Code Quality     | `setTrace` enumerates the valid level set twice — once in a `Set` and once in a match expression — for the same five string values.                                        | `CommandHandler.scala`                             | Replace both with a single `Map[String, Level]`; derive the valid-set from its `keySet`.                                        |
| [x]    | P8.3-018   | Suggestion | Code Quality     | The `(StreamAssistant, String)` tuple stored in the `sessions` `Ref` has no named structure; field access is positional and fragile.                                       | `OllamaModelGateway.scala`                         | Introduce a `private case class SessionEntry(assistant: StreamAssistant, modelId: String)`.                                     |
| [x]    | P8.3-019   | Suggestion | Documentation    | `/personality` shell command is not listed in the roadmap appendix table of supported shell commands.                                                                      | `development_roadmap.md`                           | Add `/personality` row to the appendix table.                                                                                   |
| [x]    | P8.3-020   | Suggestion | Documentation    | `StreamedChatSpec` file name is misleading — the file only tests `LangChainConfig` defaults, not streaming.                                                               | `StreamedChatSpec.scala`                           | Rename to `LangChainConfigSpec`.                                                                                                |
| [ ]    | P8.3-021   | Suggestion | Infrastructure   | CI Ollama cache key does not include the model digest; if an Ollama tag is updated upstream, CI may run with stale model weights without any indication.                   | `.github/workflows/scala.yml`                      | Known limitation; consider including a manifest digest in the cache key in a future infrastructure pass.                        |
| [ ]    | P8.3-022   | Suggestion | Test Coverage    | Ollama is installed in CI (Phase 8.4 groundwork) but no test actually exercises the live gateway; the integration is wired but untested end-to-end.                        | `.github/workflows/scala.yml`, `OllamaModelGateway.scala` | Track as a future-phase item; add a live gateway smoke test once the memory system (Phase 9) stabilises the test harness.      |

---

## Grouped Sections

### Correctness / Data Integrity

**V018 enum case mismatch causes silent fallback to default personality on every new install** (P8.3-001) — CONFIRMED BY 4 REVIEWERS

The Flyway migration `V018__personality.sql` seeds the `server_settings` table with the JSON value `"formality":"professional"` (lowercase). The zio-json codec derived for the `Formality` sealed enum encodes and decodes enum values using their PascalCase class names (`"Professional"`, `"Casual"`, `"Academic"`, `"Technical"`). When `PersonalityServiceImpl.live` reads this row at startup, `json.as[Personality]` fails with a decode error on the `formality` field. The `getOrElse(Personality.default)` call swallows the failure and the service starts up serving `Personality.default` — the seeded `prompt` text and any other seeded customisation are completely ignored. Every clean installation is silently broken; operators who write a meaningful system prompt into the seed and redeploy will find it has no effect.

The roadmap spec itself shows `"formality":"professional"` in the design section (line 401), so the seed faithfully follows the spec — but the spec predates the codec decision to use PascalCase.

Recommended fix — change V018 to use `"Professional"`:

```sql
-- V018__personality.sql line 7 (corrected)
INSERT INTO server_settings (key, value) VALUES (
  'personality',
  '{"name":"Jorlan","formality":"Professional","languages":["en"],"expertise":[],"prompt":"..."}'
);
```

Alternative: add a case-insensitive custom decoder:

```scala
given JsonDecoder[Formality] = JsonDecoder[String].mapOrFail { s =>
  Formality.values.find(_.toString.equalsIgnoreCase(s))
    .toRight(s"Unknown Formality: $s")
}
```

The SQL fix is lower-risk; the custom decoder is more defensive against future seed drift.

---

### Security

**`serverPersonality` query is unauthenticated** (P8.3-002) — CONFIRMED BY 4 REVIEWERS

Every other compound query in `JorlanAPI.scala` gates on `actorIdFromSession` + `requireCapability` before returning data. The new `serverPersonality` field at line 291 reads:

```scala
serverPersonality = ZIO.serviceWithZIO[PersonalityService](_.get())
```

There is no session check and no capability check. The admin-authored system prompt may contain sensitive operational instructions (business rules, data handling policies, persona details the operator considers proprietary). Any caller who can reach the GraphQL endpoint — including unauthenticated external requests if the server is internet-facing — can read this data.

Recommended fix — mirror the pattern used by every other admin query:

```scala
serverPersonality = for {
  actorId <- actorIdFromSession
  _       <- requireCapability("admin.personality.read", actorId)
  p       <- ZIO.serviceWithZIO[PersonalityService](_.get())
} yield p
```

If a design decision has been made that personality is intentionally public (for example, to allow UI branding before login), that decision must be documented with an explicit comment at the field site so future reviewers do not flag it as an oversight.

---

### Concurrency

**`OllamaModelGateway.getOrCreate` is susceptible to a TOCTOU race** (P8.3-003) — CONFIRMED BY 4 REVIEWERS

The class docstring claims "all reads/writes use `Ref.modify` to avoid TOCTOU races." The implementation at lines 65–78 performs a two-step operation: first `sessions.get` (a read), then conditionally `sessions.update` (a write). Between these two ZIO steps, the fiber can be preempted. Under concurrent messages arriving for the same session simultaneously, two fibers can both observe the entry as absent, both construct a `StreamAssistant` with a fresh `InMemoryChatMemoryStore`, and race to write. The second writer silently overwrites the first. The first assistant's `InMemoryChatMemoryStore` leaks, and any response streamed by the first fiber before it is overwritten is lost. This is a correctness issue for long-lived sessions under moderate concurrency.

Recommended fix — use a single `Ref.modify` for the conditional atomic insert:

```scala
// Build the candidate entry before entering modify (may be discarded)
def buildEntry(sessionId: SessionId): UIO[SessionEntry] = ???

sessions.modify { map =>
  map.get(sessionId) match {
    case Some(existing) => (existing, map)            // another fiber won the race; use theirs
    case None           => (candidate, map + (sessionId -> candidate))  // we win; install ours
  }
}
```

The candidate `StreamAssistant` built by the losing fiber must be shut down or discarded to avoid the memory leak. The fix requires building the candidate speculatively before entering `modify` and then conditionally discarding it.

---

### Observability / Error Handling

**`PersonalityServiceImpl.live` silently discards corrupt stored personality** (P8.3-004) — CONFIRMED BY 2 REVIEWERS

When the row in `server_settings` exists but cannot be decoded as `Personality`, the service falls back to `Personality.default` with no log output. Operators who store an invalid JSON structure (for example, after a manual DB edit or a partial migration failure) will see the system silently revert to the default personality. There is no `ZIO.logWarning` or `ZIO.logError` call to indicate what went wrong or what key was corrupt.

Recommended fix:

```scala
json.as[Personality] match {
  case Right(p)  => ZIO.succeed(p)
  case Left(err) =>
    ZIO.logWarning(s"Corrupt personality in server_settings (key=personality): $err. Falling back to default.") *>
      ZIO.succeed(Personality.default)
}
```

This fix is especially important because P8.3-001 (the enum case mismatch) means this branch is hit on every new install until that bug is resolved.

**`InitClient.checkStatus` has no read timeout** (P8.3-005)

The sttp request in `InitClient.checkStatus` is issued with no explicit read timeout. The JVM `java.net.http.HttpClient` default is no read timeout. If the server accepts the TCP connection but is slow to respond (e.g., under DB pressure at startup), the shell process hangs at the login screen indefinitely with no user-visible progress indicator and no recovery path until the OS-level connection timeout fires (typically several minutes).

Recommended fix:

```scala
basicRequest
  .get(statusUri)
  .readTimeout(5.seconds)
  .send(backend)
```

---

### Test Coverage

**No GraphQL-level tests for personality query or mutation** (P8.3-006) — CONFIRMED BY 3 REVIEWERS

**`PersonalityServiceImpl` deserialization from pre-populated store untested** (P8.3-010)

The `fakePersonality` stub has been correctly added to the test layer in `JorlanAPISpec.scala`, but no test actually submits a `{ serverPersonality { ... } }` query or a `mutation { updatePersonality(...) }` mutation through the Caliban interpreter. The capability gate on `updatePersonality` is entirely untested at the GraphQL level. The existing `PersonalityServiceSpec` exercises the service in isolation with 15 thorough tests, but the GraphQL surface area is dark.

Similarly, all `PersonalityServiceSpec` tests start from an empty `InMemoryServerSettingsRepo`. The `Some(json)` deserialization branch in `PersonalityServiceImpl.scala:43-47` — the path that actually runs on a non-fresh installation — is never reached. The silent fallback discovered in P8.3-001 and P8.3-004 cannot be caught by the current test suite.

| Missing Test                                               | Gap                                                                            |
|------------------------------------------------------------|--------------------------------------------------------------------------------|
| GraphQL `serverPersonality` query returns default          | Query path through Caliban interpreter is untested                             |
| GraphQL `updatePersonality` succeeds with `allowAll`       | Mutation path and persistence through GraphQL layer untested                   |
| GraphQL `updatePersonality` rejected with `denyAll`        | Admin capability guard on mutation is untested                                 |
| `PersonalityServiceImpl` loads pre-seeded valid JSON       | `Some(json)` branch never exercised; regression risk for P8.3-001 fix          |
| `PersonalityServiceImpl` logs warning on corrupt JSON      | `Left` error branch and its observability improvement (P8.3-004) untested      |

Recommended fix: add a `personalitySuite` to `JorlanAPISpec.scala` and a pre-seeded-repo test to `PersonalityServiceSpec.scala`.

**Shell `/personality` update path not implemented** (P8.3-007) — CONFIRMED BY 2 REVIEWERS

The Phase 8.3 roadmap explicitly specifies: "sub-commands or interactive prompts allow updating individual fields or the full prompt." The shell implementation at `CommandHandler.scala` displays the current personality correctly but there is no mechanism to invoke `updatePersonality` from the shell. The mutation exists server-side and is guarded by the correct capability, but it is unreachable from the shell interface. This is a roadmap item marked `[x]` that is only half-complete.

---

### Code Quality

**`fakePersonality` implementation triplicated across test files** (P8.3-009) — CONFIRMED BY 2 REVIEWERS

An anonymous class implementing `PersonalityService` with hardcoded default values appears identically in `AgentRunnerSpec.scala:25`, `JorlanAPISpec.scala:85`, and `GraphQLApiSpec.scala:35`. The project already has a clean precedent for this pattern: `FakeModelGateway.scala`. Any new method added to `PersonalityService` requires three independent edits to the test suite, and any discrepancy between the three implementations can cause subtle test divergence.

Recommended fix: create `server/src/test/scala/jorlan/service/FakePersonalityService.scala`:

```scala
object FakePersonalityService {
  val layer: ULayer[PersonalityService] = ZLayer.succeed {
    new PersonalityService {
      def get(): UIO[Personality] = ZIO.succeed(Personality.default)
      def update(p: Personality): IO[JorlanError, Personality] = ZIO.succeed(p)
    }
  }
}
```

**`showPersonality` nesting depth** (P8.3-014)

`CommandHandler.showPersonality` at lines 188–213 reaches five levels of nesting: a JSON parsing `for` comprehension inside a lambda inside a `foldZIO` success branch. The parsing logic is not independently testable in this form.

Recommended fix: extract a pure function:

```scala
def parsePersonalityText(json: Json): Option[String] = {
  for {
    obj      <- json.asObject
    name     <- obj("name").flatMap(_.asString)
    formality <- obj("formality").flatMap(_.asString)
    // ...
  } yield s"Name: $name\nFormality: $formality\n..."
}
```

This makes the `foldZIO` branches symmetric (each branch is a single effectful call) and enables unit testing of the formatting logic without the ZIO effect stack.

**`setTrace` dual enumeration** (P8.3-017)

The five valid log level strings (`"trace"`, `"debug"`, `"info"`, `"warn"`, `"error"`) are enumerated twice in `CommandHandler.scala`: once in a `Set` for validation, and again in a `match` expression for conversion. A single `Map[String, Level]` serves both purposes and eliminates the duplication.

**`(StreamAssistant, String)` tuple in sessions Ref** (P8.3-018)

The `sessions` `Ref` in `OllamaModelGateway` stores `(StreamAssistant, String)` tuples. Positional access (`._1`, `._2`) makes the code harder to read and field ordering fragile. A `private case class SessionEntry(assistant: StreamAssistant, modelId: String)` improves both readability and maintenance safety.

---

### Documentation

**`OllamaModelGateway` class doc is incorrect and incomplete** (P8.3-008)

The class-level ScalaDoc states "all reads/writes use `Ref.modify` to avoid TOCTOU races." This is no longer true (see P8.3-003) and gives false confidence to future maintainers. Additionally, the doc does not mention that when the personality is updated and the system prompt changes, existing `StreamAssistant` instances are discarded and their conversation histories are lost. This is a significant behavioral contract that callers (particularly the `AgentRunnerImpl`) should be aware of.

**`PersonalityKey`, `AgentRunnerImpl`, and GraphQL fields undocumented** (P8.3-011, P8.3-012)

`ServerSettingsRepository.PersonalityKey` is a constant that bridges the domain and persistence layers with no documentation explaining its purpose or format. `AgentRunnerImpl` has no class-level doc. The `serverPersonality` and `updatePersonality` GraphQL fields have no ScalaDoc — particularly important for `updatePersonality`, where the required capability string (`admin.personality.update`) is not discoverable without reading the implementation.

---

### Infrastructure / API Design

**`ServerPersonalityInput` naming convention violation** (P8.3-013) — CONFIRMED BY 2 REVIEWERS

Every other mutation input type in `JorlanAPI.scala` follows the `VerbNounInput` pattern: `CreateUserInput`, `UpdateUserInput`, `CreateSessionInput`. The new type `ServerPersonalityInput` breaks this convention. It should be `UpdatePersonalityInput` to remain consistent and to make its purpose immediately obvious from the name alone.

**CI `sbt` invocation missing `--error` flag** (P8.3-015) — CONFIRMED BY 2 REVIEWERS

The project `CLAUDE.md` mandates `sbt --error` for all sbt invocations to reduce output and preserve token budget in agent-assisted sessions. The CI workflow at `.github/workflows/scala.yml:66` uses bare `sbt test`, generating verbose output. This should be `sbt --error test`.

---

## Cross-Cutting Patterns

**Silent failure on decode errors** is the most systemic pattern in this phase, independently flagged by the Functional Scala Reviewer, ScalaDoc Auditor, Performance Oracle, and SRS/SDD Conformance Reviewer. P8.3-001 (V018 enum case mismatch), P8.3-004 (corrupt personality silently falls back), and P8.3-010 (untested deserialization branch) all share the same root cause: zio-json decode errors are consumed by `getOrElse` rather than surfaced via `ZIO.logWarning` or propagated as typed errors. The pattern suggests a project-wide convention should be established: every `json.as[T].getOrElse` in a startup path should become `json.as[T].fold(err => ZIO.logWarning(...) *> ZIO.succeed(default), ZIO.succeed(_))`.

**Unauthenticated API surface** was flagged by four agents from four different angles (security, pattern recognition, SRS conformance, and test coverage). P8.3-002 is an isolated instance, but the fact that four independent reviewers flagged it suggests the auth guard pattern (always add `actorIdFromSession` + `requireCapability`) needs to be enforced more systematically — possibly via a linting rule or a project checklist item for new GraphQL fields.

**Test stub duplication** was observed by the Code Simplicity Reviewer and Pattern Recognition Specialist across P8.3-009 (three copies of `fakePersonality`). The root cause is that the `FakeModelGateway` pattern — the correct precedent — was established in Phase 8.2 but not applied to the new `PersonalityService` when it was introduced. A project convention of "every service trait gets a `Fake*` companion in the test module when it is first created" would prevent this drift.

**Concurrency model drift** appears in P8.3-003 and P8.3-008 together: the implementation diverged from the stated `Ref.modify` contract (documented in the class doc), and the class doc was not updated. Both the correctness bug and the stale documentation are symptoms of the same root cause. Future phases should treat class-level doc comments describing concurrency guarantees as load-bearing — they should be reviewed whenever the implementation changes.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count |
|------------|-------|
| Critical   | 2     |
| Warning    | 6     |
| Suggestion | 14    |
| **Total**  | **22** |

**Issues by area:**

| Area                | Count |
|---------------------|-------|
| Code Quality        | 5     |
| Test Coverage       | 4     |
| Documentation       | 5     |
| Security            | 1     |
| Data Integrity      | 1     |
| Concurrency         | 1     |
| Observability       | 1     |
| Performance         | 1     |
| API Design          | 2     |
| Infrastructure      | 1     |
| **Total**           | **22** |

**Agent contribution:**

| Agent                           | Unique Findings | Cross-Confirmed |
|---------------------------------|-----------------|-----------------|
| Functional Scala Reviewer       | 1               | 5               |
| ScalaDoc Auditor                | 2               | 3               |
| Performance Oracle              | 1               | 3               |
| Test Coverage Tracker           | 2               | 3               |
| Code Simplicity Reviewer        | 2               | 3               |
| Pattern Recognition Specialist  | 1               | 4               |
| SRS/SDD Conformance Reviewer    | 1               | 4               |

**Phase 8.3/8.4 scope completion:**

| Item                                                                                 | Status |
|--------------------------------------------------------------------------------------|--------|
| Flyway V018: seed `server_settings` key `personality`                                | ⚠️     |
| `Personality` domain type with zio-json codec                                        | ✅     |
| `PersonalityService` trait + companion in `model/`                                   | ✅     |
| `PersonalityServiceImpl` + `PersonalityService.live` layer in `server/`              | ✅     |
| `AgentRunnerImpl` updated: system prompt constructed from personality on every call  | ✅     |
| Admin GraphQL query `serverPersonality`                                               | ⚠️     |
| Admin GraphQL mutation `updatePersonality` (capability-gated)                        | ✅     |
| Unit tests: `PersonalityService` get/update round-trip                               | ✅     |
| Unit tests: `AgentRunnerImpl` system-prompt construction for each formality level    | ✅     |
| GraphQL-level tests for `serverPersonality` query and `updatePersonality` mutation   | ❌     |
| Shell `/personality` command — display                                               | ✅     |
| Shell `/personality` command — update sub-commands / interactive prompts             | ❌     |
| Phase 8.4: CI Ollama installation and `FakeModelGateway` wiring                     | ✅     |
| Phase 8.4: `AgentRunnerSpec` and `AgentSessionManagerSpec` CI-safe with fake gateway | ✅     |
| Phase 8.4: Live gateway smoke test exercising Ollama on CI                           | ❌     |

Notes:
- V018 status is ⚠️ because the migration runs but the seeded data is silently ignored due to P8.3-001.
- `serverPersonality` query status is ⚠️ because it functions but lacks an authentication guard (P8.3-002).
- The live gateway smoke test is intentionally deferred to a future phase per the Phase 8.4 groundwork acceptance note.

---

## What Was Done Well

**`PersonalityService` follows the established service trait pattern exactly**: domain type and trait in `model/`, implementation in `server/`, ZIO accessor delegation in the companion object. This mirrors `EventLogService` and `UserService` precisely and makes the new service immediately familiar to anyone who has worked in the codebase.

**`Personality.buildSystemPrompt` is pure and exhaustively tested**: all four `Formality` values, both language edge cases (`List("en")` English-only hint vs. multilingual instruction), and the `expertise` on/off path are covered. Fifteen focused unit tests in `PersonalityServiceSpec` give high confidence in the core prompt-construction logic.

**`InMemoryServerSettingsRepo` follows the established `InMemoryRepositories` test helper pattern precisely**: the test double is self-contained and composable, consistent with how other in-memory repositories are constructed in the project test suite.

**`updatePersonality` capability string follows the dotted namespace convention**: `admin.personality.update` is consistent with `admin.user.create`, `admin.session.terminate`, and other capability strings in the API. This discipline makes capability auditing straightforward.

**`AgentRunnerSpec` and `AgentSessionManagerSpec` use `FakeModelGateway` correctly**: both tests are CI-safe and do not require a live Ollama instance. The Phase 8.4 groundwork is correctly isolated from the live model dependency.

**`personalityService` is correctly sequenced in `AgentRunnerImpl`**: the personality `get()` call is made before the model call, ensuring the system prompt reflects the current personality on every inference request, not a stale cached value from service construction.
