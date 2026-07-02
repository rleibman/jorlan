# Mini-Design: Pipeline Jobs

## Motivation

Scheduled Jorlan jobs today are single-prompt agent invocations. For complex use cases (food calendar,
birthday reminders, weekly health check-ins), this creates two failure modes with local/small models:

1. **Context saturation** — the model loses track of early constraints (dietary rules, location, household
   facts) as the ReAct loop grows long
2. **Multi-objective drift** — when simultaneously satisfying 6+ constraints while calling tools and
   producing structured output, small models quietly drop constraints

A **pipeline** breaks a job into an ordered sequence of focused steps. Each step has a small prompt, a
limited tool allowlist, and a single responsibility. Outputs from earlier steps are passed explicitly to
later steps rather than relying on the model's attention span. This improves accuracy, observability,
and testability.

---

## Core Design Decision: All Scheduled Jobs Are Pipelines

A pipeline of one step is equivalent to a traditional single-prompt job. There is no separate "simple
job" concept — everything is a pipeline. This unification:

- Removes a concept from the model (one fewer thing to explain)
- Means all scheduled jobs get invariant injection, accuracy mode, and per-step observability
- Lets simple cases start as a one-step pipeline and grow to multi-step without restructuring

---

## New Model Concepts

### PipelineStep

```scala
case class PipelineStep(
  name:         String,              // human-readable label ("gather-context", "plan-meals")
  systemPrompt: String,              // the step's specific instructions
  userPrompt:   String,              // may reference {{invariants.X}} and {{steps.NAME.output}}
  tools:        List[String],        // tool namespace allowlist ("calendar", "weather", "memory")
  mode:         StepMode,            // ReactLoop or SingleCall
  outputVar:    String,              // key under which this step's output is stored in context
  retryOnFail:  Int = 0,            // number of automatic retries before halting the pipeline
)

enum StepMode {
  /** Full ReAct loop: model may call tools in multiple rounds before producing output. */
  case ReactLoop
  /** Single LLM call: no tools, used for pure-reasoning steps. Cheaper and faster. */
  case SingleCall
}
```

`SingleCall` is appropriate for steps that do only reasoning — "given this context, decide which cuisine
for each night." `ReactLoop` is needed when tools must be called — "search for a recipe and fetch it."

### Pipeline (replaces SchedulerJob's prompt/config shape)

```scala
case class Pipeline(
  steps:       List[PipelineStep],
  invariants:  Map[String, String] = Map.empty,  // see Invariants section
  personality: Option[String]       = None,       // None = accuracy mode
)
```

### Updated SchedulerJob

The existing `SchedulerJob` adds a `pipeline: Pipeline` field in place of the current
`systemPrompt`/`agentId` fields. Existing one-step usage maps cleanly: wrap the existing prompt in a
`PipelineStep` with `mode = ReactLoop`.

---

## Invariants

### What They Are

Invariants are key-value facts that are **always injected** into every step of every pipeline run for
an agent, with no search and no chance of being missed. They are different from `memory` entries:

| | `memory` | Invariants |
|---|---|---|
| Retrieval | Semantic search — may or may not surface | Always injected — deterministic |
| Format | Prose paragraphs | Key-value strings |
| Purpose | Contextual knowledge, history | Hard constraints and standing facts |
| Examples | "I tried that restaurant last year" | "location: Las Vegas", "Sarah: no cruciferous veg" |

### Where Invariants Are Stored

Invariants are stored at two levels:

1. **Agent-level invariants** — stored on the `Agent` entity in the database. Apply to every pipeline
   this agent runs. Set in the agent configuration UI.
2. **Pipeline-level invariants** — stored in the `Pipeline` definition. Apply only to this pipeline.
   Override agent-level invariants with the same key.

When a step runs, both sets are merged (pipeline-level wins) and injected into the prompt.

### How Invariants Are Injected

Injected as a clearly delimited block, prepended to the user prompt:

```
=== INVARIANTS ===
location: Las Vegas, Nevada, USA (timezone: America/Los_Angeles)
household: Roberto and Sarah
sarah_dietary: Sarah cannot eat cruciferous vegetables (broccoli, cauliflower, cabbage,
               Brussels sprouts, kale, bok choy). Hard constraint, no exceptions.
meat_preference: Prefer to limit red meat. Mostly fish, poultry, vegetarian. Red meat OK occasionally.
grocery_day: Thursday evening. Thursday dinner should be simple (30 min or less).
currency: USD
language: English
=== END INVARIANTS ===
```

