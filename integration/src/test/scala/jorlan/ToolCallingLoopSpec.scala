/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import _root_.auth.oauth.{OAuthService, OAuthStateStore}
import _root_.auth.{AuthConfig, AuthServer}
import caliban.GraphQLInterpreter
import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.db.JorlanContainer
import jorlan.db.repository.{QuillRepositories, ZIORepositories}
import jorlan.graphql.JorlanAPI
import jorlan.service.*
import jorlan.service.llm.FakeModelGateway
import jorlan.service.memory.MemoryServiceImpl
import jorlan.service.schedule.{JobManagerImpl, TriggerEngine}
import jorlan.service.skills.SkillRegistry
import jorlan.service.skills.declarative.SkillLifecycleService
import just.semver.SemVer
import zio.*
import zio.http.Client
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scala.language.unsafeNulls

/** Integration test verifying the ReAct tool-calling loop end-to-end with a real MariaDB.
  *
  * Exercises: `submitMessage` mutation → `FakeModelGateway` returns `ToolCallRequested` steps → tool invoked via
  * `SkillRegistry` → `FinalAnswer` → `agentResponseStream` delivers all chunks in order.
  *
  * The bootstrap provides only `ZIORepositories & ConfigurationService` (the shared DB layer). Each test constructs a
  * fresh `FakeModelGateway.stepsLayer` via `provideSomeLayer` so the `Ref[List[ChatStep]]` is never shared between
  * tests and tests remain independent.
  */
object ToolCallingLoopSpec extends ZIOSpec[ZIORepositories & ConfigurationService] {

  private type FullEnv = JorlanEnvironment & JorlanSession & GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any]
  private type Interp = GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any]

  override val bootstrap: TaskLayer[ZIORepositories & ConfigurationService] =
    ZLayer.make[ZIORepositories & ConfigurationService](
      JorlanContainer.configLayer,
      QuillRepositories.live,
    )

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

  private val stubOAuthCredentialService: ULayer[OAuthCredentialService] = ZLayer.succeed(
    new OAuthCredentialService {
      override def store(
        userId:    UserId,
        provider:  String,
        plainJson: Json,
      ): IO[JorlanError, Unit] = ZIO.unit
      override def load(
        userId:   UserId,
        provider: String,
      ): IO[JorlanError, Option[Json]] = ZIO.none
      override def revoke(
        userId:   UserId,
        provider: String,
      ):                                          IO[JorlanError, Unit] = ZIO.unit
      override def listProviders(userId: UserId): IO[JorlanError, List[String]] = ZIO.succeed(List.empty)
      override def refreshAccessToken(
        userId:   UserId,
        provider: String,
      ): IO[JorlanError, String] =
        ZIO.fail(JorlanError("No OAuth credentials configured in test environment"))
      override def getExpiresAt(
        userId:   UserId,
        provider: String,
      ): IO[JorlanError, Option[java.time.Instant]] = ZIO.none
    },
  )

  private val echoSkill: Skill = new Skill {
    override def descriptor: SkillDescriptor =
      SkillDescriptor(
        name = "echo",
        tier = SkillTier.BuiltIn,
        skillVersion = SemVer.parse(jorlan.BuildInfo.version).getOrElse(jorlan.BuildInfo.version),
        tools = List(
          ToolDescriptor(
            "echo.run",
            "Echo a tool call",
            Json.Obj(zio.Chunk.empty),
            Json.Obj(zio.Chunk.empty),
            List.empty,
          ),
        ),
      )
    override def invoke(
      ctx:  InvocationContext,
      tool: String,
      args: Json,
    ): IO[JorlanError, Json] = ZIO.succeed(Json.Str(s"echo:$tool"))
  }

  /** Builds the full service stack above the shared DB layer for a given sequence of model steps.
    *
    * Each call to this method creates a fresh `FakeModelGateway.stepsLayer`, so two tests using different step
    * sequences are fully independent.
    */
  private def fullEnvLayer(
    steps: List[ChatStep],
  ): ZLayer[ZIORepositories & ConfigurationService, Throwable, FullEnv] =
    ZLayer.makeSome[ZIORepositories & ConfigurationService, FullEnv](
      stubCapabilityEvaluator,
      ApprovalHub.live,
      ApprovalServiceImpl.live,
      jorlan.auth.JorlanAuthServer.live,
      authConfigLayer,
      oauthLayer,
      OAuthStateStore.live(),
      SessionHub.live,
      ToolEventHub.live,
      EventLogHub.live,
      FakeModelGateway.stepsLayer(steps),
      AgentSessionManagerImpl.live,
      NoOpEmbeddingLayers.embeddingStoreLayer,
      NoOpEmbeddingLayers.embeddingModelLayer,
      MemoryServiceImpl.live,
      SkillRegistry.liveWith(echoSkill),
      AgentRunnerImpl.live,
      JobManagerImpl.live,
      TriggerEngine.live,
      ZLayer.succeed(ConnectorManager.empty),
      NotificationRouter.live,
      stubOAuthCredentialService,
      Client.default,
      DashboardService.live,
      OAuthReconnectService.live,
      SkillLifecycleService.live,
      ZLayer.succeed(JorlanSession.serverSession),
      ZLayer.fromZIO(JorlanAPI.api.interpreter.orDie),
    )

  override def spec: Spec[ZIORepositories & ConfigurationService & TestEnvironment & Scope, Any] =
    suite("ToolCallingLoop integration")(
      test("submitMessage triggers tool call and FinalAnswer chunks arrive via agentResponseStream") {
        for {
          interp        <- ZIO.service[Interp]
          sessionResult <- interp.execute("""mutation { createSession { id } }""")
          sessionId = {
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(sessionResult.data.toString).map(m => AgentSessionId(m.group(1).toLong)).get
          }
          connId <- ConnectionId.randomZIO
          runner <- ZIO.service[AgentRunner]
          stream <- runner.subscribeToSession(sessionId, connId)
          fiber  <- stream.takeUntil(_.finished).runCollect.fork
          _      <- interp.execute(
            s"""mutation { submitMessage(sessionId: ${sessionId.value}, content: "use tool") }""",
          )
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
      }.provideSomeLayer[ZIORepositories & ConfigurationService](
        fullEnvLayer(
          List(
            ToolCallRequested(id = "tc-1", name = "echo.run", argsJson = """{}"""),
            FinalAnswer(ZStream.fromIterable(List("Tool result: ", "done"))),
          ),
        ),
      ),
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)

}
