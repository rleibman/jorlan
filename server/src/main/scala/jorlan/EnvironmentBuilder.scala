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

import _root_.auth.oauth.{OAuthService, OAuthStateStore}
import _root_.auth.{AuthConfig, AuthServer, key}
import jorlan.auth.JorlanAuthServer
import jorlan.connector.*
import jorlan.connector.telegram.*
import jorlan.db.repository.{QuillRepositories, ZIORepositories}
import jorlan.domain.*
import jorlan.email.{ImapSmtpProvider, PgpService}
import jorlan.google.{GmailProvider, GoogleCalendarProvider, GoogleDriveProvider, OAuthCredentialEncryptor, OAuthCredentialServiceImpl}
import jorlan.service.*
import jorlan.service.llm.OllamaModelGateway
import jorlan.service.memory.MemoryServiceImpl
import jorlan.service.schedule.{JobManagerImpl, TriggerEngine}
import jorlan.service.skills.SkillRegistry
import zio.http.Client
import zio.{ULayer, URLayer, ZIO, ZLayer}

// $COVERAGE-OFF$ Layer wiring requires all external infrastructure (DB, model server) — not unit-testable
object EnvironmentBuilder {

  private val oauthServiceLayer: ZLayer[ConfigurationService, ConfigurationError, OAuthService] =
    ZLayer
      .fromZIO(
        ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map { cfg =>
          val a = cfg.jorlan.auth
          OAuthService.live() // Note if you later want to support oauth
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

  /** ZLayer that provides [[OAuthCredentialService]] from config + repositories + HTTP client. */
  private val oauthCredentialServiceLayer: URLayer[ConfigurationService & ZIORepositories & Client, OAuthCredentialService] =
    ZLayer.fromZIO {
      for {
        config     <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie
        repos      <- ZIO.service[ZIORepositories]
        httpClient <- ZIO.service[Client]
        secretKey   = config.jorlan.auth.secretKey.key
        googleCfg   = config.jorlan.google
        encryptor   = OAuthCredentialEncryptor(secretKey)
        // Wrap the QuillExternalCredentialRepository (RepositoryTask) to IO[JorlanError, A]
        repoWrapped = new jorlan.google.IOExternalCredentialRepository {
          override def upsert(
            userId:        UserId,
            provider:      String,
            encryptedData: zio.json.ast.Json,
            expiresAt:     Option[java.time.Instant],
            scopes:        Option[String],
          ): zio.IO[JorlanError, Unit] =
            repos.extCredential.upsert(userId, provider, encryptedData, expiresAt, scopes)
              .mapError(JorlanError(_))

          override def find(userId: UserId, provider: String): zio.IO[JorlanError, Option[ExternalCredential]] =
            repos.extCredential.find(userId, provider).mapError(JorlanError(_))

          override def delete(userId: UserId, provider: String): zio.IO[JorlanError, Unit] =
            repos.extCredential.delete(userId, provider).mapError(JorlanError(_))

          override def listByUser(userId: UserId): zio.IO[JorlanError, List[ExternalCredential]] =
            repos.extCredential.listByUser(userId).mapError(JorlanError(_))
        }
      } yield OAuthCredentialServiceImpl.make(
        repo = repoWrapped,
        encryptor = encryptor,
        clientId = googleCfg.clientId,
        clientSecret = googleCfg.clientSecret,
        client = httpClient,
      )
    }

  val live: ULayer[JorlanEnvironment] =
    ZLayer
      .make[JorlanEnvironment](
        ConfigurationServiceImpl.live,
        ZLayer.fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.auth)),
        QuillRepositories.live,
        CapabilityEvaluatorImpl.live,
        ApprovalServiceImpl.live,
        JorlanAuthServer.live,
        oauthServiceLayer,
        OAuthStateStore.live(),
        SessionHub.live,
        ToolEventHub.live,
        OllamaModelGateway.live,
        AgentSessionManagerImpl.live,
        MemoryServiceImpl.live,
        NotificationRouter.live,
        SkillRegistry.liveSecure,
        AgentRunnerImpl.live,
        JobManagerImpl.live,
        TriggerEngine.live,
        MessageIngressImpl.live,
        liveConnectorManagerLayer,
        oauthCredentialServiceLayer,
        Client.default,
      ).orDie

}
// $COVERAGE-ON$
