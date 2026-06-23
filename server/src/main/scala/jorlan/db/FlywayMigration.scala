/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.db

import jorlan.{DatabaseConfig, FlywayConfig}
import org.flywaydb.core.Flyway
import zio.{Task, ZIO}

import scala.language.unsafeNulls

/** Runs Flyway database schema migrations as plain effects — no ZIO service required.
  *
  * Call [[FlywayMigration.migrate]] once during application startup, passing the resolved config values.
  */
object FlywayMigration {

  def migrate(
    flywayConfig: FlywayConfig,
    dbConfig:     DatabaseConfig,
  ): Task[Unit] =
    if (!flywayConfig.enabled) {
      ZIO.logInfo("Flyway migrations disabled — skipping")
    } else {
// $COVERAGE-OFF$
      ZIO.logInfo("Starting Flyway database migrations...") *>
        ZIO
          .attempt(createFlyway(flywayConfig, dbConfig).migrate())
          .flatMap { result =>
            ZIO.logInfo(
              s"Flyway migrations complete: ${result.migrationsExecuted} executed, " +
                s"target schema version ${result.targetSchemaVersion}",
            )
          }
// $COVERAGE-ON$
    }

  def validate(
    flywayConfig: FlywayConfig,
    dbConfig:     DatabaseConfig,
  ): Task[Unit] =
    ZIO.attempt(createFlyway(flywayConfig, dbConfig).validate()) *> ZIO.logInfo("Flyway validation passed")

  def info(
    flywayConfig: FlywayConfig,
    dbConfig:     DatabaseConfig,
  ): Task[Unit] =
    ZIO
      .attempt(createFlyway(flywayConfig, dbConfig).info().all().toList)
      .flatMap { migrations =>
        ZIO.foreachDiscard(migrations) { m =>
          ZIO.logInfo(s"  [${m.getState}] V${m.getVersion} — ${m.getDescription}")
        }
      }

  private def createFlyway(
    flywayConfig: FlywayConfig,
    dbConfig:     DatabaseConfig,
  ): Flyway = {
    val fb = Flyway
      .configure()
      .dataSource(makeDataSource(dbConfig))
      .locations(flywayConfig.locations*)
      .cleanDisabled(flywayConfig.cleanDisabled)
      .validateOnMigrate(flywayConfig.validateOnMigrate)
      .mixed(flywayConfig.mixed)
      .baselineOnMigrate(flywayConfig.baselineOnMigrate)
      .baselineVersion(flywayConfig.baselineVersion)
      .baselineDescription(flywayConfig.baselineDescription)

    val withTarget = flywayConfig.target.filter(_.nonEmpty).fold(fb)(t => fb.target(t))

    withTarget.load()
  }

}
