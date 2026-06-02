#!/usr/bin/env bash
set -euo pipefail

# Resolve the project root relative to this script's location.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

STAGED_BIN="$PROJECT_ROOT/shell/target/universal/stage/bin/jorlan-shell"

if [[ -x "$STAGED_BIN" ]]; then
  # Use the pre-staged binary (fast startup).
  exec "$STAGED_BIN" "$@"
else
  # Fall back to sbt run (slower, but no staging step required).
  # Build the sbt task string with each arg properly shell-quoted so
  # spaces and special characters inside arguments are preserved.
  SBT_TASK="shell/run"
  for arg in "$@"; do
    SBT_TASK="$SBT_TASK $(printf '%q' "$arg")"
  done
  cd "$PROJECT_ROOT"
  exec sbt --error "$SBT_TASK"
fi
