# Coverage-Off Analysis

This document lists code areas that are candidates for `// $COVERAGE-OFF$` / `// $COVERAGE-ON$` 
pragmas, with reasoning for each.  Only areas where unit testing is genuinely infeasible or 
counter-productive are included; all other low-coverage areas should be addressed with more tests.

---

## 1. `jorlan.shell.tui.LanternaScreen` — 0% (285 stmts)
**File:** `shell/src/main/scala/jorlan/shell/tui/JorlanScreen.scala`

**Reasoning:** This class drives a real Lanterna terminal UI (terminal window management, raw-mode 
I/O, cursor movement, colour rendering).  A unit test would require either a real PTY or a 
Lanterna screen emulator, neither of which is available in CI.  The `FakeScreen` trait already 
provides a test double for all code that *uses* the screen; the implementation itself is 
integration-level infrastructure tied to the terminal process.

**Recommendation:** `$COVERAGE-OFF$` the entire `LanternaScreen` class body.

---

## 2. `jorlan.shell.JorlanShell` — 8.1% (258 stmts)
**File:** `shell/src/main/scala/jorlan/JorlanShell.scala`

**Reasoning:** `JorlanShell` is the TUI application entry point.  It constructs the Lanterna 
screen, starts the event loop, wires the subscription WebSocket fiber, and calls the terminal's 
`addKeyStroke` / `stopScreen` lifecycle methods.  All of these require a live terminal process.  
The small amount that is covered comes from object-construction side effects.  The logic that 
matters (command handling, session management, screen output) is exercised through 
`CommandHandlerSpec` and `ShellStateSpec` via fakes.

**Recommendation:** `$COVERAGE-OFF$` the `JorlanShell.run` / `JorlanShell.live` implementation 
bodies.  Keep the `object JorlanShell` declaration outside the pragma so the companion is still 
counted.

---

## 3. `jorlan.shell.tui.JorlanScreen` (interface) — 8% (25 stmts)
**File:** `shell/src/main/scala/jorlan/shell/tui/JorlanScreen.scala`

**Reasoning:** The `JorlanScreen` trait's default method bodies delegate to the `LanternaScreen` 
implementation.  The 8% that IS covered comes from the `FakeScreen` test double.  The remaining 
uncovered branches (e.g., `setModeStatus` fallback paths) require a live terminal.

**Recommendation:** `$COVERAGE-OFF$` the `LanternaScreen` inner class and any trait methods that 
are solely wiring delegates to the live implementation.

---

## 4. `jorlan.shell.client.SubscriptionClientImpl` — 0% (120 stmts)
**File:** `shell/src/main/scala/jorlan/shell/client/SubscriptionClient.scala`

**Reasoning:** This class opens a WebSocket connection to the live Jorlan server, sets up ZIO 
streams over the socket, and processes subscription events.  It cannot be meaningfully tested 
without a running server.  The `FakeScreen` + `fakeSubscriptionClient` stubs in 
`CommandHandlerSpec` cover the consuming side; this class is an I/O adapter.

**Recommendation:** `$COVERAGE-OFF$` the `SubscriptionClientImpl` class body.

---

## 5. `jorlan.Jorlan` — 21.6% (74 stmts)
**File:** `server/src/main/scala/jorlan/Jorlan.scala`

**Reasoning:** This is the server `ZIOApp` bootstrap: it reads config from the environment, wires 
the full `EnvironmentBuilder` layer graph, and starts the HTTP server.  The covered 21% comes from 
`ZIOApp` trait initialization.  Everything else (env-var validation, port binding, signal 
handling) requires a running MariaDB and Qdrant instance, which is the domain of the integration 
test suite.

**Recommendation:** `$COVERAGE-OFF$` the `run` method body.  The `ZIOApp` declaration line itself 
should remain outside the pragma.

---

## 6. `jorlan.EnvironmentBuilder` — 0% (31 stmts)
**File:** `server/src/main/scala/jorlan/EnvironmentBuilder.scala`

**Reasoning:** `EnvironmentBuilder` assembles the complete ZIO layer graph from real infrastructure 
layers (MariaDB connection pool, Flyway, Qdrant, Ollama).  Its only purpose is layer wiring; there 
is no domain logic to assert against.  Testing it requires all external services to be running, 
which belongs to the integration test suite.  The unit tests already exercise all individual 
service layers through `InMemoryRepositories` and fakes.

**Recommendation:** `$COVERAGE-OFF$` the entire `EnvironmentBuilder` object body.

---

