/*
 * Copyright (c) 2025 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import com.typesafe.config.ConfigFactory
import jorlan.{AppConfig, ConfigurationError, ConfigurationService}
import zio.{IO, ULayer, ZLayer}

import java.io.File
import scala.language.unsafeNulls

object ConfigurationServiceImpl {

  val live: ULayer[ConfigurationService] = ZLayer.succeed(new ConfigurationService {

    lazy override val appConfig: IO[ConfigurationError, AppConfig] = {
      val confFileName =
        Option(System.getProperty("application.conf"))
          .getOrElse("./src/main/resources/application.conf")
      val confFile = new File(confFileName)
      // AppConfig.read calls .orDie internally so config errors become defects,
      // keeping the return type compatible with IO[ConfigurationError, AppConfig].
      AppConfig.read(
        ConfigFactory
          .parseFile(confFile)
          .withFallback(ConfigFactory.load())
          .resolve(),
      )
    }

  })

}
