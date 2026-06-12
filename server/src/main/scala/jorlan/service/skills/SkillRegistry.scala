/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, ToolDescriptor}
import jorlan
.*
import jorlan.service.{CapabilityEvaluator, ToolSpec}
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

  /** Return all registered [[Skill]] instances. */
  def allSkills: UIO[List[Skill]]

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

  def getSkill[S <: Skill](name: String): UIO[Option[S]]

}

object SkillRegistry {

  private def makeCache: UIO[Ref[Option[List[ToolSpec]]]] = Ref.make(Option.empty[List[ToolSpec]])

  /** Build an empty registry with capability enforcement disabled (for tests). */
  val live: ULayer[SkillRegistry] =
    ZLayer.fromZIO(
      for {
        ref   <- Ref.make(Map.empty[String, Skill])
        cache <- makeCache
      } yield SkillRegistryLive(ref, None, cache),
    )

  /** Build a registry pre-populated with the given skills (capability enforcement disabled; for tests). */
  def liveWith(skills: Skill*): ULayer[SkillRegistry] =
    ZLayer.fromZIO(
      for {
        ref   <- Ref.make(Map.empty[String, Skill])
        cache <- makeCache
        registry = SkillRegistryLive(ref, None, cache)
        _ <- ZIO.foreachDiscard(skills)(registry.register)
      } yield registry: SkillRegistry,
    )

  /** Build an empty registry wired with [[CapabilityEvaluator]] for production use. */
  val liveSecure: URLayer[CapabilityEvaluator, SkillRegistry] =
    ZLayer.fromZIO(
      for {
        evaluator <- ZIO.service[CapabilityEvaluator]
        ref       <- Ref.make(Map.empty[String, Skill])
        cache     <- makeCache
      } yield SkillRegistryLive(ref, Some(evaluator), cache),
    )

  /** Build a pre-populated registry wired with [[CapabilityEvaluator]] for production use. */
  def liveSecureWith(skills: Skill*): URLayer[CapabilityEvaluator, SkillRegistry] =
    ZLayer.fromZIO(
      for {
        evaluator <- ZIO.service[CapabilityEvaluator]
        ref       <- Ref.make(Map.empty[String, Skill])
        cache     <- makeCache
        registry = SkillRegistryLive(ref, Some(evaluator), cache)
        _ <- ZIO.foreachDiscard(skills)(registry.register)
      } yield registry: SkillRegistry,
    )

  def register(skill: Skill): URIO[SkillRegistry, Unit] =
    ZIO.serviceWithZIO[SkillRegistry](_.register(skill))

  def allSkills: URIO[SkillRegistry, List[Skill]] =
    ZIO.serviceWithZIO[SkillRegistry](_.allSkills)

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

class SkillRegistryLive(
  skills:         Ref[Map[String, Skill]],
  evaluator:      Option[CapabilityEvaluator],
  toolSpecsCache: Ref[Option[List[ToolSpec]]],
) extends SkillRegistry {

  override def register(skill: Skill): UIO[Unit] =
    for {
      _ <- ZIO
        .logWarning(s"SkillRegistry: overwriting existing skill '${skill.descriptor.name}'")
        .whenZIO(skills.get.map(_.contains(skill.descriptor.name)))
      _ <- skills.update(m => m + (skill.descriptor.name -> skill))
      _ <- toolSpecsCache.set(None)
    } yield ()

  override def allSkills: UIO[List[Skill]] =
    skills.get.map(_.values.toList)

  override def allTools: UIO[List[ToolDescriptor]] =
    skills.get.map(_.values.toList.flatMap(_.descriptor.tools))

  override def allToolSpecs: UIO[List[ToolSpec]] =
    toolSpecsCache.get.flatMap {
      case Some(cached) => ZIO.succeed(cached)
      case None         =>
        allTools
          .map(_.map(t => ToolSpec(t.name, t.description, t.inputSchema.toString))).tap(specs =>
            toolSpecsCache.set(Some(specs)),
          )
    }

  def getSkill[S <: Skill](name: String): UIO[Option[S]] =
    skills.get.map(_.find(_._2.descriptor.name == name).map(_._2.asInstanceOf[S]))

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
              checkCapabilities(toolName, ctx, skill).flatMap {
                case Some(denied) => ZIO.succeed(Json.Str(s"Error: $denied"))
                case None         =>
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
  }

  /** Returns `Some(denialReason)` if capability gating is enabled and any required capability is denied. */
  private def checkCapabilities(
    toolName: String,
    ctx:      InvocationContext,
    skill:    Skill,
  ): UIO[Option[String]] =
    evaluator match {
      case None     => ZIO.none
      case Some(ev) =>
        skill.descriptor.tools.find(_.name == toolName) match {
          case None                                            => ZIO.none
          case Some(tool) if tool.requiredCapabilities.isEmpty => ZIO.none
          case Some(tool)                                      =>
            ZIO.foldLeft(tool.requiredCapabilities)(Option.empty[String]) {
              (
                denied,
                cap,
              ) =>
                if (denied.isDefined) ZIO.succeed(denied)
                else
                  ev.evaluate(
                    CapabilityRequest(cap, ctx.actorId, ctx.agentId, ctx.sessionId, None),
                  ).fold(
                      _ => Some(s"capability check failed for '${cap.value}'"),
                      {
                        case EvaluationResult.ExplicitDeny => Some(s"access denied: explicit deny on '${cap.value}'")
                        case EvaluationResult.DefaultDeny  => Some(s"access denied: no permission for '${cap.value}'")
                        case _                             => None
                      },
                    )
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
      requiredKeys = tool.inputSchema match {
        case Json.Obj(fields) =>
          fields
            .collectFirst { case ("required", Json.Arr(reqs)) =>
              reqs.collect { case Json.Str(k) => k }.toList
            }.getOrElse(Nil)
        case _ => Nil
      }
      missingKeys = requiredKeys.filterNot(k => obj.fields.exists(_._1 == k))
      _ <-
        if (missingKeys.isEmpty) Right(())
        else Left(s"missing required fields: ${missingKeys.mkString(", ")}")
    } yield args
    ZIO.succeed(result)
  }

}
