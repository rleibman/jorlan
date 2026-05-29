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
SCALAR_MAPPINGS="\
UserId:jorlan.domain.UserId,\
RoleId:jorlan.domain.RoleId,\
PermissionId:jorlan.domain.PermissionId,\
AgentId:jorlan.domain.AgentId,\
AgentSessionId:jorlan.domain.AgentSessionId,\
ApprovalRequestId:jorlan.domain.ApprovalRequestId,\
EventLogId:jorlan.domain.EventLogId,\
WorkspaceId:jorlan.domain.WorkspaceId,\
ModelId:jorlan.domain.ModelId,\
CapabilityName:jorlan.domain.CapabilityName,\
Instant:java.time.Instant,\
Long:Long,\
Unit:Unit"

IMPORTS="\
jorlan.domain._,\
jorlan.shell.client.JorlanClientDecoders._"

sbt --error \
  "project server" \
  "calibanGenClient $SCHEMA $OUTPUT --genView true --packageName jorlan.graphql.client --enableFmt false --scalarMappings $SCALAR_MAPPINGS --imports $IMPORTS"
echo "Done. Client written to $OUTPUT"
