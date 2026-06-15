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

import auth.{AuthConfig, AuthenticatedSession, key}
import jorlan.*
import jorlan.domain.*
import jorlan.service.OAuthCredentialService
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** OAuth2 routes for integrating external providers (currently: Google).
  *
  *   - `GET /api/oauth/start/:provider` — Session-authenticated route (requires a valid Bearer token in the
  *     Authorization header). Returns JSON `{"authUrl":"..."}` pointing at Google's consent page.
  *   - `GET /api/oauth/callback/google` — Unauthenticated (Google callback). Verifies the state JWT, exchanges the
  *     authorization code for tokens, stores credentials, and redirects to `/?oauth=success`.
  *
  * The start route must be mounted behind the `bearerSessionProvider` middleware so that `JorlanSession` is populated
  * from the Bearer token. The callback route is intentionally unauthenticated.
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

  val GoogleAuthBase = "https://accounts.google.com/o/oauth2/v2/auth"
  private val GoogleTokenUri = "https://oauth2.googleapis.com/token"

  // ─── State JWT helpers ────────────────────────────────────────────────────────

  private def hmacSign(
    payload: String,
    secret:  String,
  ): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(payload.getBytes("UTF-8")))
  }

  private val StateJwtTtlSeconds = 1800L

  /** Nonce store: maps each issued nonce to its expiry. Consumed on first use in the callback route. */
  type NonceStore = Ref[Map[String, Long]]

  private[routes] case class StatePayload(
    userId:   Long,
    provider: String,
    exp:      Long,
    nonce:    String,
  ) derives JsonEncoder, JsonDecoder

  private def generateNonce(): String = {
    val bytes = new Array[Byte](16)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  private[routes] def buildStateJwt(
    userId:     UserId,
    provider:   String,
    secret:     String,
    nonceStore: NonceStore,
  ): UIO[String] =
    Clock.instant.flatMap { now =>
      val exp = now.getEpochSecond + StateJwtTtlSeconds
      val nonce = generateNonce()
      val payload = StatePayload(userId.value, provider, exp, nonce)
      val encoded = Base64.getUrlEncoder.withoutPadding.encodeToString(payload.toJson.getBytes("UTF-8"))
      val sig = hmacSign(encoded, secret)
      nonceStore.update(_ + (nonce -> exp)).as(s"$encoded.$sig")
    }

  private[routes] def verifyAndConsumeStateJwt(
    token:      String,
    secret:     String,
    nowSeconds: Long,
    nonceStore: NonceStore,
  ): IO[String, StatePayload] = {
    val parts = token.split("\\.", 2)
    if (parts.length != 2) {
      ZIO.fail("Invalid state token format")
    } else {
      val encoded = parts(0)
      val sig = parts(1)
      if (hmacSign(encoded, secret) != sig) {
        ZIO.fail("State token signature invalid")
      } else {
        val payloadStr = new String(Base64.getUrlDecoder.decode(encoded), "UTF-8")
        payloadStr.fromJson[StatePayload] match {
          case Left(e)                        => ZIO.fail(s"State token parse error: $e")
          case Right(p) if p.exp < nowSeconds => ZIO.fail("State token expired")
          case Right(p)                       =>
            nonceStore
              .modify { store =>
                if (store.contains(p.nonce)) (true, store - p.nonce)
                else (false, store)
              }
              .flatMap {
                case true  => ZIO.succeed(p)
                case false => ZIO.fail("State token already used or unknown nonce")
              }
        }
      }
    }
  }

  /** Keep the synchronous version for backward-compatible unit tests. */
  private[routes] def verifyStateJwt(
    token:      String,
    secret:     String,
    nowSeconds: Long,
  ): Either[String, StatePayload] = {
    val parts = token.split("\\.", 2)
    if (parts.length != 2) {
      Left("Invalid state token format")
    } else {
      val encoded = parts(0)
      val sig = parts(1)
      if (hmacSign(encoded, secret) != sig) {
        Left("State token signature invalid")
      } else {
        val payloadStr = new String(Base64.getUrlDecoder.decode(encoded), "UTF-8")
        payloadStr.fromJson[StatePayload] match {
          case Left(e)  => Left(s"State token parse error: $e")
          case Right(p) =>
            if (p.exp < nowSeconds) Left("State token expired")
            else Right(p)
        }
      }
    }
  }

  // ─── Routes factory ───────────────────────────────────────────────────────────

  /** Routes requiring a valid session — must be mounted behind `bearerSessionProvider`. */
  def authenticatedRoutes(
    authConfig: AuthConfig,
    googleCfg:  jorlan.GoogleOAuthSettings,
    nonceStore: NonceStore,
  ): Routes[JorlanSession, Nothing] = {
    val secret = authConfig.secretKey.key

    val startRoute: Route[JorlanSession, Nothing] =
      Method.GET / "api" / "oauth" / "start" / string("provider") -> handler {
        (
          provider: String,
          _:        Request,
        ) =>
          (for {
            userId <- ZIO
              .serviceWith[JorlanSession](_.user.map(_.id))
              .someOrFail(JorlanError("Unauthenticated"))
            _ <- ZIO.unless(provider.toLowerCase == "google")(
              ZIO.fail(JorlanError(s"Unsupported OAuth provider: $provider. Supported: google")),
            )
            stateJwt <- buildStateJwt(userId, provider, secret, nonceStore)
            scopes = GoogleScopes
            scopeStr = URLEncoder.encode(scopes.mkString(" "), "UTF-8")
            redirectEnc = URLEncoder.encode(googleCfg.redirectUri, "UTF-8")
            clientEnc = URLEncoder.encode(googleCfg.clientId, "UTF-8")
            stateEnc = URLEncoder.encode(stateJwt, "UTF-8")
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

    Routes(startRoute)
  }

  /** Unauthenticated routes — Google callback redirect, does not require a session. */
  def unauthenticatedRoutes(
    authConfig:   AuthConfig,
    googleCfg:    jorlan.GoogleOAuthSettings,
    oauthCredSvc: OAuthCredentialService,
    httpClient:   Client,
    nonceStore:   NonceStore,
  ): Routes[Any, Nothing] = {
    val secret = authConfig.secretKey.key

    val callbackRoute: Route[Any, Nothing] =
      Method.GET / "api" / "oauth" / "callback" / "google" -> handler { (req: Request) =>
        val qp = req.url.queryParams
        (for {
          code <- ZIO
            .fromOption(qp.queryParam("code"))
            .orElseFail(JorlanError("Missing code parameter"))
          state <- ZIO
            .fromOption(qp.queryParam("state"))
            .orElseFail(JorlanError("Missing state parameter"))
          now     <- Clock.instant
          payload <- verifyAndConsumeStateJwt(state, secret, now.getEpochSecond, nonceStore)
            .mapError(e => JorlanError(e))
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
          _ <- oauthCredSvc.store(UserId(payload.userId), payload.provider, tokenJson)
        } yield Response.redirect(URL.decode("/?oauth=success").getOrElse(URL.empty)))
          .catchAll { e =>
            ZIO.logWarning(s"[oauth:callback] error=${e.getMessage}") *>
              ZIO.succeed(Response.redirect(URL.decode("/?oauth=error").getOrElse(URL.empty)))
          }
      }

    Routes(callbackRoute)
  }

}
