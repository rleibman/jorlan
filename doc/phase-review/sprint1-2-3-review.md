/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law. All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders. If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

# Sprint 1-2-3 Tech Debt Report — Jorlan

**Reviewed by**: Multi-agent review (Performance Oracle, Code Simplicity, Functional Scala, Pattern Recognition, Test Coverage, SRS/SDD Conformance, ScalaDoc Auditor, UI Test Plan Writer)
**Date**: 2026-06-29
**Branch**: `Sprint3`
**Scope**: Sprints 1, 2, and 3 — EmbeddingStore, ContactsSkill→UserManagementSkill merge, EmailSkill, MemorySkill, ShellSkill, WorkspaceSkill, DeclarativeSkill, DeclarativeSkillManifest, HttpApiExecutor, ManifestValidator, PromptTemplateExecutor, SkillLifecycleService, SkillAuthoringSkill, CreateSkillWizard, CustomSkillsPage, DiscordConnectorSkill, DiscordApiClient, DiscordMessageNormalizer, RssFeedSkill, RssFeedParser, RssFeedPlugin, ApprovalHub, ApprovalServiceImpl, HumanApprovalNotifierImpl, ToolEventHub

---

## Executive Summary

The three sprints delivered a large volume of new capability: a declarative skill system with authoring wizard and web UI, the Discord connector, an RSS skill, and a full human-in-the-loop Approvals subsystem. Architecture choices are generally sound — the capability-based permission model is consistently applied, ZIO layers are well-structured, and the new reactive approval flow (ApprovalHub + ToolEventHub) follows established hub patterns from earlier phases.

The most severe issues are concentrated in two areas. First, the `approvalNotifications` GraphQL subscription broadcasts every approval request to every connected user without any capability or identity guard — a direct security regression. Second, six new components added in Sprint 2 and Sprint 3 (HttpApiExecutor, PromptTemplateExecutor, DeclarativeSkill.invoke, RssFeedSkill.fetch, SkillAuthoringSkill, and the toolEvents subscription) shipped with zero test coverage, leaving their critical paths entirely invisible to the test suite. Additionally, `SkillPluginLoader` leaks a `URLClassLoader` on every load, and `DiscordApiClient.disconnect()` executes three synchronous side effects outside the ZIO effect system.

**Overall health: Issues Present — ready to advance with open items tracked.** The security issue (PS123-003) and the six test-coverage criticals (PS123-007 through PS123-012) must be resolved before production use. All other items should be addressed within the next one to two sprints.

Documentation coverage is mixed: the RSS and Discord INSTALL.md files contain inaccurate or dangling instructions, the declarative-skills mini-design is out of sync with the implementation in four distinct ways, and the new Approvals feature has no user-facing reference document at all.

---

## Prioritized Tech Debt Table

