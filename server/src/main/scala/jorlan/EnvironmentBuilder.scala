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

import jorlan.db.repository.QuillRepositories
import jorlan.db.{ConfigurationServiceImpl, FlywayMigration}
import jorlan.service.{EventLogService, EventLogServiceImpl}
import zio.{ULayer, ZLayer}

/** Assembles the production ZIO environment layer for the [[Jorlan]] application.
  *
  * Add new service layers here as additional subsystems are introduced. The `ZLayer.make` macro resolves the dependency
  * graph and will fail at compile time if any layer is missing.
  */
object EnvironmentBuilder {

  val live: ULayer[JorlanEnvironment] =
    ZLayer.make[JorlanEnvironment](
      ConfigurationServiceImpl.live,
      FlywayMigration.live,
      QuillRepositories.live,
      EventLogServiceImpl.live,
    )

}
