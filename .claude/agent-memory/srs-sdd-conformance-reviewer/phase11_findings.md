---
name: phase11-findings
description: Phase 11 Telegram Connector conformance review — key violations and patterns (2026-06-07)
metadata:
  type: project
---

Phase 11 delivered the plugin seam and Telegram connector with these notable gaps:

1. **Reply path is checked off but not implemented**: `TelegramConnectorSkill` stores `agentRunner` but never calls `subscribeToSession`. The `ZStream` import is unused. Roadmap item is incorrectly marked `[x]`.

2. **UnrecognizedIdentityPolicy is dead code**: `TelegramConfig.unrecognizedPolicy` field exists but `MessageIngressImpl.handleUnrecognized` always does the same thing (log + drop) regardless of policy. SDD §5 line 317 requires connector-policy-driven behavior. The `receive` signature does not accept a policy parameter.

3. **Missing test: resolve-or-create session reuse**: Roadmap lists "resolve-or-create session" as a required test case; only one session path tested.

4. **Event log missing sessionId**: `logInboundEvent` in `MessageIngressImpl` always passes `sessionId = None` even when a session was resolved/created just above.

5. **Resolve-or-create uses in-memory search on limited page**: `searchSessions(pageSize=100)` + in-memory `.find` — not using DB index; `AgentSessionSearch` has no `chatRef` filter; users with >100 sessions may get duplicate sessions.

6. **`Instant.now()` in normalizer**: `TelegramConnectorSkill.scala:52` uses `java.time.Instant.now()` (impure side-effect) instead of ZIO `Clock.instant` supplied by the caller. Minor ZIO style violation.

7. **Module placement deviation from original mini-design**: Traits (`Skill`, `MessageIngress`, `InboundMessage`) are in `connector-api/jorlan.connector` rather than `model/jorlan.service` and `model/jorlan.domain` as originally specified. This was documented as a deliberate restructuring in the roadmap ("Module restructuring — added per Phase 11 review") and all checkboxes reflect the new location. Architectural direction (model → connector-api → server) is still correct.

**Why:** [[recurring_patterns]] — reply path completion and event log completeness are recurring gaps.
**How to apply:** In future phases, verify that `agentRunner` fields are actually used, and that event log entries carry full context.
