# Mini-Design: Orchestrator and Sub-Agents (Path C — Future)

## What This Is

Path C is a future evolution beyond the pipeline model. Instead of a human-authored ordered script,
an **orchestrator agent** receives a high-level goal and *dynamically decides at runtime* how to
decompose it — which sub-agents to spin up, what to give them, and how to aggregate their results.

This is the difference between a recipe (pipeline) and a chef (orchestrator). The pipeline says "do
step 1, then step 2, then step 3." The orchestrator says "figure out what steps are needed, assign
them, and combine the results." The orchestrator can also run sub-agents **in parallel**.

This document records the concept for future design so that today's architecture decisions leave the
door open for it.

---

## Why This Is Intriguing

### Dynamic task decomposition

A pipeline is authored once and re-run. Its structure is fixed. An orchestrator can adapt:
- A food calendar agent on a week with a dinner party adapts the planning differently than a normal week
- A software development manager agent notices a critical security issue and spawns a dedicated
  security audit sub-agent not in the original plan
- A travel planning agent realizes the destination has a language barrier and spawns a language
  briefing sub-agent it normally doesn't include

The orchestrator's reasoning IS the decomposition logic, not a fixed script.

### Parallel execution

Pipeline steps are sequential. An orchestrator can spawn multiple sub-agents simultaneously and join
their results. For food calendar:

```
Orchestrator
├── Sub-agent A: fetch calendar + weather context
├── Sub-agent B: search MusicBrainz for new releases (music-mood-enhancer)
└── Sub-agent C: check email for travel confirmations

All three run in parallel. Orchestrator waits for all, then reasons over combined output.
```

This dramatically reduces wall-clock time for jobs that gather context from multiple independent
sources.

### Specialization

Each sub-agent has a focused identity:
- A "meal planner" sub-agent that knows cuisine, dietary science, and seasonal cooking — and only that
- A "calendar assistant" sub-agent that knows Google Calendar operations inside out
- A "grocery logistics" sub-agent that knows Ourgroceries and shopping optimization

The orchestrator assigns work to the right specialist. Specialists never need to understand the
broader context — they just do their job.

### Self-reflection and retry

If a sub-agent's output looks wrong, the orchestrator can:
- Ask it to retry with corrected input
- Assign the work to a different sub-agent with different instructions
- Proceed with a fallback plan
- Ask the user for clarification before continuing

None of this is possible in a fixed pipeline.

---

## How It Differs from Pipelines

| Dimension | Pipeline (Path B) | Orchestrator (Path C) |
|---|---|---|
| Structure | Fixed at authoring time | Dynamic at runtime |
| Decomposition | Human decides steps | Orchestrator decides steps |
| Execution | Sequential | Sequential or parallel |
| Failure recovery | Halt at failed step | Orchestrator can reroute |
| Testability | Steps are independently testable | Sub-agents are independently testable |
| Model requirements | Small models viable per step | Orchestrator itself needs a capable model |
| Complexity to build | Moderate | High |
| Complexity to author | Moderate (write steps manually) | Low (write a goal statement) |

---

## Architecture Sketch

### Components

```
┌───────────────────────────────────────────────────────────┐
│ OrchestratorAgent                                         │
│   system prompt: "Decompose goals into sub-tasks.         │
│                   Assign to sub-agents. Aggregate."       │
│                                                           │
│   Tools:                                                  │
│     orchestrator.spawn_agent(name, systemPrompt, input,   │
│                              tools, deadline?)            │
│     orchestrator.await_agent(handle) → output             │
│     orchestrator.cancel_agent(handle)                     │
│     orchestrator.spawn_parallel([AgentSpec]) → [handle]   │
│     orchestrator.await_all([handle]) → [output]           │
└───────────────────────────────────────────────────────────┘
         │ spawns
         ▼
┌─────────────────────────────┐  ┌─────────────────────────────┐
│ Sub-Agent A                 │  │ Sub-Agent B                 │
│ (focused, limited prompt)   │  │ (focused, limited prompt)   │
│ tools: [calendar, weather]  │  │ tools: [search, http_fetch] │
└─────────────────────────────┘  └─────────────────────────────┘
```

### New tools needed

```
orchestrator.spawn_agent
  Input:  { name: String, systemPrompt: String, userPrompt: String,
            tools: List[String], timeoutSeconds?: Int }
  Output: { handle: String }

orchestrator.await_agent
  Input:  { handle: String }
  Output: { status: "success"|"failed"|"timeout", output: String }

orchestrator.spawn_parallel
  Input:  { agents: List[AgentSpec] }
  Output: { handles: List[String] }

orchestrator.await_all
  Input:  { handles: List[String] }
  Output: { results: List[{ handle, status, output }] }
```

