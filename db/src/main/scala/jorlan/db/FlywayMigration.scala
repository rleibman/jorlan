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

import jorlan.{AppConfig, ConfigurationService}
import org.flywaydb.core.Flyway
import zio.{Cause, UIO, URIO, ZIO, ZLayer}

import scala.language.unsafeNulls

trait FlywayMigration {

  def migrate:  UIO[Unit]
  def validate: UIO[Unit]
  def info:     UIO[Unit]

}

object FlywayMigration {

  val live: ZLayer[ConfigurationService, Nothing, FlywayMigration] =
    ZLayer.fromZIO {
      ZIO
        .serviceWithZIO[ConfigurationService](_.appConfig)
        .orDie
        .map { config =>
          val flyway = createFlyway(config)
          FlywayMigrationLive(flyway, config)
        }
    }

  val runMigrations: URIO[FlywayMigration, Unit] =
    ZIO.serviceWithZIO[FlywayMigration](_.migrate)

  private def createFlyway(config: AppConfig): Flyway = {
    val flywayConfig = config.jorlan.flyway
    val fb = Flyway
      .configure()
      .dataSource(config.dataSource)
      .locations(flywayConfig.locations*)
      .cleanDisabled(flywayConfig.cleanDisabled)
      .validateOnMigrate(flywayConfig.validateOnMigrate)
      .mixed(flywayConfig.mixed)
      .baselineOnMigrate(flywayConfig.baselineOnMigrate)
      .baselineVersion(flywayConfig.baselineVersion)
      .baselineDescription(flywayConfig.baselineDescription)

    val withTarget =
      if (flywayConfig.target.nonEmpty) fb.target(flywayConfig.target)
      else fb

    withTarget.load()
  }

}

private case class FlywayMigrationLive(
  flyway: Flyway,
  config: AppConfig,
) extends FlywayMigration {

  override val migrate: UIO[Unit] =
    if (!config.jorlan.flyway.enabled) {
      ZIO.logInfo("Flyway migrations disabled — skipping")
    } else {
      ZIO.logInfo("Starting Flyway database migrations...") *>
        ZIO
          .attempt(flyway.migrate())
          .foldCauseZIO(
            cause => ZIO.logErrorCause("Flyway migration failed", cause).unit,
            result =>
              ZIO.logInfo(
                s"Flyway migrations complete: ${result.migrationsExecuted} executed, " +
                  s"target schema version ${result.targetSchemaVersion}",
              ),
          )
    }

  override val validate: UIO[Unit] =
    ZIO
      .attempt(flyway.validate())
      .foldCauseZIO(
        cause => ZIO.logErrorCause("Flyway validation failed", cause).unit,
        _ => ZIO.logInfo("Flyway validation passed"),
      )

  override val info: UIO[Unit] =
    ZIO
      .attempt(flyway.info().all().toList)
      .foldCauseZIO(
        cause => ZIO.logErrorCause("Flyway info failed", cause).unit,
        migrations =>
          ZIO.foreachDiscard(migrations) { m =>
            ZIO.logInfo(s"  [${m.getState}] V${m.getVersion} — ${m.getDescription}")
          },
      )

}
