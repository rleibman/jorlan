/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.service.ModelGateway
import just.semver.SemVer
import zio.*
import zio.http.Client
import zio.json.ast.Json

/** A runtime `Skill` constructed from a [[DeclarativeSkillManifest]]. Dispatches each tool invocation to either
  * [[HttpApiExecutor]] or [[PromptTemplateExecutor]] based on the tool's executor config.
  *
  * @param manifest
  *   Parsed and validated skill manifest. Defines the skill name, tier, and tool descriptors.
  * @param client
  *   HTTP client used by [[HttpApiExecutor]] for outbound requests.
  * @param gateway
  *   Model gateway used by [[PromptTemplateExecutor]] for LLM inference.
  */
class DeclarativeSkill(
  manifest: DeclarativeSkillManifest,
  client:   Client,
  gateway:  ModelGateway,
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = manifest.name,
    tier = SkillTier.Declarative,
    tools = manifest.tools.map { t =>
      ToolDescriptor(
        name = t.name,
        description = t.description,
        inputSchema = t.inputSchema,
        outputSchema = t.outputSchema,
        requiredCapabilities = t.requiredCapabilities.map(CapabilityName(_)),
        examplePrompts = t.examplePrompts,
      )
    },
    skillVersion = manifest.version,
    keywords = manifest.keywords,
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    manifest.tools.find(_.name == tool) match {
      case None    => ZIO.fail(JorlanError(s"DeclarativeSkill '${manifest.name}': unknown tool '$tool'"))
      case Some(t) =>
        t.executor match {
          case ExecutorConfig.HttpApi(config) =>
            HttpApiExecutor.execute(config, args, client)
          case ExecutorConfig.PromptTemplate(config) =>
            PromptTemplateExecutor.execute(config, args, ctx.sessionId.getOrElse(AgentSessionId.empty), gateway)
        }
    }

}

object DeclarativeSkill {

  /** Constructs a [[DeclarativeSkill]] from an already-validated manifest.
    *
    * Prefer this factory over `new` — it makes the intent clear at call sites where the manifest has been obtained from
    * [[SkillLifecycleService]] after passing [[ManifestValidator]].
    *
    * @param manifest
    *   Validated manifest (name, version, tools with executor configs).
    * @param client
    *   ZIO HTTP client injected for [[HttpApiExecutor]] tool calls.
    * @param gateway
    *   Model gateway injected for [[PromptTemplateExecutor]] tool calls.
    */
  def from(
    manifest: DeclarativeSkillManifest,
    client:   Client,
    gateway:  ModelGateway,
  ): DeclarativeSkill = new DeclarativeSkill(manifest, client, gateway)

}
