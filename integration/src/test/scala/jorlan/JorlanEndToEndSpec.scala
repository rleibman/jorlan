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
import jorlan.db.JorlanContainer
import jorlan.db.repository.{QuillRepositories, ZIORepositories}
import jorlan.google.{GoogleCalendarSkill, GoogleDriveSkill}
import jorlan.graphql.JorlanAPI
import jorlan.service.llm.FakeModelGateway
import jorlan.service.memory.MemoryServiceImpl
import jorlan.service.schedule.{JobManagerImpl, TriggerEngine}
import jorlan.service.skills.{EmailSkill, SkillRegistry}
import jorlan.service.*
import zio.*
import zio.http.Client
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import scala.language.unsafeNulls

/** End-to-end integration tests using a real MariaDB (Testcontainers) and the full service stack via
  * [[Jorlan.buildRoutes]].
  *
  * Tests verify:
  *   - `Jorlan.buildRoutes` succeeds with a container-backed environment (exercises JorlanRoutes + auth setup)
  *   - GraphQL queries/mutations work end-to-end through the full service layer
  *   - The server-identity session (seeded user id=1) is present and queryable
  */
object JorlanEndToEndSpec
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

  // Stub providers for Phase 13 skills — fail with a clear error so the dispatch path is exercised without real APIs
  private val stubEmailProvider: EmailProvider[[A] =>> IO[JorlanError, A]] =
    new EmailProvider[[A] =>> IO[JorlanError, A]] {
      override def listMessages(
        userId:     UserId,
        maxResults: Int,
        query:      Option[String],
      ): IO[JorlanError, List[EmailMessage]] =
        ZIO.fail(JorlanError("No email credentials in test environment"))
      override def getMessage(
        userId:    UserId,
        messageId: EmailMessageId,
      ): IO[JorlanError, EmailMessage] =
        ZIO.fail(JorlanError("No email credentials in test environment"))
      override def sendDraft(
        userId: UserId,
        draft:  EmailDraft,
      ): IO[JorlanError, EmailMessageId] =
        ZIO.fail(JorlanError("No email credentials in test environment"))
      override def createDraft(
        userId: UserId,
        draft:  EmailDraft,
      ): IO[JorlanError, String] =
        ZIO.fail(JorlanError("No email credentials in test environment"))
      override def archiveMessage(
        userId:    UserId,
        messageId: EmailMessageId,
      ): IO[JorlanError, Unit] =
        ZIO.fail(JorlanError("No email credentials in test environment"))
      override def deleteMessage(
        userId:    UserId,
        messageId: EmailMessageId,
      ): IO[JorlanError, Unit] =
        ZIO.fail(JorlanError("No email credentials in test environment"))
      override def moveMessage(
        userId:    UserId,
        messageId: EmailMessageId,
        toFolder:  String,
      ): IO[JorlanError, Unit] =
        ZIO.fail(JorlanError("No email credentials in test environment"))
      override def flagMessage(
        userId:    UserId,
        messageId: EmailMessageId,
        flagged:   Option[Boolean],
        read:      Option[Boolean],
      ): IO[JorlanError, Unit] =
        ZIO.fail(JorlanError("No email credentials in test environment"))
      override def listFolders(userId: UserId): IO[JorlanError, List[EmailFolderInfo]] =
        ZIO.fail(JorlanError("No email credentials in test environment"))
      override def forwardMessage(
        userId:    UserId,
        messageId: EmailMessageId,
        to:        List[String],
        note:      Option[String],
      ): IO[JorlanError, EmailMessageId] =
        ZIO.fail(JorlanError("No email credentials in test environment"))
    }

  private val stubCalendarProvider: CalendarProvider[[A] =>> IO[JorlanError, A]] =
    new CalendarProvider[[A] =>> IO[JorlanError, A]] {
      override def listCalendars(userId: UserId): IO[JorlanError, List[UserCalendar]] =
        ZIO.fail(JorlanError("No calendar credentials in test environment"))
      override def listEvents(
        userId:     UserId,
        calendarId: CalendarId,
        maxResults: Int,
        timeMin:    Option[Instant],
        timeMax:    Option[Instant],
      ): IO[JorlanError, List[CalendarEntry]] =
        ZIO.fail(JorlanError("No calendar credentials in test environment"))
      override def getEvent(
        userId:     UserId,
        calendarId: CalendarId,
        eventId:    CalendarEventId,
      ): IO[JorlanError, CalendarEntry] =
        ZIO.fail(JorlanError("No calendar credentials in test environment"))
      override def createEvent(
        userId:     UserId,
        calendarId: CalendarId,
        entry:      CalendarEntry,
      ): IO[JorlanError, CalendarEntry] =
        ZIO.fail(JorlanError("No calendar credentials in test environment"))
      override def updateEvent(
        userId:     UserId,
        calendarId: CalendarId,
        entry:      CalendarEntry,
      ): IO[JorlanError, CalendarEntry] =
        ZIO.fail(JorlanError("No calendar credentials in test environment"))
      override def deleteEvent(
        userId:     UserId,
        calendarId: CalendarId,
        eventId:    CalendarEventId,
      ): IO[JorlanError, Unit] =
        ZIO.fail(JorlanError("No calendar credentials in test environment"))
    }

  private val stubDriveProvider: DriveProvider[[A] =>> IO[JorlanError, A]] =
    new DriveProvider[[A] =>> IO[JorlanError, A]] {
      override def listFiles(
        userId:     UserId,
        folderId:   Option[String],
        query:      Option[String],
        maxResults: Int,
      ): IO[JorlanError, List[DriveFile]] =
        ZIO.fail(JorlanError("No drive credentials in test environment"))
      override def readTextFile(
        userId: UserId,
        fileId: DriveFileId,
      ): IO[JorlanError, String] =
        ZIO.fail(JorlanError("No drive credentials in test environment"))
      override def downloadFile(
        userId: UserId,
        fileId: DriveFileId,
      ): IO[JorlanError, Array[Byte]] =
        ZIO.fail(JorlanError("No drive credentials in test environment"))
    }

  private val skillRegistryLayer: URLayer[ZIORepositories, SkillRegistry] =
    (SkillRegistry.live ++ ZLayer.environment[ZIORepositories]) >>>
      ZLayer.fromZIO {
        for {
          repos <- ZIO.service[ZIORepositories]
          reg   <- ZIO.service[SkillRegistry]
          _     <- reg.register(new EmailSkill(stubEmailProvider))
          _     <- reg.register(new GoogleCalendarSkill(stubCalendarProvider))
          _     <- reg.register(new GoogleDriveSkill(stubDriveProvider))
        } yield reg
      }

  private val envLayer: TaskLayer[JorlanEnvironment] =
    ZLayer.make[JorlanEnvironment](
      configLayer,
      QuillRepositories.live,
      stubCapabilityEvaluator, // real CapabilityEvaluator tested separately in CapabilityEvaluatorSpec
      ApprovalServiceImpl.live,
      jorlan.auth.JorlanAuthServer.live,
      authConfigLayer,
      oauthLayer,
      OAuthStateStore.live(),
      SessionHub.live,
      ToolEventHub.live,
      EventLogHub.live,
      FakeModelGateway.layer(List("test")),
      AgentSessionManagerImpl.live,
      NoOpEmbeddingLayers.embeddingStoreLayer,
      NoOpEmbeddingLayers.embeddingModelLayer,
      MemoryServiceImpl.live,
      skillRegistryLayer,
      AgentRunnerImpl.live,
      JobManagerImpl.live,
      TriggerEngine.live,
      ZLayer.succeed(ConnectorManager.empty),
      NotificationRouter.live,
      stubOAuthCredentialService,
      Client.default,
      DashboardService.live,
    )

  private type Interp = GraphQLInterpreter[JorlanApiEnv & JorlanSession, Any]

  override val bootstrap: ZLayer[Any, Any, FullEnv] =
    ZLayer.make[FullEnv](
      envLayer,
      ZLayer.succeed(JorlanSession.serverSession),
      ZLayer.fromZIO(JorlanAPI.api.interpreter.orDie),
    )

  override def spec: Spec[FullEnv & TestEnvironment & Scope, Any] =
    suite("Jorlan end-to-end (real DB)")(
      test("Jorlan.buildRoutes succeeds with full environment") {
        Jorlan.zapp().as(assertTrue(true))
      },
      test("seeded server user is returned by users query") {
        for {
          interp <- ZIO.service[Interp]
          result <- interp.execute("""{ users { id displayName } }""")
        } yield assertTrue(
          result.errors.isEmpty,
          result.data.toString.contains("server"),
        )
      },
      test("createUser and user(id) round-trip with real DB") {
        for {
          interp       <- ZIO.service[Interp]
          createResult <- interp.execute(
            """mutation { createUser(displayName: "E2EUser", email: "e2e@test.com") { id displayName email } }""",
          )
          id = {
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(createResult.data.toString).map(_.group(1).toLong).getOrElse(-1L)
          }
          queryResult <- interp.execute(s"""{ user(value: $id) { id displayName email } }""")
        } yield assertTrue(
          createResult.errors.isEmpty,
          queryResult.errors.isEmpty,
          queryResult.data.toString.contains("E2EUser"),
          queryResult.data.toString.contains("e2e@test.com"),
        )
      },
      test("createRole then assignRole to a user persisted to DB") {
        for {
          interp     <- ZIO.service[Interp]
          userResult <- interp.execute(
            """mutation { createUser(displayName: "E2ERoleUser", email: "e2erole@test.com") { id } }""",
          )
          userId = {
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(userResult.data.toString).map(_.group(1).toLong).getOrElse(-1L)
          }
          roleResult <- interp.execute("""mutation { createRole(name: "e2e-role", description: "E2E") { id } }""")
          roleId = {
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(roleResult.data.toString).map(_.group(1).toLong).getOrElse(-1L)
          }
          _           <- interp.execute(s"""mutation { assignRole(userId: $userId, roleId: $roleId) }""")
          rolesResult <- interp.execute(s"""{ roles(userId: $userId) { id name } }""")
        } yield assertTrue(
          userResult.errors.isEmpty,
          roleResult.errors.isEmpty,
          rolesResult.errors.isEmpty,
          rolesResult.data.toString.contains("e2e-role"),
        )
      },
      test("grantPermission and permissions(userId) with real DB") {
        for {
          interp     <- ZIO.service[Interp]
          userResult <- interp.execute(
            """mutation { createUser(displayName: "E2EPermUser", email: "e2eperm@test.com") { id } }""",
          )
          userId = {
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(userResult.data.toString).map(_.group(1).toLong).getOrElse(-1L)
          }
          grantResult <- interp.execute(
            s"""mutation { grantPermission(resource: "shell", action: "exec", userId: $userId) { id resource } }""",
          )
          permsResult <- interp.execute(s"""{ permissions(userId: $userId) { resource action } }""")
        } yield assertTrue(
          userResult.errors.isEmpty,
          grantResult.errors.isEmpty,
          permsResult.errors.isEmpty,
          permsResult.data.toString.contains("shell"),
          permsResult.data.toString.contains("exec"),
        )
      },
      test("storeMemory → listMemory → forgetMemory round-trip with real DB") {
        for {
          interp      <- ZIO.service[Interp]
          storeResult <- interp.execute(
            """mutation { storeMemory(key: "e2e.memory", text: "E2E memory value", scope: User) { id scope recordKey } }""",
          )
          listResult <- interp.execute("""{ listMemory(scope: User) { id scope recordKey } }""")
          id = {
            import scala.language.unsafeNulls
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(storeResult.data.toString).map(_.group(1).toLong).getOrElse(0L)
          }
          forgetResult <- interp.execute(s"""mutation { forgetMemory(value: $id) }""")
          afterList    <- interp.execute("""{ listMemory(scope: User) { id scope recordKey } }""")
        } yield assertTrue(
          storeResult.errors.isEmpty,
          listResult.data.toString.contains("e2e.memory"),
          forgetResult.data.toString.contains("true"),
          !afterList.data.toString.contains("e2e.memory"),
        )
      },
      test("markMemoryShared makes record visible under Shared scope") {
        for {
          interp      <- ZIO.service[Interp]
          storeResult <- interp.execute(
            """mutation { storeMemory(key: "e2e.share", text: "shared value", scope: User) { id } }""",
          )
          id = {
            import scala.language.unsafeNulls
            val pat = """"id":([0-9]+)""".r
            pat.findFirstMatchIn(storeResult.data.toString).map(_.group(1).toLong).getOrElse(0L)
          }
          shareResult <- interp.execute(s"""mutation { markMemoryShared(value: $id) { id scope } }""")
          sharedList  <- interp.execute("""{ listMemory(scope: Shared) { id scope recordKey } }""")
        } yield assertTrue(
          storeResult.errors.isEmpty,
          shareResult.data.toString.contains("Shared"),
          sharedList.data.toString.contains("e2e.share"),
        )
      },
    ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

}
