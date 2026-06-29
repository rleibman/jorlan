/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import _root_.auth.oauth.{OAuthProviderConfig, OAuthService, OAuthStateStore}
import _root_.auth.{AuthConfig, AuthServer, key}
import ai.{EmbeddingModel, EmbeddingStore, LangChainServiceBuilder}
import jorlan.*
import jorlan.auth.JorlanAuthServer
import jorlan.connector.*
import jorlan.db.repository.{QuillRepositories, ZIORepositories}
import jorlan.discord.{DiscordApiClientLive, DiscordConfig, DiscordConnectorSkill}
import jorlan.google.*
import jorlan.service.*
import jorlan.service.llm.OllamaModelGateway
import jorlan.service.skills.declarative.SkillLifecycleService
import jorlan.service.memory.MemoryServiceImpl
import jorlan.service.schedule.{JobManagerImpl, TriggerEngine}
import jorlan.service.skills.SkillRegistry
import jorlan.telegram.{TelegramApiClientLive, TelegramConfig, TelegramConnectorSkill}
import zio.http.Client
import zio.{ULayer, URLayer, ZIO, ZLayer}

import javax.sql.DataSource

// $COVERAGE-OFF$ Layer wiring requires all external infrastructure (DB, model server) — not unit-testable
object EnvironmentBuilder {

  private val dataSourceLayer: ZLayer[QuillRepositories, Nothing, DataSource] =
    ZLayer.fromZIO(ZIO.serviceWith[QuillRepositories](_.dataSourceLayer)).flatten

  private val oauthServiceLayer: ZLayer[ConfigurationService, ConfigurationError, OAuthService] =
    ZLayer
      .fromZIO(
        ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map { cfg =>
          val googleCfg = cfg.jorlan.google
          val discordCfg = cfg.jorlan.discordLogin
          val tgCfg = cfg.jorlan.telegramLogin

          val googleConfig = Option.when(googleCfg.clientId.nonEmpty)(
            OAuthProviderConfig(
              clientId = googleCfg.clientId,
              clientSecret = googleCfg.clientSecret,
              authorizationUri = "https://accounts.google.com/o/oauth2/v2/auth",
              tokenUri = "https://oauth2.googleapis.com/token",
              userInfoUri = "https://www.googleapis.com/oauth2/v3/userinfo",
              redirectUri = googleCfg.redirectUri,
              scopes = List("openid", "email", "profile"),
            ),
          )
          val discordConfig = Option.when(discordCfg.clientId.nonEmpty)(
            OAuthProviderConfig(
              clientId = discordCfg.clientId,
              clientSecret = discordCfg.clientSecret,
              authorizationUri = "https://discord.com/oauth2/authorize",
              tokenUri = "https://discord.com/api/oauth2/token",
              userInfoUri = "https://discord.com/api/users/@me",
              redirectUri = discordCfg.redirectUri,
              scopes = List("identify", "email"),
            ),
          )
          val telegramConfig = Option.when(tgCfg.botToken.nonEmpty)(
            OAuthProviderConfig(
              clientId = tgCfg.botUsername,
              clientSecret = tgCfg.botToken,
              authorizationUri = "",
              tokenUri = "",
              userInfoUri = "",
              redirectUri = tgCfg.redirectUri,
              scopes = List.empty,
            ),
          )

          OAuthService.live(
            googleConfig = googleConfig,
            discordConfig = discordConfig,
          )
        },
      ).flatten