| Status | Feature ID  | Severity   | Area             | Issue                                                                                                                                             | File : Line                                                    | Recommended Action                                                                                                        |
|--------|-------------|------------|------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| [x]    | PS123-001   | Critical   | Performance      | `HttpApiExecutor` carries no request timeout — one misbehaving endpoint can exhaust the thread pool.                                              | `HttpApiExecutor.scala:53`                                     | Add `.timeoutFail(JorlanError(...))(30.seconds)` around `client.batched(req)`.                                            |
| [x]    | PS123-002   | Critical   | Functional Purity| `DiscordApiClient.disconnect()` executes three synchronous side effects at definition time, outside the returned `ZIO.blocking(...)`.             | `DiscordApiClient.disconnect()`                                | Wrap all side-effectful calls inside `ZIO.succeed { ... }.flatMap { jda => ZIO.blocking(...) }`.                          |
| [x]    | PS123-003   | Critical   | Security         | `approvalNotifications` GQL subscription has no `requireCapability` guard and broadcasts ALL approval requests to ALL connected users.            | `JorlanAPI.scala:2007`                                         | Add `actorIdFromSession`, `requireCapability("approval.read", actorId)`, and filter by `requestorUserId`.                 |
| [x]    | PS123-004   | Critical   | Correctness      | `HumanApprovalNotifier` is dead code that would cause double event-log writes if wired alongside `ApprovalServiceImpl`. (confirmed by 2 reviewers)| `HumanApprovalNotifierImpl.scala:16`, `ApprovalServiceImpl.scala:89` | Decide ownership; remove the duplicate write path; wire or delete `HumanApprovalNotifier`.                           |
| [x]    | PS123-005   | Critical   | Resource Management | `SkillPluginLoader` leaks `URLClassLoader` — the release function is `_ => ZIO.unit`; also uses `ZIO.attempt` for blocking class loading.    | `SkillPluginLoader.scala:55-61`                                | Replace release with `cl => ZIO.attempt(cl.close()).ignore`; use `ZIO.attemptBlockingIO` for class loading.               |
| [x]    | PS123-006   | Critical   | Code Quality     | `Skill` trait has exact duplicate methods: `str`/`optStr` and `int`/`optInt` — identical bodies, only parameter name differs.                    | `Skill.scala:45-53,93-101`                                     | Remove `optStr` and `optInt`; all callers use `str` and `int`.                                                            |
| [x]    | PS123-007   | Critical   | Test Coverage    | `rss.fetch` tool has zero tests — `fetchFeed`, `fetchXml`, and HTTP error paths are all untested.                                                | `RssFeedSkill.scala:159-215`                                   | Add `RssFeedSkillSpec` with a stub `Client`.                                                                              |
| [x]    | PS123-008   | Critical   | Test Coverage    | `HttpApiExecutor` has zero direct tests — URL-template substitution, HTTP dispatch, and error handling are untested.                             | `HttpApiExecutor.scala:24-63`                                  | Create `HttpApiExecutorSpec` with a stub `Client`.                                                                        |
| [x]    | PS123-009   | Critical   | Test Coverage    | `PromptTemplateExecutor` has zero tests — template substitution, FinalAnswer path, and ToolCallRequested path are untested.                      | `PromptTemplateExecutor.scala:18-51`                           | Create `PromptTemplateExecutorSpec` using `FakeModelGateway`.                                                             |
| [x]    | PS123-010   | Critical   | Test Coverage    | `DeclarativeSkill.invoke` has zero direct tests — executor routing logic is untested.                                                            | `DeclarativeSkill.scala:43-57`                                 | Add `DeclarativeSkillSpec`.                                                                                               |
| [x]    | PS123-011   | Critical   | Test Coverage    | `SkillAuthoringSkill` has zero tests — propose happy path, missing manifest, and invalid JSON are all untested.                                  | `SkillAuthoringSkill.scala:55-94`                              | Create `SkillAuthoringSkillSpec`.                                                                                         |
| [x]    | PS123-012   | Critical   | Test Coverage    | `toolEvents` GQL subscription has zero tests — field projection and session-id scoping are untested.                                             | `JorlanAPI.scala:2038-2044`                                    | Add `toolEvents` suite to `JorlanAPISpec`.                                                                                |
| [x]    | PS123-013   | Critical   | Documentation    | `rss-feed` module is absent from `build.sbt`; INSTALL.md commands (`sbt rssFeedSkillJVM/package`) would fail.                                   | `rss-feed/INSTALL.md:13-30`, `build.sbt`                       | Add module to `build.sbt` or correct the INSTALL.md instructions.                                                        |
| [x]    | PS123-014   | Critical   | Documentation    | `discord/INSTALL.md` section 6 documents non-existent Discord OAuth login with env vars that do not exist.                                       | `discord/INSTALL.md:76-90`                                     | Remove section 6 or mark clearly as "Coming soon".                                                                        |
| [x]    | PS123-015   | Warning    | Performance      | `RssFeedSkill` fetches full XML body on every call with no ETag/`Last-Modified` conditional GET or local cache.                                  | `RssFeedSkill.scala:202-215`                                   | Cache `(ETag, Last-Modified, parsedEntries)` per URL in a `Ref`.                                                         |
| [x]    | PS123-016   | Warning    | Performance      | `DocumentBuilderFactory` constructed per parse call, triggering `ServiceLoader` scan and 6 `setFeature` calls on every fetch.                   | `RssFeedParser.scala:23-31`                                    | Cache factory as a `lazy val`.                                                                                            |
| [x]    | PS123-017   | Warning    | Concurrency      | `ApprovalHub` uses sequential `ZIO.foreachDiscard` fan-out while `ToolEventHub` correctly uses `ZIO.foreachParDiscard`.                          | `ApprovalHub.scala:31`                                         | Replace with `ZIO.foreachParDiscard`.                                                                                     |
| [x]    | PS123-018   | Warning    | Resource Management | `ApprovalHub.preDecisions` map grows unbounded — orphaned entries accumulate if an agent fiber is interrupted before `awaitDecision`.        | `ApprovalHub.scala:74-76`                                      | Attach TTL-based expiry or remove entries on approval timeout.                                                            |
| [x]    | PS123-019   | Warning    | Observability    | `DiscordApiClient` drops queue-head messages silently when the inbound queue is at capacity with no log or metric.                               | `DiscordApiClient.scala:103-105`                               | Add `ZIO.logWarning` on drop; emit a metric counter.                                                                      |
| [x]    | PS123-020   | Warning    | Performance      | `ManifestValidator` and `SkillLifecycleService` double-serialize JSON: AST rendered to `String` then reparsed via `toString.fromJson`.           | `ManifestValidator.scala:18`, `SkillLifecycleService.scala:250` | Use `manifestJson.as[DeclarativeSkillManifest]` directly on the AST.                                                    |
| [x]    | PS123-021   | Warning    | Correctness      | `HttpApiExecutor.execute` does not check `resp.status.isSuccess` — HTTP error responses are returned to the agent as successful JSON.            | `HttpApiExecutor.scala`                                        | Check `resp.status.isSuccess`; fail with `JorlanError` on non-2xx.                                                       |
| [x]    | PS123-022   | Warning    | Code Quality     | `HttpApiExecutorConfig.responseJsonPath` is defined but never read — dead configuration field.                                                   | `HttpApiExecutor.scala`                                        | Remove the field or implement it.                                                                                         |
| [x]    | PS123-023   | Warning    | Functional Purity| `RssFeedParser.parseRss`/`parseAtom` use `asInstanceOf[Element]` — should use pattern matching.                                                 | `RssFeedParser.scala`                                          | Replace with `case el: Element =>`.                                                                                       |
| [x]    | PS123-024   | Warning    | Concurrency      | `ToolEventHub` and `ApprovalHub` use `Queue.bounded(256)` with back-pressuring `offer` — a slow subscriber can suspend agent fibers.            | `ToolEventHub.scala`, `ApprovalHub.scala`                      | Use `Queue.dropping` or `Queue.sliding`.                                                                                  |
| [x]    | PS123-025   | Warning    | Documentation    | `HumanApprovalNotifierImpl` has a stale "Phase 8 stub… wired in Phase 11" comment; real notification delivery is unimplemented.                 | `HumanApprovalNotifierImpl.scala`                              | Update or remove the comment; implement or document the gap.                                                              |
| [x]    | PS123-026   | Warning    | Resource Management | `ApprovalHub.subscribeToNewRequests` — queue leaks into the subscribers list if the returned stream is never consumed.                       | `ApprovalHub.scala:83-92`                                      | Add `ZStream.ensuring` cleanup; document the behavior in Scaladoc.                                                        |
| [x]    | PS123-027   | Warning    | Functional Purity| `SkillLifecycleService` calls `Instant.now()` in 3 places, bypassing ZIO `Clock`. (confirmed by 4 reviewers)                                   | `SkillLifecycleService.scala:91,104,158`                       | Use `Clock.instant` throughout.                                                                                           |
| [x]    | PS123-028   | Warning    | Data Integrity   | `SkillLifecycleService.approve` silently overwrites the original `createdAt` timestamp on approval because `Instant.now()` is used unconditionally. | `SkillLifecycleService.scala:158`                           | Fetch the existing record first; preserve original `createdAt`.                                                           |
| [x]    | PS123-029   | Warning    | Correctness      | `runSandboxTest` auto-passes all HTTP tools without testing anything; `SandboxTested` status conveys false confidence.                           | `SkillLifecycleService.scala:209-232`                          | Either probe the HTTP endpoint or rename status to `SandboxSkipped`.                                                      |
| [x]    | PS123-030   | Warning    | Architecture     | `SkillLifecycleService` writes no event log entries for any lifecycle transition, violating SDD architecture principle 3. (confirmed by 3 reviewers) | `SkillLifecycleService.scala`                              | Add `repos.eventLog.append` for approve, reject, and advance-to-AwaitingApproval.                                        |
| [x]    | PS123-031   | Warning    | Code Quality     | `DiscordConnectorSkill.processMessage` bot filter is buried as a trailing `.unless` with a redundant normalizer check.                           | `DiscordConnectorSkill.scala:253-282`                          | Move bot check to the first guard with an explicit debug log on skip.                                                     |
| [x]    | PS123-032   | Warning    | Security         | `rss.save_feed` and `rss.remove_feed` are gated on the `rss.read` capability despite being state-mutating operations. (confirmed by 2 reviewers) | `RssFeedSkill.scala:124,136`                                  | Introduce and require an `rss.manage` capability for write tools.                                                         |
| [x]    | PS123-033   | Warning    | Architecture     | `CapabilityEvaluatorImpl` injects the concrete `ZIOPermissionRepository` directly, bypassing the abstract `PermissionRepository[F[_]]` trait.   | `CapabilityEvaluatorImpl.scala:27`                             | Inject the abstract `PermissionRepository[F[_]]` or explicitly document the coupling as accepted.                         |
| [x]    | PS123-034   | Warning    | Error Handling   | `SkillLifecycleService.approve` silently substitutes `unsafeParse("1.0.0")` when a version string is malformed instead of failing.              | `SkillLifecycleService.scala:155`                              | Fail with `JorlanError` on malformed version.                                                                             |
| [x]    | PS123-035   | Warning    | Code Quality     | `substitute` function is copied verbatim between `HttpApiExecutor` and `PromptTemplateExecutor` in the same package.                             | `HttpApiExecutor.scala:66-79`, `PromptTemplateExecutor.scala:37-50` | Extract to `object DeclarativeArgSubstitution`.                                                                      |
| [x]    | PS123-036   | Warning    | Code Quality     | `RssFeedSkill` reinvents `fieldStr`/`fieldIntOpt` helpers already available from the `Skill` trait.                                             | `RssFeedSkill.scala:217-243`                                   | Use inherited `str`/`int` or add `requireStr` to the `Skill` base trait.                                                 |
| [x]    | PS123-037   | Warning    | Code Quality     | `requireStr`/`requireLong` helpers are independently invented in three skills: UserManagementSkill, SkillAuthoringSkill, and RssFeedSkill. (confirmed by 2 reviewers) | Multiple files                                 | Add `requireStr`/`requireLong` to the `Skill` base trait and delete the per-skill copies.                                |
| [x]    | PS123-038   | Warning    | Code Quality     | `SkillLifecycleService` version-lookup pattern (`findVersion(...).someOrFail(...)`) is duplicated across `advance`, `approve`, and `reject`.     | `SkillLifecycleService.scala:116,140,171`                      | Extract `private def requireVersion(id): IO[JorlanError, SkillVersion]`.                                                 |
| [x]    | PS123-039   | Warning    | Code Quality     | `SkillLifecycleService.runSandboxTest` duplicates the `upsertVersionStatus` call in both branches of an if/else.                                | `SkillLifecycleService.scala:215-232`                          | Hoist the common call before the if/else.                                                                                 |
| [x]    | PS123-040   | Warning    | Code Quality     | `ManifestValidator` repeats `if (cond) Nil else List(msg)` 8 times with no shared helper.                                                       | `ManifestValidator.scala:28-98`                                | Add `private def require(cond: Boolean, msg: => String): List[String]`.                                                   |
| [x]    | PS123-041   | Warning    | Code Quality     | `SkillAuthoringSkill.propose` chains 4 sequential `advance()` calls with manual short-circuit on failure.                                        | `SkillAuthoringSkill.scala:78-81`                              | Extract `advanceWhileOk(versionId, stepsRemaining: Int)` with a recursive loop.                                           |
| [x]    | PS123-042   | Warning    | Code Quality     | Null-channel guard is duplicated across `sendToChannel`, `getChannelHistory`, and `getChannelInfo` in `DiscordApiClient`.                        | `DiscordApiClient.scala:147,181,207`                           | Extract `private def withChannel[A](jda, channelId)(f): Task[A]`.                                                        |
| [x]    | PS123-043   | Warning    | Code Quality     | `DiscordConnectorSkill.invoke` uses manual `.asObject.getOrElse` instead of inherited `str`/`int` helpers.                                      | `DiscordConnectorSkill.scala:183-219`                          | Replace with inherited helpers and a for-comprehension.                                                                   |
| [x]    | PS123-044   | Warning    | Code Quality     | `UserManagementSkill.requireLong` is 30 lines handling a string-fallback path that schema validation should already prevent.                     | `UserManagementSkill.scala:610-640`                            | Simplify to the `Json.Num` case only; fail on anything else.                                                              |
| [x]    | PS123-045   | Warning    | Test Coverage    | `ApprovalHub` subscriber-cleanup path (`ZStream.ensuring`) is never tested.                                                                      | `ApprovalHub.scala:83-92`                                      | Add a test that verifies stream completion removes the subscriber.                                                        |
| [x]    | PS123-046   | Warning    | Test Coverage    | `ToolEventHub` subscriber-cleanup path is never tested.                                                                                          | `ToolEventHub.scala:55-62`                                     | Add a test that verifies stream completion removes the subscriber.                                                        |
| [x]    | PS123-047   | Warning    | Test Coverage    | `ApprovalServiceImpl.recordDecision` Cancelled and Expired branches are not tested — only Rejected is covered.                                   | `ApprovalServiceImpl.scala:60-61`                              | Add tests for all decision branches.                                                                                      |
| [x]    | PS123-048   | Warning    | Test Coverage    | `ApprovalServiceImpl` once-grant re-use path (`findApprovedRequest`) is not tested.                                                              | `ApprovalServiceImpl.scala:110-111`                            | Add a test verifying re-use of an existing once-grant.                                                                    |
| [x]    | PS123-049   | Warning    | Test Coverage    | `SkillLifecycleService` not-found error paths for `advance`, `approve`, and `reject` are not tested.                                            | `SkillLifecycleService.scala:116,141,171`                      | Add 3 failure-path tests.                                                                                                 |
| [x]    | PS123-050   | Warning    | Test Coverage    | `SkillLifecycleService.createDraft` existing-skill reuse path (same name creates shared `skillId`) is not tested.                               | `SkillLifecycleService.scala:81-84`                            | Add test asserting the same name reuses the existing `skillId`.                                                           |
| [x]    | PS123-051   | Warning    | Test Coverage    | `SkillLifecycleService.approve` does not verify that the skill becomes callable via the registry after approval.                                 | `SkillLifecycleService.scala:162`                              | Assert skill is callable via registry after `approve`.                                                                    |
| [x]    | PS123-052   | Warning    | Test Coverage    | `approvalNotifications` GQL subscription has no test that publishes a real event through the hub.                                               | `JorlanAPISpec.scala:496-506`                                  | Add a live-publish test.                                                                                                  |
| [x]    | PS123-053   | Warning    | Test Coverage    | `InMemorySkillRepo.getConnector`, `.searchConnectors`, and `.upsertConnector` are implemented as `???`.                                          | `InMemoryRepositories.scala:946-950`                           | Replace with stub implementations that return empty/success results.                                                      |
| [x]    | PS123-054   | Warning    | Test Coverage    | `ManifestValidator` has 3 untested branches: empty `userPromptTemplate`, empty `description`, and non-object schema.                            | `ManifestValidator.scala:91-98`                                | Add 3 targeted validation tests.                                                                                          |
| [x]    | PS123-055   | Warning    | Documentation    | `doc/skills/contacts.md` has full user-management docs; `doc/skills/user-management.md` is a stale stub — two competing files.                  | `doc/skills/contacts.md`, `doc/skills/user-management.md`     | Merge content into `user-management.md`; delete `contacts.md`.                                                            |
| [x]    | PS123-056   | Warning    | Documentation    | `phase16-declarative-skills.md` specifies 8 public `SkillLifecycleService` methods but only 4 are implemented (collapsed `advance()` design).   | `doc/mini-designs/phase16-declarative-skills.md:139-158`       | Update to reflect the collapsed design.                                                                                   |
| [x]    | PS123-057   | Warning    | Documentation    | `phase16-declarative-skills.md` describes sandbox test as "real invocation"; actual behaviour is a no-op auto-pass.                             | `doc/mini-designs/phase16-declarative-skills.md:163`           | Document the MVP auto-pass behaviour explicitly.                                                                          |
| [x]    | PS123-058   | Warning    | Documentation    | `phase16-declarative-skills.md` says `submitForApproval` creates an `ApprovalRequest`; it actually only updates version status.                 | `doc/mini-designs/phase16-declarative-skills.md:163-165`       | Clarify the separate approval paths in the doc.                                                                           |
| [x]    | PS123-059   | Warning    | Documentation    | `phase16-declarative-skills.md` uses `"type"` discriminator in ExecutorConfig JSON examples but actual zio-json encoding is `{"HttpApi":{...}}`. | `doc/mini-designs/phase16-declarative-skills.md:56-74`        | Update examples to match actual zio-json ADT encoding.                                                                    |
| [x]    | PS123-060   | Warning    | Documentation    | `MCP.md` is 90% OurGroceries-specific; general reference material (capabilities, transport types, env vars) is buried at the end.               | `MCP.md:1-154`                                                 | Restructure: general reference first, OurGroceries as an example section.                                                 |
| [x]    | PS123-061   | Warning    | Documentation    | The Approvals feature is fully wired but has no user-facing documentation.                                                                       | `doc/skills/` (absent)                                         | Create `doc/skills/approvals.md` explaining triggers, timeout, UI/shell paths, and distinction from skill lifecycle.      |
| [x]    | PS123-062   | Warning    | Documentation    | `doc/skills/notify.md` does not mention Discord as a supported notification channel despite Sprint 3 adding it.                                  | `notify.md:31-57`                                              | Add a Discord example to the `notify.channel` documentation.                                                             |
| [x]    | PS123-063   | Warning    | Architecture     | `PermissionReview` lifecycle step auto-advances without a human gate, contrary to the SRS human-in-the-loop specification.                      | `SkillLifecycleService.scala`                                  | Document the MVP deviation or implement a human review gate.                                                              |
| [x]    | PS123-064   | Warning    | Observability    | `requestApproval` event log entry omits `agentId` and `sessionId` correlation IDs, making incident tracing difficult.                           | `ApprovalServiceImpl.scala`                                    | Add `agentId` and `sessionId` to the event payload.                                                                      |
| [x]    | PS123-065   | Warning    | Package Coherence| `LifecycleResult` type is defined in two separate packages.                                                                                      | `SkillLifecycleService.scala`                                  | Consolidate to a single canonical location in the `model` module.                                                         |
| [x]    | PS123-066   | Warning    | Architecture     | GQL lifecycle mutations route through `SkillRegistry`; `SkillAuthoringSkill` routes through `SkillLifecycleService` — two divergent paths.     | Architecture concern                                           | Document the intended path or unify both callers through `SkillLifecycleService`.                                         |
| [x]    | PS123-067   | Suggestion | Performance      | `DiscordConnectorSkill` processes messages serially in the receive loop, stalling event ingestion.                                               | `DiscordConnectorSkill.scala:248-250`                          | `processMessage(msg).forkDaemon *> eventLoop` to decouple processing from polling.                                        |
| [x]    | PS123-068   | Suggestion | Performance      | `CreateSkillWizard.buildManifestJson` is recomputed on every state change including minor boolean flag flips.                                    | `CreateSkillWizard.scala:377`                                  | Memoize or compute lazily only on relevant field changes.                                                                 |
| [x]    | PS123-069   | Suggestion | Code Quality     | `CreateSkillWizard` duplicates a `Box.withProps(...)` column-flex layout pattern 6 times.                                                        | `CreateSkillWizard.scala:232-384`                              | Extract `def columnFlex(children: VdomNode*)`.                                                                            |
| [x]    | PS123-070   | Suggestion | Code Quality     | `buildManifestJson` constructs JSON via fragile 40-line string interpolation.                                                                    | `CreateSkillWizard.scala:68-108`                               | Build a structured case class with `derives JsonEncoder` instead.                                                         |
| [x]    | PS123-071   | Suggestion | Code Quality     | `RssFeedParser.nodeListToSeq` uses a mutable `ListBuffer` and `var i`. (confirmed by 2 reviewers)                                               | `RssFeedParser.scala:107-115`                                  | Replace with `List.tabulate(nl.getLength)(nl.item(_))`.                                                                   |
| [x]    | PS123-072   | Suggestion | Architecture     | `ToolEventHub` and `ApprovalHub` are structurally identical with no shared abstraction.                                                          | Both hub files                                                 | Extract `trait PubSubHub[K, V]` and have both implement it.                                                               |
| [x]    | PS123-073   | Suggestion | Architecture     | Discord connector lacks a name-resolution path equivalent to Telegram's `nameResolver`.                                                          | `DiscordConnectorSkill.scala`                                  | Document the asymmetry or inject a `DiscordNameResolver`.                                                                 |
| [x]    | PS123-074   | Suggestion | Documentation    | `SkillLifecycleService` public methods lack `@param`/`@return` Scaladoc.                                                                         | `SkillLifecycleService.scala:34-53`                            | Add method-level Scaladoc with tier distinctions.                                                                         |
| [x]    | PS123-075   | Suggestion | Documentation    | `ManifestValidator` Scaladoc says "Returns a list of error messages" but the actual return is `Either[List[String], ...]`.                       | `ManifestValidator.scala:12-13`                                | Fix Scaladoc to reflect the `Either` return type.                                                                         |
| [x]    | PS123-076   | Suggestion | Documentation    | `DeclarativeSkill` constructor and `from()` factory have no Scaladoc.                                                                            | `DeclarativeSkill.scala:20-24,63-67`                           | Add `@param` tags and a method summary.                                                                                   |
| [x]    | PS123-077   | Suggestion | Documentation    | `ToolEventHub` sealed `ToolEvent` variants and `publish` have no Scaladoc; drop semantics are undocumented.                                      | `ToolEventHub.scala:23-36`                                     | Add `@param` tags and a note on drop semantics.                                                                           |

