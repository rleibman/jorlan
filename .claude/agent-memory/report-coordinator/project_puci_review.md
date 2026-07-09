---
name: project-puci-review
description: Use-Case Manifest Importer (PUCI) review written 2026-07-06; 10 findings, 3 critical (1 resolved), 2 real prod bugs found+fixed (Agent.upsert dup-row, 16 search methods pagination-before-sort)
metadata:
  type: project
---

Wrote `doc/phase-review/use-case-manifest-importer-review.md` on 2026-07-06 for the Use-Case Manifest
Importer work (not a numbered roadmap phase — used "PUCI" as the ID prefix, e.g. PUCI-001).

Key facts for future reference:
- New sbt modules this phase: `useCaseImporter` (CLI, `UseCaseImporterApp`) and `shellClient` (extracted
  from `shell`, holds `ZIOClientRepositories`).
- Two real pre-existing production bugs were found and fixed in `QuillRepositories.scala`: `Agent.upsert`
  silently duplicated rows on update (MariaDB `LAST_INSERT_ID()`/`ON DUPLICATE KEY UPDATE` quirk), and 16
  search methods across nearly every domain type paginated before sorting (root cause of the Event Log
  web page showing almost no recent activity). Neither fix has regression tests yet (PUCI-009, open).
- 3 findings were fixed directly in-session after agents reported them and marked [x] resolved in the
  table: wrong run-command module path in 4 doc locations + sbtn-hang discovery (PUCI-001), wrong
  per-agent vs per-user uniqueness scope in manifest-schema.md (PUCI-002), and opaque runtime pipeline
  template-reference errors now caught by a new `validatePipelineReferences` fail-fast check (PUCI-003).
- 2 Critical items left open and featured prominently in the executive summary: the ai.timeout 5->10min
  bump is likely ineffective for scheduled pipelines because TriggerEngine's independent jobTimeout
  (default 300s, `configuration.scala:51`) fires first (PUCI-004); and RoleSearch/AgentSearch have no
  server-side exact-match name filter, so client-side `.find()` over a single page silently misses rows
  past pageSize — provisionAgent/userByEmail's `pageSize=500` workaround is the same bug at a bigger
  threshold, not a fix (PUCI-005, confirmed by 2 reviewers).
- Recurring cross-phase pattern continues: missing event-log entries on new GraphQL mutations is now a
  3rd occurrence (after Phase 8 and Sprint 1-3 reviews) — see [[project_sprint1_2_3_review]]. Also newly
  reintroduced: the floating-methods-on-a-facade anti-pattern reappeared on the freshly-extracted
  ZIOClientRepositories, despite an earlier repo refactor ([[repo_refactor_floating_methods]] in main
  project memory) deliberately eliminating it.
