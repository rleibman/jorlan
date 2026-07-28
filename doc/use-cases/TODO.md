# Use-Case Implementation TODO

Gaps discovered while translating every use-case doc into a manifest (`manifests/*.json`, 2026-07-08).
Each manifest provisions what is feasible **today**; the items below are what's needed to unlock the rest.
Manifests whose core is blocked ship with `"trigger": null` (job exists, runs manually only) or `"job": null`.

## Legend

- **MCP** — configure an existing MCP server (mostly credentials + enabling the placeholder the manifest registers, disabled)
- **Script** — a small script/binary must exist on the server host
- **Declarative skill** — author via the skill-authoring system (HTTP-based)
- **Native skill** — new Jorlan skill module (real development work)
- **Platform** — server feature work

## Per-use-case gaps

### birthday_reminder (trigger disabled until the grampsweb MCP is live)
- [x] **MCP**: `grampsweb` registered in the manifest (disabled) — `npx -y mcp-grampsweb` over stdio, env `GRAMPS_API_URL` (set to https://ancestry.leibmanland.com), `GRAMPS_USERNAME`, `GRAMPS_PASSWORD`, `GRAMPS_TREE_ID`. Fill the `CHANGE_ME` credentials and enable it. NOTE: the server authenticates eagerly at launch, so it must have valid creds before enabling or McpManager's launch will fail. Exposes `gramps_search`/`gramps_find`/`gramps_get`/`gramps_get_ancestors`/`gramps_get_descendants`/`gramps_recent_changes` + create tools (all gated on the single `mcp.call` capability, namespaced `mcp.grampsweb.*`).
- [ ] **Verify the birthday query**: the MCP is general genealogy CRUD/search, not a purpose-built "upcoming birthdays" query, so the `get-birthdays` step drives `gramps_search` to find people with a birthday today/tomorrow — needs testing with a capable model, and likely a Gramps QL query refinement. Enable the daily trigger (`0 0 8 ? * *`) once it reliably returns birthdays.
- [x] **GQL guidance seeded**: `mcp-grampsweb` reports a failed GQL query as a bare `[422] UNPROCESSABLE ENTITY`, throwing away the Gramps API's actual explanation (`{"error":{"message":"Expected end of text, found '=' (at char 16)"}}`). Blind, the model just guesses new syntax until the tool budget runs out. The manifest now seeds the verified GQL rules as a user-scoped memory and repeats them in the `get-birthdays` step prompt: living = `death_ref_index < 0` (`= -1` is a parse error — GQL cannot take a negative literal after `=`), `gender = 1`/`0`, `~` for substring, `and`/`or` to combine, and "a 422 means bad GQL, fall back to `gramps_find`". Upstream fix would be for `mcp-grampsweb` to pass the 422 body through.
- [ ] **Native skill** (nicer long-term, optional now the MCP exists): `gramps` — `list_upcoming_birthdays`, `get_person`, `data_quality_report`.

### travel_planning (no scheduled job; interactive agent only)
- [ ] **MCP**: google-maps placeholder registered (disabled) — set `GOOGLE_MAPS_API_KEY`, enable.
- [ ] **Declarative skills**: `currency_exchange` (open.er-api.com, free), `visa_requirements` (Sherpa/Timatic), `flight_search` (Amadeus sandbox).
- [ ] Edit the `travel.preferences` memory seed with real preferences.

### language_coach (daily lesson job live)
- [ ] **Declarative skills**: `translation` (DeepL free tier / LibreTranslate), `dictionary` (dictionaryapi.dev, free, no key).
- [ ] Edit the `language.profile` memory seed.
- [ ] Out of scope for now: speech-to-text / text-to-speech (audio I/O pipeline).

### music_coach (daily practice-plan job live)
- [ ] Edit the `music.profile` memory seed.
- [ ] Out of scope: audio recording analysis, real-time metronome, ear-training playback.

### skill_development_program (weekly plan job live)
- [ ] Edit the `srs.program` memory seed.
- [ ] **Platform** (nice-to-have): a real SRS engine; today approximated with `memory` + agent reasoning.

### meeting_assistant (weekday morning digest live)
- [ ] **Declarative skills**: `zoom_transcripts` (Zoom API v2), Google Meet transcripts via Drive.
- [ ] **Platform** (from doc): per-meeting one-shot briefing jobs (a calendar-monitor step creating `scheduler.create_job(runAt = meeting - 1h)`); the daily digest covers the core meanwhile.
- [ ] Out of scope: live audio recording/transcription, vendor meeting bots.

### music_mood_enhancer (weekly discovery job live; playback interactive via lyrion)
- [ ] **Declarative skills**: `musicbrainz` (free, no key), `lastfm` (free key), `discogs` (free key).
- [ ] Seed real `music-tag:` memories for the library (two examples provided) and edit `music.favorite-artists`.

### smart_home (weather-watch job live; device control blocked)
- [ ] **MCP (HIGH PRIORITY — the key enabler)**: Home Assistant MCP placeholder registered (disabled) — set the HA URL + long-lived token, enable. HA 2025.1+ has the MCP server built in.
- [ ] **Platform**: verify approval gates fire for high-risk HA tools (locks, alarm, garage) once enabled.
- [ ] Out of scope: camera vision analysis, voice assistant.

### home_maintenance_manager (weekly review job live, memory-approximated)
- [ ] **Native skill**: `chore` — completion-based rescheduling FSM (`create/complete/list/skip/defer/history`) + `chores` DB table. Today approximated with `chore:` memory records (two example seeds to edit).
- [ ] OCR for warranties/receipts: `shell.run("tesseract ...")` if installed, or cloud OCR declarative skill.

### project_manager (weekly review job live)
- [ ] **MCP**: github placeholder registered (disabled) — set PAT, enable (needed for software projects).
- [ ] **Native skill** (Phase 16 roadmap): `notes` — until then `workspace.*` substitutes.
- [ ] Edit the `project.registry` memory seed.
- [ ] Out of scope: Jira/Linear/Asana (declarative skills possible), Gantt rendering.

### community_manager (monthly digest job live)
- [ ] **MCP**: slack placeholder registered (disabled) — set bot token + team id, enable (if community uses Slack).
- [ ] **Declarative skills**: Twilio SMS, Google Forms, Mailchimp/Buttondown — as needed.
- [ ] Edit the `community.profile` memory seed.

### software_development_manager (weekly security digest live; repo work blocked)
- [ ] **MCP (HIGH PRIORITY)**: github placeholder registered (disabled) — set PAT, enable.
- [ ] **Native skill** (Phase 16 roadmap): first-party `github` skill with approval-system integration.
- [ ] **Declarative skills**: `github_actions_ci` (Actions runs), `github_releases`.
- [ ] **Platform**: inbound webhook ingress for real-time CI failure alerts (currently polling).
- [ ] Edit the `swdev.stack` memory seed.

### expense_budget_tracking (weekly email scan live)
- [ ] **MCP**: markitdown placeholder registered (disabled) — for PDF bank statements/receipts; or `shell.run("pdftotext ...")` if poppler-utils installed.
- [ ] **Declarative skill**: `currency_exchange` (shared with travel_planning).
- [ ] OCR for scanned receipts: tesseract via shell or cloud OCR.
- [ ] Edit the `budget.categories` memory seed.
- [ ] Out of scope: direct bank APIs (Plaid/Open Banking).

### weight_exercise_tracker (weekly review job live, memory-approximated)
- [ ] **Native skill**: `health` — structured `log_weight/log_exercise/get_weight_trend/get_exercise_history/get_personal_records` + DB table. Today approximated with `health:` memory keys logged via chat.
- [ ] **Declarative skills** (device sync): Withings / Google Fit REST via `http_fetch` + OAuth.
- [ ] Edit the `health.profile` memory seed.
- [ ] Out of scope: automatic watch sync, chart rendering, sleep tracking.

### food_calendar (already implemented; reference)
- [ ] **MCP**: ourgroceries custom wrapper (`/opt/jorlan-mcp/ourgroceries-mcp/index.js`) still needs to be written; placeholder registered (disabled).

### inbox_briefing (already implemented; reference)
- [ ] The full inbox-management use case also wants hourly triage, drafting replies, and archiving with approval gates — the daily briefing is the provisioned core; extend with more steps/jobs when a capable model is the daily driver.

## Cross-cutting platform TODOs

- [ ] **Approval-gate verification for MCP tools** — smart_home and swdev rely on approval gates around high-risk MCP-provided tools; verify the capability system covers dynamically-registered MCP tools.
- [ ] **Webhook ingress** — several use cases (CI alerts, community forums, real-time mail) want push instead of polling.
- [ ] **Audio I/O pipeline** — blocks language/music coach pronunciation & ear-training features and meeting transcription; large, deliberately deferred.
- [ ] **`notes` native skill** — Phase 16 roadmap; wanted by project_manager.
- [ ] **Chore/health native skills** — same shape (structured table + FSM tools); could share a design.

## Importing

Import selectively — each import provisions a role, agent, and (usually) a cron job:

```
sbt "useCaseImporter/runMain jorlan.shell.UseCaseImporterApp doc/use-cases/manifests/<name>.json"
```

Recommended order: start with the coach/review jobs (language_coach, music_coach, project_manager) once a
cloud model is the daily driver — they're one-notification-a-day/week jobs and cheap to run. Crons are
evaluated in the server's local timezone (day-of-week: 0=Monday..6=Sunday): e.g. `0 0 8 ? * *` = 8 AM
daily. Edit every `EDIT ME` memory seed and `CHANGE_ME` credential after import.
