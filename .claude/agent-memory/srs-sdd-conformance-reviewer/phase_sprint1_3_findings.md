---
name: phase-sprint1-3-findings
description: Conformance review findings for Sprint 1, 2, and 3 work (Approvals, Declarative Skills, Discord, RSS) — 2026-06-29
metadata:
  type: project
---

Sprint 1-3 review (2026-06-29). Key patterns found:

- Skill lifecycle transitions write no event log entries (MAJOR, recurring pattern)
- Instant.now() used in ZIO context in SkillLifecycleService and SkillRegistryLive (MAJOR, recurring pattern)
- RSS rss.save_feed / rss.remove_feed require only rss.read despite being state-mutating (MAJOR)
- PermissionReview step auto-advances without human gate (MAJOR)
- requestApproval loses agentId/sessionId correlation IDs in event log (MAJOR)
- Duplicate LifecycleResult type in two packages (MINOR)
- HumanApprovalNotifierImpl is dead/orphaned code (Phase 8 stub, not wired) (MINOR)
- Discord processMessage isBot check ordering is post-filter instead of pre-filter (MINOR)
- Roadmap appendix tables not updated for Discord connector and Declarative JSON (MINOR)
- SkillLifecycleService and SkillRegistryLive duplicate the full lifecycle state machine (MAJOR)
- GQL lifecycle mutations route through SkillRegistry; SkillAuthoringSkill routes through SkillLifecycleService
- ApprovalHub correctly wired to approvalNotifications GQL subscription
- ApprovalServiceImpl correctly writes ApprovalRequested and ApprovalGranted/Denied events
- CapabilityEvaluatorImpl correctly implements 7-step evaluation order from SDD §6.5
- DeclarativeSkill correctly marked Tier.Declarative, extends Skill (not ConnectorSkill)
- Discord connector correctly extends ConnectorSkill, uses MessageIngress, wired via EnvironmentBuilder
