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

import com.dimafeng.testcontainers.MariaDBContainer
import jorlan.db.repository.{
  AgentRepository,
  ArtifactRepository,
  ConversationRepository,
  EventLogRepository,
  MemoryRepository,
  PermissionRepository,
  QuillRepositories,
  SchedulerRepository,
  SkillRepository,
  UserRepository,
}
import jorlan.{
  AppConfig,
  ConfigurationError,
  ConfigurationService,
  DataSourceConfig,
  DatabaseConfig,
  FlywayConfig,
  HttpConfig,
  JorlanConfig,
}
import org.flywaydb.core.Flyway
import zio.*

import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

object JorlanContainer {

  private val containerLayer: TaskLayer[MariaDBContainer] = ZLayer.fromZIO(
    ZIO.attemptBlocking {
      val c = MariaDBContainer()
      c.container.setPortBindings(List("3308:3306").asJava)
      c.container.start()
      c
    },
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
          ),
        ),
        flyway = FlywayConfig(enabled = false),
        http = HttpConfig(),
      ),
    )

  val configLayer: TaskLayer[ConfigurationService] = ZLayer
    .fromZIO(
      ZIO.serviceWithZIO[MariaDBContainer] { container =>
        migrateWithFlyway(container).map { _ =>
          val config = makeConfig(container)
          new ConfigurationService {
            override val appConfig: IO[ConfigurationError, AppConfig] = ZIO.succeed(config)
          }
        }
      },
    ).provideSome[Any](containerLayer)

  val repositoryLayer: TaskLayer[
    UserRepository & AgentRepository & ConversationRepository & SkillRepository & MemoryRepository &
      EventLogRepository & SchedulerRepository & ArtifactRepository & PermissionRepository,
  ] =
    configLayer >>> QuillRepositories.live.orDie

}
