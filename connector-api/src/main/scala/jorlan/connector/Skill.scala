/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.connector

import jorlan.*
import jorlan.domain.*
import zio.*
import zio.json.ast.Json

/** A loadable unit of agent capability: a namespace of one or more tools the agent may invoke.
  *
  * Each `Skill` exposes its tool catalogue via [[descriptor]] and routes invocations via [[invoke]]. Input/output JSON
  * schema validation against [[ToolDescriptor.inputSchema]] is the registry's responsibility (Phase 12), not the
  * skill's.
  */
trait Skill {

  /** Static description of this skill: name, tier, and tool catalogue. */
  def descriptor: SkillDescriptor

  /** Execute a tool from this skill's namespace.
    *
    * @param ctx
    *   the resolved authority context (user, agent, session) — supplied by the runtime, never by the model
    * @param tool
    *   fully qualified tool name (e.g. `"telegram.send_message"`)
    * @param args
    *   JSON object with the tool arguments; shape must conform to the tool's [[ToolDescriptor.inputSchema]]
    * @return
    *   JSON result of the tool execution, or a [[JorlanError]] if the tool name is unknown or the invocation fails
    */
  def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json]

}

/** A [[Skill]] that also bridges an external system, adding an ingress lifecycle.
  *
  * Egress (e.g. `telegram.send_message`) flows through the inherited [[invoke]]. Ingress (listening for inbound events)
  * is managed by [[start]] / [[stop]], which are forked as daemons at boot by [[ConnectorManager]].
  */
trait ConnectorSkill extends Skill {

  def connectorType: ConnectorType

  def instanceId: ConnectorInstanceId

  /** Begin ingress: poll/listen → normalize → [[MessageIngress.receive]]. Runs until [[stop]] is called. */
  def start: IO[JorlanError, Unit]

  /** Stop ingress and release resources. */
  def stop: IO[JorlanError, Unit]

}

/** Static description of a [[Skill]]: its namespace name, trust tier, and the tools it exposes.
  *
  * @param name
  *   skill namespace (e.g. `"telegram"`); must be unique across all registered skills
  * @param tier
  *   trust tier controlling how the skill is isolated at runtime
  * @param tools
  *   the tools this skill exposes; each tool is addressable by its [[ToolDescriptor.name]]
  */
case class SkillDescriptor(
  name:  String,
  tier:  SkillTier,
  tools: List[ToolDescriptor],
)

/** Descriptor for a single tool within a [[Skill]] namespace.
  *
  * @param name
  *   Fully qualified tool name, e.g. `"telegram.send_message"`.
  * @param inputSchema
  *   JSON Schema for the tool's argument object.
  * @param outputSchema
  *   JSON Schema for the tool's return value.
  * @param requiredCapabilities
  *   Capabilities the caller must hold before this tool may be invoked.
  */
case class ToolDescriptor(
  name:                 String,
  description:          String,
  inputSchema:          Json,
  outputSchema:         Json,
  requiredCapabilities: List[CapabilityName],
)

/** The authority context a tool runs under — resolved by the runtime, never supplied by the model.
  *
  * @param actorId
  *   The authenticated user on whose behalf the tool is invoked.
  * @param agentId
  *   The agent session that triggered the invocation, if any.
  * @param sessionId
  *   The active agent session, if any.
  */
case class InvocationContext(
  actorId:   UserId,
  agentId:   Option[AgentId],
  sessionId: Option[AgentSessionId],
)
