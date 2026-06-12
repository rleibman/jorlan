/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.auth

import auth.*
import auth.oauth.OAuthUserInfo
import jorlan.*
import jorlan.db.repository.{ZIOEventLogRepository, ZIORepositories, ZIOUserRepository}
import zio.*
import zio.json.ast.Json

/** Wires Jorlan's [[ZIOUserRepository]] into zio-auth's [[AuthServer]] contract.
  *
  * Passwords are never stored or compared in Scala — the `login` and `changePassword` methods delegate to inline SQL
  * that calls `SHA2(password, 512)` on the database side.
  *
  * Email registration and confirmation flows are intentionally not exposed; user creation happens via the admin GraphQL
  * API or via OAuth auto-creation.
  */
object JorlanAuthServer {

  val live: ZLayer[ZIORepositories, Nothing, AuthServer[User, UserId, ConnectionId]] =
    ZLayer.fromZIO {
      ZIO.service[ZIORepositories].map { repo =>
        new AuthServer[User, UserId, ConnectionId] {

          override def getPK(user: User): UserId = user.id

          override def login(
            email:        String,
            password:     String,
            connectionId: Option[ConnectionId],
          ): IO[AuthError, Option[User]] = repo.user.login(email, password).mapError(AuthError(_))

          override def logout(): ZIO[JorlanSession, AuthError, Unit] =
            ZIO.serviceWithZIO[JorlanSession](session =>
              ZIO.logInfo(s"User ${session.user.map(_.displayName).getOrElse("unknown")} logged out"),
            )

          override def changePassword(
            userPK:      UserId,
            newPassword: String,
          ): ZIO[JorlanSession, AuthError, Unit] = repo.user.changePassword(userPK, newPassword).mapError(AuthError(_))

          override def userByEmail(email: String): IO[AuthError, Option[User]] =
            repo.user.userByEmail(email).mapError(AuthError(_))

          override def userByPK(pk: UserId): IO[AuthError, Option[User]] = repo.user.getById(pk).mapError(AuthError(_))

          override def userByOAuthProvider(
            provider:   String,
            providerId: String,
          ): IO[AuthError, Option[User]] =
            ChannelType.fromProvider(provider) match {
              case Some(channelType) => repo.user.userByChannelIdentity(channelType, providerId).mapError(AuthError(_))
              case None              => ZIO.none
            }

          override def createOAuthUser(
            oauthInfo:    OAuthUserInfo,
            provider:     String,
            connectionId: Option[ConnectionId],
          ): IO[AuthError, User] = {
            for {
              now  <- Clock.instant
              user <- repo.user
                .upsert(
                  User(
                    id = UserId.empty,
                    displayName = oauthInfo.name,
                    email = oauthInfo.email,
                    createdAt = now,
                    updatedAt = now,
                    active = oauthInfo.emailVerified,
                  ),
                ).mapError(AuthError(_))
              _ <- ChannelType.fromProvider(provider) match {
                case Some(channelType) =>
                  repo.user
                    .upsertChannelIdentity(
                      ChannelIdentity(
                        id = ChannelIdentityId.empty,
                        userId = user.id,
                        channelType = channelType,
                        channelUserId = oauthInfo.providerId,
                        verified = true,
                        providerData = Some(oauthInfo.rawData),
                        createdAt = now,
                      ),
                    ).mapError(AuthError(_)).unit
                case None => ZIO.unit
              }
            } yield user
          }

          override def linkOAuthToUser(
            user:         User,
            provider:     String,
            providerId:   String,
            providerData: Json,
          ): IO[AuthError, User] = {
            for {
              now <- Clock.instant
              _   <- ChannelType.fromProvider(provider) match {
                case Some(channelType) =>
                  repo.user
                    .upsertChannelIdentity(
                      ChannelIdentity(
                        id = ChannelIdentityId.empty,
                        userId = user.id,
                        channelType = channelType,
                        channelUserId = providerId,
                        verified = true,
                        providerData = Some(providerData),
                        createdAt = now,
                      ),
                    ).mapError(AuthError(_)).unit
                case None => ZIO.unit
              }
            } yield user
          }

          // Self-registration is not enabled — user creation goes through the admin API.
          override def createUser(
            name:     String,
            email:    String,
            password: String,
          ): IO[AuthError, User] = ZIO.fail(AuthError("Self-registration is not supported. Contact an administrator."))

          // Email flows are not wired — no self-registration in Jorlan.
          override def sendEmail(
            subject: String,
            body:    String,
            user:    User,
          ): IO[AuthError, Unit] = ZIO.unit

          // Users are auto-activated (via OAuth) or created active by admin.
          override def activateUser(userPK: UserId): IO[AuthError, Unit] = ZIO.unit

        }
      }
    }

}
