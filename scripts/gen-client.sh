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
OUTPUT1="$PROJECT_ROOT/shell/src/main/scala/jorlan/graphql/client/JorlanClient.scala"
OUTPUT2="$PROJECT_ROOT/web/src/main/scala/jorlan/graphql/client/JorlanClient.scala"

if [ ! -f "$SCHEMA" ]; then
  echo "Schema file not found: $SCHEMA"
  echo "Run scripts/gen-schema.sh first."
  exit 1
fi

echo "Generating client from $SCHEMA ..."
mkdir -p "$(dirname "$OUTPUT1")"
mkdir -p "$(dirname "$OUTPUT2")"
cd "$PROJECT_ROOT"
SCALAR_MAPPINGS="\
UserId:jorlan.UserId,\
RoleId:jorlan.RoleId,\
PermissionId:jorlan.PermissionId,\
AgentId:jorlan.AgentId,\
AgentSessionId:jorlan.AgentSessionId,\
ApprovalRequestId:jorlan.ApprovalRequestId,\
EventLogId:jorlan.EventLogId,\
WorkspaceId:jorlan.WorkspaceId,\
MemoryRecordId:jorlan.MemoryRecordId,\
ModelId:jorlan.ModelId,\
CapabilityName:jorlan.CapabilityName,\
ChannelType:jorlan.ChannelType,\
ApprovalStatus:jorlan.ApprovalStatus,\
ApprovalMode:jorlan.ApprovalMode,\
EventType:jorlan.EventType,\
SessionStatus:jorlan.SessionStatus,\
MemoryScope:jorlan.MemoryScope,\
RiskClass:jorlan.RiskClass,\
CapabilityGrantId:jorlan.CapabilityGrantId,\
Formality:jorlan.Formality,\
JobStatus:jorlan.JobStatus,\
MissedRunPolicy:jorlan.MissedRunPolicy,\
RetryBackoffPolicy:jorlan.RetryBackoffPolicy,\
SchedulerJobId:jorlan.SchedulerJobId,\
SchedulerTriggerId:jorlan.SchedulerTriggerId,\
SkillId:jorlan.SkillId,\
TriggerType:jorlan.TriggerType,\
Instant:java.time.Instant,\
Long:Long,\
Unit:Unit"

IMPORTS="\
jorlan._,\
JorlanClientDecoders.given"

sbt --error \
  "project server" \
  "calibanGenClient $SCHEMA $OUTPUT1 --genView true --packageName jorlan.graphql.client --enableFmt false --scalarMappings $SCALAR_MAPPINGS --imports $IMPORTS" \
  "calibanGenClient $SCHEMA $OUTPUT2 --genView true --packageName jorlan.graphql.client --enableFmt false --scalarMappings $SCALAR_MAPPINGS --imports $IMPORTS"
echo "Done. Client written to $OUTPUT1 and $OUTPUT2"
