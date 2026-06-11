---
name: phase15-web-frontend-perf
description: Phase 15 web frontend performance findings — adapter reconstruction, client-side filter, missing cache headers, adapter churn, polling gap
metadata:
  type: project
---

Key hot-path findings from Phase 15 web module review:

- **ScalaJSClientAdapter reconstructed on every render** in ChatPage (line 63) and SchedulerPage (line 97) — should be hoisted to component state or a val.
- **URI parse called on every render/call site** — 8 call sites across pages reconstruct the URI each time; should be a shared `val`.
- **MemoryPage client-side filter** (line 86) runs on every keystroke across the full in-memory list; server already supports `textSearch` arg — use it with debounce.
- **ApprovalsPage double-filter**: server is queried without a status filter, then `.filter(_.status == Pending)` is applied client-side (line 113).
- **StaticFileRoutes no Cache-Control headers** — JS bundle served without `Cache-Control: max-age` or `immutable`; no content-hash filenames in webpack config either.
- **AppShell fetches approvals on every mount** (line 57) without a refresh mechanism; stale badge count.
- **SchedulerPage jobAction re-fetches all jobs after each mutation** (line 131–138) — two serial GraphQL calls per button click.
- **EventLogPage unbounded event list** capped at 200 but uses prepend + take on every message (line 67) — O(n) per event.
- **ChatPage adapter rebuilt on every render** (line 63 inside render body).
- **Global scalaJSStage := FastOptStage** in build.sbt (line 539) — no production fullOpt stage configured; bundle will be significantly larger in prod without explicit fullOpt setting.
- **listApprovals and listMemory have no pagination args used** — all records fetched every time.
- **WebSocket KA interval set to `timeout` (8 minutes)** — interval fires every 8 minutes which is too coarse to detect stale connections promptly.
