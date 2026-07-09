/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.db

import com.typesafe.config.ConfigFactory
import jorlan.AppConfig
import org.flywaydb.core.Flyway
import zio.*

import java.io.File
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

/** Standalone utility to repair Flyway schema history.
  *
  * Run this when you get validation errors about migration description mismatches. This updates the
  * flyway_schema_history table to match the current migration files.
  *
  * Usage: sbt "project server" "runMain jorlan.db.FlywayRepair"
  */
object FlywayRepair extends ZIOAppDefault {

  /** Load config without the full startup env-var validation — this tool only needs DB + Flyway settings. */
  private val loadConfig: Task[AppConfig] =
    ZIO
      .attempt {
        // Provide a dummy auth key so the config schema can be decoded; FlywayRepair never uses it.
        val fallback = ConfigFactory.parseString("jorlan.auth.secretKey = dummy-repair-key")
        val raw = Option(java.lang.System.getProperty("application.conf"))
          .map(path => ConfigFactory.parseFile(File(path)).withFallback(ConfigFactory.load()).resolve())
          .getOrElse(ConfigFactory.load().resolve())
        fallback.withFallback(raw).resolve()
      }
      .flatMap(AppConfig.read)

  override def run: ZIO[Any, Any, Any] =
    (for {
      _      <- ZIO.logInfo("Starting Flyway repair...")
      config <- loadConfig
      flyway <- ZIO.attempt(
        Flyway
          .configure()
          .dataSource(makeDataSource(config.jorlan.db))
          .locations(config.jorlan.flyway.locations*)
          .cleanDisabled(config.jorlan.flyway.cleanDisabled)
          .baselineOnMigrate(config.jorlan.flyway.baselineOnMigrate)
          .baselineVersion(config.jorlan.flyway.baselineVersion)
          .baselineDescription(config.jorlan.flyway.baselineDescription)
          .load(),
      )
      _      <- ZIO.logInfo("Running Flyway repair to fix schema history mismatches...")
      result <- ZIO.attempt(flyway.repair())
      _      <- ZIO.logInfo(
        s"Flyway repair completed successfully!\n" +
          s"  Removed failed migrations: ${result.migrationsRemoved.size()}\n" +
          s"  Deleted missing migrations: ${result.migrationsDeleted.size()}\n" +
          s"  Aligned applied migrations: ${result.migrationsAligned.size()}\n" +
          s"\nYou can now start the application normally.",
      )
      _ <- ZIO.logInfo("\nRepair Details:")
      _ <- ZIO.foreachDiscard(result.repairActions.asScala)(action => ZIO.logInfo(s"  - $action"))
    } yield ())
      .tapError(error =>
        ZIO.logError(s"Flyway repair failed: ${error.getMessage}") *>
          ZIO.logErrorCause("Repair error details:", Cause.fail(error)),
      )

}
