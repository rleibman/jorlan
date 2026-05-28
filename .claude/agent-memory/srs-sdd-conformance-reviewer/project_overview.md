---
name: project-overview
description: Jorlan project context for conformance reviews — tech stack, key docs, branch structure
metadata:
  type: project
---

Jorlan is a Scala 3 / ZIO / MariaDB / Caliban secure agent runtime. Development proceeds in numbered phases on branches named `phase-N/description`. Spec docs live in `doc/`. SRS in `doc/SoftwareRequirementsSpecification.md`, SDD in `doc/SoftwareDesignDocument.md`, orchestrator addendum in `doc/orchestrator_integration_addendum.md`, roadmap in `doc/development_roadmap.md`.

Key architectural principles (from CLAUDE.md):
1. Deny-by-default capability model
2. Domain layer must NOT depend on DB or connector specifics
3. Every significant action writes to the append-only event log
4. GraphQL is the primary external API surface
5. MariaDB is canonical source of truth
6. Multi-user from day one

Phase sequence: 0 Foundation → 1 Domain → 2 Persistence → 3 EventLog → 4 Auth/Identity → 5 CapabilityKernel → 6 GraphQL Skeleton → 7 Shell → 8 Agent Runtime → 14 Orchestrator Integration.

**Why:** Understanding phase ordering is critical to distinguishing "not yet implemented" (deferred to a future phase) from "MISSING" violations in the current phase's scope.
**How to apply:** When flagging missing requirements, always check whether they are deferred to a later phase in the roadmap before classifying as MISSING vs. out-of-scope.
