/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
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
