/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
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
