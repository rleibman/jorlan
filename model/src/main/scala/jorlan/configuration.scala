/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import com.typesafe.config.Config as TypesafeConfig
import zio.config.magnolia.DeriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.{IO, UIO, ZIO}

import scala.language.unsafeNulls

/** JDBC connection-pool settings, mapped directly from `application.conf`. */
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

case class HttpConfig(
  host: String = "0.0.0.0",
  port: Int = 8080,
)

/** Raw config for a single OAuth 2.0 provider, read from `application.conf`. Converted to
  * `auth.oauth.OAuthProviderConfig` during environment assembly.
  */
case class OAuthProviderSettings(
  clientId:         String,
  clientSecret:     String,
  authorizationUri: String,
  tokenUri:         String,
  userInfoUri:      String,
  redirectUri:      String,
  scopes:           List[String],
)

/** Authentication and session configuration. */
case class AuthSettings(
  secretKey:        String,
  accessTtlMinutes: Int = 60,
  refreshTtlDays:   Int = 30,
  google:           Option[OAuthProviderSettings] = None,
  github:           Option[OAuthProviderSettings] = None,
  discord:          Option[OAuthProviderSettings] = None,
)

case class JorlanConfig(
  db:     DatabaseConfig,
  flyway: FlywayConfig = FlywayConfig(),
  http:   HttpConfig = HttpConfig(),
  auth:   AuthSettings,
)

/** Root application configuration. Wraps all subsystem configs. The connection pool is created in the `db` module. */
case class AppConfig(jorlan: JorlanConfig)

object AppConfig {

  /** Derives an [[AppConfig]] from a Typesafe `Config` tree using `zio-config-magnolia`. Any derivation failure is
    * treated as an unrecoverable defect (`orDie`).
    */
  def read(typesafeConfig: TypesafeConfig): UIO[AppConfig] =
    TypesafeConfigProvider
      .fromTypesafeConfig(typesafeConfig)
      .load(DeriveConfig.derived[AppConfig].desc)
      .orDie

}

/** Base error type for configuration loading failures. */
sealed abstract class ConfigurationError(
  override val msg:   String,
  override val cause: Option[Throwable] = None,
) extends JorlanError(msg, cause)

case class ConfigLoadError(
  override val msg:   String,
  override val cause: Option[Throwable] = None,
) extends ConfigurationError(msg, cause)

/** ZIO service that provides the resolved [[AppConfig]]. Implementations may load from the classpath, environment
  * variables, or a test fixture.
  */
trait ConfigurationService {

  def appConfig: IO[ConfigurationError, AppConfig]

}
