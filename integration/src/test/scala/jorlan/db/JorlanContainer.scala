/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.db

import _root_.auth.{AuthConfig, SecretKey}
import com.dimafeng.testcontainers.MariaDBContainer
import jorlan.db.repository.*
import jorlan.*
import org.flywaydb.core.Flyway
import zio.*

import scala.language.unsafeNulls

object JorlanContainer {

  private val containerLayer: TaskLayer[MariaDBContainer] = ZLayer.scoped(
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val c = MariaDBContainer()
        c.container.start()
        c
      },
    )(c => ZIO.attemptBlocking(c.container.stop()).orDie),
  )

  private def migrateWithFlyway(container: MariaDBContainer): Task[Unit] =
    ZIO.attemptBlocking {
      Flyway
        .configure()
        .dataSource(
          container.container.getJdbcUrl,
          container.container.getUsername,
          container.container.getPassword,
        )
        .locations("classpath:sql")
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load()
        .migrate()
    }.unit

  private def makeConfig(container: MariaDBContainer): AppConfig =
    AppConfig(
      JorlanConfig(
        db = DatabaseConfig(
          dataSource = DataSourceConfig(
            driver = "org.mariadb.jdbc.Driver",
            url = container.container.getJdbcUrl,
            user = container.container.getUsername,
            password = container.container.getPassword,
            maximumPoolSize = 3,
            minimumIdle = 1,
          ),
        ),
        flyway = FlywayConfig(enabled = false),
        http = HttpConfig(),
        auth = AuthConfig(secretKey = SecretKey("test-secret-key-for-integration-tests")),
      ),
    )

  private val configFromContainerLayer: ZLayer[MariaDBContainer, Throwable, ConfigurationService] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[MariaDBContainer] { container =>
        migrateWithFlyway(container).as {
          val config = makeConfig(container)
          new ConfigurationService {
            override val appConfig: IO[ConfigurationError, AppConfig] = ZIO.succeed(config)
          }
        }
      },
    )

  val configLayer: TaskLayer[ConfigurationService] = containerLayer >>> configFromContainerLayer

  private val databaseConfigLayer: TaskLayer[DatabaseConfig] =
    configLayer >>> ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie.map(_.jorlan.db))

  val repositoryLayer: TaskLayer[ZIORepositories] =
    configLayer >>> QuillRepositories.live

}
