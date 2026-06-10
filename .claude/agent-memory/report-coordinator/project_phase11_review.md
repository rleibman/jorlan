---
name: project-phase11-review
description: Phase 11 Telegram Connector tech debt review — key findings and health verdict
metadata:
  type: project
---

Phase 11 review written 2026-06-07. Saved to `doc/phase-review/phase11-review.md`.

**Why:** Health verdict is Critical Issues — not ready to advance to Phase 12. Four critical blockers must be resolved first.

**How to apply:** When Phase 12 work begins, check that P11-001 through P11-007 are resolved (reply path, session reuse filter, unsafe .get in invoke, UnrecognizedIdentityPolicy, Base64 outside ZIO, required-field validation, fiber leak on shutdown).

Key facts:
- 55 total findings: 7 Critical, 26 Warning, 22 Suggestion
- Most-confirmed finding: `Instant.now()` in normalizeMessage — flagged by all 5 content-reviewing agents (P11-010)
- Reply path (AgentRunner.subscribe → telegram.send_message) entirely absent despite roadmap checkbox marked [x] — P11-001
- `resolveOrCreateSession` defeats V024 DB index — in-memory Scala filter after 100-row page cap — P11-002
- Two open design questions for Phase 12: reply egress path ownership (OD-1) and UnrecognizedIdentityPolicy enforcement scope (OD-2)
- `connector-api` module boundary and `TelegramApiClient` trait-with-fake were noted as done well
