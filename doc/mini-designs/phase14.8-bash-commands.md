# Phase 14.8 — Safe Bash Commands in ShellSkill

## Summary

Add seven pre-configured, read-only bash-like tools to the existing `ShellSkill`. These tools require no admin
allowlist configuration and are sandboxed to a configurable base directory (`sandboxRoot`). They are useful for
agents performing file inspection, log tailing, or workspace exploration.

## New Tools

All seven tools require the `shell.read` capability (distinct from `shell.execute`).

| Tool         | Binary  | Description                                      |
|--------------|---------|--------------------------------------------------|
| `shell.ls`   | `ls`    | List directory contents                          |
| `shell.cat`  | `cat`   | Read file contents                               |
| `shell.grep` | `grep`  | Search for a pattern in a file or directory tree |
| `shell.find` | `find`  | Find files/directories matching criteria         |
| `shell.head` | `head`  | Return first N lines of a file                   |
| `shell.tail` | `tail`  | Return last N lines of a file                    |
| `shell.wc`   | `wc`    | Word/line/char count of a file                   |

## Sandboxing

Each tool resolves its `path` argument relative to `settings.sandboxRoot` (a new field in `ShellSettings`,
defaulting to `"."`). After resolution and normalization, the path is checked with `resolved.startsWith(sandboxRoot)`.
Any path that escapes the sandbox (e.g. `../../etc/passwd`) fails immediately with
`JorlanError("Path traversal rejected: '...'")`.

## Capability

`shell.read` — a new, dedicated capability for the read-only sandbox commands. It is seeded as `Persistent`
in `InitService.systemCapabilities`. The existing `shell.execute` capability governs `shell.run` and is unchanged.

## Resource Limits

- **Timeout**: 10 seconds (hardcoded for all safe commands; not configurable per invocation)
- **Max output**: 64 KB (`MaxSafeOutputBytes = 65536`). Output exceeding this limit is truncated and a notice
  appended: `\n[output truncated at 64 KB]`
- `shell.cat` additionally accepts `maxLines` (default 500); lines beyond this limit are discarded with a notice.

## Configuration Changes

Add `sandboxRoot: String = "."` to `ShellSettings`. This defaults to the current working directory (the workspace
root when the server is started from the project root).

## No New SBT Module

All changes are confined to:
- `server/src/main/scala/jorlan/configuration.scala` — `ShellSettings.sandboxRoot`
- `server/src/main/scala/jorlan/service/skills/ShellSkill.scala` — 7 new tools + helper methods
- `server/src/main/scala/jorlan/init/InitService.scala` — seed `shell.read`
- `server/src/test/scala/jorlan/service/skills/ShellSkillSpec.scala` — new tests

## Path Resolution Helper

```scala
private def resolveSafePath(relOrAbs: String): IO[JorlanError, java.nio.file.Path] = {
  val resolved = sandboxRoot.resolve(relOrAbs).normalize()
  if (!resolved.startsWith(sandboxRoot))
    ZIO.fail(JorlanError(s"Path traversal rejected: '$relOrAbs'"))
  else
    ZIO.succeed(resolved)
}
```

`sandboxRoot` is computed once at construction time as `Paths.get(settings.sandboxRoot).toAbsolutePath.normalize()`.
