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
import jorlan.db.FlywayMigration
import jorlan.db.repository.QuillRepositories
import jorlan.service.*
import zio.{ULayer, URLayer, ZIO, ZLayer, durationInt}

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

  val live: ULayer[JorlanEnvironment] =
    ZLayer
      .make[JorlanEnvironment](
        ConfigurationServiceImpl.live,
        databaseConfigLayer,
        flywayConfigLayer,
        langChainConfigLayer,
        FlywayMigration.live,
        QuillRepositories.live,
        EventLogServiceImpl.live,
        UserServiceImpl.live,
        PermissionServiceImpl.live,
        RiskClassifierImpl.live,
        CapabilityEvaluatorImpl.live,
        ApprovalPolicyEngineImpl.live,
        ApprovalServiceImpl.live,
        JorlanAuthServer.live,
        authConfigLayer,
        oauthServiceLayer,
        OAuthStateStore.live(),
        SessionHub.live,
        OllamaModelGateway.live,
        AgentSessionManagerImpl.live,
        AgentRunnerImpl.live,
        PersonalityServiceImpl.live,
      ).orDie

}
