Create a modern landing page for **Jorlan** at **jorlan-ai.com**.

Jorlan is an open-source, self-hosted assistant and agent platform for people who want a powerful AI assistant they can
trust and control.

It should appeal to:

* Home users
* Power users
* Families
* Open-source developers
* Homelab operators
* Small teams
* Scala / ZIO developers
* People interested in local-first AI
* People who want a safer alternative to loosely controlled agent systems

Jorlan can be used as:

* A personal assistant
* A home assistant
* A family operations assistant
* A project assistant
* A software development assistant
* A smart-home coordinator
* A self-hosted automation platform

The design should feel:

* Trustworthy
* Useful
* Calm
* Modern
* Technical enough for developers
* Approachable enough for non-engineers
* Open-source friendly
* Privacy-conscious

Avoid:

* Generic AI startup hype
* Cartoon robots
* Corporate stock photos
* Excessive gradients
* Overly enterprise-focused messaging
* Making it seem only for engineers
* Any lies, anything made up, marketing speak.

Visual inspiration:

* Home Assistant
* Grafana
* Docker
* GitHub
* ZIO
* JetBrains
* Obsidian
* Open-source project documentation sites

Color palette:

* Dark theme by default
* Deep charcoal background
* Blue, teal, or violet accents
* Clean cards
* Clear typography
* Subtle diagrams
* Friendly but serious visual language

## Hero Section

Headline:

**Your AI Assistant, Under Your Control**

Subheadline:

**Jorlan is an open-source, self-hosted assistant platform for managing everyday life, home automation, projects,
communication, and software workflows — with permissions, memory, scheduling, and traceability built in.**

Primary CTA:

**View on GitHub**

Secondary CTA:

**Read the Docs**

Tertiary CTA:

**See Use Cases**

Hero visual:

Show a clean dashboard-style interface, not a chatbot-only screen.

The hero visual should suggest:

* An active assistant
* Recent tasks
* Upcoming reminders
* Permission requests
* Connected services
* A trace of what the assistant did

The product should feel like a real control center for personal and household automation.

## Screenshots Section

Create a section titled:

**See What Jorlan Can Do**

Include placeholder screenshot cards for:

1. Assistant Dashboard

Shows active tasks, recent activity, reminders, and connected users.

2. Permission Review

Shows an action requiring approval, such as sending an email, running a shell command, or changing a smart-home setting.

3. Execution Trace

Shows a transparent step-by-step trace of an assistant action.

Example:

User request
→ Memory lookup
→ Skill selected
→ Permission checked
→ Action approved
→ Result delivered

4. Use Case Library

Shows configured assistant behaviors such as:

* Meal Planning
* Email Inbox Management
* Travel Planning
* Smart Home
* Music Collection Manager
* Project Manager

5. Home Assistant View

Shows smart-home state, devices, chores, music playback, and alerts.

6. Developer View

Shows skills, connectors, GraphQL API, traces, and logs.

The screenshots can be realistic placeholders, but they should look like actual application screens.

## What Is Jorlan Section

Title:

**More Than a Chatbot**

Text:

Jorlan is a self-hosted assistant platform that connects memory, skills, schedules, approvals, and external systems into
one controllable runtime.

It can help with everyday activities like planning meals, managing email, tracking chores, monitoring home devices,
organizing travel, and coordinating projects.

It can also support developer workflows through typed skills, GraphQL APIs, structured traces, and integrations with
tools such as Git, Ollama, and orchestrators like Paperclip.

Use a simple diagram:

Users
→ Communication Channels
→ Jorlan Runtime
→ Skills and Connectors
→ External Systems

## Feature Section

Create six feature cards.

### 1. Private and Self-Hosted

Run Jorlan on your own infrastructure and control your own data.

### 2. Permissioned by Design

Sensitive actions require explicit approval. Jorlan is designed around deny-by-default permissions.

### 3. Memory That Works for You

Jorlan can remember preferences, projects, routines, and household context.

### 4. Scheduled and Event-Driven

Create reminders, recurring chores, scheduled tasks, and event-based automations.

### 5. Useful Around the Home

Coordinate meals, music, travel, smart-home devices, chores, calendars, and family workflows.

### 6. Built for Extensibility

Developers can add skills, connectors, workflows, and integrations using structured schemas and Scala.

## Use Cases Section

Title:

**One Assistant, Many Jobs**

Create a grid of use case cards.

Use cases:

* Meal Planning
* Email Inbox Management
* Travel Planning
* Birthday Reminders
* Smart Home
* Music Collection Manager
* Home Maintenance
* Weight and Exercise Tracking
* Project Management
* Meeting Assistant
* Software Development Manager
* Community Manager

Each card should include:

* Icon
* Short title
* One sentence description

Example:

**Home Maintenance**
Track chores, filters, warranties, repairs, and seasonal maintenance.

**Music Collection Manager**
Control your Lyrion server, discover forgotten favorites, and find new releases.

**Software Development Manager**
Coordinate repositories, issues, builds, orchestrators, and engineering tasks.

## Trust and Control Section

Title:

**Know What Your Assistant Is Doing**

Explain that Jorlan emphasizes transparency.

Include visual elements showing:

* Permission requests
* Event logs
* Execution traces
* Human approvals
* Artifacts
* Scheduled tasks

Text:

Jorlan is designed so users can inspect what happened, why it happened, what data was used, and what actions were taken.

This is a key differentiator from agent systems that hide behavior inside prompts or opaque tool calls.

## Open Source Section

Title:

**Open Source and Built in the Open**

Text:

Jorlan is intended to be open-source from the beginning. The project welcomes contributors interested in self-hosted AI,
home automation, developer tooling, Scala, ZIO, privacy, and safer agent systems.

Calls to action:

* View on GitHub
* Read the Architecture
* Explore Use Cases
* Join the Discussion

## Developer Section

Title:

**For Developers Who Want More Control**

This section should appeal to technical contributors without making the whole product feel engineer-only.

Highlight:

* Scala 3
* ZIO
* MariaDB
* GraphQL with Caliban
* Ollama support
* Typed skills
* Structured permissions
* Durable traces
* Scheduler
* Connector framework

Include a small code or schema snippet showing a skill manifest or permission declaration.

Example visual:

```json
{
  "skill": "home.changeFilterReminder",
  "capabilities": [
    "scheduler.create",
    "memory.write"
  ],
  "approval": "once"
}
```

## Comparison Section

Title:

**A Different Kind of Agent Platform**

Create a respectful comparison table.

Compare:

* Chat-only assistants
* OpenClaw-style agent systems
* Jorlan

Rows:

* Self-hosted
* Multi-user
* Permission model
* Traceability
* Scheduling
* Home automation
* Structured skills
* Orchestrator integration
* Local model support
* Extensibility

Tone should be factual, not hostile.

Avoid insulting competitors.

## Orchestrator Integration Section

Title:

**Works With Other Agent Systems**

Text:

Jorlan is designed to be controlled by people and by orchestrators. Systems such as Paperclip can submit structured
work, receive progress updates, inspect traces, and collect results through a typed API.

Mention:

* GraphQL API
* Execution handles
* Approval workflows
* Artifact retrieval
* Event subscriptions

## Footer

Include:

* GitHub
* Documentation
* Architecture
* Use Cases
* Community
* License

Footer tagline:

**Jorlan: open-source AI assistance with memory, permissions, scheduling, and control.**

Overall impression:

The landing page should make home users think:

“This could help me run my life and home.”

It should make developers think:

“This is serious infrastructure I can extend.”

It should make open-source contributors think:

“This is useful, principled, and worth helping build.”
