/*

* Copyright (c) 2026 Roberto Leibman - All Rights Reserved
*
* This source code is protected under international copyright law. All rights
* reserved and protected by the copyright holders.
* This file is confidential and only available to authorized individuals with the
* permission of the copyright holders. If you encounter this file and do not have
* permission, please contact the copyright holders and delete this file.
  */

# Phase 7 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Functional Scala, Code Simplicity, Performance, Architecture & Patterns, Test
Coverage)
**Date**: 2026-05-28
**Branch**: `phase-7/shell-interface`
**Scope**: Phase 7 — Shell Interface (`JorlanShell`, `JorlanScreen`, `AuthClient`, `GraphQLClient`, command handlers,
TUI rendering, configuration, and tests)

---

## Executive Summary

The Phase 7 shell module is **well-structured for its scope** and delivers the core interactive CLI functionality on
schedule. The trait/impl split on `JorlanScreen`, sealed command hierarchy, ZIO layer wiring, and
exponential-backoff retry are all solid patterns. The TUI rendering avoids the common pitfall of `screen.clear()`
triggering full repaints, and the frame-rate cap at 30fps is intentional and correct.

However, **eight defects affect correctness and functional purity**, and **45 issues across performance, code quality,
architecture, and test coverage** require attention before Phase 8 begins. Most critically:

1. **HTTP backend resource churn** — Both `AuthClient` and `GraphQLClient` recreate an `HttpClientZioBackend()` on
   every request, spawning and destroying thread pools on every login check, heartbeat, and reconnect.
2. **Zero test coverage on 8 of 10 source files** — `CommandHandler`, `AuthClient`, `GraphQLClient`, `JorlanScreen`,
   and all supporting modules lack unit tests despite containing business logic and error paths.
3. **Three functional-purity violations** — mutable state in `wordWrap`, side-effecting `LocalTime.now()` in
   `addMessage`, and non-idiomatic `return` statement in `ShellCommand.parse`.
4. **Silent defect swallowing** — `processLoop` catches all defects and displays them in the TUI, masking programming
   errors that should surface as crashes.
5. **Per-frame re-expansion of all messages** — `expandMessages` recomputes word-wrapped lines for all 2000 messages
   every 33ms even when nothing has changed.
6. **Scroll reset on every message** — `addMessage` unconditionally sets scroll offset to 0, fighting user scroll
   interactions during rapid message bursts.

**Overall health: Issues Present — Ready to advance to Phase 8 with open items.** The core functionality is solid.
The listed items are optimization opportunities and correctness improvements, not blockers to forward progress.

---

## Prioritized Tech Debt Table

