---
name: project-api-surface
description: Jorlan has a Scala.js + React SPA (web module) and a separate Next.js landing page. Primary API is Caliban GraphQL.
metadata:
  type: project
---

Jorlan now has two frontend surfaces:

1. **Landing page** — Next.js/React marketing site in `landing/`. Static content, all links point to GitHub.
2. **Web SPA** — Scala.js + React 19 + MUI v9. Routes: hash-based (#/skills, #/custom-skills, #/mcp, #/approvals, etc.). Authentication via AuthClient. App at http://localhost:9000 when running.

Key web pages and routes:
- `#/` — Chat (always mounted)
- `#/dashboard` — Dashboard
- `#/sessions` — Sessions
- `#/approvals` — ApprovalsPage (GQL + WS subscription)
- `#/memory` — Memory
- `#/scheduler` — Scheduler
- `#/events` — Event Log (always mounted)
- `#/skills` — SkillsPage (skill registry, enable/disable, config, docs)
- `#/custom-skills` — CustomSkillsPage (declarative skill CRUD + approvals workflow)
- `#/mcp` — McpServersPage (MCP server CRUD + reload)
- `#/users` — UsersPage
- `#/roles` — RolesPage
- `#/settings` — SettingsPage
- `#/oauth` — OAuthManagementPage

Key files:
- `web/src/main/scala/jorlan/web/AppRouter.scala` — hash routing
- `web/src/main/scala/jorlan/web/AppShell.scala` — nav drawer + app bar
- `web/src/main/scala/jorlan/web/pages/` — all page components
- `web/src/main/scala/jorlan/web/components/CreateSkillWizard.scala` — 5-step wizard
- `server/src/main/scala/jorlan/graphql/JorlanAPI.scala` — GraphQL API
- `web/src/main/scala/jorlan/web/AsyncCallbackRepositories.scala` — GQL client wrappers

**Why:** Phase 15 added the Scala.js web frontend. Test plans can now target real browser-rendered screens.
**How to apply:** Write UI test cases as browser interaction sequences against http://localhost:9000.
