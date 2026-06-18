/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.routes

import _root_.auth.*
import _root_.auth.oauth.{OAuthService, OAuthStateStore}
import jorlan.*
import zio.*
import zio.http.*

case class AuthRoutes(authServer: AuthServer[User, UserId, ConnectionId])
    extends AppRoutes[AuthConfig & OAuthService & OAuthStateStore, JorlanSession, JorlanError] {

  override def api: ZIO[
    AuthConfig,
    JorlanError,
    Routes[AuthConfig & OAuthService & OAuthStateStore & JorlanSession, JorlanError],
  ] =
    authServer.authRoutes.map(_.mapError(JorlanError.apply))

  override def unauth: ZIO[
    AuthConfig & OAuthService & OAuthStateStore,
    JorlanError,
    Routes[AuthConfig & OAuthService & OAuthStateStore, JorlanError],
  ] =
    authServer.unauthRoutes.map(_.mapError(JorlanError.apply))

}