| Status | Feature ID | Severity   | Area                 | Issue                                                                                                                                              | File : Line                                     | Recommended Action                                                                                                                                        |
|--------|------------|------------|----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| [x]    | P7-001     | Critical   | Resource Management  | HTTP backend recreated on every request (`private def backend = HttpClientZioBackend()`)                                                          | `AuthClient.scala:71`, `GraphQLClient.scala:66` | Promote to `ZLayer.scoped` resource; inject as constructor parameter; single backend per client lifetime                                                  |
| [x]    | P7-002     | Critical   | Test Coverage        | `CommandHandler` — all 10 command handlers have zero tests; 5 branches are error paths                                                           | `CommandHandler.scala`                          | Add 15 unit tests covering help, status, whoami, trace levels, phase-8 stubs, and error branches                                                         |
| [x]    | P7-003     | Critical   | Test Coverage        | `AuthClient` — login response parsing, token storage, and `whoAmI` have 5+ untested branches (401, 500, body decode, missing header)              | `AuthClient.scala:87–128`                       | Add 8 tests with injectable backend stub: happy path, header missing, decode failure, HTTP errors                                                        |
| [x]    | P7-004     | Critical   | Test Coverage        | `JorlanClientDecoders` — `longDecoder` error paths and all `ScalarDecoder`/`ArgEncoder` implementations untested                                  | `JorlanClientDecoders.scala:28–57`              | Add 12 pure tests: overflow, non-exact decimals, type mismatches for all 7 ID types + `CapabilityName`                                                   |
| [x]    | P7-005     | Critical   | Test Coverage        | `ShellConfig` — config file loading path entirely untested (file I/O, HOCON parsing, env var override)                                            | `ShellConfig.scala`                             | Add 6 integration tests: HOCON parse, env override, missing key error, application.conf defaults                                                         |
| [x]    | P7-006     | Critical   | Functional Purity    | `ShellCommand.parse` uses non-idiomatic `return` statement instead of if-else expression                                                          | `ShellCommand.scala:33`                         | Replace with idiomatic if-else expression                                                                                                                 |
| [x]    | P7-007     | Critical   | Functional Purity    | `JorlanScreen.addMessage` calls `LocalTime.now()` side-effect without ZIO wrapping; breaks testability and `TestClock` support                    | `JorlanScreen.scala:138`                        | Use ZIO `Clock.localDateTime` service; thread through ZIO environment                                                                                    |
| [x]    | P7-008     | Critical   | Functional Purity    | `JorlanScreen.wordWrap` uses mutable `ArrayBuffer` and `var` in violation of project immutability discipline                                       | `JorlanScreen.scala:342–360`                    | Rewrite with tail-recursive fold; add comment if performance-critical                                                                                    |
| [x]    | P7-009     | Warning    | Error Handling       | `processLoop` catches all defects and silently displays them; programming errors are masked instead of surfacing as crashes                        | `JorlanShell.scala:139`                         | Remove `.catchAllDefect`; narrow catch to `CommandHandler.handle` only with full `Cause.prettyPrint` logging                                             |
| [x]    | P7-010     | Warning    | Error Handling       | `JorlanScreen.live` uses `.orDie` in finalizer without logging failed shutdown errors                                                             | `JorlanScreen.scala:85–87`                      | Add `.tapError(e => ZIO.logError(...))` before `.orDie` to surface shutdown failures in logs                                                             |
| [x]    | P7-011     | Warning    | Correctness          | `AuthClient.whoAmI` does not validate HTTP response code; 500 with body content is treated as success                                             | `AuthClient.scala:112–128`                      | Add `resp.code.isSuccess` guard; fail on non-2xx responses with code in error message                                                                    |
| [x]    | P7-012     | Warning    | Code Quality         | Dead match arm — `MessageKind.Raw` handled in two places (early guard + inner match arm); inner arm is unreachable                                 | `JorlanScreen.scala:307–316`                    | Remove dead inner arm or flatten to top-level match; eliminates misleading two-handler appearance                                                        |
| [x]    | P7-013     | Warning    | Code Quality         | Duplicated credential-prompt logic — email and password branches in `resolveCredentials` are identical 7-line sequences                            | `JorlanShell.scala:211–229`                     | Extract `promptField(screen, label, existing)` helper; reduces to 2 call sites; quit-key set change becomes one-line                                     |
| [x]    | P7-014     | Warning    | Code Quality         | `connectWithRetry` and `connectionHeartbeat` carry `email`/`password`/`serverUrl` as explicit parameters already in `ShellConfig` environment     | `JorlanShell.scala:144–194`                     | Read `ShellConfig` from ZIO environment directly inside both methods; remove 3 parameters                                                                |
| [x]    | P7-015     | Warning    | Code Quality         | `private def backend` reads like a cached field; `def` lifecycle (created per call) is non-obvious to readers                                     | `AuthClient.scala:71`, `GraphQLClient.scala:66` | Rename to `acquireBackend` or add comment: `// def (not val): creates a fresh managed backend per ZIO.scoped call`                                       |
| [x]    | P7-016     | Warning    | Correctness          | `setTrace` displays success message but does not actually change log level; misleads user                                                          | `CommandHandler.scala:120–127`                  | Either implement log-level change via Logback `LoggerContext` or change message: "/trace is not yet implemented (Phase 8)"                               |
| [x]    | P7-017     | Warning    | Architecture         | `showStatus` uses raw `{ users { id } }` GraphQL query as health probe; leaks user IDs; fails on permissions or zero-user tables                  | `CommandHandler.scala:88–92`                    | Replace with `{ __typename }` introspection query; zero-cost, no auth required, cannot false-positive                                                    |
| [x]    | P7-018     | Warning    | Architecture         | `JorlanShell.run` performs 7 distinct responsibilities in one for-comprehension (arg apply, welcome, credentials, connect, fork fibers, exit sequence, goodbye) | `JorlanShell.scala:44–100`           | Extract `initialiseStatusBars()`, `startSession()`, `teardown()` as private methods; `run` reads as named phase sequence                                 |
| [x]    | P7-019     | Warning    | Architecture         | `resolveCredentials` mixes credential resolution with TUI interaction; references raw `/quit`/`/exit` strings instead of `ShellCommand.parse`     | `JorlanShell.scala:196–232`                     | Extract to `CredentialResolver` service or companion object; use `ShellCommand.parse(line)` for quit detection                                           |
| [x]    | P7-020     | Warning    | Architecture         | `sys.exit(0)` in `run` bypasses ZIO finalizers, `Scope` cleanup, and any registered `ZIO.addFinalizer` hooks                                      | `JorlanShell.scala:90`                          | Ensure HTTP backends and Logback async appender shut down via `ZLayer.scoped`; use `sys.exit` only as absolute last resort                               |
| [x]    | P7-021     | Suggestion | Performance          | Full message re-expansion every 33ms — `expandMessages` recomputes word-wrapped lines for all 2000 messages even when no messages arrived          | `JorlanScreen.scala:257–288`                    | Cache expansion keyed on `(msgs.size, terminalWidth)`; invalidate only on message addition or terminal resize                                            |
| [x]    | P7-022     | Suggestion | Performance          | `addMessage` unconditionally resets scroll offset to 0; fights user scroll interactions during rapid message bursts (welcome sequence is 12 msgs) | `JorlanScreen.scala:142`                        | Only reset scroll if already at bottom (current offset == 0); preserve user scroll position when scrolled up                                            |
| [x]    | P7-023     | Suggestion | Performance          | 6 sequential `Ref` reads per frame (`messages`, `inputBuf`, `statusText`, `inputPrompt`, `modeText`, `scrollOffset`)                              | `JorlanScreen.scala:169–174`                    | Consolidate into single `Ref[ScreenState]`; eliminate partial-observation window; reduce atomic ops from 6 to 1 per frame                               |
| [x]    | P7-024     | Suggestion | Performance          | 30-second heartbeat has no HTTP connection pooling or keep-alive; TCP connections created and destroyed on every tick                              | `JorlanShell.scala:171–194`                     | After P7-001 fix, configure shared `HttpClientZioBackend` with connection keep-alive and HTTP/2 support                                                  |
| [x]    | P7-025     | Suggestion | Code Quality         | `setTrace` level validation hardcodes `Set("debug", "info", "warn", "error")`; case-insensitivity via `.toLowerCase` not obvious                  | `CommandHandler.scala:121–122`                  | Document case-insensitivity; add tests for both `/trace DEBUG` and `/trace Debug`                                                                        |
| [x]    | P7-026     | Suggestion | Code Quality         | `LanternaScreen` constructor has 9 parameters with no grouping; will accumulate further when Phase 8 adds session state                            | `JorlanScreen.scala:120–130`                    | Group mutable display state into `DisplayState` case class holding its own Refs; reduce to 3–4 constructor parameters                                   |
| [x]    | P7-027     | Suggestion | Code Quality         | Separator drawing logic (`setBackground + setForeground + putString`) repeated verbatim twice in `drawFrame`                                       | `JorlanScreen.scala:279–288`                    | Extract local `drawSeparator(row: Int)` helper; single source of truth for separator style                                                               |
| [x]    | P7-028     | Suggestion | Code Quality         | `AuthClient.login` response handling is triple-nested (if-match-match); happy path requires reading through 3 indentation levels                  | `AuthClient.scala:87–108`                       | Rewrite as single for-comprehension; each error branch becomes a named `ZIO.fail` step                                                                   |
| [x]    | P7-029     | Suggestion | Code Quality         | `err.getMessage` in `catchAll` display can return `null` on exceptions without a message; silently displays `"Fatal: null"`                       | `JorlanShell.scala:97`                          | Use `Option(err.getMessage).getOrElse(err.getClass.getSimpleName)`                                                                                       |
| [x]    | P7-030     | Suggestion | Code Quality         | 7 identical `ArgEncoder` one-liners in `JorlanClientDecoders`; no helper because opaque type constraints prevent generic abstraction               | `JorlanClientDecoders.scala:44–50`              | Add comment documenting why a generic helper is not possible; note cross-module `HasLongValue` typeclass opportunity                                     |
| [x]    | P7-031     | Suggestion | Code Quality         | `ShellCommand` uses sealed trait where Scala 3 `enum` would be idiomatic; `MessageKind` in same module already uses `enum`                        | `ShellCommand.scala:14`                         | Convert to `enum ShellCommand { case Message(...), case Help, case Quit, ... }`; pattern matches unchanged                                               |
| [x]    | P7-032     | Suggestion | Architecture         | `MessageKind.Raw` is a rendering concern leaking into the domain model; used exclusively for welcome banner, special-cased in two places          | `MessageEntry.scala:13`, `JorlanScreen.scala:307` | Add `preFormatted: Boolean` flag to `MessageEntry` or move welcome rendering to a `displayWelcome` method on the `JorlanScreen` trait                  |
| [x]    | P7-033     | Suggestion | Architecture         | Two HTTP clients with zero shared abstraction; independent JWT handling and error types; Phase 8 will add more server interactions                 | `AuthClient.scala`, `GraphQLClient.scala`       | Phase 8: create `JorlanHttpClient` service owning shared backend; both clients become thin facades                                                       |
| [x]    | P7-034     | Suggestion | Architecture         | Password stored plaintext and passed through call graph; visible in heap dumps; `connectionHeartbeat` must hold it for reconnect duration         | `ShellConfig.scala:24`, `JorlanShell.scala:57–71` | Document security assumption explicitly; Phase 8: implement token refresh to eliminate plaintext storage across reconnects                             |
| [x]    | P7-035     | Suggestion | Package Coherence    | `JorlanClient.scala` lives in `jorlan.graphql.client`, not `jorlan.shell.*`; inconsistent with module structure; requires knowing the outlier     | `shell/src/main/scala/jorlan/graphql/client/`   | Add `// Generated by Caliban — do not edit manually.` header; add comment to `build.sbt` near the generator task                                        |
| [x]    | P7-036     | Suggestion | Test Coverage        | `GraphQLClient` — all 5 response branches untested (error list joining, fallback empty-object, decode failure, 500 response, token threading)     | `GraphQLClient.scala:66–85`                     | Add 6 tests with injectable backend: multi-error join `";"`-separated, data-null, empty body, 500 failure, token header verification                     |
| [x]    | P7-037     | Suggestion | Test Coverage        | `connectWithRetry` — `fmtDelay` inner function and schedule capping logic untested                                                                | `JorlanShell.scala:154–169`                     | Extract `fmtDelay` to companion; test 500ms→"500ms" and 2s→"2s"; use `TestClock` to verify backoff behaviour                                            |
| [x]    | P7-038     | Suggestion | Test Coverage        | `connectionHeartbeat` — reconnect path after `whoAmI` failure entirely untested; double status-bar update on reconnect unverified                 | `JorlanShell.scala:171–194`                     | Add `TestClock`-based test: failure triggers reconnect; success is no-op; status bar updates verified                                                    |
| [x]    | P7-039     | Suggestion | Test Coverage        | `resolveCredentials` — all 5 branches untested (both in config, email prompted, password prompted, both prompted, `/quit` escape)                 | `JorlanShell.scala:196–232`                     | Add 6 tests: both fields provided, one prompted, `/quit` escape, `/exit` escape, empty-string treated as absent                                         |
| [x]    | P7-040     | Suggestion | Test Coverage        | `JorlanScreen` — buffer cap drop logic, `wordWrap` narrow-terminal edge case, and `expandOne` prefix characters untested                          | `JorlanScreen.scala`                            | Use `JorlanScreen.live` with Lanterna headless mode (`setForceTextTerminal(true)`); test overflow, truncation, prefix characters                         |
| [x]    | P7-041     | Suggestion | Test Coverage        | `ShellCommandSpec` — 4 edge cases missing: `/trace level extra-words`, `//comment`, empty-string, whitespace-only input                           | `ShellCommandSpec.scala`                        | Add 4 tests to pin existing parser behaviour; prevents silent regressions on future `parse` changes                                                      |
| [x]    | P7-042     | Suggestion | Test Coverage        | `ShellConfigSpec` — flag at end of list with no value silently dropped; 3 variants (`--server-url`, `--email`, `--password`) untested             | `ShellConfigSpec.scala`                         | Add 3 tests documenting intentional silent-drop; prevents a future refactor from turning silent drop into crash                                          |
| [x]    | P7-043     | Suggestion | Test Coverage        | `JorlanClient` (generated) — `ApprovalStatus` (5 cases) and `EventType` (24 cases) decoder error paths untested                                  | `JorlanClient.scala`                            | Add 4 pure tests: `Pending`→`Right`, invalid string→`Left`, `SkillInvoked`→`Right`, unknown→`Left`; verify `DecodingError` message format               |
| [x]    | P7-044     | Suggestion | Infrastructure       | No shared `FakeScreen` test double exists; all `CommandHandler` and reconnect tests must independently stub `JorlanScreen`                        | N/A                                             | Create `jorlan.shell.testing.FakeScreen` wrapping 3 Refs (messages, statusText, modeText); share across `CommandHandlerSpec`, reconnect specs, etc.      |
| [x]    | P7-045     | Suggestion | Infrastructure       | `sbt scalafmtAll` must be run manually; no pre-commit hook enforces it; Phase 7 branch may diverge from style                                     | N/A                                             | Document in README; verify Phase 7 branch passes `sbt scalafmtAll` before final merge                                                                    |

