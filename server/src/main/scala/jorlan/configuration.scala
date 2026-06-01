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

import _root_.ai.LangChainConfig
import com.typesafe.config.{Config as TypesafeConfig, ConfigFactory}
import zio.config.magnolia.DeriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.{IO, UIO, ZIO, ZLayer}

import java.io.File
import scala.language.unsafeNulls

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

/** Root server configuration, assembled from all module configs. */
case class JorlanConfig(
  db:     DatabaseConfig,
  flyway: FlywayConfig = FlywayConfig(),
  http:   HttpConfig = HttpConfig(),
  auth:   AuthSettings,
  ai:     LangChainConfig = LangChainConfig(),
)

/** Root application configuration. */
case class AppConfig(jorlan: JorlanConfig)

object AppConfig {

  def read(typesafeConfig: TypesafeConfig): UIO[AppConfig] =
    TypesafeConfigProvider
      .fromTypesafeConfig(typesafeConfig)
      .load(DeriveConfig.derived[AppConfig].desc)
      .orDie

}

// $COVERAGE-OFF$
sealed abstract class ConfigurationError(
  override val msg:   String,
  override val cause: Option[Throwable] = None,
) extends JorlanError(msg, cause)

case class ConfigLoadError(
  override val msg:   String,
  override val cause: Option[Throwable] = None,
) extends ConfigurationError(msg, cause)

// $COVERAGE-ON$

trait ConfigurationService {

  def appConfig: IO[ConfigurationError, AppConfig]

}

object ConfigurationServiceImpl {

  private val requiredEnvVars: List[(String, String)] = List(
    "JORLAN_AUTH_SECRET_KEY" -> "JWT signing secret (generate with: openssl rand -hex 32)",
  )

  private val validateEnvVars: IO[ConfigLoadError, Unit] = {
    val missing = requiredEnvVars.filter { case (key, _) =>
      Option(System.getenv(key)).forall(_.nn.isBlank)
    }
    if (missing.isEmpty) ZIO.unit
    else {
      val lines = missing.map { case (k, desc) => s"  $k — $desc" }.mkString("\n")
      ZIO.fail(
        ConfigLoadError(
          s"""|Jorlan cannot start: missing required environment variables.
              |
              |$lines
              |
              |Copy .env.example to .env, fill in the values, and source it before starting:
              |  cp .env.example .env
              |  # edit .env
              |  source .env
              |""".stripMargin,
        ),
      )
    }
  }

  val live: ZLayer[Any, Nothing, ConfigurationService] = ZLayer.succeed(new ConfigurationService {

    override val appConfig: IO[ConfigurationError, AppConfig] =
      validateEnvVars *>
        ZIO
          .attempt {
            Option(System.getProperty("application.conf"))
              .map(path => ConfigFactory.parseFile(new File(path)).withFallback(ConfigFactory.load()).resolve())
              .getOrElse(ConfigFactory.load().resolve())
          }
          .mapError(e => ConfigLoadError(e.getMessage.nn, Some(e)))
          .flatMap(AppConfig.read)

  })

}
