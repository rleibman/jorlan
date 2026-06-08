---
name: feedback-review-style
description: Style conventions for Jorlan phase review reports — derived from Phase 10 review as canonical reference
metadata:
  type: feedback
---

Use `doc/phase-review/phase10-review.md` as the canonical style reference for all phase reviews.

Key conventions:
- Feature IDs: `P[phase]-[NNN]` (zero-padded three digits, globally unique)
- Table columns: Status | Feature ID | Severity | Area | Issue | File : Line | Recommended Action
- Issue column: one sentence, append `(confirmed by N reviewers)` when 2+ agents flagged independently
- Grouped Sections: one section per theme; include code snippets for complex fixes
- Cross-Cutting Patterns section is required — name the specific finding IDs sharing each pattern
- Summary Statistics: severity table, area table, agent contribution table, scope completion table
- Scope completion uses ✅ / ⚠️ / ❌

**Why:** Consistency across phase reviews makes it easy to scan history and track which patterns recur.

**How to apply:** When writing any new phase review, open phase10-review.md first to calibrate format and tone before writing.