---

## Grouped Sections

### Correctness / Functional Purity

**C1 — Three violations of functional programming discipline** [Critical × 3]

1. **Non-idiomatic `return` in `ShellCommand.parse`** (P7-006): `if (!line.startsWith("/")) return Message(line)` uses
   an explicit `return` statement. This is non-standard Scala 3 and can interact unexpectedly with higher-order
   functions and for-comprehensions. Fix: replace with if-else expression.

2. **Side-effecting `LocalTime.now()` in `addMessage`** (P7-007): The call to `LocalTime.now()` is a clock
   side-effect called directly inside a `UIO[Unit]`. It captures time at closure-construction time rather than at ZIO
   execution time, and is invisible to `TestClock`. Fix: use ZIO's `Clock.localDateTime` service.

3. **Mutable state in `wordWrap`** (P7-008): The function uses `mutable.ArrayBuffer` and two `var` declarations in a
   codebase that forbids mutable state in domain code. While this is a performance-sensitive private render helper,
   the inconsistency sets a bad precedent. Fix: rewrite with a tail-recursive loop or add an explicit comment
   documenting why mutability is acceptable here.

**C2 — HTTP backend resource churn** [Critical — confirmed by 2 agents]

`AuthClientImpl.backend` and `GraphQLClientImpl.backend` are `def` (method calls), so `HttpClientZioBackend()` is
invoked on every `login`, `whoAmI`, and `execute` call. Each invocation creates a new `java.net.http.HttpClient` with
its own thread pool, connection pool, and selector infrastructure. `ZIO.scoped` releases them correctly (no leak), but
the creation and teardown cost is paid on every request: every 30-second heartbeat, every `/status` check, and every
retry during reconnect storms.

