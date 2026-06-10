/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, ToolDescriptor}
import jorlan.domain.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Registry of all active [[Skill]] instances in the server.
  *
  * Provides tool discovery (for the ReAct loop) and dispatches tool invocations from [[AgentRunnerImpl]].
  */
trait SkillRegistry {

  /** Register a skill, making its tools available to the ReAct loop. */
  def register(skill: Skill): UIO[Unit]

  /** Return all tool descriptors from every registered skill. */
  def allTools: UIO[List[ToolDescriptor]]

  /** Return all tools as [[ToolSpec]] values (model-module-safe view, no connector-api dependency). */
  def allToolSpecs: UIO[List[ToolSpec]]

  /** Invoke a named tool, returning its result as JSON.
    *
    * On failure the error is wrapped as `Json.Str("Error: <message>")` so the model can read it and recover.
    */
  def invoke(
    toolName: String,
    argsJson: String,
    ctx:      InvocationContext,
  ): UIO[Json]

}

object SkillRegistry {

  val live: ULayer[SkillRegistry] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[String, Skill]).map(SkillRegistryLive(_)),
    )

  /** Build a registry pre-populated with the given skills. */
  def liveWith(skills: Skill*): ULayer[SkillRegistry] =
    ZLayer.fromZIO(
      for {
        ref <- Ref.make(Map.empty[String, Skill])
        registry = SkillRegistryLive(ref)
        _ <- ZIO.foreach(skills)(registry.register)
      } yield registry: SkillRegistry,
    )

  def register(skill: Skill): URIO[SkillRegistry, Unit] =
    ZIO.serviceWithZIO[SkillRegistry](_.register(skill))

  def allTools: URIO[SkillRegistry, List[ToolDescriptor]] =
    ZIO.serviceWithZIO[SkillRegistry](_.allTools)

  def allToolSpecs: URIO[SkillRegistry, List[ToolSpec]] =
    ZIO.serviceWithZIO[SkillRegistry](_.allToolSpecs)

  def invoke(
    toolName: String,
    argsJson: String,
    ctx:      InvocationContext,
  ): URIO[SkillRegistry, Json] =
    ZIO.serviceWithZIO[SkillRegistry](_.invoke(toolName, argsJson, ctx))

}

class SkillRegistryLive(skills: Ref[Map[String, Skill]]) extends SkillRegistry {

  override def register(skill: Skill): UIO[Unit] =
    skills.update(m => m + (skill.descriptor.name -> skill))

  override def allTools: UIO[List[ToolDescriptor]] =
    skills.get.map(_.values.toList.flatMap(_.descriptor.tools))

  override def allToolSpecs: UIO[List[ToolSpec]] =
    allTools.map(_.map(t => ToolSpec(t.name, t.description, t.inputSchema.toString)))

  override def invoke(
    toolName: String,
    argsJson: String,
    ctx:      InvocationContext,
  ): UIO[Json] = {
    val skillNamespace = toolName.takeWhile(_ != '.')
    skills.get.flatMap { map =>
      map.get(skillNamespace) match {
        case None =>
          ZIO.succeed(Json.Str(s"Error: unknown skill namespace '$skillNamespace'"))
        case Some(skill) =>
          validateRequiredFields(toolName, argsJson, skill).flatMap {
            case Left(err) =>
              ZIO.succeed(Json.Str(s"Error: $err"))
            case Right(args) =>
              skill
                .invoke(ctx, toolName, args)
                .tapError(e => ZIO.logWarning(s"Skill '$toolName' failed: ${e.msg}"))
                .fold(
                  e => Json.Str(s"Error: ${e.msg}"),
                  identity,
                )
          }
      }
    }
  }

  private def validateRequiredFields(
    toolName: String,
    argsJson: String,
    skill:    Skill,
  ): UIO[Either[String, Json]] = {
    val result = for {
      args <- Json.decoder.decodeJson(argsJson).left.map(e => s"invalid JSON: $e")
      obj  <- args match {
        case o: Json.Obj => Right(o)
        case _ => Left("arguments must be a JSON object")
      }
      tool <- skill.descriptor.tools
        .find(_.name == toolName)
        .toRight(s"unknown tool '$toolName' in skill '${skill.descriptor.name}'")
      requiredKeys <- tool.inputSchema match {
        case Json.Obj(fields) =>
          fields
            .collectFirst { case ("required", Json.Arr(reqs)) =>
              reqs.collect { case Json.Str(k) => k }.toList
            }.orElse(Some(Nil)).toRight("malformed inputSchema")
        case _ => Right(Nil)
      }
      missingKeys = requiredKeys.filterNot(k => obj.fields.exists(_._1 == k))
      _ <-
        if (missingKeys.isEmpty) Right(())
        else Left(s"missing required fields: ${missingKeys.mkString(", ")}")
    } yield args
    ZIO.succeed(result)
  }

}
