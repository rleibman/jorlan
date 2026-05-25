/*
 * Copyright (c) 2025 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import jorlan.db.FlywayMigration
import zio.http.*
import zio.logging.backend.SLF4J
import zio.{EnvironmentTag, Runtime, Scope, ZIO, ZIOApp, ZIOAppArgs, ZLayer}

type JorlanEnvironment = ConfigurationService & FlywayMigration

object Jorlan extends ZIOApp {

  override type Environment = JorlanEnvironment

  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> EnvironmentBuilder.live

  private val healthRoutes: Routes[Any, Response] = Routes(
    Method.GET / "health" -> Handler.ok,
  )

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Throwable, Unit] =
    for {
      config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie
      _      <- FlywayMigration.runMigrations
      _      <- ZIO.logInfo(s"Jorlan starting on ${config.jorlan.http.host}:${config.jorlan.http.port}")
      _ <- Server
        .serve(healthRoutes)
        .provideSomeLayer(Server.defaultWithPort(config.jorlan.http.port))
    } yield ()

}