---

## Grouped Sections

### Correctness and Security

**Missing auth guard on `approvalNotifications` subscription** (PS123-003) CONFIRMED BY 2 REVIEWERS

The `approvalNotifications` GraphQL subscription, as implemented in `JorlanAPI.scala:2007`, emits every approval request to every connected WebSocket client without consulting session identity or capabilities. Any authenticated user — including one with read-only or agent-only access — can subscribe and receive all pending tool approval requests from all other users. The pattern used by every other subscription in the file (`actorIdFromSession` + `requireCapability`) was simply omitted here.

```scala
// Minimal fix (add to approvalNotifications resolver)
for {
  actorId <- actorIdFromSession(session)
  _       <- requireCapability("approval.read", actorId)
  stream  <- approvalHub.subscribe.filter(_.requestorUserId == actorId)
} yield stream
```

**`HttpApiExecutor` does not check HTTP response status** (PS123-021)

`HttpApiExecutor.execute` passes the response body to the agent regardless of the HTTP status code. A declarative skill calling an endpoint that returns 403 or 500 will receive the error body as if it were a successful JSON payload, silently corrupting agent reasoning. Adding a `resp.status.isSuccess` guard before body deserialization is a one-line fix.

**`HumanApprovalNotifier` dead code with double-write risk** (PS123-004) CONFIRMED BY 2 REVIEWERS

