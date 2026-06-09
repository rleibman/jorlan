/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import auth.AuthServer
import auth.oauth.OAuthUserInfo
import jorlan.*
import jorlan.auth.JorlanAuthServer
import jorlan.db.TestFixtures.*
import jorlan.db.repository.*
import jorlan.domain.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object AuthServerSpec extends ZIOSpec[ZIORepositories & AuthServer[User, UserId, ConnectionId]] {

  private type AuthEnv = ZIORepositories & AuthServer[User, UserId, ConnectionId]

  override def bootstrap: ZLayer[Any, Any, AuthEnv] =
    ZLayer.make[AuthEnv](JorlanContainer.repositoryLayer, JorlanAuthServer.live)

  override def spec: Spec[AuthEnv & TestEnvironment & Scope, Any] =
    suite("JorlanAuthServer")(
      test("login returns user for valid credentials") {
        for {
          repo       <- ZIO.serviceWith[ZIORepositories](_.user)
          authServer <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
          user       <- repo.upsert(User(UserId.empty, "AuthLogin", "auth-login@test.com", T0, T0))
          _          <- repo.changePassword(user.id, "secure-pass")
          result     <- authServer.login("auth-login@test.com", "secure-pass", None)
        } yield assertTrue(
          result.isDefined,
          result.exists(_.id == user.id),
        )
      },
      test("login returns None for wrong password") {
        for {
          repo       <- ZIO.serviceWith[ZIORepositories](_.user)
          authServer <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
          user       <- repo.upsert(User(UserId.empty, "AuthWrong", "auth-wrong@test.com", T0, T0))
          _          <- repo.changePassword(user.id, "real-pass")
          result     <- authServer.login("auth-wrong@test.com", "wrong-pass", None)
        } yield assertTrue(result.isEmpty)
      },
      test("userByEmail returns the correct user") {
        for {
          repo       <- ZIO.serviceWith[ZIORepositories](_.user)
          authServer <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
          user       <- repo.upsert(User(UserId.empty, "EmailLookup", "email-lookup@test.com", T0, T0))
          found      <- authServer.userByEmail("email-lookup@test.com")
          miss       <- authServer.userByEmail("nobody-here@test.com")
        } yield assertTrue(
          found.isDefined,
          found.exists(_.id == user.id),
          miss.isEmpty,
        )
      },
      test("userByPK returns the correct user") {
        for {
          repo       <- ZIO.serviceWith[ZIORepositories](_.user)
          authServer <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
          user       <- repo.upsert(User(UserId.empty, "PkLookup", "", T0, T0))
          found      <- authServer.userByPK(user.id)
          miss       <- authServer.userByPK(UserId(Long.MaxValue))
        } yield assertTrue(
          found.isDefined,
          found.exists(_.id == user.id),
          miss.isEmpty,
        )
      },
      test("createOAuthUser creates a user and channel identity") {
        for {
          repo       <- ZIO.serviceWith[ZIORepositories](_.user)
          authServer <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
          oauthInfo = OAuthUserInfo(
            providerId = "google-uid-001",
            email = "oauth-user@gmail.com",
            name = "OAuthUser",
            avatarUrl = None,
            emailVerified = true,
            rawData = Json.Obj(),
          )
          created    <- authServer.createOAuthUser(oauthInfo, "google", None)
          identities <- repo.getChannelIdentities(created.id)
        } yield assertTrue(
          created.displayName == "OAuthUser",
          created.email.contains("oauth-user@gmail.com"),
          identities.nonEmpty,
          identities.exists(_.channelType == ChannelType.Google),
          identities.exists(_.channelUserId == "google-uid-001"),
        )
      },
      test("linkOAuthToUser adds a channel identity to an existing user") {
        for {
          repo       <- ZIO.serviceWith[ZIORepositories](_.user)
          authServer <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
          user       <- repo.upsert(User(UserId.empty, "LinkOAuth", "link-oauth@test.com", T0, T0))
          linked     <- authServer.linkOAuthToUser(user, "github", "gh-user-42", Json.Obj())
          identities <- repo.getChannelIdentities(linked.id)
        } yield assertTrue(
          linked.id == user.id,
          identities.nonEmpty,
          identities.exists(_.channelType == ChannelType.GitHub),
          identities.exists(_.channelUserId == "gh-user-42"),
        )
      },
      test("createUser returns AuthError — self-registration is not supported") {
        for {
          authServer <- ZIO.service[AuthServer[User, UserId, ConnectionId]]
          result     <- authServer.createUser("NewUser", "new@test.com", "pass").exit
        } yield assertTrue(result.isFailure)
      },
    ) @@ TestAspect.sequential

}