Invariants can also be referenced by name in step prompts using `{{invariants.KEY}}`:
```
userPrompt: "It is {{invariants.grocery_day}}. Plan meals accordingly."
```

### Authoring Invariants

Invariants are written as plain English key-value pairs in the agent configuration UI. Keys are
arbitrary strings; values are plain text. The agent UI shows a table with Add/Edit/Delete rows.

---

## Personality and Accuracy Mode

### The Problem with Personality in Scheduled Jobs

Jorlan's conversational agents have a "personality" — a system prompt that shapes tone, verbosity, and
style. This is appropriate for interactive chat where engagement matters.

For scheduled pipeline jobs, personality is counterproductive:
- Adds tokens to an already large context
- Encourages verbose, conversational output when structured JSON is needed
- "Creative" personalities may invent facts or soften hard constraints ("well, maybe a little broccoli...")

### Accuracy Mode

When `personality = None` (the default for pipelines), Jorlan uses a minimal, accuracy-focused system
prompt instead of any configured personality:

```
You are a precise, reliable assistant. Follow instructions exactly.
Produce only what is asked. If a constraint says "no broccoli", never include broccoli.
When outputting structured data, produce valid JSON matching the requested schema.
Do not explain your reasoning unless explicitly asked.
Do not add unsolicited commentary.
```

This prompt prioritizes literal instruction-following over conversational quality — the right trade-off
for scheduled, automated work.

### Named Accuracy Personalities

A set of built-in "accuracy" personalities can be registered for specific domains:

| Name | Optimized for |
|---|---|
| `accurate` | Default accuracy mode (above) |
| `accurate-chef` | Meal planning; food safety constraints taken seriously |
| `accurate-analyst` | Financial and data analysis; no rounding or approximation |
| `accurate-coder` | Code generation; strictly follows specifications |

Custom personalities can still be assigned if conversational output is wanted for a pipeline.

---

## Context Passing Between Steps

### Shared Context Map

`JobManagerImpl` maintains a `Map[String, Any]` (serialized as JSON) across step executions. When a
step completes:
- Its output is stored at key `steps.<outputVar>.output`
- Its status is stored at key `steps.<outputVar>.status` (`"success"` | `"failed"` | `"skipped"`)

### Template Substitution in User Prompts

Before each step runs, `userPrompt` undergoes template substitution:

| Template | Resolves to |
|---|---|
| `{{invariants.KEY}}` | The invariant value for KEY |
| `{{steps.NAME.output}}` | The output of the step whose `outputVar` is NAME |
| `{{steps.NAME.status}}` | The status of that step |
| `{{pipeline.name}}` | The pipeline name |
| `{{pipeline.run_id}}` | The current run's unique ID |
| `{{now}}` | Current ISO-8601 timestamp |
| `{{run.context}}` | Free-form text provided at trigger time (empty string if not given) |

## Run Context (Manual Trigger Override)

When a pipeline is triggered **manually** (from the web UI or via Telegram), the user can optionally
provide free-form text that applies only to that single run. This is distinct from invariants (which
are permanent) and from the pipeline definition (which is shared across all runs).

**Examples:**
- "We have guests on Saturday. Please plan a full dinner for 6 people that evening — something impressive."
- "I'd love to have ceviche at least once this week."
- "Skip Sunday — we'll be at a restaurant."
- "Sarah's mother is visiting; she also can't eat shellfish."

### How It Works

The run context is passed into the pipeline trigger call:

```scala
// GraphQL mutation
triggerPipeline(jobId: JobId!, runContext: String): PipelineRunId
```

`JobManagerImpl` stores the run context in the `PipelineRun` record and makes it available as
`{{run.context}}` in every step's template substitution. If no run context was provided (scheduled
auto-run), `{{run.context}}` resolves to an empty string and the block is omitted from the prompt.

### Injection

Run context is injected as its own clearly delimited block, **after** invariants but **before** the
step's user prompt, so it overrides invariants in the model's attention without overwriting them:

```
=== INVARIANTS ===
location: Las Vegas, Nevada, USA
sarah_dietary: Sarah cannot eat cruciferous vegetables ...
...
=== END INVARIANTS ===

=== THIS RUN ONLY ===
We have guests on Saturday. Please plan a full dinner for 6 people that evening — something impressive.
=== END THIS RUN ONLY ===

[step user prompt follows]
```

