---
name: project_sprint1_2_3_review
description: Sprint 1-2-3 (PS123) tech debt review written 2026-06-29; 77 findings, 14 critical
metadata:
  type: project
---

Sprint 1-2-3 review written 2026-06-29 to `/home/rleibman/projects/jorlan/doc/phase-review/sprint1-2-3-review.md`.

**Why:** Multi-sprint review covering Discord connector, RSS skill, Declarative skill system, Approvals subsystem, and web UI additions.

**Key stats:** 77 findings total — 14 Critical, 52 Warning, 11 Suggestion. Feature ID prefix: PS123-NNN.

**Critical highlights:**
- PS123-003: `approvalNotifications` GQL subscription has no auth/capability guard (security regression)
- PS123-005: `SkillPluginLoader` leaks URLClassLoader
- PS123-007 to PS123-012: Six new components shipped with zero test coverage
- PS123-013/014: INSTALL.md inaccuracies for rss-feed and discord modules

**Top cross-cutting patterns (recurring across agents):**
1. `Instant.now()` bypassing ZIO Clock — 4 agents confirmed, 3rd phase this appears
2. Missing event log writes for significant actions — 3 agents confirmed
3. Capability guard gaps on new GQL subscriptions and write tools — 2+ agents
4. Helper method duplication across skills (requireStr/requireLong triplicated)
5. Zero-coverage entire components (new pattern, not just branch gaps)

**How to apply:** If reviewing next sprint, check whether these recurring patterns recur; if so, recommend structural fixes (lint rules, base trait additions, PR checklist) rather than per-instance fixes.