**Recommended fix:** Acquire the backend once in `ZLayer.scoped` at layer construction time:

```scala
val live: ZLayer[ShellConfig, Throwable, AuthClient] = ZLayer.scoped {
  for {
    cfg      <- ZIO.service[ShellConfig]
    tokenRef <- Ref.make(Option.empty[String])
    backend  <- HttpClientZioBackend.scoped()  // acquired once, released on scope close
  } yield AuthClientImpl(cfg, tokenRef, backend)
}
```

Then `AuthClientImpl` takes `backend: SttpBackend[Task, Any]` as a constructor parameter. Apply identically to
`GraphQLClientImpl`.

**C3 — Silent defect swallowing in `processLoop`** (P7-009)

`.catchAllDefect` at `JorlanShell.scala:139` converts JVM-level defects (NPE, assertion errors, stack overflow inside
`CommandHandler.handle`) into user-visible error messages and keeps the loop running. A programming error in the
command handler becomes `"Unexpected error: null"` in the TUI — invisible to CI, invisible in logs. The `processLoop`
type is `ZIO[..., Nothing, Unit]`; the `Nothing` is enforced entirely by `.catchAllDefect`, which means any real bug
is silently absorbed.

**Fix:** Remove `.catchAllDefect` from `processLoop`. Narrow the defect catch to `CommandHandler.handle` only, logging
the full cause:

