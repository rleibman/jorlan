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
import jorlan.db.repository.ServerSettingsRepository
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
          initialized <- settings.get("initialized").map { case Some(Json.Bool(v)) => v; case _ => false }
          nameJson    <- settings.get("serverName")
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

  def make(
    startTime:          Long,
    serverSettingsRepo: ServerSettingsRepository,
    initService:        InitService,
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
        for {
          body   <- req.body.asString.orDie
          result <- body.fromJson[InitRequest] match {
            case Left(err) =>
              ZIO.succeed(Response.badRequest(Map("error" -> err).toJson))
            case Right(r) =>
              initService
                .complete(r.token, r.serverName, r.adminEmail, r.adminName, r.adminPassword)
                .foldZIO(
                  {
                    case v: ValidationError =>
                      ZIO.succeed(
                        Response(Status.BadRequest, body = Body.fromString(Map("error" -> v.getMessage).toJson)),
                      )
                    case err =>
                      ZIO.succeed(
                        Response(Status.Forbidden, body = Body.fromString(Map("error" -> err.getMessage).toJson)),
                      )
                  },
                  _ => ZIO.succeed(Response.json("""{"success":true}""")),
                )
          }
        } yield result
      },
    )

    statusR ++ initR ++ catchAll
  }

}
