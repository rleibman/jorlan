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
import jorlan.db.repository.{RepositoryError, ServerSettingsRepository}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.util.concurrent.TimeUnit
import scala.language.unsafeNulls

// ─── Routes ───────────────────────────────────────────────────────────────────

/** `GET /api/status` — available in both initialized and setup-mode servers. Reads state dynamically per request. */
object StatusRoutes {

  def routes(
    startTime: Long,
    settings:  ServerSettingsRepository,
  ): Routes[Any, Nothing] =
    Routes(
      Method.GET / "api" / "status" -> handler {
        for {
          (initializedJson, nameJson) <-
            settings.get(ServerSettingsRepository.InitializedKey) <&>
              settings.get(ServerSettingsRepository.ServerNameKey)
          initialized = initializedJson.collect { case Json.Bool(v) => v }.getOrElse(false)
          name = nameJson.collect { case Json.Str(s) => s }.getOrElse("Jorlan")
          now <- Clock.currentTime(TimeUnit.MILLISECONDS)
          status = ServerStatus(initialized, jorlan.BuildInfo.version, name, now - startTime)
        } yield Response.json(status.toJson)
      },
    )

}

/** HTTP app served while the server is uninitialized.
  *
  * Serves `GET /api/status` and `POST /api/init`; returns 503 for everything else.
  */
object SetupModeApp {

  private val notInitializedResponse: Response =
    Response(
      Status.ServiceUnavailable,
      body = Body.fromString("""{"error":"server not initialized — POST /api/init to set up"}"""),
    )

  /** Builds the pre-initialization HTTP app.
    *
    * Serves `GET /api/status` and `POST /api/init`; returns 503 for all other paths so callers know the server is not
    * yet ready. Delegates status handling to [[StatusRoutes]] so the response shape is identical before and after
    * initialization.
    */
  def make(
    startTime:          Long,
    serverSettingsRepo: ServerSettingsRepository,
    initService:        InitService,
    tokenStore:         InitTokenStore,
    initDone:           Option[Promise[Nothing, Unit]] = None,
  ): Routes[Any, Nothing] = {
    val catchAll = Routes(
      Method.ANY / trailing -> handler {
        (
          _: Path,
          _: Request,
        ) =>
          ZIO.succeed(notInitializedResponse)
      },
    )

    val statusR = StatusRoutes.routes(startTime, serverSettingsRepo)

    val initR: Routes[Any, Nothing] = Routes(
      Method.POST / "api" / "init" -> handler { (req: Request) =>
        val isLocalhost = req.remoteAddress.exists(_.isLoopbackAddress)
        for {
          body   <- req.body.asString.orDie
          result <- body.fromJson[InitRequest] match {
            case Left(err) =>
              ZIO.succeed(Response.badRequest(Map("error" -> err).toJson))
            case Right(r) =>
              for {
                effectiveToken <-
                  if (isLocalhost && r.token.isEmpty) tokenStore.token.map(_.getOrElse(""))
                  else ZIO.succeed(r.token)
                resp <- initService
                  .complete(effectiveToken, r.serverName, r.adminEmail, r.adminName, r.adminPassword)
                  .foldZIO(
                    {
                      case v: ValidationError =>
                        ZIO.succeed(
                          Response(Status.BadRequest, body = Body.fromString(Map("error" -> v.getMessage).toJson)),
                        )
                      case _: RepositoryError =>
                        ZIO.succeed(
                          Response(
                            Status.InternalServerError,
                            body = Body.fromString(
                              Map("error" -> "Internal server error during initialization").toJson,
                            ),
                          ),
                        )
                      case err =>
                        ZIO.succeed(
                          Response(Status.Forbidden, body = Body.fromString(Map("error" -> err.getMessage).toJson)),
                        )
                    },
                    _ =>
                      ZIO.foreachDiscard(initDone)(_.succeed(())) *>
                        ZIO.succeed(Response.json("""{"success":true}""")),
                  )
              } yield resp
          }
        } yield result
      },
    )

    statusR ++ initR ++ catchAll
  }

}
