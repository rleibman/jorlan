#!/usr/bin/env bash
# Renders the Jorlan GraphQL SDL schema to server/src/main/graphql/jorlan.gql
# Run this whenever the schema changes (new queries, mutations, or types).
# The output is checked in and used by gen-client.sh to generate the shell client.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT="$PROJECT_ROOT/server/src/main/graphql/jorlan.gql"

echo "Rendering schema to $OUTPUT ..."
cd "$PROJECT_ROOT"
sbt --error "server/runMain jorlan.graphql.SchemaGen" > "$OUTPUT"
echo "Done. Schema written to $OUTPUT"