### Sub-agent lifecycle

Sub-agents are ephemeral: spawned for one task, run to completion, results returned to orchestrator.
They do not persist in the session list or event log as first-class sessions — they appear as
"children" of the orchestrator run.

---

## Integration with Phase 17 (Orchestrator Integration)

The Phase 17 roadmap item ("First-class orchestrator identity model", `submitWork` mutation,
`ExecutionStateMachine`) is the natural foundation for this. Key mappings:

| Phase 17 concept | Path C usage |
|---|---|
| `submitWork` GraphQL mutation | `orchestrator.spawn_agent` calls this internally |
| `ExecutionStateMachine` | Tracks each sub-agent's state (pending → running → complete → failed) |
| `execution(id)` query | `orchestrator.await_agent` polls or subscribes to this |
| `executionEvents` subscription | Real-time sub-agent status updates in the UI |
| `artifacts(executionId)` | Sub-agent outputs stored as artifacts |
| Approval delegation policies | Orchestrator inherits parent's approvals OR sub-agents can request separately |

Path C can be built **on top of Phase 17** without changes to the core execution model. Phase 17
builds the execution infrastructure; Path C adds an orchestrator skill that uses it.

---

## Orchestrator Model Requirements

The orchestrator agent itself needs to be a capable model — it must reason about task decomposition,
evaluate sub-agent outputs, detect errors, and decide on retry/rerouting. A 7B model is unlikely to
do this reliably.

This is the key trade-off: sub-agents can use small models (focused tasks, small prompts), but the
orchestrator should use the best available model (Claude Sonnet/Opus, GPT-4o, etc.) to ensure the
decomposition and aggregation are correct.

For local-only deployments, the orchestrator is the one place where a larger model (34B+) or cloud
routing is worth the cost.

---

## Food Calendar as Orchestrator (Example)

**Orchestrator goal prompt:**
```
Create a meal plan for the week of {{now}}. 
You have access to our calendar, the weather, our meal history, and dietary preferences (in invariants).
Spawn sub-agents as needed to gather context, generate the plan, find recipes, update the calendar,
build a shopping list, and notify us. Parallelize where possible.
```

**What the orchestrator might do at runtime:**
```
1. spawn_parallel([
     {name: "calendar-fetch", tools: ["calendar"], prompt: "Get this week's events"},
     {name: "weather-fetch", tools: ["weather"], prompt: "Get 7-day forecast for Las Vegas"},
     {name: "history-fetch", tools: ["memory"], prompt: "Get last 4 weeks of meal history"}
   ])
2. await_all(handles) → merged context

3. spawn_agent({name: "meal-planner", tools: [], mode: SingleCall,
                prompt: "Given [context], plan 7 dinners. Output JSON."})
4. await_agent(meal_planner_handle) → meal plan

5. spawn_parallel(one recipe-finder sub-agent per meal)
6. await_all(recipe handles) → 7 recipe objects

7. spawn_parallel([
     {name: "calendar-writer", tools: ["calendar"], prompt: "Create 7 calendar events"},
     {name: "shopping-builder", tools: [], prompt: "Aggregate shopping list from recipes"},
   ])
8. await_all([calendar_writer, shopping_builder])

9. spawn_agent({name: "ourgroceries-writer", tools: ["ourgroceries"],
                prompt: "Add shopping list to Ourgroceries"})
10. spawn_agent({name: "notifier", tools: ["scheduler", "telegram"],
                 prompt: "Schedule prep reminders and send summary"})
```

Total wall-clock time dominated by the parallel recipe fetches — probably 10–30 seconds instead of
the sequential 2–5 minutes.

---

## When to Build This

Path C should not be built before:
1. **Pipeline jobs (Path B) are stable and in use** — the orchestrator builds on the same primitives
2. **Phase 17 orchestrator integration is complete** — provides `submitWork` and `ExecutionStateMachine`
3. **At least one complex use case (food calendar, community manager) has been running on pipelines**
   and the team has observed where dynamic decomposition would have helped

Path C is the right answer when:
- Jobs have variable structure that can't be predetermined (not every week has a dinner party)
- Parallel sub-agent execution meaningfully reduces latency
- The orchestrator model is capable enough (cloud API or large local model available)

---

## Summary

Path C turns Jorlan from a "run my script" system into a "pursue my goal" system. The pipeline
(Path B) is a recipe; the orchestrator is a chef. Both are valuable at different levels of
complexity and model capability. Build Path B now; build Path C once Phase 17 is complete and the
use cases demand it.