`HumanApprovalNotifierImpl` was scaffolded as a stub but never actually wired. `ApprovalServiceImpl` already writes the approval event to the log directly. If `HumanApprovalNotifier` is ever wired without removing `ApprovalServiceImpl`'s direct write, every approval event will be recorded twice. The ownership boundary must be decided and one path removed.

**Silent version fallback in `approve`** (PS123-034)

`SkillLifecycleService.approve` calls `unsafeParse("1.0.0")` when a version string fails to parse instead of propagating a `JorlanError`. This silently assigns version 1.0.0 to a skill that may have a different intended version, creating a data integrity issue that is invisible to callers and the event log.

---

### Resource Management

**`SkillPluginLoader` leaks `URLClassLoader`** (PS123-005)

`SkillPluginLoader` acquires a `URLClassLoader` via `ZIO.acquireRelease` but provides `_ => ZIO.unit` as the release function. Every declarative skill load therefore leaks a classloader and its associated JAR file handles. On a long-running server this will exhaust file descriptors. The fix is a single-line change to the release arm: `cl => ZIO.attempt(cl.close()).ignore`. The blocking class-loading call should additionally use `ZIO.attemptBlockingIO`.

**`ApprovalHub.preDecisions` map grows unbounded** (PS123-018)

The `preDecisions` map in `ApprovalHub` accumulates entries for every approval request. If an agent fiber is cancelled or interrupted before it calls `awaitDecision`, the corresponding map entry is never removed. On a long-running server with many short-lived approval cycles the map will grow without bound. A TTL-based cleanup (or removal on the notification side when the request expires) is needed.

