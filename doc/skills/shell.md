# Shell Skill

Execute shell commands and read files within a configured sandbox, subject to an allowlist of permitted binaries.

**Skill name:** `shell`  
**Tier:** Built-in (always enabled)  
**Source:** `server/src/main/scala/jorlan/service/skills/ShellSkill.scala`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Provides agents with controlled access to the server's shell environment. All commands are sandboxed to a configured root directory and constrained to a configurable allowlist of permitted executables.

## Tools

| Tool | Description |
|------|-------------|
| `shell.run` | Execute an allowlisted shell command |
| `shell.ls` | List directory contents |
| `shell.cat` | Read file contents |
| `shell.grep` | Search for a pattern in files |
| `shell.find` | Find files matching criteria |

### `shell.run`

**Input:** `{ "command": "<binary>", "args": ["<arg1>", "<arg2>"], "workingDir": "<path>" }`

The binary must be in the configured allowlist. The working directory is restricted to the sandbox root.

### `shell.ls` / `shell.cat` / `shell.grep` / `shell.find`

Standard filesystem operations, all restricted to the sandbox root. No allowlist check required for these read-only tools.

---

## Capabilities required

| Capability | Tools |
|-----------|-------|
| `shell.read` | `shell.ls`, `shell.cat`, `shell.grep`, `shell.find` |
| `shell.execute` | `shell.run` |

`shell.execute` is considered high-risk. It requires explicit capability grant and admin approval by default.

---

## Example prompts

- "List the files in the workspace"
- "Show me the contents of config.json"
- "Find all Python files modified today"
- "Search for 'ERROR' in the logs"
- "Run the backup script"

---

## Configuration

Shell settings are stored in `server_settings` under key `skill.shell` (editable via **Skills → Shell → Configure**).

Example config JSON:

{
  "allowedBinaries": ["echo", "ls", "cat", "grep", "find", "pwd"],
  "timeoutSeconds": 30,
  "captureThreshold": 65536,
  "sandboxRoot": "."
}

| Field | Description |
|-------|-------------|
| `sandboxRoot` | Absolute path to the sandbox root directory |
| `allowedCommands` | Binaries permitted by `shell.run` |

---

## Security notes

- `shell.run` calls are subject to both capability gating AND the approval workflow
- Commands outside the allowlist are rejected before execution
- All paths are resolved relative to the sandbox root; `..` traversal is blocked
- Consider granting `shell.execute` only on a `PerInvocation` or `Once` approval basis
