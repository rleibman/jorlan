/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
          Method.GET / "health" -> Handler.ok,
        Method.GET / "unauth" / "unauthtest.html" -> handler((_: Request) =>
          Handler.html(s"<html>Test Unauth Ok!</html>"),
        ).flatten,
      ),
    )

}
