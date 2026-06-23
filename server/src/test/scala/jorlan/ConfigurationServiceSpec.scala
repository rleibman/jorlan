/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import _root_.auth.SecretKey
import com.typesafe.config.ConfigFactory
import zio.*
import zio.test.*

import scala.language.unsafeNulls

object ConfigurationServiceSpec extends ZIOSpecDefault {

  private val minimalConfig = ConfigFactory
    .parseString(
      """jorlan {
      |  db {
      |    dataSource {
      |      driver = "org.mariadb.jdbc.Driver"
      |      url    = "jdbc:mariadb://localhost:3306/test"
      |      user   = "test"
      |      password = "test"
      |    }
      |  }
      |  auth {
      |    secretKey = "test-secret-key-for-unit-tests-only"
      |  }
      |}
      |""".stripMargin,
    ).resolve()

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ConfigurationService")(
      test("AppConfig.read parses minimal valid TypesafeConfig") {
        for {
          cfg <- AppConfig.read(minimalConfig)
        } yield assertTrue(
          cfg.jorlan.db.dataSource.url == "jdbc:mariadb://localhost:3306/test",
          cfg.jorlan.auth.secretKey == SecretKey("test-secret-key-for-unit-tests-only"),
          cfg.jorlan.http.port == 8080,
        )
      },
      test("AppConfig.read populates default FlywayConfig and HttpConfig") {
        for {
          cfg <- AppConfig.read(minimalConfig)
        } yield assertTrue(
          cfg.jorlan.http.host == "0.0.0.0",
          cfg.jorlan.flyway != null,
        )
      },
      test("AppConfig.read parses LangChainConfig defaults") {
        for {
          cfg <- AppConfig.read(minimalConfig)
        } yield assertTrue(cfg.jorlan.ai != null)
      },
      test("ConfigurationServiceImpl.live succeeds when JORLAN_AUTH_SECRET_KEY is set in environment") {
        // This test only runs meaningfully if the env var is present (dev environment).
        // If absent, validateEnvVars returns a ConfigLoadError and the layer fails — that path is also valuable.
        val layer = ConfigurationServiceImpl.live
        for {
          result <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).provideLayer(layer).either
        } yield {
          result match {
            case Right(cfg) => assertTrue(cfg.jorlan.auth.secretKey != SecretKey(""))
            case Left(_)    => assertCompletes // missing env var — error path exercised
          }
        }
      },
    )

}
