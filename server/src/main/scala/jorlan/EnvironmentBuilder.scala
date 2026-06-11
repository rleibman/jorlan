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

  private val agentSettingsLayer: ZLayer[ConfigurationService, ConfigurationError, AgentSettings] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.agent))

  private val workspaceSettingsLayer: ZLayer[ConfigurationService, ConfigurationError, WorkspaceSettings] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.workspace))

  private val shellSettingsLayer: ZLayer[ConfigurationService, ConfigurationError, ShellSettings] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.shell))

  // TODO, move this to something that happens AFTER the system is built.
  private val liveSkillRegistryLayer: ZLayer[
    CapabilityEvaluator & MemorySkill & SchedulerSkill & ShellSkill & WorkspaceSkill & ContactsSkill, // & NotifySkill,
    Nothing,
    SkillRegistry,
  ] =
    ZLayer.fromZIO {
      for {
//          notifySkill    <- ZIO.service[NotifySkill] //TODO... missing this skill!! but we get a circular dependency
        contactsSkill  <- ZIO.service[ContactsSkill]
        workspaceSkill <- ZIO.service[WorkspaceSkill]
        shellSkill     <- ZIO.service[ShellSkill]
        memorySkill    <- ZIO.service[MemorySkill]
        schedulerSkill <- ZIO.service[SchedulerSkill]
      } yield SkillRegistry.liveSecureWith(contactsSkill, workspaceSkill, shellSkill, memorySkill, schedulerSkill) // notifySkill,
    }.flatten

  // TODO THis is insane, building the environment shouldn't take these many layers.
  // Things should be grouped together, and in particular, skills shouldn't be in the environment as they should
  // be discoverable.
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
        MemoryServiceImpl.live,
        CheckpointSummarizerImpl.live,
        ZLayer.succeed(CheckpointPolicy.onSessionEnd),
        NotificationRouter.live,
        ////////////////////////////////////
        // Built in skills
        MemorySkill.live,
        SchedulerSkill.live,
//        NotifySkill.live, //TODO... missing this skill!! but we get a circular dependency
        ContactsSkill.live,
        WorkspaceSkill.live,
        ShellSkill.live,
        ////////////////////////////////////
        liveSkillRegistryLayer,
        AgentRunnerImpl.live,
        JobManagerImpl.live,
        TriggerEngine.live,
        MessageIngressImpl.live,
        liveConnectorManagerLayer,

        agentSettingsLayer,
        shellSettingsLayer,
        workspaceSettingsLayer,

        Client.default,
      ).orDie

}
// $COVERAGE-ON$
