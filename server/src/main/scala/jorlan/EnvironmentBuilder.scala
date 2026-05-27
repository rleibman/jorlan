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

import _root_.auth.{AuthConfig, AuthServer, SecretKey}
import _root_.auth.oauth.{OAuthProviderConfig, OAuthService, OAuthStateStore}
import jorlan.auth.JorlanAuthServer
import zio.durationInt
import jorlan.db.repository.QuillRepositories
import jorlan.db.{ConfigurationServiceImpl, FlywayMigration}
import jorlan.domain.{ConnectionId, User, UserId}
import jorlan.service.{EventLogService, EventLogServiceImpl}
import zio.{ULayer, ZIO, ZLayer}

object EnvironmentBuilder {

  private val authConfigLayer: ZLayer[ConfigurationService, Nothing, AuthConfig] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie.map { cfg =>
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

  private val oauthServiceLayer: ZLayer[ConfigurationService, Nothing, OAuthService] =
    ZLayer
      .fromZIO(
        ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie.map { cfg =>
          val a = cfg.jorlan.auth
          OAuthService.live(
            googleConfig = a.google.map(toProviderConfig),
            githubConfig = a.github.map(toProviderConfig),
            discordConfig = a.discord.map(toProviderConfig),
          )
        },
      ).flatten

  val live: ULayer[JorlanEnvironment] =
    ZLayer.make[JorlanEnvironment](
      ConfigurationServiceImpl.live,
      FlywayMigration.live,
      QuillRepositories.live,
      EventLogServiceImpl.live,
      JorlanAuthServer.live,
      authConfigLayer,
      oauthServiceLayer,
      OAuthStateStore.live(),
    )

}