**`ApprovalHub` subscriber queue leaks** (PS123-026)

`subscribeToNewRequests` appends a new `Queue` to the subscribers list. If the caller acquires the stream but never consumes it (for example, a WebSocket client that disconnects immediately), the queue remains in the list forever and will receive all future events without any consumer draining them. The returned `ZStream` should use `ZStream.ensuring` to remove its queue from the subscribers list on completion.

---

### Architecture and Layer Discipline

**Skill lifecycle transitions write no event log entries** (PS123-030) CONFIRMED BY 3 REVIEWERS

SDD architecture principle 3 states "every significant action writes to the append-only event log." `SkillLifecycleService` performs `approve`, `reject`, `advance`, and `createDraft` transitions with no corresponding event log writes. This means the entire declarative skill lifecycle is invisible to the audit trail, incident reconstruction, and any future analytics that rely on the event log.

Pattern Recognition, Functional Scala, and SRS/SDD reviewers all independently flagged this omission. The fix is to call `repos.eventLog.append(...)` at each state-transition point.

**`rss.save_feed`/`rss.remove_feed` require wrong capability** (PS123-032) CONFIRMED BY 2 REVIEWERS

The RSS feed management tools gate write operations on the `rss.read` capability, violating the deny-by-default model: a user who can read feeds is implicitly granted the ability to modify them. An `rss.manage` capability should be introduced for all state-mutating RSS tools. Pattern Recognition and SRS/SDD reviewers both flagged this independently.

