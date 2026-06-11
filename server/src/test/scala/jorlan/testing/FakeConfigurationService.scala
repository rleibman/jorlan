/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.testing

import _root_.auth.{AuthConfig, SecretKey}
import jorlan.*
import zio.*

/** Minimal [[ConfigurationService]] for unit tests that exercise services depending on [[ConfigurationService]] but
  * cannot use [[ConfigurationServiceImpl.live]] (which requires the JORLAN_AUTH_SECRET_KEY env var).
  */
object FakeConfigurationService {

  val defaultConfig: AppConfig = AppConfig(
    jorlan = JorlanConfig(
      db = DatabaseConfig(
        DataSourceConfig(
          driver = "org.mariadb.jdbc.Driver",
          url = "jdbc:mariadb://localhost:3306/jorlan_test",
          user = "test",
          password = "test",
        ),
      ),
      auth = AuthConfig(secretKey = SecretKey("test-secret-key-for-integration-tests")),
    ),
  )

  val layer: ULayer[ConfigurationService] = ZLayer.succeed(new ConfigurationService {
    override def appConfig: IO[ConfigurationError, AppConfig] = ZIO.succeed(defaultConfig)
  })

}
