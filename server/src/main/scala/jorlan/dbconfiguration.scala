/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

/** JDBC connection-pool settings, mapped directly from `application.conf`. */
case class DataSourceConfig(
  driver:                  String,
  url:                     String,
  user:                    String,
  password:                String,
  maximumPoolSize:         Int = 10,
  minimumIdle:             Int = 2,
  connectionTimeoutMillis: Long = 30000,
  idleTimeoutMillis:       Long = 600000,
  keepaliveTimeMillis:     Long = 300000,
)

case class DatabaseConfig(dataSource: DataSourceConfig)

/** Flyway schema-migration settings.
  *
  * @param target
  *   If set, Flyway will migrate only up to this version (useful for partial rollout). `None` means migrate to latest.
  * @param cleanDisabled
  *   `true` by default — prevents accidental `flyway.clean()` in production.
  */
case class FlywayConfig(
  locations:           List[String] = List("classpath:sql"),
  enabled:             Boolean = true,
  cleanDisabled:       Boolean = true,
  validateOnMigrate:   Boolean = true,
  mixed:               Boolean = false,
  target:              Option[String] = None,
  baselineOnMigrate:   Boolean = true,
  baselineVersion:     String = "0",
  baselineDescription: String = "Initial",
)
