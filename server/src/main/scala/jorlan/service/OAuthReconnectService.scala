/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import auth.{AuthConfig, key}
import jorlan.*
import zio.*
import zio.json.*

import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Manages the OAuth reconnect flow for already-authenticated users (e.g. re-linking Google credentials).
  *
  * This is separate from the login OAuth flow (handled by zio-auth). The reconnect flow:
  *   1. GQL mutation `startOAuth` calls [[buildAuthUrl]] and returns the Google authorization URL to the client.
  *   2. Browser navigates to that URL (no Bearer token needed).
  *   3. Google redirects to `/api/oauth/callback/google` with `code` and `state`.
  *   4. The callback calls [[verifyAndConsume]] to recover `(userId, provider)`, then stores the credential.
  *
  * State format: `base64url(payload).HmacSHA256(base64url(payload))` where payload is
  * `{"userId":N,"provider":"...","exp":unixSeconds,"nonce":"..."}`.
  */
trait OAuthReconnectService {

  /** Build the full Google authorization URL for a reconnect flow, embedding a signed state JWT. */
  def buildAuthUrl(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, String]

  /** Verify and consume the state parameter from the Google callback. Returns `(userId, provider)`. */
  def verifyAndConsume(state: String): IO[JorlanError, (UserId, String)]

}

object OAuthReconnectService {

  private val GoogleScopes = List(
    "https://www.googleapis.com/auth/gmail.modify",
    "https://www.googleapis.com/auth/calendar",
    "https://www.googleapis.com/auth/contacts.readonly",
    "https://www.googleapis.com/auth/drive.readonly",
  )

  private val GoogleAuthBase = "https://accounts.google.com/o/oauth2/v2/auth"
  private val StateJwtTtlSeconds = 1800L

  private case class StatePayload(
    userId:   Long,
    provider: String,
    exp:      Long,
    nonce:    String,
  ) derives JsonCodec

  private def generateNonce(): String = {
    val bytes = new Array[Byte](16)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  private def hmacSign(
    payload: String,
    secret:  String,
  ): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(payload.getBytes("UTF-8")))
  }

  /** Create a service instance for the given `secret`, Google `clientId`, and `redirectUri`.
    *
    * Call this from tests to avoid requiring the full `AuthConfig`/`ConfigurationService` environment.
    */
  def make(
    secret:            String,
    googleClientId:    String,
    googleRedirectUri: String,
  ): UIO[OAuthReconnectService] =
    Ref.make(Map.empty[String, Long]).map { nonceStore =>
      new OAuthReconnectService {

        override def buildAuthUrl(
          userId:   UserId,
          provider: String,
        ): IO[JorlanError, String] = {
          for {
            _ <- ZIO.unless(provider.toLowerCase == "google")(
              ZIO.fail(JorlanError(s"Unsupported OAuth provider: $provider. Supported: google")),
            )
            now <- Clock.instant
            exp = now.getEpochSecond + StateJwtTtlSeconds
            nonce = generateNonce()
            payload = StatePayload(userId.value, provider, exp, nonce)
            encoded = Base64.getUrlEncoder.withoutPadding.encodeToString(payload.toJson.getBytes("UTF-8"))
            sig = hmacSign(encoded, secret)
            stateJwt = s"$encoded.$sig"
            _ <- nonceStore.update(_ + (nonce -> exp))
            scopes = GoogleScopes
            scopeStr = URLEncoder.encode(scopes.mkString(" "), "UTF-8")
            clientIdEnc = URLEncoder.encode(googleClientId, "UTF-8")
            redirectEnc = URLEncoder.encode(googleRedirectUri, "UTF-8")
            stateEnc = URLEncoder.encode(stateJwt, "UTF-8")
            authUrl =
              s"$GoogleAuthBase" +
                s"?response_type=code" +
                s"&client_id=$clientIdEnc" +
                s"&scope=$scopeStr" +
                s"&redirect_uri=$redirectEnc" +
                s"&state=$stateEnc" +
                s"&access_type=offline" +
                s"&prompt=consent"
          } yield authUrl
        }

        override def verifyAndConsume(state: String): IO[JorlanError, (UserId, String)] = {
          val parts = state.split("\\.", 2)
          if (parts.length != 2) {
            ZIO.fail(JorlanError("Invalid OAuth state token format"))
          } else {
            val encoded = parts(0)
            val sig = parts(1)
            if (hmacSign(encoded, secret) != sig) {
              ZIO.fail(JorlanError("OAuth state token signature invalid"))
            } else {
              val payloadStr = new String(Base64.getUrlDecoder.decode(encoded), "UTF-8")
              payloadStr.fromJson[StatePayload] match {
                case Left(e)  => ZIO.fail(JorlanError(s"OAuth state token parse error: $e"))
                case Right(p) =>
                  for {
                    now <- Clock.instant
                    _   <- ZIO.when(p.exp < now.getEpochSecond)(
                      ZIO.fail(JorlanError("OAuth state token expired")),
                    )
                    ok <- nonceStore.modify { store =>
                      if (store.contains(p.nonce)) (true, store - p.nonce)
                      else (false, store)
                    }
                    _ <- ZIO.unless(ok)(ZIO.fail(JorlanError("OAuth state token already used or unknown nonce")))
                  } yield (UserId(p.userId), p.provider)
              }
            }
          }
        }
      }
    }

  val live: ZLayer[AuthConfig & ConfigurationService, ConfigurationError, OAuthReconnectService] =
    ZLayer.fromZIO {
      for {
        authConfig <- ZIO.service[AuthConfig]
        appConfig  <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig)
        svc        <- make(
          authConfig.secretKey.key,
          appConfig.jorlan.google.clientId,
          appConfig.jorlan.google.redirectUri,
        )
      } yield svc
    }

}
