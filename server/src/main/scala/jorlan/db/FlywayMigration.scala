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
        withFlyway(flywayConfig, dbConfig)(_.migrate())
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
    withFlyway(flywayConfig, dbConfig)(_.validate()) *> ZIO.logInfo("Flyway validation passed")

  def info(
    flywayConfig: FlywayConfig,
    dbConfig:     DatabaseConfig,
  ): Task[Unit] =
    withFlyway(flywayConfig, dbConfig)(_.info().all().toList)
      .flatMap { migrations =>
        ZIO.foreachDiscard(migrations) { m =>
          ZIO.logInfo(s"  [${m.getState}] V${m.getVersion} — ${m.getDescription}")
        }
      }

  /** Runs one Flyway command against a pool that exists only for as long as the command does.
    *
    * `createFlyway` used to call `makeDataSource` inline, which hands Flyway an *unmanaged* HikariDataSource that
    * nobody ever closes. Migration is a startup thing, but the pool it opened was not: it kept `minimumIdle`
    * connections alive, and its housekeeping thread kept the pool itself from ever being collected, for the entire
    * life of the process — a second pool, permanently, next to the application's real one.
    */
  private def withFlyway[A](
    flywayConfig: FlywayConfig,
    dbConfig:     DatabaseConfig,
  )(
    command: Flyway => A,
  ): Task[A] =
    ZIO.scoped {
      managedDataSource(dbConfig).flatMap(ds => ZIO.attempt(command(createFlyway(flywayConfig, ds))))
    }

  private def createFlyway(
    flywayConfig: FlywayConfig,
    dataSource:   javax.sql.DataSource,
  ): Flyway = {
    val fb = Flyway
      .configure()
      .dataSource(dataSource)
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
