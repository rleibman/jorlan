/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.google

import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.{AccessToken, GoogleCredentials}
import jorlan.JorlanError
import jorlan.UserId
import jorlan.service.OAuthCredentialService
import zio.*

/** Abstract base for Google API provider implementations.
  *
  * Centralizes token-based credential management and per-user client caching. Subclasses implement [[makeClient]] to
  * build the service-specific API client from an access token. The cache holds one client per `UserId`; when
  * [[OAuthCredentialService.refreshAccessToken]] returns a new token (i.e., the old one was refreshed), the stale
  * cached client is replaced automatically.
  *
  * @tparam C
  *   The Google API client type (e.g. `Gmail`, `Calendar`, `Drive`).
  */
private[google] abstract class GoogleApiProvider[C](
  credentials: OAuthCredentialService,
  transport:   com.google.api.client.http.HttpTransport,
  jsonFactory: com.google.api.client.json.JsonFactory,
  clientCache: Ref[Map[UserId, (String, C)]],
) {

  /** Build a service-specific API client using the given access token. */
  protected def makeClient(accessToken: String): C

  /** Human-readable API name used in error messages (e.g. `"Gmail"`, `"Calendar"`, `"Drive"`). */
  protected def apiName: String

  /** Obtain a valid (possibly cached) API client for the given user and run `f` against it.
    *
    * The client is reused from the cache when the access token has not changed since the last call. A new client is
    * built and cached whenever [[OAuthCredentialService.refreshAccessToken]] returns a fresh token.
    */
  protected def withClient[A](userId: UserId)(f: C => A): IO[JorlanError, A] =
    for {
      token  <- credentials.refreshAccessToken(userId, "google")
      client <- clientCache.modify { cache =>
        cache.get(userId) match {
          case Some((cachedToken, c)) if cachedToken == token =>
            (c, cache)
          case _ =>
            val c = makeClient(token)
            (c, cache.updated(userId, (token, c)))
        }
      }
      result <- ZIO
        .attemptBlocking(f(client))
        .mapError(e => JorlanError(s"$apiName API error: ${e.getMessage}", Some(e)))
    } yield result

  /** Construct an [[HttpCredentialsAdapter]] from a raw access token string. */
  protected def buildAdapter(accessToken: String): HttpCredentialsAdapter = {
    val googleCreds = GoogleCredentials.create(new AccessToken(accessToken, null)).nn
    new HttpCredentialsAdapter(googleCreds)
  }

}
