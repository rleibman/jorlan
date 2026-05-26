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

import jorlan.db.FlywayMigration
import zio.http.*
import zio.logging.backend.SLF4J
import zio.{EnvironmentTag, Runtime, Scope, ZIO, ZIOApp, ZIOAppArgs, ZLayer}

/** ZIO environment type required by the main application. */
type JorlanEnvironment = ConfigurationService & FlywayMigration

/** Main entry point for the Jorlan server.
  *
  * Startup sequence:
  *   1. Resolve configuration from `application.conf`.
  *   2. Run Flyway schema migrations.
  *   3. Start the zio-http server on the configured port.
  *
  * A `GET /health` route is always registered; additional routes will be added as subsystems are wired in.
  */
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
