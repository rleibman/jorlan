# Workspace Skill

Read and write files in a persistent per-agent workspace directory.

**Skill name:** `workspace`  
**Tier:** Built-in (always enabled)  
**Source:** `server/src/main/scala/jorlan/service/skills/WorkspaceSkill.scala`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Gives agents a persistent file workspace they can use to store documents, code, data files, and other artifacts across sessions.

## Tools

| Tool | Description |
|------|-------------|
| `workspace.read` | Read the content of a file in the workspace |
| `workspace.write` | Write (overwrite) a file in the workspace |
| `workspace.search` | List files matching a path prefix |
| `workspace.delete` | Delete a file from the workspace |
| `workspace.snapshot` | Create a snapshot (tarball) of the workspace |

### `workspace.read`

**Input:** `{ "path": "reports/monthly.txt" }`

### `workspace.write`

**Input:** `{ "path": "reports/monthly.txt", "content": "<file content>" }`

### `workspace.search`

**Input:** `{ "prefix": "reports/" }`

### `workspace.snapshot`

**Input:** `{}` — Returns a download URL for a `.tar.gz` of the workspace

---

## Capabilities required

| Capability | Tools |
|-----------|-------|
| `workspace.read` | `workspace.read`, `workspace.search`, `workspace.snapshot` |
| `workspace.write` | `workspace.write` |
| `workspace.delete` | `workspace.delete` |

---

## Example prompts

- "Save the analysis results to a file called 'output.txt'"
- "Read the notes I saved last week"
- "List all the files in the workspace"
- "Delete the old draft files"
- "Create a snapshot of the workspace"

---

## Configuration

The workspace root is configured in `application.conf`:

```hocon
jorlan.workspace {
  root = "/var/lib/jorlan/workspace"
}
```

Each agent gets its own subdirectory under the workspace root. Paths within tool calls are relative to that subdirectory and cannot escape it.
