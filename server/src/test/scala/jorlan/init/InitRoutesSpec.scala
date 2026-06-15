/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.init

import jorlan.*
import jorlan.db.repository.{RepositoryError, ZIORepositories, ZIOServerSettingsRepository}
import jorlan.testing.InMemoryRepositories
import jorlan.testing.InMemoryRepositories.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import scala.language.unsafeNulls

/** P8.1-003: HTTP-layer tests for [[StatusRoutes]] and [[SetupModeApp]].
  *
  * Tests cover: status shape, 400/403/500 distinction, malformed JSON, 503 catch-all.
  */
object InitRoutesSpec extends ZIOSpec[ZIORepositories & JorlanSession] {

  override val bootstrap: ULayer[ZIORepositories & JorlanSession] =
    InMemoryRepositories.live() ++ ZLayer.succeed(JorlanSession.serverSession)

  import TestUtil.*

  // ─── Stub InitService ───────────────────────────────────────────────────────

  private class StubInitService(result: IO[JorlanError, Unit]) extends InitService {

    override def isInitialized: IO[JorlanError, Boolean] = ZIO.succeed(false)
    override def complete(
      token:         String,
      serverName:    String,
      adminEmail:    String,
      adminName:     String,
      adminPassword: String,
    ):                                   IO[JorlanError, Unit] = result
    override def topUpAdminCapabilities: IO[JorlanError, Unit] = ZIO.unit

  }

  private def stubService(result: IO[JorlanError, Unit] = ZIO.unit): InitService =
    StubInitService(result)

  private val startTime = 0L

  private val noopTokenStore: InitTokenStore = new InitTokenStore {
    override def token:                    UIO[Option[String]] = ZIO.none
    override def invalidate:               UIO[Unit] = ZIO.unit
    override def isValid:                  UIO[Boolean] = ZIO.succeed(false)
    override def verify(provided: String): UIO[Boolean] = ZIO.succeed(false)
  }

  private def tokenStoreWith(storedToken: String): InitTokenStore =
    new InitTokenStore {
      override def token:                    UIO[Option[String]] = ZIO.some(storedToken)
      override def invalidate:               UIO[Unit] = ZIO.unit
      override def isValid:                  UIO[Boolean] = ZIO.succeed(true)
      override def verify(provided: String): UIO[Boolean] = ZIO.succeed(provided == storedToken)
    }

  // ─── Helpers ────────────────────────────────────────────────────────────────

  private def getStatus(routes: Routes[ZIORepositories & JorlanSession, JorlanError]) =
    routes.handleErrorCauseZIO(Jorlan.mapError).run(Method.GET, Path.decode("/api/status"))

  private def postInit(
    routes: Routes[ZIORepositories & JorlanSession, Nothing],
    body:   String,
  ) =
    routes.run(
      Method.POST,
      Path.decode("/api/init"),
      headers = Headers(Header.ContentType(MediaType.application.json)),
      body = Body.fromString(body),
    )

  private val validInitBody = InitRequest("tok", "MyServer", "admin@example.com", "Admin", "password123!").toJson

  private def postInitWithAddress(
    routes:        Routes[ZIORepositories & JorlanSession, Nothing],
    body:          String,
    remoteAddress: Option[InetAddress],
  ) =
    routes.runZIO(
      Request(
        method = Method.POST,
        url = URL.root.path(Path.decode("/api/init")),
        headers = Headers(Header.ContentType(MediaType.application.json)),
        body = Body.fromString(body),
        remoteAddress = remoteAddress,
      ),
    )

  // ─── Tests ──────────────────────────────────────────────────────────────────