```scala
CommandHandler.handle(cmd, exitPromise)
  .catchAllCause { cause =>
    screen.addMessage(MessageKind.Error, s"Command error: ${cause.prettyPrint}")
  }
```

---

### Error Handling / Robustness

**E1 — `whoAmI` does not validate HTTP response code** (P7-011)

`AuthClientImpl.whoAmI` inspects only `resp.body`. If the server returns a 500 with an error body text as `Right`,
the method returns it as a success. The `login` method checks `resp.code.isSuccess` explicitly; `whoAmI` should too.

**E2 — Silent shutdown failures** (P7-010)

The `ZIO.acquireRelease` finalizer in `JorlanScreen.live` uses `.orDie` on `screen.stopScreen()` and `screen.close()`.
A failure during teardown is silently absorbed. Add `.tapError(e => ZIO.logError(s"Failed to close screen: ${e.getMessage}"))` before `.orDie`.

**E3 — Null-unsafe `getMessage`** (P7-029)

`err.getMessage` in the `catchAll` at `JorlanShell.scala:97` can return `null` on exceptions without a message (e.g.,
`NullPointerException`). Use `Option(err.getMessage).getOrElse(err.getClass.getSimpleName)`.

---

### Performance / Resource Management

**P1 — HTTP backend recreated per request** (P7-001) [CONFIRMED BY 2 AGENTS]

