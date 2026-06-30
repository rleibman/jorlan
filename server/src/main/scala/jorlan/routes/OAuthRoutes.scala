/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.routes

import jorlan.*
import jorlan.service.{OAuthCredentialService, OAuthReconnectService}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.net.URLEncoder

/** OAuth2 callback route for re-linking external credentials from an already-authenticated user.
  *
  *   - `GET /api/oauth/callback/google` — Unauthenticated (Google callback). Verifies the signed state JWT via
  *     [[OAuthReconnectService.verifyAndConsume]], exchanges the authorization code for tokens, stores credentials in
  *     [[OAuthCredentialService]], and redirects to `/?oauth=success`.
  *
  * The start of this flow is initiated by the `startOAuth` GraphQL mutation, which calls
  * [[OAuthReconnectService.buildAuthUrl]] directly and returns the Google consent URL to the frontend. This avoids
  * requiring the browser to include a Bearer token in a plain GET request.
  */
class OAuthRoutes extends AppRoutes[JorlanEnvironment, JorlanSession, JorlanError] {

  private val GoogleTokenUri = "https://oauth2.googleapis.com/token"

  override def api: ZIO[JorlanEnvironment, JorlanError, Routes[JorlanEnvironment & JorlanSession, JorlanError]] =
    ZIO.succeed(Routes.empty)

  override def unauth: ZIO[JorlanEnvironment, JorlanError, Routes[JorlanEnvironment, JorlanError]] =
    for {
      oauthReconnect <- ZIO.service[OAuthReconnectService]
      oauthCredSvc   <- ZIO.service[OAuthCredentialService]
      httpClient     <- ZIO.service[Client]
      config         <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
    } yield {
      val googleCfg = config.jorlan.google

      val callbackRoute: Route[Any, Nothing] =
        Method.GET / "api" / "oauth" / "callback" / "google" -> handler { (req: Request) =>
          val qp = req.url.queryParams
          (for {
            _     <- ZIO.logInfo(s"[oauth:callback] received Google callback, params=${qp.map.keys.mkString(",")}")
            code  <- ZIO.fromOption(qp.queryParam("code")).orElseFail(JorlanError("Missing code parameter"))
            state <- ZIO.fromOption(qp.queryParam("state")).orElseFail(JorlanError("Missing state parameter"))
            (userId, provider) <- oauthReconnect.verifyAndConsume(state)
            tokenBody = Body.fromString(
              s"code=${URLEncoder.encode(code, "UTF-8")}" +
                s"&client_id=${URLEncoder.encode(googleCfg.clientId, "UTF-8")}" +
                s"&client_secret=${URLEncoder.encode(googleCfg.clientSecret, "UTF-8")}" +
                s"&redirect_uri=${URLEncoder.encode(googleCfg.redirectUri, "UTF-8")}" +
                s"&grant_type=authorization_code",
            )
            tokenReq = Request
              .post(URL.decode(GoogleTokenUri).getOrElse(URL.empty), tokenBody)
              .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
            tokenResp <- Client
              .batched(tokenReq)
              .mapError(e => JorlanError(s"Token exchange HTTP error: $e"))
              .provideEnvironment(ZEnvironment(httpClient))
            tokenStr <- tokenResp.body.asString
              .mapError(e => JorlanError(s"Token body read failed: ${e.getMessage}", Some(e)))
            _ <- ZIO.when(!tokenResp.status.isSuccess)(
              ZIO.logDebug(s"[oauth:callback] Google token error body: $tokenStr") *>
                ZIO.fail(JorlanError("Google token exchange failed")),
            )
            tokenJson <- ZIO
              .fromEither(tokenStr.fromJson[Json])
              .mapError(e => JorlanError(s"Token JSON parse failed: $e"))
            _ <- oauthCredSvc.store(userId, provider, tokenJson)
          } yield Response.redirect(URL.decode("/?oauth=success").getOrElse(URL.empty)))
            .catchAll { e =>
              ZIO.logWarning(s"[oauth:callback] error=${e.getMessage}") *>
                ZIO.succeed(Response.redirect(URL.decode("/?oauth=error").getOrElse(URL.empty)))
            }
        }

      Routes(callbackRoute)
    }

}

object OAuthRoutes {

  def apply(): UIO[OAuthRoutes] = ZIO.succeed(new OAuthRoutes)

}
