/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import _root_.auth.oauth.{OAuthService, OAuthStateStore}
import _root_.auth.{AuthConfig, AuthServer}
import caliban.GraphQLInterpreter
import jorlan.db.JorlanContainer
import jorlan.db.repository.QuillRepositories
import jorlan.domain.*
import jorlan.graphql.JorlanAPI
import jorlan.service.*
import jorlan.service.llm.FakeModelGateway
import jorlan.service.memory.MemoryServiceImpl
import jorlan.service.schedule.{JobManagerImpl, TriggerEngine}
import jorlan.service.skills.SkillRegistry
import zio.*
import zio.http.Client
import zio.stream.ZStream
import zio.test.*

import scala.language.unsafeNulls

/** Integration test verifying the ReAct tool-calling loop end-to-end with a real MariaDB.
  *
  * Exercises: `submitMessage` mutation → `FakeModelGateway` returns `ToolCallRequested` steps → tool invoked via
  * `SkillRegistry` → `FinalAnswer` → `agentResponseStream` delivers all chunks in order.
  */
object ToolCallingLoopSpec
    extends ZIOSpec[
      JorlanEnvironment & JorlanSession & GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any],
    ] {

  private type FullEnv = JorlanEnvironment & JorlanSession & GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any]

  private val configLayer = JorlanContainer.configLayer

  private val authConfigLayer: ZLayer[ConfigurationService, Nothing, AuthConfig] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie.map(_.jorlan.auth))

  private val oauthLayer: ZLayer[ConfigurationService, Nothing, OAuthService] =
    ZLayer
      .fromZIO(
        ZIO
          .serviceWithZIO[ConfigurationService](_.appConfig).orDie.as(
            OAuthService.live(googleConfig = None, githubConfig = None, discordConfig = None),
          ),
      ).flatten

  private val stubCapabilityEvaluator: ULayer[CapabilityEvaluator] =
    ZLayer.succeed((_: CapabilityRequest) => ZIO.succeed(EvaluationResult.ResourcePermissionAllows))

  /** FakeModelGateway steps: one ToolCallRequested → FinalAnswer */
  private val toolCallingSteps: List[ChatStep] = List(
    ToolCallRequested(id = "tc-1", name = "echo.run", argsJson = """{}"""),
    FinalAnswer(ZStream.fromIterable(List("Tool result: ", "done"))),
  )

  private val envLayer: TaskLayer[JorlanEnvironment] =
    ZLayer.make[JorlanEnvironment](
      configLayer,
      QuillRepositories.live,
      stubCapabilityEvaluator,
      ApprovalServiceImpl.live,
      jorlan.auth.JorlanAuthServer.live,
      authConfigLayer,
      oauthLayer,
      OAuthStateStore.live(),
      SessionHub.live,
      ToolEventHub.live,
      FakeModelGateway.stepsLayer(toolCallingSteps),
      AgentSessionManagerImpl.live,
      MemoryServiceImpl.live,
      SkillRegistry.live,
      AgentRunnerImpl.live,
      JobManagerImpl.live,
      TriggerEngine.live,
      ZLayer.succeed(ConnectorManager.empty),
      NotificationRouter.live,
      Client.default,
    )

  private type Interp = GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any]

  override val bootstrap: ZLayer[Any, Any, FullEnv] =
    ZLayer.make[FullEnv](
      envLayer,
      ZLayer.succeed(JorlanSession.serverSession),
      ZLayer.fromZIO(JorlanAPI.api.interpreter.orDie),
    )

  override def spec: Spec[FullEnv & TestEnvironment & Scope, Any] =
    suite("ToolCallingLoop integration")(
      test("submitMessage triggers tool call and FinalAnswer chunks arrive via agentResponseStream") {
        for {
          interp <- ZIO.service[Interp]
          // Create an agent session
          sessionResult <- interp.execute("""mutation { createSession { id } }""")
          sessionId = {
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(sessionResult.data.toString).map(m => AgentSessionId(m.group(1).toLong)).get
          }
          // Subscribe to the response stream before submitting the message
          connId <- ConnectionId.randomZIO
          runner <- ZIO.service[AgentRunner]
          stream <- runner.subscribeToSession(sessionId, connId)
          fiber  <- stream.takeUntil(_.finished).runCollect.fork
          // Submit the message
          _ <- interp.execute(
            s"""mutation { submitMessage(sessionId: ${sessionId.value}, content: "use tool") }""",
          )
          // Collect the streamed chunks
          chunks <- fiber.join
        } yield {
          val texts = chunks.toList.filterNot(_.finished).map(_.content)
          assertTrue(
            sessionResult.errors.isEmpty,
            chunks.nonEmpty,
            chunks.last.finished,
            texts.exists(_.contains("⟳")),
            texts.exists(t => t.contains("Tool result:") || t.contains("done")),
          )
        }
      },
    ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

}