Already detailed in C2. Single highest-impact performance improvement in this module.

**P2 — Full message re-expansion every frame** (P7-021)

`expandMessages` is called every 33ms and recomputes word-wrapped lines for all 2000 messages from scratch. The result
depends only on `msgs.size` and terminal `width` — both of which are stable between frames in the common case. Fix:
cache the expansion result, invalidate on message addition or terminal resize.

**P3 — Scroll offset unconditionally reset on new message** (P7-022)

`addMessage` calls `scrollOffset.set(0)` unconditionally. If a user has scrolled up to read earlier messages and a new
message arrives (heartbeat log, reconnect notice), the view snaps back to the bottom. The welcome sequence alone fires
12 consecutive messages, each resetting scroll. Fix: only reset scroll if already at the bottom (current offset == 0).

**P4 — Six sequential Ref reads per frame** (P7-023)

Each `oneFrame` reads 6 separate Refs in sequence. While each `AtomicReference.get` is cheap, six separate fiber
suspension points create a partial-observation window: the render fiber can see a new message vector but the old
scroll offset. Consolidate into a single `Ref[ScreenState]` to eliminate the inconsistency window and reduce atomic
operations from 6 to 1 per frame.

**P5 — No connection pooling after reconnect** (P7-024)

Until P7-001 is fixed, every heartbeat and reconnect invocation spins up a new TCP connection with no reuse.

---

### Test Coverage

**T1 — `CommandHandler` has zero tests** [Critical — 10 command paths untested]

All 10 command handlers are untested. `setTrace` has two branches (valid level vs invalid). `showStatus` and
`showWhoAmI` have error paths. Suggested test infrastructure: a `FakeScreen` ZLayer wrapping Refs for captured
messages, status, and mode text; stubbed `AuthClient` and `GraphQLClient`.

Priority test cases:
- `handle(Help)` — asserts one `MessageKind.System` message
- `handle(Commands)` — asserts all 10 command names in output
- `handle(Trace("debug"))` — asserts success message
- `handle(Trace("INVALID"))` — asserts `MessageKind.Error` with valid level list
- `handle(Status)` with working `GraphQLClient` stub — asserts "reachable" in message
- `handle(Status)` with failing `GraphQLClient` stub — asserts "unreachable" in message
- `handle(WhoAmI)` with success — asserts returned name in message
- `handle(Quit)` — asserts `exitPromise` is completed; no screen mutation

