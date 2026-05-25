/*
 * Copyright (c) 2025 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import com.typesafe.config.Config as TypesafeConfig
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import zio.config.magnolia.DeriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.{IO, UIO, ZIO}

import scala.language.unsafeNulls

case class DataSourceConfig(
  driver:                  String,
  url:                     String,
  user:                    String,
  password:                String,
  maximumPoolSize:         Int = 10,
  minimumIdle:             Int = 2,
  connectionTimeoutMillis: Long = 30000,
)

case class DatabaseConfig(dataSource: DataSourceConfig)

case class FlywayConfig(
  locations:           List[String] = List("classpath:sql"),
  enabled:             Boolean = true,
  cleanDisabled:       Boolean = true,
  validateOnMigrate:   Boolean = true,
  mixed:               Boolean = false,
  target:              String = "",
  baselineOnMigrate:   Boolean = true,
  baselineVersion:     String = "0",
  baselineDescription: String = "Initial",
)

case class HttpConfig(
  host: String = "0.0.0.0",
  port: Int = 8080,
)

case class JorlanConfig(
  db:     DatabaseConfig,
  flyway: FlywayConfig = FlywayConfig(),
  http:   HttpConfig = HttpConfig(),
)

case class AppConfig(jorlan: JorlanConfig) {

  lazy val dataSource: HikariDataSource = {
    val hc = new HikariConfig()
    hc.setDriverClassName(jorlan.db.dataSource.driver)
    hc.setJdbcUrl(jorlan.db.dataSource.url)
    hc.setUsername(jorlan.db.dataSource.user)
    hc.setPassword(jorlan.db.dataSource.password)
    hc.setMaximumPoolSize(jorlan.db.dataSource.maximumPoolSize)
    hc.setMinimumIdle(jorlan.db.dataSource.minimumIdle)
    hc.setConnectionTimeout(jorlan.db.dataSource.connectionTimeoutMillis)
    hc.setAutoCommit(true)
    new HikariDataSource(hc)
  }

}

object AppConfig {

  def read(typesafeConfig: TypesafeConfig): UIO[AppConfig] =
    TypesafeConfigProvider
      .fromTypesafeConfig(typesafeConfig)
      .load(DeriveConfig.derived[AppConfig].desc)
      .orDie

}

sealed abstract class ConfigurationError(message: String, cause: Throwable | Null = null)
  extends Exception(message, cause)

case class ConfigLoadError(msg: String, rootCause: Throwable | Null = null)
  extends ConfigurationError(msg, rootCause)

trait ConfigurationService {

  def appConfig: IO[ConfigurationError, AppConfig]

}
