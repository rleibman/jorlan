# Jorlan Use Case Gap Analysis

This document summarizes which use cases are satisfiable with current Jorlan capabilities, what MCPs need to
be added, what new native skills need to be built, and what remains out of scope for now.

Each use case file contains a full `## Implementation in Jorlan` section with detailed instructions.

---

## Status Legend

| Symbol | Meaning                                                                  |
|--------|--------------------------------------------------------------------------|
| ✅      | Satisfiable with existing Jorlan skills (agent prompt + configuration)   |
| 🔧     | Needs MCPs to be configured and/or declarative HTTP skills to be defined |
| 🔨     | Needs new native Jorlan skills to be built                               |
| ⚠️     | Partially feasible; some requirements are not yet possible               |

---

## Use Case Status Overview

| Use Case                                                           | Status | Key gap                                                               |
|--------------------------------------------------------------------|--------|-----------------------------------------------------------------------|
| [food_calendar.md](food_calendar.md)                               | 🔧     | Ourgroceries MCP (custom wrapper needed)                              |
| [inbox-management.md](inbox-management.md)                         | ✅      | No gaps — email skill covers all requirements                         |
| [music-mood-enhancer.md](music-mood-enhancer.md)                   | 🔧     | MusicBrainz / Last.fm declarative skills                              |
| [meeting-assistant.md](meeting-assistant.md)                       | ✅⚠️    | Text-only; audio recording/transcription not feasible                 |
| [project-manager.md](project-manager.md)                           | 🔧     | GitHub MCP for software projects                                      |
| [community-manager.md](community-manager.md)                       | 🔧     | Slack MCP (if community uses Slack)                                   |
| [smart-home.md](smart-home.md)                                     | 🔧     | Home Assistant MCP (key enabler — covers all smart home protocols)    |
| [travel-planning.md](travel-planning.md)                           | 🔧     | Google Maps MCP + currency exchange declarative skill                 |
| [software-development-manager.md](software-development-manager.md) | 🔧🔨   | GitHub MCP now; native `github` skill planned Phase 16                |
| [birthday-reminder.md](birthday-reminder.md)                       | 🔧🔨   | Gramps XML export via shell (workaround); native `gramps` skill ideal |
| [expense-budget-tracking.md](expense-budget-tracking.md)           | 🔧⚠️   | PDF extraction MCP; OCR for receipts; no bank API                     |
| [home-maintanence-manager.md](home-maintanence-manager.md)         | 🔨⚠️   | Native `chore` skill needed for FSM scheduling                        |
| [language-coach.md](language-coach.md)                             | 🔧⚠️   | Translation + dictionary declarative skills; no audio                 |
| [skill-development-program.md](skill-development-program.md)       | ✅⚠️    | SRS approximated via scheduler+memory; no audio/video                 |
| [music-coach.md](music-coach.md)                                   | ✅⚠️    | Text coaching fully feasible; audio analysis not possible             |
| [weight-exercise-tracker.md](weight-exercise-tracker.md)           | 🔨⚠️   | Native `health` skill recommended; manual logging as workaround       |

---

## Current Jorlan Skill Inventory (reference)

### Built-in skills

- `memory` — remember / search / forget / semantic search
- `scheduler` — cron and one-shot job management
- `email` — full email operations (Gmail or IMAP/SMTP)
- `notify` — user and channel notifications
- `shell` — run / ls / cat / grep / find / head / tail / wc
- `user_mgmt` — user, role, and capability management
- `workspace` — file-like storage with semantic search
- `skill_authoring` — propose declarative skill manifests

### External skill modules

- `weather` — forecasts by location
- `search` — `search.web`, `search.news`
- `calculator` — arithmetic evaluation
- `units` — unit conversion
- `market` — stock quotes, market news, watchlist
- `lyrion` — full Lyrion/Squeezebox music server control
- `time` — time zone conversion, duration math
- `http_fetch` — `http_fetch.get`, `http_fetch.post`
- `rss` — fetch, save, and list RSS feeds

### Google Services

- `GoogleCalendarSkill` — full calendar CRUD
- `GoogleContactsSkill` — contact lookup
- `GoogleDriveSkill` — file listing, reading, downloading

### Connectors

- `TelegramConnectorSkill` — send/receive via Telegram
- `DiscordConnectorSkill` — send/receive via Discord

### MCP infrastructure

- Full stdio, HTTP, and HTTP+SSE MCP client support
- Any MCP server can be added via Jorlan Settings → MCP Servers

---

## What Needs to Be Added

### Must-add MCPs (no code changes — configure via Jorlan MCP Manager)

| MCP                                        | Primary use case(s)                   | Priority  | Notes                                                                                 |
|--------------------------------------------|---------------------------------------|-----------|---------------------------------------------------------------------------------------|
| Ourgroceries MCP (custom wrapper)          | food_calendar                         | 🔴 High   | No official API; write a small Node.js stdio MCP using the `ourgroceries` npm package |
| Home Assistant MCP                         | smart_home                            | 🔴 High   | Built into HA 2025.1+; enables ALL smart home protocols via one integration           |
| `@modelcontextprotocol/server-github`      | software-dev-manager, project-manager | 🟡 Medium | Official Anthropic MCP; free; requires GitHub PAT                                     |
| `@modelcontextprotocol/server-slack`       | community-manager                     | 🟡 Medium | Official Anthropic MCP; requires Slack OAuth token                                    |
| `@modelcontextprotocol/server-google-maps` | travel-planning                       | 🟢 Low    | Official Anthropic MCP; requires Google Maps API key                                  |
| MarkItDown MCP or `pdftotext`              | expense-budget-tracking               | 🟢 Low    | PDF-to-text extraction for receipts/statements                                        |

