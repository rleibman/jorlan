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
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.db.repository.ZIORepositories
import jorlan.domain.*
import jorlan.testing.{FakeConfigurationService, InMemoryRepositories, NoOpMemoryService}
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

/** Tests that [[AgentRunnerImpl]] executes the ReAct loop correctly when the model requests tool calls. */
object AgentRunnerReActSpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ULayer[ZIORepositories] = InMemoryRepositories.live()

  private val sessionId = AgentSessionId(99L)
  private val userId = UserId(1L)

  /** A stub skill that echoes its tool name back as the result. */
  private val echoSkill: Skill = new Skill {
    override val descriptor: SkillDescriptor = SkillDescriptor(
      name = "echo",
      tier = SkillTier.BuiltIn,
      tools = List(
        ToolDescriptor(
          name = "echo.run",
          description = "Echo the input",
          inputSchema = Json.decoder
            .decodeJson("""{"type":"object","properties":{},"required":[]}""")
            .getOrElse(Json.Obj()),
          outputSchema = Json.Obj("type" -> Json.Str("string")),
          requiredCapabilities = Nil,
        ),
      ),
    )
    override def invoke(
      ctx:  InvocationContext,
      tool: String,
      args: Json,
    ): IO[JorlanError, Json] =
      ZIO.succeed(Json.Str(s"echo:$tool"))
  }

  private def reactLayers(steps: List[ChatStep]): URLayer[ZIORepositories, AgentRunner & SessionHub] =
    ZLayer.makeSome[ZIORepositories, AgentRunner & SessionHub](
      FakeModelGateway.stepsLayer(steps),
      SessionHub.live,
      NoOpMemoryService.layer,
      SkillRegistry.liveWith(echoSkill),
      FakeConfigurationService.layer,
      AgentRunnerImpl.live,
    )

  private def runWithSubscription(
    message: String,
  ): ZIO[AgentRunner & SessionHub & ZIORepositories, Any, Chunk[ResponseChunk]] =
    for {
      connId <- ConnectionId.randomZIO
      stream <- ZIO.serviceWithZIO[AgentRunner](_.subscribeToSession(sessionId, connId))
      fiber  <- stream.takeUntil(_.finished).runCollect.fork
      _      <- ZIO.serviceWithZIO[AgentRunner](_.processMessage(sessionId, message, Some(userId)))
      result <- fiber.join
    } yield result

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("AgentRunnerReAct")(
      test("direct FinalAnswer publishes all tokens then finished sentinel") {
        for {
          received <- runWithSubscription("hi")
        } yield {
          val tokens = received.toList.filterNot(_.finished).map(_.content)
          assertTrue(tokens == List("hello", " world"), received.last.finished)
        }
      }.provideSome[ZIORepositories](reactLayers(List(FinalAnswer(ZStream.fromIterable(List("hello", " world")))))),
      test("ToolCallRequested → FinalAnswer invokes skill and streams final answer") {
        for {
          received <- runWithSubscription("call echo")
        } yield {
          val tokens = received.toList.filterNot(_.finished).map(_.content)
          assertTrue(
            tokens.nonEmpty,
            received.last.finished,
          )
        }
      }.provideSome[ZIORepositories](
        reactLayers(
          List(
            ToolCallRequested("id1", "echo.run", "{}"),
            FinalAnswer(ZStream.fromIterable(List("done"))),
          ),
        ),
      ),
      test("ToolLoopExceeded when all steps are tool calls") {
        for {
          received <- runWithSubscription("loop forever")
        } yield {
          assertTrue(
            received.last.finished,
            received.exists(c => c.content.contains("Tool call limit reached") || c.isError),
          )
        }
      }.provideSome[ZIORepositories](reactLayers(List.fill(11)(ToolCallRequested("id", "echo.run", "{}")))),
    )

}
