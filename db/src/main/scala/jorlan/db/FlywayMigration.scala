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
import zio.{Task, UIO, URIO, ZIO, ZLayer}

import scala.language.unsafeNulls

/** ZIO service that manages Flyway database schema migrations.
  *
  * Consumers should use [[FlywayMigration.runMigrations]] rather than obtaining the service directly — that accessor is
  * a one-liner suitable for the application startup sequence.
  */
trait FlywayMigration {

  /** Apply all pending Flyway migrations. Fails with a `Throwable` if Flyway reports an error, aborting startup. */
  def migrate: Task[Unit]

  /** Validate that applied migrations match the classpath — useful in staging after a deployment. Fails on mismatch. */
  def validate: Task[Unit]

  /** Log the full migration history (state, version, description) at INFO level. */
  def info: Task[Unit]

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

  /** Convenience accessor: runs `migrate` from the ZIO environment. Intended for the startup sequence:
    * `_ <- FlywayMigration.runMigrations`.
    */
  val runMigrations: ZIO[FlywayMigration, Throwable, Unit] =
    ZIO.serviceWithZIO[FlywayMigration](_.migrate)

  private def createFlyway(config: AppConfig): Flyway = {
    val flywayConfig = config.jorlan.flyway
    val fb = Flyway
      .configure()
      .dataSource(makeDataSource(config))
      .locations(flywayConfig.locations*)
      .cleanDisabled(flywayConfig.cleanDisabled)
      .validateOnMigrate(flywayConfig.validateOnMigrate)
      .mixed(flywayConfig.mixed)
      .baselineOnMigrate(flywayConfig.baselineOnMigrate)
      .baselineVersion(flywayConfig.baselineVersion)
      .baselineDescription(flywayConfig.baselineDescription)

    val withTarget = flywayConfig.target.fold(fb)(t => fb.target(t))

    withTarget.load()
  }

}

private case class FlywayMigrationLive(
  flyway: Flyway,
  config: AppConfig,
) extends FlywayMigration {

  override val migrate: Task[Unit] =
    if (!config.jorlan.flyway.enabled) {
      ZIO.logInfo("Flyway migrations disabled — skipping")
    } else {
      ZIO.logInfo("Starting Flyway database migrations...") *>
        ZIO
          .attempt(flyway.migrate())
          .flatMap { result =>
            ZIO.logInfo(
              s"Flyway migrations complete: ${result.migrationsExecuted} executed, " +
                s"target schema version ${result.targetSchemaVersion}",
            )
          }
    }

  override val validate: Task[Unit] =
    ZIO.attempt(flyway.validate()) *> ZIO.logInfo("Flyway validation passed")

  override val info: Task[Unit] =
    ZIO
      .attempt(flyway.info().all().toList)
      .flatMap { migrations =>
        ZIO.foreachDiscard(migrations) { m =>
          ZIO.logInfo(s"  [${m.getState}] V${m.getVersion} — ${m.getDescription}")
        }
      }

}