  private val liveConnectorManagerLayer
    : ZLayer[ZIORepositories & MessageIngress & Client, JorlanError, ConnectorManager] =
    ZLayer.fromZIO {
      for {
        repos <- ZIO.service[ZIORepositories]
        skillRepo = repos.skill
        ingress    <- ZIO.service[MessageIngress]
        httpClient <- ZIO.service[Client]
        connectors <- skillRepo
          .searchConnectors(ConnectorSearch())
        telegramInstances = connectors.filter(_.connectorType == ConnectorType.Telegram)
        _ <- ZIO.logInfo(s"[connector] found ${telegramInstances.length} Telegram connector instance(s) in DB")
        // Parse configs, logging any that fail
        parsed <- ZIO.foreach(telegramInstances) { ci =>
          ZIO
            .fromEither(ci.configJson.as[TelegramConfig])
            .foldZIO(
              err => ZIO.logWarning(s"[connector:${ci.id}] Failed to parse TelegramConfig: $err").as(None),
              cfg => ZIO.some((ci, cfg)),
            )
        }
        // Deduplicate by bot token — two rows with the same token create two competing polling loops
        deduped = parsed.flatten.distinctBy(_._2.botToken)
        _ <- ZIO.when(deduped.length < parsed.flatten.length)(
          ZIO.logWarning(
            s"[connector] Skipping ${parsed.flatten.length - deduped.length} duplicate Telegram instance(s) with the same bot token",
          ),
        )
        // Resolver: look up a user's Telegram chatId by display name
        telegramNameResolver: jorlan.telegram.TelegramNameResolver = { name =>
          repos.user
            .search(UserSearch(fuzzyName = Some(name), active = Some(true))).mapError(JorlanError(_)).flatMap { users =>
              val ranked = jorlan.service.FuzzyNameMatch.rank(users, name)(_.displayName)
              ZIO
                .foreach(ranked.headOption) { user =>
                  repos.user
                    .getChannelIdentities(user.id).mapError(JorlanError(_)).map { ids =>
                      ids.find(_.channelType == ChannelType.Telegram).map(_.channelUserId)
                    }
                }.map(_.flatten)
            }
        }
        telegramSkills <- ZIO.foreach(deduped) { case (ci, cfg) =>
          ZIO.logInfo(s"[connector:${ci.id}] creating Telegram connector") *>
            TelegramConnectorSkill.make(
              cfg,
              ci.id,
              TelegramApiClientLive(cfg, httpClient),
              ingress,
              telegramNameResolver,
            )
        }
        discordInstances = connectors.filter(_.connectorType == ConnectorType.Discord)
        _ <- ZIO.logInfo(s"[connector] found ${discordInstances.length} Discord connector instance(s) in DB")
        parsedDiscord <- ZIO.foreach(discordInstances) { ci =>
          ZIO
            .fromEither(ci.configJson.as[DiscordConfig])
            .foldZIO(
              err => ZIO.logWarning(s"[connector:${ci.id}] Failed to parse DiscordConfig: $err").as(None),
              cfg => ZIO.some((ci, cfg)),
            )
        }
        dedupedDiscord = parsedDiscord.flatten.distinctBy(_._2.botToken)
        _ <- ZIO.when(dedupedDiscord.length < parsedDiscord.flatten.length)(
          ZIO.logWarning(
            s"[connector] Skipping ${parsedDiscord.flatten.length - dedupedDiscord.length} duplicate Discord instance(s) with the same bot token",
          ),
        )
        discordSkills <- ZIO.foreach(dedupedDiscord) { case (ci, cfg) =>
          ZIO.logInfo(s"[connector:${ci.id}] creating Discord connector") *>
            DiscordConnectorSkill.make(cfg, ci.id, DiscordApiClientLive(cfg), ingress)
        }
      } yield ConnectorManager.fromSkills(telegramSkills ++ discordSkills)
    }

  /** ZLayer that provides [[OAuthCredentialService]] from config + repositories + HTTP client. */
  private val oauthCredentialServiceLayer
    : ZLayer[ConfigurationService & ZIORepositories & Client, JorlanError, OAuthCredentialService] =
    ZLayer.fromZIO {
      for {
        config     <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
        repos      <- ZIO.service[ZIORepositories]
        httpClient <- ZIO.service[Client]
        googleCfg = config.jorlan.google
        _ <- ZIO.when(googleCfg.clientId.isEmpty)(
          ZIO.logWarning(
            "google.clientId is not configured — OAuth flows and Gmail/Calendar/Drive skills will not work",
          ),
        )
        _ <- ZIO.when(googleCfg.clientSecret.isEmpty)(
          ZIO.logWarning(
            "google.clientSecret is not configured — OAuth flows and Gmail/Calendar/Drive skills will not work",
          ),
        )
        encryptionKey =
          if (googleCfg.credentialEncryptionKey.nonEmpty) googleCfg.credentialEncryptionKey
          else config.jorlan.auth.secretKey.key
        encryptor = OAuthCredentialEncryptor(encryptionKey)
        repoWrapped = MappedExternalCredentialRepository(repos.extCredential, JorlanError(_))
        oauthSvc <- OAuthCredentialServiceImpl.make(
          repo = repoWrapped,
          encryptor = encryptor,
          clientId = googleCfg.clientId,
          clientSecret = googleCfg.clientSecret,
          client = httpClient,
        )
      } yield oauthSvc
    }

  val live: ULayer[JorlanEnvironment] =
    ZLayer
      .make[JorlanEnvironment](
        ConfigurationServiceImpl.live,
        ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.ai)),
        ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.auth)),
        QuillRepositories.live,
        dataSourceLayer,
        CapabilityEvaluatorImpl.live,
        ApprovalServiceImpl.live,
        JorlanAuthServer.live,
        oauthServiceLayer,
        OAuthStateStore.live(),
        SessionHub.live,
        ToolEventHub.live,
        EventLogHub.live,
        OllamaModelGateway.live,
        LangChainServiceBuilder.ollamaEmbeddingModelLayer,
        EmbeddingStore.mariadb("jorlan_memory"),
        AgentSessionManagerImpl.live,
        MemoryServiceImpl.live,
        NotificationRouter.live,
        SkillRegistry.liveSecure,
        DashboardService.live,
        AgentRunnerImpl.live,
        JobManagerImpl.live,
        TriggerEngine.live,
        MessageIngressImpl.live,
        liveConnectorManagerLayer,
        oauthCredentialServiceLayer,
        Client.default,
        OAuthReconnectService.live,
        SkillLifecycleService.live,
      ).orDie

}
// $COVERAGE-ON$
