---
name: sprint123-perf
description: Sprint 1/2/3 performance findings — RSS, Discord connector (JDA), ApprovalHub, ToolEventHub, HttpApiExecutor, ManifestValidator, CreateSkillWizard
metadata:
  type: project
---

Key findings from the Sprint 1-3 performance review (2026-06-29):

- **DocumentBuilderFactory per call**: RssFeedParser creates a new factory+builder for every parse. Cache the factory as a lazy val.
- **RSS no ETag/conditional GET**: Every `rss.fetch` fetches the full feed. No caching layer. Agents that poll feeds will generate full HTTP traffic on every call.
- **HttpApiExecutor no timeout**: `client.batched(req)` with no deadline. A slow declarative skill endpoint will block a fiber indefinitely. Critical issue.
- **JSON double-serialization**: Both `ManifestValidator.validate` and `SkillLifecycleService.parseManifest` call `manifestJson.toString.fromJson[...]` — the AST is serialized to string then parsed again.
- **ApprovalHub sequential fan-out**: Uses `ZIO.foreachDiscard` (sequential); ToolEventHub correctly uses `ZIO.foreachParDiscard`. Inconsistency — fix ApprovalHub to match.
- **ApprovalHub preDecisions leak**: Entries added to preDecisions when completeDecision fires before awaitDecision are never removed if awaitDecision never runs (agent fiber interrupted). TTL or cleanup-on-expiry needed.
- **Discord queue silent drop**: `LinkedBlockingQueue(1024)` drops oldest message on overflow with no log warning. Under high-traffic guilds messages are silently lost.
- **Discord sequential ingress**: processMessage is awaited sequentially before next event loop iteration — slow agent processing stalls the receive loop.
- **Instant.now() in effects**: SkillLifecycleService.approve/createDraft uses `Instant.now()` directly in ZIO for-comprehensions (recurring pattern, not clock-safe).

**Why**: Applies to `server`, `rss-feed`, `discord` modules.
**How to apply**: Flag these areas in future reviews; HttpApiExecutor timeout is the top production risk.