## 7. `jorlan.ConfigurationServiceImpl` — 0% (18 stmts)
**File:** `server/src/main/scala/jorlan/configuration.scala`

**Reasoning:** This class reads `JorlanConfig` from environment variables via ZIO Config.  Its 
only logic is config-source wiring; it has no domain behavior.  Testing it requires either 
setting real environment variables or using ZIO Config's test backend, and the values it loads 
(DB URL, API keys) cannot be asserted in isolation.

**Recommendation:** `$COVERAGE-OFF$` the `ConfigurationServiceImpl` class body and its 
companion `val live` layer.

---

## 8. `jorlan.db.FlywayMigrationLive` — 23.8% (21 stmts)
**File:** `db/src/main/scala/jorlan/db/FlywayMigration.scala`

**Reasoning:** `FlywayMigrationLive` runs Flyway against a real MariaDB connection.  The 23% 
covered comes from object construction; the actual `migrate()` call requires a live database.  
This is correctly tested in `InitServiceIntegrationSpec` via Testcontainers.  Unit testing a 
migration runner without a database is not meaningful.

**Recommendation:** `$COVERAGE-OFF$` the `FlywayMigrationLive.migrate` method body.  The 
`FlywayMigration` trait and `FlywayConfig` case class should remain uncovered (or tested via 
integration tests as they already are).

---

## 9. `jorlan.shell.client.GraphQLClientImpl` — 61% (25/41 stmts)
**File:** `shell/src/main/scala/jorlan/shell/client/GraphQLClient.scala`

**Reasoning:** The uncovered 39% consists of the HTTP transport layer: `zio-http` request 
construction, JSON serialisation of the GraphQL request body, and HTTP response parsing.  This 
code cannot be meaningfully tested without a live HTTP server (the real server or a mock HTTP 
server).  The `GraphQLClientSpec` already tests the higher-level `run[Selection]` method via 
`WireMock`; any remaining gaps are transport-layer boilerplate.

**Recommendation:** Consider adding a WireMock-based test for the remaining HTTP paths before 
applying `$COVERAGE-OFF$`.  If the paths are truly just error-case HTTP plumbing with no domain 
logic (e.g., HTTP 5xx fallback), then `$COVERAGE-OFF$` is appropriate for those branches only.

---

## 10. `jorlan.auth.$anon` — 57.1% (32/56 stmts)
**File:** `server/src/main/scala/jorlan/auth/JorlanAuthServer.scala`

**Reasoning:** The uncovered 42% consists of OAuth callback handling, session token generation, 
and HTTP redirect logic.  These paths require a live OAuth provider (Google/GitHub) or a mock 
HTTP server.  The `AuthServerSpec` integration test covers these via Testcontainers + WireMock.  
Unit coverage is not meaningful here because the value comes from testing the full OAuth round-trip.

**Recommendation:** Do NOT apply `$COVERAGE-OFF$` here.  Instead, expand `AuthServerSpec` 
integration tests to cover the redirect and error-response code paths.

---

## Summary Table

| Class | Coverage | Recommended Action |
|-------|----------|--------------------|
| `LanternaScreen` | 0% | `$COVERAGE-OFF$` — terminal I/O |
| `JorlanShell` | 8% | `$COVERAGE-OFF$` — TUI app entry point |
| `JorlanScreen` (trait bodies) | 8% | `$COVERAGE-OFF$` — Lanterna delegates |
| `SubscriptionClientImpl` | 0% | `$COVERAGE-OFF$` — WebSocket transport |
| `Jorlan` (run method) | 21% | `$COVERAGE-OFF$` — app bootstrap |
| `EnvironmentBuilder` | 0% | `$COVERAGE-OFF$` — layer wiring only |
| `ConfigurationServiceImpl` | 0% | `$COVERAGE-OFF$` — env-var loading |
| `FlywayMigrationLive.migrate` | 23% | `$COVERAGE-OFF$` — needs real DB |
| `GraphQLClientImpl` (HTTP paths) | 61% | Expand WireMock tests first, then targeted `$COVERAGE-OFF$` |
| `JorlanAuthServer` ($anon) | 57% | Expand integration tests, NOT `$COVERAGE-OFF$` |

**Total statements that would be excluded by `$COVERAGE-OFF$`:** ~787 statements  
(LanternaScreen 285 + JorlanShell 258 + SubscriptionClientImpl 120 + EnvironmentBuilder 31 + ConfigurationServiceImpl 18 + FlywayMigrationLive ~10 + JorlanScreen/Jorlan partial)

Excluding these from the denominator would raise the effective statement coverage from 
~69% to approximately **~78–80%** on the remaining testable code.