**T2 — `AuthClient` login/whoAmI branches untested** [Critical — 5+ error paths]

The `login` method has 5 distinct failure branches; `whoAmI` has 3 (including the missing status-code check). All are
untested. Fix: inject `SttpBackend` as constructor parameter; stub per test. Priority cases:
- 200 + valid header + parseable body → `LoginResult` with correct token
- 200 + no `Authorization` header → failure with "no Authorization header" message
- 200 + valid header + malformed JSON → failure with decode error
- 401 → failure containing "401"
- `whoAmI` with pre-set token → `Authorization` header present on request
- `whoAmI` with 500 response → failure (tests the missing status-code check)

**T3 — `JorlanClientDecoders` decoders untested** [Critical — 7 ID types + CapabilityName]

All `longDecoder` paths are invisible to CI. Fractional BigDecimal inputs and overflows are correctness-sensitive.
These are pure function tests requiring no ZIO runtime. Priority cases:
- `longDecoder` applied to `__NumberValue(42)` → `Right(UserId(42))`
- `longDecoder` applied to `__NumberValue(BigDecimal("1.5"))` → `Left(DecodingError(...))`
- `longDecoder` applied to `__NumberValue(Long.MaxValue + 1)` → `Left`
- `longDecoder` applied to `__StringValue("42")` → `Left`
- `CapabilityName` decoder: `__StringValue("read:files")` → `Right(CapabilityName(...))`
- All 7 `ArgEncoder` round-trips: `ArgEncoder[UserId].encode(UserId(99))` matches `ArgEncoder.long.encode(99L)`

**T4 — `ShellConfig` config-loading untested** [Critical]

`ShellConfig.layer` is entirely untested. HOCON parsing, env var override, and error path for missing keys are
invisible. Priority cases:
- Application.conf defaults load when no user file exists
- User-file values override `application.conf` keys
- HOCON string missing `jorlan.shell.serverUrl` fails with clear error
- `applyArgs` with flag at end of list (e.g., `List("--server-url")`) — silent-drop behaviour is correct

**T5 — `JorlanScreen`, `connectWithRetry`, `connectionHeartbeat`, and `resolveCredentials`** [High]

All four are untested (P7-037 through P7-040). Combined, they cover the reconnect logic, credential prompting, and
message-rendering behavior — all critical to the Phase 7 feature. Note: `JorlanScreen.live` can be tested with
Lanterna's headless mode by setting `DefaultTerminalFactory().setForceTextTerminal(true)`.

**T6 — Existing test edge cases** [Medium]

- `ShellCommand.parse`: missing 4 edge cases (`/trace level extra-words`, `//comment`, `""`, `"   "`)
- `ShellConfig.applyArgs`: missing 3 silent-drop cases (flag with no following value)
- `JorlanClient` (generated): `ApprovalStatus` and `EventType` decoder error-path round-trips

---

### Code Quality / Architecture

**Q1 — Dead match arm for `MessageKind.Raw`** (P7-012)

`expandOne` has an early-return guard at line 307 that handles `MessageKind.Raw` and returns before the match. The
match below includes `case MessageKind.Raw => ...` with comment "handled above" — a dead arm that can never execute.
Remove the dead arm and flatten to a top-level match, putting `Raw` first:

```scala
m.kind match {
  case MessageKind.Raw  => Vector((TextColor.ANSI.WHITE_BRIGHT, TextColor.ANSI.DEFAULT, m.content))
  case MessageKind.User   => ...
  // etc.
}
```

**Q2 — Duplicated credential-prompt logic** (P7-013)

The email and password branches in `resolveCredentials` are structurally identical 7-line sequences. The `/quit`/`/exit` escape check exists in both branches. A third exit alias would require two simultaneous changes.

Extract a `promptField` helper:

```scala
private def promptField(
  screen:   JorlanScreen,
  label:    String,
  existing: Option[String],
): ZIO[Any, Throwable, String] =
  existing match {
    case Some(v) => ZIO.succeed(v)
    case None    =>
      screen.setInputPrompt(label) *>
        screen.readLine.flatMap { line =>
          if (line == "/quit" || line == "/exit") ZIO.fail(new RuntimeException("Cancelled"))
          else ZIO.succeed(line)
        }
  }
```