The "THIS RUN ONLY" label is deliberate — it signals to the model that this information is
situational, not a standing rule, which prevents it from being over-generalized.

### Injected into every step

Run context is available in `{{run.context}}` for every step, not just the first. This matters
because "guests on Saturday for 6" affects the recipe search (step 3), the shopping list quantities
(step 5), and the Ourgroceries quantities (step 6) — not only the meal planning step.

Step authors can choose whether to reference `{{run.context}}` explicitly or let it arrive via the
injected block. For steps where it's particularly relevant, explicitly calling it out in the
`userPrompt` can improve model attention:

```
userPrompt: "Meal plan: {{steps.plan.output}}
             Note any special instructions for this run: {{run.context}}"
```

### UI

In the **SchedulerPage** pipeline list, each pipeline has a **▶ Run Now** button. Clicking it opens
a small modal:

```
Run "Food Calendar" now
───────────────────────────────────────
Any special instructions for this run?
┌─────────────────────────────────────┐
│ We have guests Saturday — dinner    │
│ for 6, something impressive.        │
└─────────────────────────────────────┘
[ Cancel ]                   [ Run → ]
```

The text field is optional — leaving it blank and clicking Run triggers the pipeline with no run
context (identical to a scheduled auto-run).

### Telegram trigger

If a Telegram command triggers a manual pipeline run, the message text after the command becomes the
run context:

```
/run food-calendar I'd love ceviche this week
```

This requires wiring the Telegram connector to parse `/run <pipeline-name> <context>` commands,
which is a natural extension of the existing command handling.

---

### Example: Food Calendar Pipeline

```
Step 1 name="gather-context"  outputVar="ctx"
  systemPrompt: "Fetch calendar events, weather, meal history, and dietary preferences. 
                 Output a JSON object with keys: events, weather, mealHistory, preferences."
  userPrompt:   "Gather context for meal planning. Current time: {{now}}"
  tools:        ["calendar", "weather", "memory"]
  mode:         ReactLoop

Step 2 name="plan-meals"  outputVar="plan"
  systemPrompt: "You are a meal planner. Given the context, assign one meal concept per dinner.
                 Output a JSON array: [{day, date, cuisineType, complexity, notes}]
                 Complexity: 'simple' (≤30min), 'medium' (≤60min), 'complex' (>60min or overnight prep)."
  userPrompt:   "Context: {{steps.ctx.output}}"
  tools:        []
  mode:         SingleCall

Step 3 name="find-recipes"  outputVar="recipes"
  systemPrompt: "For each meal concept, find a recipe. Output JSON: 
                 [{day, mealName, recipeUrl, ingredients: [{name, quantity, unit}], prepMinutes}]"
  userPrompt:   "Meal plan: {{steps.plan.output}}"
  tools:        ["search", "http_fetch"]
  mode:         ReactLoop

Step 4 name="create-calendar-events"  outputVar="events"
  systemPrompt: "Create a dinner calendar event for each recipe. 
                 Title = meal name. Description = recipe URL + brief summary."
  userPrompt:   "Recipes: {{steps.recipes.output}}"
  tools:        ["calendar"]
  mode:         ReactLoop

Step 5 name="build-shopping-list"  outputVar="shopping"
  systemPrompt: "Aggregate and deduplicate ingredients from all recipes into a categorized shopping list.
                 Output JSON: [{category, items: [{name, quantity, unit}]}]"
  userPrompt:   "Recipes: {{steps.recipes.output}}"
  tools:        []
  mode:         SingleCall

Step 6 name="add-to-ourgroceries"  outputVar="groceries_done"
  systemPrompt: "Add all shopping list items to the Groceries list in Ourgroceries."
  userPrompt:   "Shopping list: {{steps.shopping.output}}"
  tools:        ["ourgroceries"]
  mode:         ReactLoop

Step 7 name="schedule-and-notify"  outputVar="notify_done"
  systemPrompt: "1. For recipes with prepMinutes > 60 or requiring overnight prep, 
                    create a scheduler reminder for the evening before.
                 2. Send a Telegram message summarizing the week's meal plan."
  userPrompt:   "Recipes: {{steps.recipes.output}}"
  tools:        ["scheduler", "telegram"]
  mode:         ReactLoop
```