**Two divergent lifecycle mutation paths** (PS123-066)

GQL lifecycle mutations route through `SkillRegistry`, while `SkillAuthoringSkill.propose` routes through `SkillLifecycleService` directly. This creates two code paths with potentially different validation, event-logging, and permission-checking behaviors. One path should be authoritative; callers should converge on it.

**`CapabilityEvaluatorImpl` bypasses abstract trait** (PS123-033)

`CapabilityEvaluatorImpl` injects the concrete `ZIOPermissionRepository` rather than the abstract `PermissionRepository[F[_]]` that every other evaluator in the layer stack uses. This creates an invisible coupling between the capability evaluation layer and the database-specific implementation, making the evaluator un-substitutable in tests without a real database.

**`PermissionReview` auto-advances without human gate** (PS123-063)

The SRS specifies human-in-the-loop review at the `PermissionReview` lifecycle step. The current implementation auto-advances through this step, silently bypassing the approval gate. This deviation from the specification should be explicitly documented as an MVP limitation; if left undocumented it will look like a bug during any compliance audit.

---

### Functional Purity

**`SkillLifecycleService` bypasses ZIO Clock** (PS123-027) CONFIRMED BY 4 REVIEWERS

`SkillLifecycleService.createDraft`, `.advance`, and `.approve` call `Instant.now()` directly at lines 91, 104, and 158, bypassing ZIO's `Clock` service. This is the same violation flagged in Phases 8 and 9 and is now the third module to repeat it. Tests cannot control or assert on timestamps, and any TestClock-based time manipulation will be silently ignored.

Performance Oracle, Code Simplicity, Functional Scala, and Pattern Recognition reviewers all independently flagged this as a cross-cutting pattern.

**`DiscordApiClient.disconnect()` runs side effects eagerly** (PS123-002)

`disconnect()` executes three synchronous side-effectful calls (JDA state changes) at method-definition time, before the returned `ZIO.blocking(...)` is executed by the ZIO runtime. This violates ZIO's referential transparency contract: calling `disconnect()` and ignoring the returned effect would still run the side effects. All three calls must be wrapped in `ZIO.succeed { ... }.flatMap(...)`.

**`RssFeedParser` uses unsafe casts** (PS123-023)

`parseRss` and `parseAtom` use `asInstanceOf[Element]` rather than pattern matching. If the DOM tree contains non-Element nodes at the expected position the cast throws a `ClassCastException` at runtime, which ZIO does not catch in a typed channel.

---

### Performance

**No request timeout on `HttpApiExecutor`** (PS123-001)

`client.batched(req)` in `HttpApiExecutor` has no timeout. A declarative skill calling an unresponsive external endpoint will block a ZIO fiber indefinitely. Because declarative skills are intended to be user-created and user-configured, the risk of pointing at a slow or unreachable endpoint is high. A 30-second default with an override in `HttpApiExecutorConfig` is the minimum acceptable guard.

**`DocumentBuilderFactory` constructed per parse** (PS123-016)

Every RSS fetch triggers a `ServiceLoader` scan and 6 `setFeature` calls to construct a new `DocumentBuilderFactory`. The factory is thread-safe and is designed to be instantiated once. Moving it to `lazy val` eliminates the per-call overhead entirely.

**`ToolEventHub`/`ApprovalHub` back-pressuring queues can suspend agent fibers** (PS123-024)

Both hubs use `Queue.bounded(256)` with `offer`, which blocks the offering fiber when the queue is full. A slow subscriber can therefore suspend the agent fiber that is trying to publish a tool event, creating invisible latency and potential deadlocks. `Queue.dropping` or `Queue.sliding` should be used; the hub's Scaladoc should document which drop semantics are chosen and why.

---

### Test Coverage

**Zero coverage for core Sprint 2 and Sprint 3 components** (PS123-007 through PS123-012)

Six components added in the last two sprints shipped with no tests at all:

| Missing Test Suite            | Gap                                                                              |
|-------------------------------|----------------------------------------------------------------------------------|
| `RssFeedSkillSpec`            | `fetchFeed`, HTTP errors, caching, duplicate detection all undetectable           |
| `HttpApiExecutorSpec`         | URL template substitution, non-2xx responses, timeout behavior all undetectable  |
| `PromptTemplateExecutorSpec`  | Template rendering, FinalAnswer path, ToolCallRequested path all undetectable    |
| `DeclarativeSkillSpec`        | Executor routing (HttpApi vs PromptTemplate dispatch) undetectable               |
| `SkillAuthoringSkillSpec`     | Propose happy path, manifest parse failure, invalid JSON all undetectable        |
| `JorlanAPISpec` (toolEvents)  | Session-id scoping, field projection, event fan-out all undetectable             |

These are the highest-priority test items because they cover the two major Sprint 2 deliverables (declarative skills) and one major Sprint 3 deliverable (RSS).

