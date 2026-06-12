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

import auth.{AuthConfig, key}
import jorlan.*
import jorlan.domain.*
import jorlan.service.OAuthCredentialService
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.net.URLEncoder
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** OAuth2 routes for integrating external providers (currently: Google).
  *
  *   - `GET /api/oauth/start/:provider` — Returns JSON `{"authUrl":"..."}`. Caller must supply `X-User-Id` header (set
  *     by auth middleware). The client should open the URL in a browser.
  *   - `GET /api/oauth/callback/google` — Unauthenticated (Google callback). Verifies the state JWT, exchanges the
  *     authorization code for tokens, stores credentials, and redirects to `/?oauth=success`.
  *
  * State JWT format: `base64url(payload).HmacSHA256(base64url(payload))` where payload is
  * `{"userId":N,"provider":"...","exp":unixSeconds}`.
  */
object OAuthRoutes {

  private val GoogleScopes = List(
    "https://www.googleapis.com/auth/gmail.modify",
    "https://www.googleapis.com/auth/calendar",
    "https://www.googleapis.com/auth/drive.readonly",
  )

  val GoogleAuthBase         = "https://accounts.google.com/o/oauth2/v2/auth"
  private val GoogleTokenUri = "https://oauth2.googleapis.com/token"

  // ─── State JWT helpers ────────────────────────────────────────────────────────

  private def hmacSign(payload: String, secret: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(payload.getBytes("UTF-8")))
  }

  private case class StatePayload(
    userId:   Long,
    provider: String,
    exp:      Long,
  ) derives JsonEncoder,
      JsonDecoder

  private def buildStateJwt(userId: UserId, provider: String, secret: String): UIO[String] =
    Clock.instant.map { now =>
      val payload = StatePayload(userId.value, provider, now.getEpochSecond + 1800L)
      val encoded = Base64.getUrlEncoder.withoutPadding.encodeToString(payload.toJson.getBytes("UTF-8"))
      val sig     = hmacSign(encoded, secret)
      s"$encoded.$sig"
    }

  private def verifyStateJwt(token: String, secret: String): Either[String, StatePayload] = {
    val parts = token.split("\\.", 2)
    if (parts.length != 2) {
      Left("Invalid state token format")
    } else {
      val encoded = parts(0)
      val sig     = parts(1)
      if (hmacSign(encoded, secret) != sig) {
        Left("State token signature invalid")
      } else {
        val payloadStr = new String(Base64.getUrlDecoder.decode(encoded), "UTF-8")
        payloadStr.fromJson[StatePayload] match {
          case Left(e)  => Left(s"State token parse error: $e")
          case Right(p) =>
            if (p.exp < Instant.now().getEpochSecond) Left("State token expired")
            else Right(p)
        }
      }
    }
  }

  // ─── Routes factory ───────────────────────────────────────────────────────────

  def routes(
    authConfig:   AuthConfig,
    googleCfg:    jorlan.GoogleOAuthSettings,
    oauthCredSvc: OAuthCredentialService,
    httpClient:   Client,
  ): Routes[Any, Nothing] = {
    val secret = authConfig.secretKey.key

    val startRoute: Route[Any, Nothing] =
      Method.GET / "api" / "oauth" / "start" / string("provider") -> handler {
        (provider: String, req: Request) =>
          (for {
            userId <- ZIO
              .fromOption(
                req.headers
                  .find(_.headerName.equalsIgnoreCase("X-User-Id"))
                  .flatMap(h => h.renderedValue.toLongOption)
                  .map(UserId(_)),
              )
              .orElseFail(JorlanError("Unauthenticated: missing X-User-Id"))
            stateJwt  <- buildStateJwt(userId, provider, secret)
            scopes     = if (provider.toLowerCase == "google") GoogleScopes else Nil
            scopeStr   = URLEncoder.encode(scopes.mkString(" "), "UTF-8")
            redirectEnc = URLEncoder.encode(googleCfg.redirectUri, "UTF-8")
            clientEnc   = URLEncoder.encode(googleCfg.clientId, "UTF-8")
            stateEnc    = URLEncoder.encode(stateJwt, "UTF-8")
            authUrl =
              s"$GoogleAuthBase?client_id=$clientEnc" +
                s"&redirect_uri=$redirectEnc" +
                s"&response_type=code" +
                s"&scope=$scopeStr" +
                s"&state=$stateEnc" +
                s"&access_type=offline" +
                s"&prompt=consent"
          } yield Response.json(s"""{"authUrl":${authUrl.toJson}}"""))
            .catchAll { e =>
              ZIO.succeed(
                Response(Status.BadRequest, body = Body.fromString(s"""{"error":${e.getMessage.toJson}}""")),
              )
            }
      }

    val callbackRoute: Route[Any, Nothing] =
      Method.GET / "api" / "oauth" / "callback" / "google" -> handler { (req: Request) =>
        val qp = req.url.queryParams
        (for {
          code    <- ZIO
            .fromOption(qp.queryParam("code"))
            .orElseFail(JorlanError("Missing code parameter"))
          state   <- ZIO
            .fromOption(qp.queryParam("state"))
            .orElseFail(JorlanError("Missing state parameter"))
          payload <- ZIO.fromEither(verifyStateJwt(state, secret)).mapError(e => JorlanError(e))
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
          tokenStr  <- tokenResp.body.asString
            .mapError(e => JorlanError(s"Token body read failed: ${e.getMessage}", Some(e)))
          tokenJson <- ZIO
            .fromEither(tokenStr.fromJson[Json])
            .mapError(e => JorlanError(s"Token JSON parse failed: $e"))
          _         <- oauthCredSvc.store(UserId(payload.userId), payload.provider, tokenJson)
        } yield Response.redirect(URL.decode("/?oauth=success").getOrElse(URL.empty)))
          .catchAll { e =>
            ZIO.logWarning(s"[oauth:callback] error=${e.getMessage}") *>
              ZIO.succeed(Response.redirect(URL.decode("/?oauth=error").getOrElse(URL.empty)))
          }
      }

    Routes(startRoute, callbackRoute)
  }

}
