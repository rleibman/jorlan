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
import _root_.auth.{AuthConfig, SecretKey}
import com.typesafe.config.{ConfigFactory, Config as TypesafeConfig}
import zio.config.magnolia.DeriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.{Duration, IO, UIO, ZIO, ZLayer}

import java.io.File
import scala.language.unsafeNulls

given DeriveConfig[SecretKey] = DeriveConfig[String].map(SecretKey(_))
given DeriveConfig[Duration] = DeriveConfig[Long].map(Duration.fromMillis)

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

/** Agent runtime configuration. */
case class AgentSettings(
  maxToolSteps: Int = 10,
)

/** Durable scheduler configuration. */
case class SchedulerSettings(
  pollIntervalSeconds: Int = 10,
  leaseTtlSeconds:     Int = 300,
  jobTimeoutSeconds:   Int = 300,
  listJobsLimit:       Int = 200,
)

/** How workspace paths are scoped per invocation. */
enum WorkspaceScope {

  case Flat, Session, User

}

/** Workspace filesystem configuration. */
case class WorkspaceSettings(
  root:         String = "/var/lib/jorlan/workspaces",
  defaultScope: WorkspaceScope = WorkspaceScope.Session,
)

/** Shell execution configuration.
  *
  * @param allowedBinaries
  *   Comma-separated list of absolute paths or bare binary names permitted for `shell.run`. Empty = all denied.
  * @param timeoutSeconds
  *   Maximum wall-clock time for a shell command before it is killed.
  * @param captureThreshold
  *   Stdout byte length above which the output is stored as an Artifact instead of inlined in the tool result.
  */
case class ShellSettings(
  allowedBinaries:  List[String] = List("echo", "ls", "cat", "grep", "find", "pwd"),
  timeoutSeconds:   Int = 30,
  captureThreshold: Int = 65536,
)

case class WebConfig(
  root: String = "/opt/jorlan/www",
)

/** Root server configuration, assembled from all module configs. */
case class JorlanConfig(
  db:        DatabaseConfig,
  auth:      AuthConfig,
  flyway:    FlywayConfig = FlywayConfig(),
  http:      HttpConfig = HttpConfig(),
  ai:        LangChainConfig = LangChainConfig(),
  agent:     AgentSettings = AgentSettings(),
  scheduler: SchedulerSettings = SchedulerSettings(),
  workspace: WorkspaceSettings = WorkspaceSettings(),
  shell:     ShellSettings = ShellSettings(),
  web:       WebConfig = WebConfig(),
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
    {
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
    }.unless(missing.isEmpty).unit
  }

  val live: ZLayer[Any, Nothing, ConfigurationService] = ZLayer.succeed(new ConfigurationService {

    override val appConfig: IO[ConfigurationError, AppConfig] =
      validateEnvVars *>
        ZIO
          .attempt {
            Option(System.getProperty("application.conf"))
              .map(path => ConfigFactory.parseFile(File(path)).withFallback(ConfigFactory.load()).resolve())
              .getOrElse(ConfigFactory.load().resolve())
          }
          .mapError(e => ConfigLoadError(e.getMessage.nn, Some(e)))
          .flatMap(AppConfig.read)

  })

}
