---
name: sprint1-2-3-screens
description: Screens and components added/changed in Sprint 1, 2, and 3; key interactions and gaps identified
metadata:
  type: project
---

## Sprint 1 — Landing Page (Next.js/React, `landing/`)

All content is static; all links open GitHub in new tab.

- **Hero.tsx** — "View on GitHub" (primary CTA), "Read the Docs" (→ GitHub README anchor), "Latest release", "Issues", "Installation guide". Terminal code block is decorative.
- **Footer.tsx** — GitHub, Install, Architecture, Issues, Apache 2.0 links.
- **OpenSource.tsx** — 4 action buttons (GitHub, Installation Guide, Architecture Docs, Issues & Discussions), 3 cards (Star repo, Fork, Request a skill → issues/new).
- **Skills.tsx** — 7 built-in + 13 plugin SkillCards. Each card: optional "Docs" link (→ GitHub blob). "Build your own skill" link at bottom.
- **App.tsx** — Composition: Hero, Screenshots, Features, Skills, UseCases, Trust, Architecture, Developer, Comparison, OpenSource, Footer.
- **Screenshots section** — Placeholder icons, no real screenshots. PS123-005 filed.

## Sprint 2 — CreateSkillWizard + CustomSkillsPage (Scala.js SPA)

### CreateSkillWizard (Dialog, 5 steps)
Steps: Executor Type → Skill Info → Tool Definition → Permissions → Review & Submit

- Step 0: Radio: http_api (default) | prompt_template
- Step 1: Skill Name, Version (default "1.0.0"), Description (multiline), Keywords (comma-sep)
- Step 2: Tool Name (label says auto-prefixed with skillName.), Tool Description; then executor-specific:
  - http_api: HTTP Method, URL, Headers (multiline), Body Template (multiline, optional)
  - prompt_template: System Prompt (multiline), User Prompt Template (multiline)
- Step 3: Required Capabilities (comma-sep), Example Prompts (one per line)
- Step 4: JSON manifest preview, errors/info/status alerts, "Create Draft" → then "Advance" buttons
- Dialog actions: Cancel always; Back on steps 1-3; Next on steps 0-3; nothing on step 4
- Advance disabled when: advancing in progress, or status not in {Draft, Validated, PermissionReviewed, SandboxTested}
- When advance → AwaitingApproval: toast "Skill submitted for approval.", calls onCreated

**Gaps:**
- PS123-001: No step-by-step field validation; Next never blocks on empty required fields.
- PS123-002: No wizard state persistence; navigating away loses all input.
- PS123-003: No "unsaved changes" guard when clicking Cancel with filled fields.

### CustomSkillsPage (#/custom-skills)
- Loads on mount: pendingSkillVersions() + allCustomSkills()
- Pending Approval section (only shows if list non-empty): table with Approve/Reject per row
- All Custom Skills section: table with status chip color-coded
  - Draft/Validated/PermissionReviewed/SandboxTested → default (grey)
  - AwaitingApproval → warning (orange)
  - Active → success (green)
  - Deprecated/Revoked → error (red)
- Reject dialog: requires non-empty reason; Reject button disabled if empty or rejecting
- Toasts: approve → "Skill approved and activated." (success), reject → "Skill rejected." (info)
- No edit or delete actions for existing custom skills.

## Sprint 3 — SkillsPage updates + McpServersPage updates + CSS

### SkillsPage (#/skills) — Sprint 3 updates
- Loads OAuth provider status for each skill on mount
- Skills with unconnected OAuth provider show warning chip "Connect [provider] to enable"; switch disabled
- Expand row shows: Docs button (if skill.doc present), keywords, config panel, tool cards
- Config panel: "Configure" button → loads JS module via script injection → custom config UI
- Save config → re-triggers validate automatically
- Docs dialog: renders Markdown via `Marked.parse`, styled by `.skill-doc-content` CSS classes (Sprint 3 CSS addition)

### McpServersPage (#/mcp) — Sprint 3 updates
- Reload button (triggers reloadMcpServers mutation, then refreshes list)
- Add/Edit dialog: transport dropdown (Stdio / Http / HttpSse), Stdio shows Command+Args, Http/HttpSse shows URL
- Name field disabled in edit mode (no rename support)
- Dynamic env var list: Add Env Var / remove per-row (✕ button)
- Delete confirmation dialog shows server name
- Save button disabled if name is empty or saving
- Keywords field with helper text

### ApprovalsPage (#/approvals)
- Already existed but surfaced via nav. GQL initial load + WebSocket subscription for live updates.
- Risk chip colors: ReadOnly/WorkspaceWrite → success; Destructive/ExternalEffect → warning; Privileged/SecuritySensitive → error
- Client-side filter: only shows Pending status approvals
- On approve/deny: removes from local list immediately

### CSS (jorlan.css Sprint 3 additions)
- `.skill-doc-content` styles: h2, h3, p, ul/ol, li, code, pre, table, th, td
- Affects Docs dialog rendering in SkillsPage only
