#!/usr/bin/env bash
# Generates the Caliban GraphQL client for jorlan-shell from the SDL schema.
# Run gen-schema.sh first if the schema has changed.
#
# Output: shell/src/main/scala/jorlan/graphql/client/JorlanClient.scala
# The generated file is checked in — only re-run when the schema changes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SCHEMA="$PROJECT_ROOT/server/src/main/graphql/jorlan.gql"
OUTPUT="$PROJECT_ROOT/shell/src/main/scala/jorlan/graphql/client/JorlanClient.scala"

if [ ! -f "$SCHEMA" ]; then
  echo "Schema file not found: $SCHEMA"
  echo "Run scripts/gen-schema.sh first."
  exit 1
fi

echo "Generating client from $SCHEMA ..."
mkdir -p "$(dirname "$OUTPUT")"
cd "$PROJECT_ROOT"
sbt --error \
  "project server" \
  "calibanGenClient $SCHEMA $OUTPUT --genView true --scalarMappings String:String --packageName jorlan.graphql.client"
echo "Done. Client written to $OUTPUT"