### Must-define declarative HTTP skills (JSON manifests, no Scala code)

| Skill               | API                                                                                | Use cases                         | Priority  |
|---------------------|------------------------------------------------------------------------------------|-----------------------------------|-----------|
| `musicbrainz`       | [MusicBrainz REST API](https://musicbrainz.org/doc/MusicBrainz_API) — free, no key | music-mood-enhancer               | 🟡 Medium |
| `lastfm`            | [Last.fm API](https://www.last.fm/api) — free key                                  | music-mood-enhancer               | 🟡 Medium |
| `translation`       | [DeepL API](https://www.deepl.com/en/docs-api/) (free tier) or LibreTranslate      | language-coach                    | 🟡 Medium |
| `dictionary`        | [Free Dictionary API](https://dictionaryapi.dev/) — completely free                | language-coach                    | 🟡 Medium |
| `currency_exchange` | [Open Exchange Rates](https://openexchangerates.org/) free tier                    | travel-planning, expense-tracking | 🟢 Low    |
| `visa_requirements` | Sherpa API or iata-timatic                                                         | travel-planning                   | 🟢 Low    |
| `discogs`           | [Discogs API](https://www.discogs.com/developers/) — free key                      | music-mood-enhancer               | 🟢 Low    |

### Must-build new native skills (require Scala implementation)

| Skill             | Primary use case        | Priority  | Description                                                                                                                                                      |
|-------------------|-------------------------|-----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `chore`           | home-maintenance        | 🔴 High   | First-class chore tracker with completion-based next-due scheduling and status FSM (Upcoming/Due Soon/Due/Overdue/Completed/Skipped/Deferred); requires DB table |
| `health`          | weight-exercise-tracker | 🟡 Medium | Structured weight, exercise session, and body measurement logging with trend queries; requires DB table                                                          |
| `gramps`          | birthday-reminder       | 🟡 Medium | Reads Gramps XML export; provides birthday lookup and data quality analysis tools                                                                                |
| `slack` connector | community-manager       | 🟡 Medium | Discord-style ConnectorSkill for Slack (planned Phase 16)                                                                                                        |
| `github`          | software-dev-manager    | 🟡 Medium | Native GitHub skill with issue/PR/code search (planned Phase 16)                                                                                                 |
| `notes`           | project-manager         | 🟢 Low    | Lightweight key-value scratchpad (planned Phase 16); use `workspace` as workaround                                                                               |

---

## What Is Not Feasible (Requires Major Infrastructure Work)

These requirements appear in one or more use cases but cannot be satisfied without significant new
infrastructure beyond what is planned for current phases:

| Requirement                       | Use cases                                      | Why not feasible                                                                           |
|-----------------------------------|------------------------------------------------|--------------------------------------------------------------------------------------------|
| Speech-to-text (microphone input) | language-coach, music-coach, meeting-assistant | Requires audio input pipeline to the agent runtime                                         |
| Text-to-speech (audio output)     | language-coach                                 | Requires audio output from the agent runtime                                               |
| Audio analysis (recordings)       | music-coach, skill-development                 | Requires a specialized audio ML model and I/O pipeline                                     |
| Live meeting transcription        | meeting-assistant                              | Requires real-time audio from meeting platforms                                            |
| Smart watch / fitness device sync | weight-exercise-tracker                        | Device-specific OAuth + Bluetooth/ANT+ integration                                         |
| Camera computer vision            | smart-home                                     | Requires a vision model wired to camera feeds                                              |
| Direct bank API access            | expense-budget-tracking                        | Requires Open Banking / Plaid; not planned                                                 |
| SMS connector                     | community-manager                              | Requires Twilio or similar; use `http_fetch` as limited workaround                         |
| Zoom/Teams/Meet bots              | meeting-assistant                              | Requires vendor bot registration and audio pipeline                                        |
| OCR for physical receipts         | expense-budget-tracking, home-maintenance      | Use `shell.run("tesseract ...")` if Tesseract installed; or cloud OCR as declarative skill |

---

## The Non-Plus-Ultra: Food Calendar

The food calendar (`food_calendar.md`) is the flagship use case demonstrating Jorlan's value as a
personal AI agent runtime. It exercises:

- Scheduled autonomous execution (Wednesday 09:00)
- Multi-source context gathering (calendar, weather, memory)
- External data fetching (recipe search and scraping)
- Calendar writing (adding meals)
- Shopping list generation (Ourgroceries via custom MCP)
- Dynamic reminder scheduling (Telegram alerts for long-prep meals)
- Memory-based personalization (dietary rules, meal history, preferred sites)

**Current blockers for full implementation:**

1. Ourgroceries MCP wrapper (needs to be written — small Node.js project)
2. Memory seeding (dietary rules and preferences need to be entered once)
3. Agent configuration (system prompt, capabilities, Wednesday cron job)

Everything else required for the food calendar is already present in Jorlan.