  override def spec: Spec[ZIORepositories & JorlanSession & (TestEnvironment & Scope), Any] =
    suite("InitRoutes")(
      suite("StatusRoutes")(
        test("returns 200 with initialized=false when not yet set up") {
          for {
            repo         <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            currentTime  <- Clock.currentTime(TimeUnit.MILLISECONDS)
            statusRoutes <- StatusRoutes(currentTime).all
            resp         <- getStatus(statusRoutes)
            body         <- resp.body.asString
            decoded = body.fromJson[ServerStatus]
          } yield assertTrue(
            resp.status == Status.Ok,
            decoded.isRight,
            decoded.toOption.exists(!_.initialized),
          )
        },
        test("returns 200 with initialized=true after setup") {
          for {
            repo <- withSettings(
              Map(
                ZIOServerSettingsRepository.InitializedKey -> Json.Bool(true),
                ZIOServerSettingsRepository.ServerNameKey  -> Json.Str("TestServer"),
              ),
            )
            currentTime  <- Clock.currentTime(TimeUnit.MILLISECONDS)
            statusRoutes <- StatusRoutes(currentTime).all.provideLayer(ZLayer.succeed[ZIORepositories](repo))
            resp         <- getStatus(statusRoutes)
            body         <- resp.body.asString
            decoded = body.fromJson[ServerStatus]
          } yield assertTrue(
            resp.status == Status.Ok,
            decoded.toOption.exists(_.initialized),
            decoded.toOption.exists(_.serverName == "TestServer"),
          )
        },
        test("serverName falls back to Jorlan when key absent") {
          for {
            repo         <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            currentTime  <- Clock.currentTime(TimeUnit.MILLISECONDS)
            statusRoutes <- StatusRoutes(currentTime).all
            resp         <- getStatus(statusRoutes)
            body         <- resp.body.asString
            decoded = body.fromJson[ServerStatus]
          } yield assertTrue(decoded.toOption.exists(_.serverName == "Jorlan"))
        },
      ),
      suite("SetupModeApp — POST /api/init")(
        test("malformed JSON body returns 400") {
          for {
            repo   <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            routes <- SetupModeApp.make(startTime, stubService(), noopTokenStore)
            resp   <- postInit(routes, "not json at all")
          } yield assertTrue(resp.status == Status.BadRequest)
        },
        test("ValidationError from service returns 400") {
          for {
            repo <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            svc = stubService(ZIO.fail(ValidationError("Password must be at least 12 characters")))
            routes <- SetupModeApp.make(startTime, svc, noopTokenStore)
            resp   <- postInit(routes, validInitBody)
          } yield assertTrue(resp.status == Status.BadRequest)
        },
        test("invalid token returns 403") {
          for {
            repo <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            svc = stubService(ZIO.fail(JorlanError("Invalid setup token")))
            routes <- SetupModeApp.make(startTime, svc, noopTokenStore)
            resp   <- postInit(routes, validInitBody)
          } yield assertTrue(resp.status == Status.Forbidden)
        },
        test("already-initialized error returns 403") {
          for {
            repo <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(true)))
            svc = stubService(ZIO.fail(JorlanError("Server is already initialized")))
            routes <- SetupModeApp.make(startTime, svc, noopTokenStore)
            resp   <- postInit(routes, validInitBody)
          } yield assertTrue(resp.status == Status.Forbidden)
        },
        test("RepositoryError returns 500") {
          for {
            repo <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            svc = stubService(ZIO.fail(RepositoryError(RuntimeException("DB failure"))))
            routes <- SetupModeApp.make(startTime, svc, noopTokenStore)
            resp   <- postInit(routes, validInitBody)
          } yield assertTrue(resp.status == Status.InternalServerError)
        },
        test("successful init returns 200 with success=true") {
          for {
            repo   <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            routes <- SetupModeApp.make(startTime, stubService(), noopTokenStore)
            resp   <- postInit(routes, validInitBody)
            body   <- resp.body.asString
          } yield assertTrue(
            resp.status == Status.Ok,
            body.contains("true"),
          )
        },
      ),
      suite("SetupModeApp — catch-all")(
        test("unknown path returns 503") {
          for {
            repo   <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            routes <- SetupModeApp.make(startTime, stubService(), noopTokenStore)
            resp   <- routes.run(Method.GET, Path.decode("/api/agents"))
          } yield assertTrue(resp.status == Status.ServiceUnavailable)
        },
        test("GET /api/status served even in setup mode") {
          for {
            repo   <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            routes <- SetupModeApp.make(startTime, stubService(), noopTokenStore)
            resp   <- getStatus(routes)
          } yield assertTrue(resp.status == Status.Ok)
        },
      ),
      suite("SetupModeApp — localhost token bypass")(
        test("localhost with empty token uses stored token and succeeds") {
          val storedToken = "server-generated-token"
          val bodyNoToken = InitRequest("", "MyServer", "admin@example.com", "Admin", "password123!").toJson
          for {
            repo <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            ts = tokenStoreWith(storedToken)
            // InitService succeeds regardless of token in this stub
            svc = stubService(ZIO.unit)
            routes <- SetupModeApp.make(startTime, svc, ts)
            resp   <- postInitWithAddress(routes, bodyNoToken, Some(InetAddress.getLoopbackAddress))
          } yield assertTrue(resp.status == Status.Ok)
        },
        test("non-localhost with empty token returns 403 (no bypass)") {
          val bodyNoToken = InitRequest("", "MyServer", "admin@example.com", "Admin", "password123!").toJson
          for {
            repo <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            svc = stubService(ZIO.fail(JorlanError("Invalid setup token")))
            routes <- SetupModeApp.make(startTime, svc, noopTokenStore)
            resp   <- postInitWithAddress(routes, bodyNoToken, None) // no remote address = non-localhost
          } yield assertTrue(resp.status == Status.Forbidden)
        },
      ),
      suite("SetupModeApp — initDone Promise")(
        test("successful init completes the initDone Promise") {
          for {
            repo     <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            initDone <- Promise.make[Nothing, Unit]
            routes   <- SetupModeApp.make(startTime, stubService(), noopTokenStore, Some(initDone))
            resp     <- postInit(routes, validInitBody)
            done     <- initDone.isDone
          } yield assertTrue(resp.status == Status.Ok, done)
        },
        test("failed init does not complete the initDone Promise") {
          for {
            repo     <- withSettings(Map(ZIOServerSettingsRepository.InitializedKey -> Json.Bool(false)))
            initDone <- Promise.make[Nothing, Unit]
            svc = stubService(ZIO.fail(JorlanError("Invalid setup token")))
            routes <- SetupModeApp.make(startTime, svc, noopTokenStore, Some(initDone))
            resp   <- postInit(routes, validInitBody)
            done   <- initDone.isDone
          } yield assertTrue(resp.status == Status.Forbidden, !done)
        },
      ),
    )

}
