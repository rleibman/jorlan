/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import com.typesafe.config.ConfigFactory
import jorlan.{AppConfig, ConfigLoadError, ConfigurationError, ConfigurationService}
import zio.{IO, ULayer, ZIO, ZLayer}

import java.io.File
import scala.language.unsafeNulls

/** Production [[ConfigurationService]] that reads from a Typesafe Config file.
  *
  * The config file path is resolved from the `application.conf` system property; if absent, classpath defaults via
  * `ConfigFactory.load()` are used. Config is merged with `ConfigFactory.load()` so environment-variable overrides
  * still apply.
  */
object ConfigurationServiceImpl {

  val live: ULayer[ConfigurationService] = ZLayer.succeed(new ConfigurationService {

    override val appConfig: IO[ConfigurationError, AppConfig] =
      ZIO
        .attempt {
          Option(System.getProperty("application.conf"))
            .map(path => ConfigFactory.parseFile(new File(path)).withFallback(ConfigFactory.load()).resolve())
            .getOrElse(ConfigFactory.load().resolve())
        }
        .mapError(e => ConfigLoadError(e.getMessage.nn, Some(e)))
        .flatMap(AppConfig.read)

  })

}
