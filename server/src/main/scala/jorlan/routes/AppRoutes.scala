/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.routes

import jorlan.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.{IO, ZIO}

trait AppRoutes[-R, -SessionType, +E] {

  final protected def seeOther(location: String): IO[JorlanError, Response] =
    for {
      url <- ZIO.fromEither(URL.decode(location)).mapError(e => JorlanError(e))
    } yield Response(Status.SeeOther, Headers(Header.Location(url)))

  final protected def json(value: Json): Response = Response.json(value.toString)

  final protected def json[A: JsonEncoder](value: A): Response = Response.json(value.toJson)

  /** These routes represent the api, the are intended to be used thorough ajax-type calls they require a session
    */
  def api: ZIO[R, E, Routes[R & SessionType, E]] = ZIO.succeed(Routes.empty)

  def unauth: ZIO[R, E, Routes[R, E]] = ZIO.succeed(Routes.empty)

  def all: ZIO[R, E, Routes[R & SessionType, E]] =
    for {
      a <- api
      u <- unauth
    } yield a ++ u

}