**`InMemorySkillRepo` stubs unimplemented** (PS123-053)

`getConnector`, `searchConnectors`, and `upsertConnector` are implemented as `???` in `InMemoryRepositories`. Any test that exercises a connector-related code path through the in-memory layer will crash at runtime rather than returning a controllable stub value.

**Hub cleanup and approval decision paths untested** (PS123-045 through PS123-054)

The `ZStream.ensuring` cleanup in both `ApprovalHub` and `ToolEventHub`, the Cancelled/Expired branches in `ApprovalServiceImpl`, the once-grant re-use path, and all three `SkillLifecycleService` not-found error paths are untested. These are the error and cleanup paths most likely to surface in production but least likely to be exercised manually.

---

### Code Quality and Simplicity

**Duplicated `substitute` function** (PS123-035)

`HttpApiExecutor` and `PromptTemplateExecutor` are in the same package and contain identical `substitute` implementations. A single `object DeclarativeArgSubstitution` with a shared method would eliminate the duplication and ensure any future bug fix is applied consistently.

**`requireStr`/`requireLong` triplicated across skills** (PS123-037) CONFIRMED BY 2 REVIEWERS

Three skills independently implement nearly identical argument-extraction helpers. This is the third recurrence of a pattern that should be part of the `Skill` base trait. Code Simplicity and Pattern Recognition reviewers both flagged it.

**`ManifestValidator` verbosity** (PS123-040)

The validator contains 8 structurally identical `if (cond) Nil else List(msg)` expressions. A two-line private helper `def require(cond: Boolean, msg: => String): List[String]` would make the validation logic significantly more readable and reduce the chance of copy-paste errors.

**`DiscordConnectorSkill.invoke` bypasses inherited helpers** (PS123-043)

The invoke method manually calls `.asObject.getOrElse(Map.empty).get("field").flatMap(_.asString)` instead of using the `str`/`int` helpers already inherited from the `Skill` trait. This is inconsistent with every other skill in the codebase and makes the intent harder to follow.

**`buildManifestJson` uses string interpolation** (PS123-070)

The 40-line string-interpolation JSON builder in `CreateSkillWizard` is fragile — any field containing a double-quote will produce malformed JSON. Building a `DeclarativeSkillManifest` case class and calling `encoder.encodeJson(manifest)` would be both safer and more readable.

---

### Documentation

**INSTALL.md accuracy issues** (PS123-013, PS123-014)

The `rss-feed/INSTALL.md` references a `sbt rssFeedSkillJVM/package` command but the module is absent from `build.sbt`, so the command fails. The `discord/INSTALL.md` section 6 documents a complete Discord OAuth login flow with env vars (`DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`, `DISCORD_REDIRECT_URI`) that do not exist in the codebase. Both documents will mislead users attempting to deploy these connectors.

**`phase16-declarative-skills.md` is out of sync in four ways** (PS123-056 through PS123-059)

The mini-design document specifies 8 public `SkillLifecycleService` methods but only 4 were implemented (the design collapsed `advance` to a single method). The sandbox test is described as a "real invocation" but is a no-op auto-pass. `submitForApproval` is described as creating an `ApprovalRequest` but it only updates version status. The `ExecutorConfig` JSON examples use `"type"` discriminator syntax but the actual zio-json encoding is `{"HttpApi":{...}}`. All four discrepancies should be corrected before new contributors read the design.

**Missing `doc/skills/approvals.md`** (PS123-061)

The Approvals feature is fully wired and exposed via a UI page, but there is no user-facing documentation explaining how approvals are triggered, how long they wait, how to respond via the shell or the web UI, or how approval differs from skill lifecycle review. This is the largest documentation gap relative to the amount of functionality delivered.

**Competing skills documentation files** (PS123-055)

`doc/skills/contacts.md` contains complete documentation for the unified UserManagementSkill (which absorbed the former ContactsSkill in Sprint 1). `doc/skills/user-management.md` is a stale stub. The `contacts.md` content should be merged into `user-management.md` and `contacts.md` deleted to eliminate the ambiguity.

---

## Appendix A — UI Test Plan (104 Test Cases)

The UI Test Plan Writer produced a comprehensive manual test plan covering the five main UI surfaces added or modified across Sprints 1-3. Key gaps identified are summarized below; the full 104-case plan is available from the `ui-test-plan-writer` agent output.

**Landing Page (TC-001 to TC-021)**
Hero CTAs, Features section, Skills section, OpenSource section, and Footer links. All links should be verified live; placeholder screenshots (showing icons instead of real app screenshots) are the primary known gap.

**AppShell and Navigation (TC-022 to TC-026)**
Nav drawer, hash routing for all top-level routes, logout flow.

**CreateSkillWizard (TC-027 to TC-048)**
All 5 wizard steps, lifecycle progression Draft→AwaitingApproval, and manifest JSON preview. Key gaps:
- Steps 0-3 have no client-side field validation; clicking Next with empty required fields succeeds silently.
- Wizard state is not persisted across navigation away and back.
- There is no unsaved-changes guard on Cancel.

**CustomSkillsPage (TC-049 to TC-060)**
Loading, empty state, pending approval table, approve/reject flows with optimistic UI.

**SkillsPage (TC-061 to TC-074)**
Loading, enable/disable toggle, OAuth warning banner, expand/collapse, configure panel, docs dialog.

**McpServersPage (TC-075 to TC-088)**
CRUD operations, transport type selection, env var management, reload trigger.

**ApprovalsPage (TC-089 to TC-096)**
Loading, risk-level chip colors, approve/deny actions, WebSocket subscription. Key gap:
- OAuth connection status is stale after connecting (requires page reload to reflect updated state).

**Cross-cutting (TC-097 to TC-104)**
Unauthenticated redirect, unknown route (404), toast notification display, CSS rendering across viewports.

---

## Cross-Cutting Patterns

