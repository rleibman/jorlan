---
name: phase83-84-findings
description: Phase 8.3 (Server Personality) and Phase 8.4 (AI CI Testing) conformance review summary (2026-06-01)
metadata:
  type: project
---

## Phase 8.3 / Phase 8.4 Conformance Review (2026-06-01)

Branch: phase-8.3/server-personality

### Critical Issues

**Formality enum JSON encoding mismatch between V018 migration and zio-json derived codec**
- V018 seeds `"formality":"professional"` (lowercase) in `server_settings`
- `Formality` enum derives `JsonEncoder, JsonDecoder` via zio-json macro; Scala 3 enum case names are PascalCase: `Professional`, `Casual`, `Academic`, `Technical`
- zio-json derived encoder uses the case name verbatim; the derived decoder expects `"Professional"` not `"professional"`
- Result: at startup, `PersonalityServiceImpl.live` calls `json.as[Personality]`, which fails to decode `formality`, and silently falls back to `Personality.default`
- The seeded personality is effectively unusable until the admin explicitly calls `updatePersonality`
- Fix: either change V018 to use `"formality":"Professional"` (PascalCase) matching the derived codec, or provide a custom `JsonDecoder` for `Formality` that accepts lowercase

### Major Issues

**`/personality` shell command is read-only; update path not implemented**
- Roadmap Phase 8.3 spec (line 436-437): "sub-commands or interactive prompts allow updating individual fields or the full prompt"
- Implementation in `CommandHandler.showPersonality` only calls the `serverPersonality` query; no update path
- `updatePersonality` mutation exists at the GraphQL layer but is not wired to any shell command
- The roadmap checkbox is marked `[x]` but the update sub-functionality is missing

**No GraphQL-level tests for `serverPersonality` query or `updatePersonality` mutation**
- `JorlanAPISpec` was not updated with tests for either the `serverPersonality` query or `updatePersonality` mutation
- Service-level tests exist (`PersonalityServiceSpec`) and are thorough, but there are no Caliban interpreter-level tests confirming the GraphQL schema and resolvers work end-to-end
- The roadmap requires unit tests that exercise the new admin query/mutation

**`/personality` command not added to the shell commands appendix table in the roadmap**
- The roadmap appendix "Supported shell commands" table (lines 685-713) has no row for `/personality`
- The command is implemented and reachable, but the canonical command registry is incomplete

### Minor Issues

- `StreamedChatSpec` (Phase 8.4 AI module test) tests only `LangChainConfig` defaults — it does not test any streaming behavior and explicitly notes the real streaming bridge requires Ollama. The test file name suggests streaming coverage but the content is narrower. The comment documenting why is appropriate and transparent.
- The CI workflow (`scala.yml`) installs and starts Ollama even though all tests that actually run in CI use `FakeModelGateway`. The Ollama setup is essentially unused in the current test suite (the `StreamedChatSpec` and `AgentRunnerSpec` both use fakes). This is not a violation — it is aligned with the Phase 8.4 roadmap spec — but the model is never called by any CI test, making the `JORLAN_AI_OLLAMA_MODEL` env var unused at runtime.

### Missing Requirements (per roadmap)

- Interactive update sub-commands for `/personality` shell command (Phase 8.3 shell spec, line 436-437)
- Unit tests: GraphQL-level test for `serverPersonality` query and `updatePersonality` mutation (Phase 8.3 server spec)
- `/personality` row in the commands appendix table

### Conformant Aspects

- `Personality` domain type: all five fields (`name`, `formality`, `languages`, `expertise`, `prompt`) correctly defined in `model/` module
- `Formality` enum: all four values (`Casual`, `Professional`, `Academic`, `Technical`) match the roadmap spec exactly
- `PersonalityService` trait: `get(): UIO[Personality]` and `update(p): IO[JorlanError, Personality]` exactly as specified
- `PersonalityServiceImpl`: `Ref`-cached implementation, loads from `ServerSettingsRepository` on startup, refreshes on write — matches spec
- `PersonalityService.live` layer: present in `PersonalityServiceImpl.live` and wired into `EnvironmentBuilder.live`
- `AgentRunnerImpl` updated to call `personalityService.get()` and `Personality.buildSystemPrompt()` on every model call; system prompt is passed to `ModelGateway.streamedResponse` as a new `systemPrompt` parameter
- `OllamaModelGateway` detects when `systemPrompt` changes and rebuilds the session assistant (invalidates per-session chat memory) — this is correct behavior
- `serverPersonality` GraphQL query: present in `Queries` case class, wired to `PersonalityService.get()`; no capability check (public read is appropriate)
- `updatePersonality` GraphQL mutation: present in `Mutations` case class, capability-gated on `"admin.personality.update"` — consistent with the `admin.` prefix pattern used for admin operations
- `ServerPersonalityInput` Caliban input type: all five fields correctly defined
- V018 Flyway migration: correct file name (`V018__personality.sql`), correct table and key name (`setting_key = 'personality'`), correct `ON DUPLICATE KEY UPDATE` semantics
- Shell title bar shows `serverName` from `/api/status` (wired in Phase 8.1 via `initialisePostLogin`)
- `/personality` shell command: `ShellCommand.Personality` enum case, parser, handler all present; reads and displays all five personality fields
- `PersonalityServiceSpec`: thorough coverage — get/update round-trip (6 tests), `buildSystemPrompt` for all four formality levels plus language/expertise/prompt cases (9 tests)
- `AgentRunnerSpec`: updated with `fakePersonality` layer; all existing tests pass with the new `PersonalityService` dependency
- `FakeModelGateway.streamedResponse` updated to accept `systemPrompt: String = ""` parameter
- Phase 8.4 CI: Ollama install, model caching, and `JORLAN_AI_OLLAMA_MODEL` env var all correctly wired in `scala.yml`
- `StreamedChatSpec` is a valid no-Ollama unit test for the `ai` module's config defaults
- `AgentSessionManagerSpec` and `AgentRunnerSpec` use `FakeModelGateway` — tests run without Ollama as required by Phase 8.4
- Architectural compliance: `Personality` and `PersonalityService` trait in `model/`; `PersonalityServiceImpl` in `server/` — correct layer separation
- `personality.name` / `serverName` design decision: roadmap explicitly states `serverName` is canonical; `personality.name` mirrors it; the implementation does NOT enforce this invariant at write time (an `updatePersonality` call can set a different `personality.name` from `serverName`), but the roadmap only says they start equal and does not mandate runtime enforcement