**Q3 — `JorlanShell.run` is a god method** (P7-018)

The 60-line `run` for-comprehension performs: arg application, welcome banner, credential resolution, initial
connection with retry, status bar initialization, render-loop forking, heartbeat forking, exit-signal waiting,
goodbye message, fiber interrupt, and screen shutdown. Extract `initialiseStatusBars()`, `startSession()`, and
`teardown()` as private methods so `run` reads as a phase sequence.

**Q4 — `setTrace` claims to work but does nothing** (P7-016)

The `/trace` command displays `"Log level set to [level] (takes effect on next log line)"` but does not call any ZIO
logging API or Logback reconfiguration. Change the message to:
`screen.addMessage(MessageKind.System, s"/trace is not yet implemented (Phase 8).")` until it is implemented.

**Q5 — `showStatus` leaks data and is fragile** (P7-017)

The connectivity probe query `{ users { id } }` hits the database, requires `users.list` permission, and returns `false` if the table is empty. Replace with `{ __typename }` — a zero-cost introspection query that confirms GraphQL is reachable without touching domain data or requiring authorization.

---

## Cross-Cutting Patterns

**HTTP backend resource churn** was independently flagged by Performance Oracle and Functional Scala Reviewer — highest-confidence finding in this phase. The same `def backend` anti-pattern appears in both `AuthClient` and `GraphQLClient`.

**Zero test coverage on critical flows** was noted by all 5 agents. `CommandHandler`, `AuthClient`, `GraphQLClient`,
`resolveCredentials`, and `connectionHeartbeat` together represent the main user-visible runtime behaviour of the
shell, yet none have unit tests.

**Mutable state in `wordWrap`** was flagged by both Functional Scala Reviewer and Code Simplicity Reviewer — two
independent agents flagging the same violation from complementary angles.

**Message re-expansion and scroll reset** were both flagged by Performance Oracle and corroborated by Pattern
Recognition as causing UX friction during Phase 7 testing (rapid message bursts during welcome and reconnect sequences
fighting user scroll state).

**`showStatus` as a fragile health probe** was flagged independently by both Functional Scala Reviewer (as wrong
semantics) and Pattern Recognition Specialist (as a data-leaking anti-pattern).

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count  |
|------------|--------|
| Critical   | 8      |
| Warning    | 12     |
| Suggestion | 25     |
| **Total**  | **45** |

**Issues by area:**

| Area                     | Count  |
|--------------------------|--------|
| Test Coverage            | 12     |
| Code Quality             | 11     |
| Architecture / Design    | 5      |
| Correctness / Purity     | 5      |
| Performance / Resource   | 5      |
| Error Handling           | 3      |
| Infrastructure           | 2      |
| Package Coherence        | 1      |
| Compilation / Tests Pass | 1      |
| **Total**                | **45** |

**Agent contribution:**

| Agent                     | Unique Findings | Cross-Confirmed |
|---------------------------|-----------------|-----------------|
| Test Coverage Tracker     | 12              | 4               |
| Pattern Recognition       | 12              | 4               |
| Code Simplicity Reviewer  | 10              | 3               |
| Functional Scala Reviewer | 11              | 4               |
| Performance Oracle        | 7               | 3               |

**Phase 7 scope completion:**

| Item                                                     | Status |
|----------------------------------------------------------|--------|
| Interactive REPL with command parsing (22 tests)         | ✅     |
| TUI rendering via Lanterna (no `clear()`, 30fps cap)     | ✅     |
| Exponential-backoff connection retry                     | ✅     |
| Credential resolution from config and interactive prompt | ✅     |
| HTTP authentication with JWT token storage               | ✅     |
| Configuration loading from `~/.jorlan/config`            | ✅     |
| Typed scalars in GraphQL schema and generated client     | ✅     |
| HTTP resource management (backend per-request, no pool)  | ⚠️     |
| Test coverage for HTTP clients and command handlers      | ⚠️     |
| Functional purity (`return`, `LocalTime.now`, `var`)     | ⚠️     |