**ZIO Clock bypass with `Instant.now()`** was independently flagged by four agents (Performance Oracle, Code Simplicity, Functional Scala, Pattern Recognition) and appears in `SkillLifecycleService` at three call sites (PS123-027, PS123-028). This is the third phase in which this violation has been identified. It should be treated as a systemic gap in onboarding guidance: the standard ZIO idiom for current time (`Clock.instant`) is apparently not well-known to all contributors. Adding a project-level linting rule or a note in CLAUDE.md would prevent the pattern from recurring.

**Duplicated helper methods across skills** was flagged by Code Simplicity and Pattern Recognition at two levels: fine-grained (`substitute` function, PS123-035) and coarse-grained (`requireStr`/`requireLong`, PS123-037; `fieldStr`/`fieldIntOpt`, PS123-036). The root cause is that the `Skill` base trait does not expose enough shared infrastructure. Each sprint's new skills re-derive helpers that should live in the trait. A single pull request adding `requireStr`, `requireLong`, and `requireStr` to `Skill` and removing the per-skill copies would close all three findings.

**Missing event log writes for significant actions** was independently confirmed by Pattern Recognition, Functional Scala, and SRS/SDD reviewers (PS123-030, PS123-064). The event log is the system's audit backbone; two entire subsystems (declarative skill lifecycle and approval requests) currently produce no event log entries. This pattern also appeared in a prior phase review. A checklist item in the phase template ("does this feature write to the event log?") would prevent recurrence.

**Security gaps introduced alongside new GQL subscriptions** appears in two independent findings (PS123-003 for `approvalNotifications`, PS123-032 for `rss.save_feed`/`rss.remove_feed`). The common root cause is that capability guards were applied to the read path but not audited for write or subscribe paths. A pre-merge checklist step — "verify requireCapability is present on every new GQL subscription and every state-mutating tool" — would address this structurally.

**Zero test coverage for entire new components** (PS123-007 through PS123-012) is a pattern unique to this review: six independent components each have zero tests. In prior phases, coverage gaps were typically on specific branches or error paths, not entire components. The Sprint 2 declarative skill subsystem and the Sprint 3 RSS skill were apparently developed without accompanying tests. Future sprints should require at minimum a smoke test per new `Skill` subclass and per new GQL subscription before the branch is submitted for review.

---

## Summary Statistics

**Issues by severity:**

| Severity   | Count |
|------------|-------|
| Critical   | 14    |
| Warning    | 52    |
| Suggestion | 11    |
| **Total**  | **77** |

**Issues by area:**

| Area                | Count |
|---------------------|-------|
| Code Quality        | 16    |
| Test Coverage       | 16    |
| Documentation       | 14    |
| Architecture        | 6     |
| Correctness         | 4     |
| Performance         | 5     |
| Functional Purity   | 3     |
| Security            | 2     |
| Resource Management | 3     |
| Concurrency         | 2     |
| Observability       | 2     |
| Error Handling      | 1     |
| Data Integrity      | 1     |
| Package Coherence   | 1     |
| **Total**           | **77** |

**Agent contribution:**

| Agent                    | Unique Findings | Cross-Confirmed |
|--------------------------|-----------------|-----------------|
| Performance Oracle       | 10              | 4               |
| Code Simplicity          | 15              | 5               |
| Functional Scala         | 9               | 5               |
| Pattern Recognition      | 13              | 7               |
| Test Coverage Tracker    | 16              | 3               |
| ScalaDoc Auditor         | 12              | 2               |
| SRS/SDD Conformance      | 6               | 4               |
| UI Test Plan Writer      | 104 UI test cases (Appendix A) | — |

**Sprint 1-2-3 scope completion:**

| Deliverable                                          | Status |
|------------------------------------------------------|--------|
| ContactsSkill merged into UserManagementSkill        | ✅     |
| EmailSkill, MemorySkill, ShellSkill, WorkspaceSkill  | ✅     |
| Module INSTALL.md / README files                     | ⚠️     |
| Landing page                                         | ✅     |
| EmbeddingStore and memory vector store               | ✅     |
| Capability grant grantee type                        | ✅     |
| DeclarativeSkill + HttpApiExecutor + PromptTemplate  | ✅     |
| ManifestValidator + SkillLifecycleService            | ⚠️     |
| SkillAuthoringSkill                                  | ⚠️     |
| GQL expansions (invokeTool, lifecycle mutations)     | ⚠️     |
| CreateSkillWizard + CustomSkillsPage                 | ⚠️     |
| MCP.md                                               | ⚠️     |
| Discord connector (full)                             | ⚠️     |
| RSS Feed skill                                       | ⚠️     |
| Approvals system (ApprovalHub, ToolEventHub, UI)     | ⚠️     |
| Test coverage for new components                     | ❌     |
| Event log integration for lifecycle and approvals    | ❌     |
| `approvalNotifications` auth guard                   | ❌     |

---

## What Was Done Well

**ZIO layer composition**: New subsystems (ApprovalHub, ToolEventHub, SkillLifecycleService, DiscordConnectorSkill) all use well-formed `ZLayer.make` / `ZLayer.fromZIO` patterns consistent with the rest of the codebase. No `provide` anti-patterns or globally mutable state were introduced.

**Hub-based reactive fan-out**: The `ToolEventHub` and `ApprovalHub` follow the same pub/sub pattern established for sessions in earlier phases. Using `ZStream` with per-subscriber queues is the right architecture; the identified issues (queue size, back-pressure semantics, leak on unconsumed stream) are configuration and cleanup details rather than structural problems.

**Declarative skill manifest design**: The `DeclarativeSkillManifest` case class hierarchy with `ExecutorConfig` ADT and `ParameterSpec` are cleanly modelled. The zio-json derivation is straightforward and the separation between manifest validation and execution is correct.

**Skill versioning schema**: Introducing explicit version tracking for declarative skills (Draft → AwaitingPermissionReview → AwaitingApproval → Approved) is the right foundation for the human-in-the-loop lifecycle. The model is sound even if some transition steps currently auto-advance.

**DiscordMessageNormalizer parity**: The normalizer correctly mirrors the Telegram normalizer's structure and transformation logic, making the two connectors easy to compare and maintain in parallel.
