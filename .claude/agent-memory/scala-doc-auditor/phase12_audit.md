---
name: phase12-audit
description: Audit results for Phase 12 Built-in Skills; key gaps, stale comments, and good examples identified
metadata:
  type: project
---

## Audit date: 2026-06-10

## Files audited
- server/src/main/scala/jorlan/service/SkillRegistry.scala
- server/src/main/scala/jorlan/service/NotificationRouter.scala
- server/src/main/scala/jorlan/service/NotifySkill.scala
- server/src/main/scala/jorlan/service/ContactsSkill.scala
- server/src/main/scala/jorlan/service/WorkspaceSkill.scala
- server/src/main/scala/jorlan/service/ShellSkill.scala
- server/src/main/scala/jorlan/service/AgentRunnerImpl.scala

## Issues found

### Stale doc (must fix)
- `model/src/main/scala/jorlan/service/AgentRunner.scala` line 22: "The full ReAct planning loop (multi-step tool dispatch) is deferred to Phase 12." — Phase 12 is COMPLETE; this sentence is now false.

### Missing ScalaDoc (public surfaces)
- `SkillRegistry.object` — `live` ZLayer value has no doc (empty registry; contrast with `liveWith` which is documented).
- `SkillRegistry.object` — companion accessor methods (`register`, `allTools`, `allToolSpecs`, `invoke`) are undocumented; they are public and the canonical call site for ZIO env-style usage.
- `SkillRegistryLive` class — no class-level ScalaDoc explaining it is the `Ref`-backed live implementation.
- `NotificationRouter.object` — `live` ZLayer value has no doc.
- `NotificationRouter.object` — companion accessors (`notifyUser`, `notifyChannel`) have no doc.
- `NotifySkill` class — no constructor parameter `@param router` doc; the `router` field drives all channel dispatch and is the primary dependency.
- `NotifySkill.object` — `live` ZLayer has no doc.
- `ContactsSkill` class — no constructor `@param repo` doc.
- `ContactsSkill.object` — `live` ZLayer has no doc.
- `WorkspaceSkill` class — constructor `@param workspaceRoot` is undocumented (especially important because it must be absolute + normalized — security invariant).
- `WorkspaceSkill.object` — `live` ZLayer has no doc (reads from `WorkspaceSettings.root` and normalises).
- `ShellSkill` class — no constructor `@param settings` doc.
- `ShellSkill.object` — `live` ZLayer has no doc.
- `AgentRunnerImpl` class — class itself has no ScalaDoc (the multi-step description is on the `private class AgentRunnerState` comment instead; that's a good detail but is on the wrong element).
- `AgentRunnerImpl` constructor parameters — none documented (`@param` missing for all 7 fields).
- `AgentRunnerImpl.object` — `live` ZLayer has no doc; `defaultMaxToolSteps` constant has no doc.
- `AgentRunnerState` — private, so low priority, but `make` factory method (also private) is the canonical constructor and has no doc.

### Missing @param / @return on specific methods
- `WorkspaceSkill.safePath` — private but has its own ScalaDoc; `@param relative` is absent (the doc describes the behaviour correctly, just missing the param tag).
- `AgentRunnerImpl.processMessage` (override) — inherits from trait, but the override has no `@inheritdoc` or supplementary note about the ReAct loop changes introduced in Phase 12.

### Configuration case classes (in configuration.scala)
- `AgentSettings` — `maxToolSteps` field has no `@param` doc (only the class-level one-liner exists).
- `WorkspaceSettings` — `root` field has no `@param` doc; the default path `/var/lib/jorlan/workspaces` is significant but undocumented.

## Good examples (no changes needed)
- `SkillRegistry` trait — clear summary, tool-discovery purpose explained, error-wrapping contract on `invoke` is documented.
- `SkillRegistry.liveWith` — `@param`-style description is clear.
- `NotificationRouter` trait — channel-preference strategy and circular-dependency design rationale are both documented.
- `NotificationRouter.notifyUser` / `notifyChannel` — `@return` contracts (Json.Str("ok") vs error string) are explicit.
- `NotifySkill` class — tool list in summary is accurate and useful.
- `ContactsSkill` class — tool list in summary is accurate.
- `WorkspaceSkill` class — path-traversal security contract and rejection behaviour are documented at class level.
- `ShellSkill` class — allowlist matching rule (last path segment or full value) and the failure mode (error without execution) are documented.
- `AgentRunnerState` comment block (on the class) — the numbered ReAct steps are detailed and accurate for Phase 12.

## Confirmed accurate descriptions (do not change)
- `NotificationRouter` bypass-of-SkillRegistry rationale is correct per architecture.
- `ShellSkill.isAllowed` exact match rule matches the implementation.
- `WorkspaceSkill.safePath` traversal rejection matches implementation.
- `AgentRunnerImpl` ReAct loop steps (1–6) match actual implementation.
