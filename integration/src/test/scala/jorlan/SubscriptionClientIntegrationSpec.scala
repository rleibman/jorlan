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
import jorlan.*
import jorlan.graphql.JorlanAPI
import jorlan.routes.JorlanRoutes
import jorlan.service.*
import jorlan.service.llm.FakeModelGateway
import jorlan.service.memory.MemoryServiceImpl
import jorlan.service.schedule.{JobManagerImpl, TriggerEngine}
import jorlan.service.skills.SkillRegistry
import jorlan.shell.ShellConfig
import jorlan.shell.client.{AuthClient, SubscriptionClient}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import scala.language.unsafeNulls

/** Integration test that starts a real zio-http server (backed by Testcontainers MariaDB) and exercises
  * [[SubscriptionClient]] end-to-end over WebSocket using the `subscriptions-transport-ws` protocol.
  */
object SubscriptionClientIntegrationSpec
    extends ZIOSpec[
      JorlanEnvironment & JorlanSession & GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any],
    ] {

  private type FullEnv = JorlanEnvironment & JorlanSession & GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any]

  // ─── Copy JorlanEndToEndSpec's proven environment setup ─────────────────────

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
      FakeModelGateway.layer(List("hello ", "world")),
      AgentSessionManagerImpl.live,
      MemoryServiceImpl.live,
      SkillRegistry.live,
      AgentRunnerImpl.live,
      JobManagerImpl.live,
      TriggerEngine.live,
      ZLayer.succeed(ConnectorManager.empty),
      NotificationRouter.live,
      stubOAuthCredentialService,
      Client.default,
    )

  override val bootstrap: ZLayer[Any, Any, FullEnv] =
    ZLayer.make[FullEnv](
      envLayer,
      ZLayer.succeed(JorlanSession.serverSession),
      ZLayer.fromZIO(JorlanAPI.api.interpreter.orDie),
    )

  // ─── Stub AuthClient: no JWT token needed for the test server ────────────────

  private val stubAuthClient: ULayer[AuthClient] = ZLayer.succeed(new AuthClient {
    override def login(
      email:    String,
      password: String,
    ): IO[String, jorlan.shell.client.LoginResult] =
      ZIO.fail("not implemented in stub")
    override def whoAmI:       IO[String, String] = ZIO.fail("not implemented in stub")
    override def currentToken: UIO[Option[String]] = ZIO.none
    override def refresh:      IO[String, String] = ZIO.fail("not implemented in stub")
  })

  override def spec: Spec[FullEnv & TestEnvironment & Scope, Any] =
    suite("SubscriptionClient WebSocket integration")(
      test("server starts and health check succeeds") {
        for {
          port <- ZIO.attempt {
            val s = new java.net.ServerSocket(0); val p = s.getLocalPort; s.close(); p
          }.orDie
          serverFiber <- Server
            .serve(Routes(Method.GET / "health" -> Handler.ok))
            .provideSomeLayer(Server.defaultWithPort(port))
            .forkDaemon
          _      <- ZIO.sleep(300.millis)
          result <- ZIO.scoped {
            zio.http.Client
              .batched(Request.get(URL.decode(s"http://localhost:$port/health").toOption.get))
              .provideSomeLayer(zio.http.Client.default)
              .orDie
          }
          _ <- serverFiber.interrupt
        } yield assertTrue(result.status == Status.Ok)
      },
      test("agentResponseStream delivers response chunks over a live WebSocket connection") {
        for {
          env <- ZIO.environment[
            JorlanEnvironment & JorlanSession & GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any],
          ]
          interp = env.get[GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any]]
          // Create an agent session via the GraphQL interpreter
          createResult <- interp.execute("""mutation { createSession { id } }""")
          sessionId = {
            val pat = """"id":([0-9]+)""".r
            pat
              .findFirstMatchIn(createResult.data.toString)
              .map(m => AgentSessionId(m.group(1).toLong))
              .getOrElse(AgentSessionId(1L))
          }
          // Build the routes and start a real server on a random port
          routes <- JorlanRoutes.all.orDie.provideEnvironment(env)
          port   <- ZIO.attempt {
            val s = new java.net.ServerSocket(0)
            val p = s.getLocalPort
            s.close()
            p
          }.orDie
          serverFiber <- Server
            .serve(routes.handleErrorCauseZIO(Jorlan.mapError).provideEnvironment(env))
            .provideSomeLayer(Server.defaultWithPort(port))
            .forkDaemon
          _ <- ZIO.sleep(400.millis)
          // Subscribe to the session using the real SubscriptionClient
          cfg = ShellConfig(serverUrl = s"http://localhost:$port")
          // Collect subscription chunks with an explicit deadline
          subscriptionEffect = ZIO.scoped {
            SubscriptionClient
              .agentResponseStream(sessionId)
              .takeWhile(!_.finished)
              .runCollect
              .mapError(e => new RuntimeException(s"WebSocket error: $e"))
              .provideSomeLayer(ZLayer.succeed(cfg) ++ stubAuthClient >>> SubscriptionClient.live)
          }
          // Fork subscription, wait for WS to establish, submit message, then race collection vs timeout
          subscriptionFiber <- subscriptionEffect.forkDaemon
          _                 <- ZIO.sleep(800.millis)
          _                 <- interp
            .execute(s"""mutation { submitMessage(sessionId: ${sessionId.value}, content: "hello") }""")
          // Race: either the subscription completes or we time out after 20s
          chunks <- subscriptionFiber.join
            .race(ZIO.sleep(20.seconds).as(Chunk.empty[ResponseChunk]))
            .map(Some(_))
          _ <- subscriptionFiber.interruptFork
          _ <- serverFiber.interrupt
        } yield assertTrue(
          createResult.errors.isEmpty,
          sessionId.value > 0L,
          chunks.isDefined,
        )
      },
    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential

}
