/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.routes

import _root_.auth.{AuthConfig, AuthServer}
import jorlan.*
import jorlan.graphql.JorlanAPI
import zio.*
import zio.http.*
import zio.nio.file.Files

object HealthRoutes extends AppRoutes[Any, Any, Nothing] {

  override def unauth: ZIO[Any, Nothing, Routes[Any, Nothing]] =
    ZIO.succeed(
      Routes(
        Method.GET / "health"                     -> Handler.ok,
        Method.GET / "unauth" / "unauthtest.html" -> handler((_: Request) =>
          Handler.html(s"<html>Test Unauth Ok!</html>"),
        ).flatten,
      ),
    )

}
