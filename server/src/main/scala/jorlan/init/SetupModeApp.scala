/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.init

import jorlan.*
import jorlan.db.repository.{RepositoryError, ZIORepositories}
import zio.*
import zio.http.*
import zio.json.*

import scala.language.unsafeNulls

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
    startTime:   Long,
    initService: InitService,
    tokenStore:  InitTokenStore,
    initDone:    Option[Promise[Nothing, Unit]] = None,
  ): ZIO[ZIORepositories, JorlanError, Routes[ZIORepositories & JorlanSession, Nothing]] = {
    val catchAll = Routes(
      Method.ANY / trailing -> handler {
        (
          _: Path,
          _: Request,
        ) =>
          ZIO.succeed(notInitializedResponse)
      },
    )
    for {
      statusR <- StatusRoutes(startTime).api
    } yield {

      val initR: Routes[Any, JorlanError] = Routes(
        Method.POST / "api" / "init" -> handler { (req: Request) =>
          val isLocalhost = req.remoteAddress.exists(_.isLoopbackAddress)
          for {
            body   <- req.body.asString.mapError(JorlanError.apply)
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
                        case e: JorlanError =>
                          ZIO.succeed(
                            Response(
                              Status.Forbidden,
                              body = Body.fromString(Map("error" -> e.getMessage).toJson),
                            ),
                          )
                      },
                      _ => ZIO.foreachDiscard(initDone)(_.succeed(())).as(Response.json("""{"success":true}""")),
                    )
                } yield resp
            }
          } yield result
        },
      )

      statusR ++ initR ++ catchAll
    }.handleErrorCauseZIO(Jorlan.mapError)
  }

}
