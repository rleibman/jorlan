# Memory Skill

Persistent, scoped memory for agents — store facts, recall them later, share across sessions.

**Skill name:** `memory`  
**Tier:** Built-in (always enabled)  
**Source:** `server/src/main/scala/jorlan/service/skills/MemorySkill.scala`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Agents can store named facts or notes in their own memory, search them later, promote memories to shared scope so other agents can see them, and delete memories they no longer need.

## Tools

| Tool | Description |
|------|-------------|
| `memory.remember` | Store a named fact or piece of information |
| `memory.search` | Search stored memories for facts matching text |
| `memory.search_semantic` | Search memories by meaning rather than exact keywords |
| `memory.forget` | Delete a specific memory record by ID |
| `memory.mark_shared` | Promote a memory to shared scope |
| `memory.mark_private` | Revert a shared memory back to private scope |

### `memory.remember`

**Input:** `{ "key": "<name>", "value": "<content>", "scope": "Session|User|Agent|Shared" }`

### `memory.search`

**Input:** `{ "query": "<text>", "scope": "Session|User|Agent|Shared" }`

**Output:** list of `{ id, key, value, scope, createdAt }`

### `memory.forget`

**Input:** `{ "id": "<memory-id>" }`

---

## Memory scopes

| Scope | Visibility |
|-------|-----------|
| `Session` | Current agent session only |
| `User` | All sessions belonging to the same user |
| `Agent` | All sessions for this agent (any user) |
| `Shared` | All agents and all users |

---

## Capabilities required

| Capability | Tools |
|-----------|-------|
| `memory.read` | `memory.search` |
| `memory.write` | `memory.remember`, `memory.forget`, `memory.mark_shared`, `memory.mark_private` |

---

## Example prompts

- "Remember that my preferred name is Roberto"
- "What do you remember about my daily routine?"
- "Forget the note you stored about the old project"
- "Share the team meeting schedule with all agents"
- "What shared memories are available?"

---

## Configuration

No configuration required. Memory is stored in the Jorlan database.
