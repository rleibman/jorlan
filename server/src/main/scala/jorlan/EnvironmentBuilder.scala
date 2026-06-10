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
import _root_.auth.oauth.{OAuthProviderConfig, OAuthService, OAuthStateStore}
import _root_.auth.{AuthConfig, AuthServer, SecretKey}
import jorlan.auth.JorlanAuthServer
import jorlan.connector.*
import jorlan.connector.telegram.*
import jorlan.db.FlywayMigration
import jorlan.db.repository.{QuillRepositories, ZIORepositories}
import jorlan.domain.*
import jorlan.service.*
import zio.http.Client
import zio.{ULayer, URLayer, ZIO, ZLayer, durationInt}

// $COVERAGE-OFF$ Layer wiring requires all external infrastructure (DB, model server) — not unit-testable
object EnvironmentBuilder {

  private val databaseConfigLayer: ZLayer[ConfigurationService, ConfigurationError, DatabaseConfig] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.db))

  private val flywayConfigLayer: ZLayer[ConfigurationService, ConfigurationError, FlywayConfig] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.flyway))

  private val langChainConfigLayer: ZLayer[ConfigurationService, ConfigurationError, LangChainConfig] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.ai))

  private val authConfigLayer: ZLayer[ConfigurationService, ConfigurationError, AuthConfig] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map { cfg =>
        val a = cfg.jorlan.auth
        AuthConfig(
          secretKey = SecretKey(a.secretKey),
          accessTTL = a.accessTtlMinutes.minutes,
          refreshTTL = a.refreshTtlDays.days,
        )
      },
    )

  private def toProviderConfig(s: OAuthProviderSettings): OAuthProviderConfig =
    OAuthProviderConfig(
      clientId = s.clientId,
      clientSecret = s.clientSecret,
      authorizationUri = s.authorizationUri,
      tokenUri = s.tokenUri,
      userInfoUri = s.userInfoUri,
      redirectUri = s.redirectUri,
      scopes = s.scopes,
    )

  private val oauthServiceLayer: ZLayer[ConfigurationService, ConfigurationError, OAuthService] =
    ZLayer
      .fromZIO(
        ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map { cfg =>
          val a = cfg.jorlan.auth
          OAuthService.live(
            googleConfig = a.google.map(toProviderConfig),
            githubConfig = a.github.map(toProviderConfig),
            discordConfig = a.discord.map(toProviderConfig),
          )
        },
      ).flatten

  private val liveConnectorManagerLayer: URLayer[ZIORepositories & MessageIngress & Client, ConnectorManager] =
    ZLayer.fromZIO {
      for {
        skillRepo  <- ZIO.serviceWith[ZIORepositories](_.skill)
        ingress    <- ZIO.service[MessageIngress]
        httpClient <- ZIO.service[Client]
        connectors <- skillRepo
          .searchConnectors(ConnectorSearch())
          .mapError(e => new RuntimeException(e.msg))
          .orDie
        telegramSkills <- ZIO.foreach(connectors.filter(_.connectorType == ConnectorType.Telegram)) { ci =>
          ZIO
            .fromEither(ci.configJson.as[TelegramConfig])
            .foldZIO(
              err => ZIO.logWarning(s"[connector:${ci.id}] Failed to parse TelegramConfig: $err").as(None),
              cfg => {
                val apiClient = TelegramApiClientLive(cfg, httpClient)
                TelegramConnectorSkill.make(cfg, ci.id, apiClient, ingress).map(Some(_))
              },
            )
        }
      } yield ConnectorManager.fromSkills(telegramSkills.flatten)
    }

  val live: ULayer[JorlanEnvironment] =
    ZLayer
      .make[JorlanEnvironment](
        ConfigurationServiceImpl.live,
        databaseConfigLayer,
        flywayConfigLayer,
        langChainConfigLayer,
        FlywayMigration.live,
        QuillRepositories.live,
        CapabilityEvaluatorImpl.live,
        ApprovalServiceImpl.live,
        JorlanAuthServer.live,
        authConfigLayer,
        oauthServiceLayer,
        OAuthStateStore.live(),
        SessionHub.live,
        OllamaModelGateway.live,
        AgentSessionManagerImpl.live,
        MemoryClassifierImpl.live,
        MemoryAccessPolicyImpl.live,
        CheckpointSummarizerImpl.live,
        ZLayer.succeed(CheckpointPolicy.onSessionEnd),
        MemoryServiceImpl.live,
        MemorySkill.live,
        AgentRunnerImpl.live,
        JobManagerImpl.live,
        TriggerEngine.live,
        MessageIngressImpl.live,
        liveConnectorManagerLayer,
        Client.default,
        // TODO Phase 12: wire SchedulerSkill.live once SkillRegistry is available
      ).orDie

}
// $COVERAGE-ON$
