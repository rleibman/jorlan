/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.usecase

import jorlan.*
import zio.json.JsonCodec

/** Machine-readable translation of one of the use-case documents under `doc/use-cases`'s "Implementation in Jorlan"
  * section, consumed by `UseCaseImporterApp` to provision a role, agent, memory seeds, MCP servers, declarative skills,
  * and a scheduled pipeline job into a running Jorlan server. See `doc/use-cases/manifest-schema.md` for field-by-field
  * documentation and `doc/use-cases/manifests/food_calendar.json` for a worked example.
  */
case class UseCaseManifest(
  useCaseName:       String,
  sourceDoc:         Option[String] = None,
  role:              ManifestRoleSpec,
  assignRoleToUser:  Option[String] = None, // email of the user who should hold this role
  agent:             ManifestAgentSpec,
  memorySeeds:       List[ManifestMemorySeed] = List.empty,
  mcpServers:        List[ManifestMcpServer] = List.empty,
  declarativeSkills: List[ManifestDeclarativeSkill] = List.empty,
  job:               Option[ManifestJob] = None,
) derives JsonCodec

case class ManifestCapabilitySpec(
  capability:   String,
  approvalMode: ApprovalMode = ApprovalMode.Persistent,
) derives JsonCodec

case class ManifestRoleSpec(
  name:         String,
  description:  Option[String] = None,
  capabilities: List[ManifestCapabilitySpec] = List.empty,
) derives JsonCodec

/** `defaultModel` is a plain `String` (not `ModelId`) for human-editability; the importer wraps it with `ModelId(_)`
  * when constructing the `Agent`.
  */
case class ManifestAgentSpec(
  name:              String,
  description:       Option[String] = None,
  defaultModel:      Option[String] = None,
  trustLevel:        Int = 0,
  prioritizedSkills: List[String] = List.empty,
  invariants:        Map[String, String] = Map.empty,
) derives JsonCodec

case class ManifestMemorySeed(
  key:   String,
  text:  String,
  scope: MemoryScope = MemoryScope.User,
) derives JsonCodec

case class ManifestMcpEnvVar(
  key:   String,
  value: String,
) derives JsonCodec

/** `transport` must match an `McpTransport` case name exactly -- `Stdio`, `Http`, `HttpSse`. `env` applies to `Stdio`
  * (the subprocess environment); `headers` applies to the HTTP transports and is how a remote server is authenticated,
  * e.g. `Authorization: Bearer <token>`.
  */
case class ManifestMcpServer(
  name:      String,
  transport: String,
  command:   Option[String] = None,
  args:      List[String] = List.empty,
  env:       List[ManifestMcpEnvVar] = List.empty,
  url:       Option[String] = None,
  enabled:   Boolean = true,
  keywords:  List[String] = List.empty,
  headers:   List[ManifestMcpEnvVar] = List.empty,
) derives JsonCodec

/** Declarative HTTP skill manifests are passed through as raw JSON text -- `DeclarativeSkillManifest` already has its
  * own codec server-side; no need to duplicate the case class hierarchy here.
  */
case class ManifestDeclarativeSkill(manifestJson: String) derives JsonCodec

case class ManifestPipelineStep(
  name:         String,
  systemPrompt: String,
  userPrompt:   String,
  tools:        List[String] = List.empty,
  mode:         StepMode = StepMode.ReactLoop,
  outputVar:    String,
  retryOnFail:  Int = 0,
) derives JsonCodec

case class ManifestTrigger(
  triggerType: TriggerType,
  expression:  String,
) derives JsonCodec

case class ManifestJob(
  name:            String,
  steps:           List[ManifestPipelineStep],
  invariants:      Map[String, String] = Map.empty,
  personality:     Option[String] = None,
  maxRetries:      Int = 0,
  backoffSeconds:  Int = 60,
  backoffPolicy:   RetryBackoffPolicy = RetryBackoffPolicy.Fixed,
  missedRunPolicy: MissedRunPolicy = MissedRunPolicy.Skip,
  trigger:         Option[ManifestTrigger] = None,
) derives JsonCodec
