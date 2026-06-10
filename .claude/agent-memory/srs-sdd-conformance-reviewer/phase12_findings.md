---
name: phase12-findings
description: Phase 12 Built-in Skills conformance review findings (2026-06-10)
metadata:
  type: project
---

Phase 12 Built-in Skills & ReAct loop review — 2026-06-10.

**Why:** To confirm the implementation matches the mini-design at doc/mini-designs/phase12-built-in-skills.md.

**How to apply:** Use as context for Phase 13+ reviews that touch SkillRegistry, ReAct loop, or skill invocation patterns.

## Conformant
- ReAct loop implemented in AgentRunnerImpl with correct recursion, max-step guard, event logging
- SkillRegistry: Ref-based, UIO error channel on invoke (errors returned as Json.Str, not exceptions)
- NotificationRouter uses ConnectorManager directly (avoids SkillRegistry cycle) — matches design §4.1
- ToolSpec lives in model module (no connector-api dependency) — matches design §4 anti-circular requirement
- AgentSettings(maxToolSteps: Int = 10) as ZIO service — matches design §10
- WorkspaceSettings and ShellSettings in JorlanConfig — matches design §10
- skills query in GQL; SkillRegistry in JorlanApiEnv
- InitService seeds all 7 Phase 12 capability grants including identity.manage
- MemorySkill + SchedulerSkill pre-registered in liveSkillRegistryLayer (EnvironmentBuilder)
- NotifySkill, ContactsSkill, WorkspaceSkill, ShellSkill registered at runtime in startServices

## Deviations / Issues
1. MAJOR: SkillRegistry.lookup absent — design spec §3.4 lists it as part of the trait; not implemented (invoke goes direct)
2. MAJOR: SkillRegistry.invoke does NOT gate on requiredCapabilities — design spec §3.4 explicitly requires CapabilityEvaluator check before invocation; InvocationContext lacks approvalId/workspaceId/traceId fields
3. MAJOR: workspace.snapshot tool missing — design spec §6.2 requires it
4. MAJOR: workspace.delete uses workspace.write capability — design spec §6.2 says Destructive with "Always" approval required
5. MAJOR: ShellSkill uses ZIO.attempt (no blocking thread pool) for process I/O; WorkspaceSkill uses ZIO.attempt for all FS calls — should use ZIO.attemptBlocking
6. MINOR: SkillInfo.tier in GQL `skills` query always hardcodes "BuiltIn" by inspection of first tool — loses actual SkillTier from SkillDescriptor
7. MINOR: ContactsSkill.identityLink uses bare Instant.now() instead of Clock.instant (test-clock unfriendly)
8. MISSING: notifyUser GQL mutation (design §8.1)
9. MISSING: toolEvents subscription (design §8.1)
10. MISSING: V025 migration adding workspace_id to agent_sessions (design §11)
11. MISSING: WorkspaceSettings.defaultScope ("session" or "user") — not in configuration model; WorkspaceSkill always uses a flat root without per-session or per-user scoping
12. MISSING: workspace scope binding (per-session/per-user directory) — WorkspaceSkill uses a single flat workspaceRoot
13. MISSING: shell artifact capture (design §7.3) — no ArtifactRepository call when stdout > captureThreshold
14. MISSING: shell event log entries ShellCommandInvoked / ShellCommandCompleted (design §7.4)
15. MISSING: InvocationContext fields workspaceId, approvalId, traceId (design §9)