Agent-level invariants for this agent:
```
location: Las Vegas, Nevada, USA
sarah_dietary: Sarah cannot eat cruciferous vegetables (broccoli, cauliflower, cabbage, Brussels sprouts, kale, bok choy).
meat_preference: Prefer mostly fish, poultry, and vegetarian. Red meat at most once per week.
grocery_day: Thursday evening. Thursday dinner must be 30 minutes or less.
weekend_cooking: Friday, Saturday, Sunday are fine for complex recipes.
```

---

## Failure Handling

### Step-level failure

If a step fails (model error, tool error, or timeout):
1. Retry up to `retryOnFail` times with the same input
2. If retries exhausted, mark the step `"failed"` in the context map
3. Halt the pipeline; subsequent steps do not run
4. Write a `PipelineRun` record with status `"failed_at_step"` and the step name

### Partial recovery

The `PipelineRun` record stores the accumulated context map at the point of failure. A future "resume"
capability (not in MVP) would allow restarting from a failed step using the stored context, rather than
re-running from step 1.

### Notification on failure

On pipeline failure, `notify.user` is called automatically with:
- Pipeline name
- Step that failed
- Error summary
- Run ID (for lookup in the EventLog page)

---

## Implementation Touchpoints

### Model layer (`model/shared/`)
- `scheduler.scala` — add `PipelineStep`, `StepMode`, `Pipeline`, update `SchedulerJob`
- `agent.scala` (if exists) — add `invariants: Map[String, String]` to agent entity

### Database (`db/` or `server/`)
- Migration: add `pipeline_steps` table (or store as JSON in `scheduler_jobs`)
- Migration: add `agent_invariants` table (or `invariants` JSONB column on `agents`)
- Migration: add `pipeline_runs` table for run history with step-level status

### `JobManagerImpl` (`server/src/main/scala/jorlan/service/schedule/`)
- Step execution loop: substitute templates, inject invariants, invoke agent runner
- Context map accumulation between steps
- Retry logic per step
- Failure notification

### `AgentRunnerImpl` (`server/src/main/scala/jorlan/service/`)
- `SingleCall` mode: bypass the ReAct loop, make a single LLM call
- Accuracy mode: substitute accuracy system prompt when personality is None

### `TriggerEngine` (`server/src/main/scala/jorlan/service/schedule/`)
- Scheduled (auto) runs: fire with no run context — `runContext = None`
- No other changes needed

### GraphQL API (`server/src/main/scala/jorlan/graphql/`)
- `createPipeline`, `updatePipeline`, `getPipeline` mutations/queries
- `triggerPipeline(jobId: JobId!, runContext: String): PipelineRunId` — manual trigger with optional context
- `pipelineRuns(pipelineId)` query for history
- `pipelineRun(runId)` with step-level status and the `runContext` that was used

### Web UI (`web/src/main/scala/jorlan/web/pages/`)
- **SchedulerPage** — add Pipeline editor: step list with drag-to-reorder, per-step prompt/tools/mode editor
- **"Run Now" button** — opens modal with optional run context textarea; calls `triggerPipeline`
- **Pipeline run detail view** — show step-by-step status, outputs, timestamps (collapsible); display run context if present
- **Agent settings** — add Invariants table editor (key/value rows)

### Telegram connector
- Parse `/run <pipeline-name> [run context text]` commands
- Text after the pipeline name becomes the `runContext`
- Call `triggerPipeline` with the parsed context

---

## What Does NOT Change

- `TriggerEngine` cron/schedule mechanism — unchanged
- `SchedulerSkill` tools (`scheduler.create_job`, etc.) — unchanged
- Approval system — pipeline steps that require approval work identically to today
- MCP tool dispatch — unchanged
- Memory, workspace, and all skill infrastructure — unchanged

---

## Summary

| Concept | Change |
|---|---|
| All scheduled jobs | Become pipelines (1-step pipeline = current behavior) |
| Invariants | Key-value facts injected into every step; stored at agent or pipeline level |
| Run context | Optional free-form text provided at manual trigger time; injected into every step as `{{run.context}}`; empty on scheduled runs |
| Personality | Defaults to `None` (accuracy mode) for pipelines; can be overridden |
| Steps | Can be `ReactLoop` (tool-using) or `SingleCall` (pure reasoning) |
| Context passing | Explicit template substitution between steps, not conversation history |
| Failure | Per-step retry, halt on exhaustion, automatic notification |
| Manual trigger | Web UI "Run Now" modal + Telegram `/run <name> [context]` command |
