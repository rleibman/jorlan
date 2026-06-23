/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.google

import jorlan.*
import jorlan.service.OAuthCredentialService
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant

/** Type alias for the concrete repository type used by this module. */
type IOExternalCredentialRepository = ExternalCredentialRepository[[A] =>> IO[JorlanError, A]]

class OAuthCredentialServiceImpl(
  repo:         ExternalCredentialRepository[[A] =>> IO[JorlanError, A]],
  encryptor:    OAuthCredentialEncryptor,
  clientId:     String,
  clientSecret: String,
  client:       Client,
  tokenCache:   Ref[Map[(UserId, String), (String, Instant)]],
) extends OAuthCredentialService {

  override def store(
    userId:    UserId,
    provider:  String,
    plainJson: Json,
  ): IO[JorlanError, Unit] =
    for {
      encrypted <- ZIO.fromEither(encryptor.encrypt(plainJson.toJson))
      expiresAt = extractExpiresAt(plainJson)
      scopes = extractField(plainJson, "scope")
      _ <- repo.upsert(userId, provider, encrypted, expiresAt, scopes)
    } yield ()

  override def load(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, Option[Json]] =
    repo.find(userId, provider).flatMap {
      case None       => ZIO.none
      case Some(cred) =>
        ZIO
          .fromEither(encryptor.decrypt(cred.credentialData))
          .flatMap(s =>
            ZIO.fromEither(s.fromJson[Json].left.map(e => JorlanError(s"Failed to parse credential JSON: $e"))),
          )
          .map(Some(_))
    }

  override def revoke(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, Unit] =
    tokenCache.update(_ - ((userId, provider))) *> repo.delete(userId, provider)

  override def listProviders(userId: UserId): IO[JorlanError, List[String]] =
    repo.listByUser(userId).map(_.map(_.provider))

  override def getExpiresAt(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, Option[Instant]] =
    repo.find(userId, provider).map(_.flatMap(_.expiresAt))

  private val refreshMarginSeconds = 60L
  private val tokenCacheTtlSeconds = 300L

  override def refreshAccessToken(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, String] =
    for {
      now         <- ZIO.clockWith(_.instant)
      cached      <- tokenCache.get.map(_.get((userId, provider)))
      accessToken <- cached match {
        case Some((token, expiresAt)) if expiresAt.isAfter(now.plusSeconds(refreshMarginSeconds)) =>
          ZIO.succeed(token)
        case _ =>
          for {
            credOpt <- load(userId, provider)
            cred    <- ZIO.fromOption(credOpt).orElseFail(JorlanError(s"No credentials found for provider: $provider"))
            expiresAt <- getExpiresAt(userId, provider)
            needsRefresh = expiresAt.forall(_.isBefore(now.plusSeconds(refreshMarginSeconds)))
            token <-
              if (needsRefresh) {
                for {
                  refreshToken <- ZIO.fromEither(
                    extractField(cred, "refresh_token")
                      .toRight(JorlanError(s"No refresh_token in credentials for $provider")),
                  )
                  tokenResp <- callTokenRefresh(refreshToken)
                  updatedCred = mergeAccessToken(cred, tokenResp.access_token, tokenResp.expires_in)
                  _ <- store(userId, provider, updatedCred)
                  newExpiry = tokenResp.expires_in.fold(now.plusSeconds(tokenCacheTtlSeconds))(s =>
                    now.plusSeconds(s.toLong),
                  )
                  _ <- tokenCache.update(_.updated((userId, provider), (tokenResp.access_token, newExpiry)))
                } yield tokenResp.access_token
              } else {
                for {
                  at <- ZIO.fromEither(
                    extractField(cred, "access_token")
                      .toRight(JorlanError(s"No access_token in credentials for $provider")),
                  )
                  cacheExpiry = expiresAt.getOrElse(now.plusSeconds(tokenCacheTtlSeconds))
                  _ <- tokenCache.update(_.updated((userId, provider), (at, cacheExpiry)))
                } yield at
              }
          } yield token
      }
    } yield accessToken

  private def extractField(
    json: Json,
    key:  String,
  ): Option[String] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Str(v)) => v }
      case _                => None
    }

  private def extractExpiresAt(json: Json): Option[Instant] =
    json match {
      case Json.Obj(fields) =>
        fields.collectFirst { case ("expiry_date", Json.Num(n)) => Instant.ofEpochMilli(n.longValue) }
      case _ => None
    }

  private def mergeAccessToken(
    existing:       Json,
    newAccessToken: String,
    expiresIn:      Option[Int],
  ): Json = {
    val newExpiryMillis = expiresIn.map(s => java.time.Instant.now().plusSeconds(s.toLong).toEpochMilli)
    existing match {
      case Json.Obj(fields) =>
        val without = fields.filter(f => f._1 != "access_token" && f._1 != "expiry_date")
        val withToken = without :+ ("access_token" -> Json.Str(newAccessToken))
        val withExpiry = newExpiryMillis.fold(withToken)(ms => withToken :+ ("expiry_date" -> Json.Num(ms)))
        Json.Obj(withExpiry)
      case other => other
    }
  }

  private case class TokenResponse(
    access_token: String,
    expires_in:   Option[Int],
    token_type:   Option[String],
  ) derives JsonCodec

  private def run[A](effect: ZIO[Client, JorlanError, A]): IO[JorlanError, A] =
    effect.provideEnvironment(ZEnvironment(client))

  private def callTokenRefresh(refreshToken: String): IO[JorlanError, TokenResponse] = {
    import java.net.URLEncoder
    val body = Body.fromString(
      s"client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
        s"&client_secret=${URLEncoder.encode(clientSecret, "UTF-8")}" +
        s"&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}" +
        s"&grant_type=refresh_token",
    )
    val request = Request
      .post(
        URL.decode("https://oauth2.googleapis.com/token").toOption.getOrElse(URL.empty),
        body,
      ).addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
    run(
      Client
        .batched(request)
        .mapError(e => JorlanError(s"Token refresh HTTP error: $e")),
    ).flatMap { resp =>
      resp.body.asString
        .mapError(e => JorlanError(s"Token refresh body error: ${e.getMessage}", Some(e)))
        .flatMap { responseBody =>
          if (!resp.status.isSuccess)
            ZIO.fail(JorlanError(s"Token refresh failed (${resp.status.code}): $responseBody"))
          else
            ZIO.fromEither(
              responseBody.fromJson[TokenResponse].left.map(e => JorlanError(s"Failed to parse token response: $e")),
            )
        }
    }
  }

}

/** Adapts any error-typed [[ExternalCredentialRepository]] to the `IO[JorlanError, *]` effect used by
  * [[OAuthCredentialServiceImpl]], mapping the error type via [[JorlanError]].apply.
  *
  * Used in [[jorlan.EnvironmentBuilder]] to bridge the `db` module's `RepositoryError` to `JorlanError`.
  */
class MappedExternalCredentialRepository[E](
  underlying: ExternalCredentialRepository[[A] =>> IO[E, A]],
  mapError:   E => JorlanError,
) extends IOExternalCredentialRepository {

  override def upsert(
    userId:        UserId,
    provider:      String,
    encryptedData: zio.json.ast.Json,
    expiresAt:     Option[java.time.Instant],
    scopes:        Option[String],
  ): IO[JorlanError, Unit] =
    underlying.upsert(userId, provider, encryptedData, expiresAt, scopes).mapError(mapError)

  override def find(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, Option[ExternalCredential]] =
    underlying.find(userId, provider).mapError(mapError)

  override def delete(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, Unit] =
    underlying.delete(userId, provider).mapError(mapError)

  override def listByUser(userId: UserId): IO[JorlanError, List[ExternalCredential]] =
    underlying.listByUser(userId).mapError(mapError)

  override def listOAuthProviders(): IO[JorlanError, List[String]] =
    underlying.listOAuthProviders().mapError(mapError)
  override def startOAuth(provider: String): IO[JorlanError, Option[String]] =
    underlying.startOAuth(provider).mapError(mapError)
  override def revokeOAuth(provider: String): IO[JorlanError, Unit] =
    underlying.revokeOAuth(provider).mapError(mapError)
  override def oauthStatus(provider: String): IO[JorlanError, Option[OAuthStatus]] =
    underlying.oauthStatus(provider).mapError(mapError)

}

object OAuthCredentialServiceImpl {

  /** Build an [[OAuthCredentialServiceImpl]] from a plain-IO [[ExternalCredentialRepository]].
    *
    * Intended for use in [[jorlan.EnvironmentBuilder]] where the repository is wrapped manually.
    */
  def make(
    repo:         IOExternalCredentialRepository,
    encryptor:    OAuthCredentialEncryptor,
    clientId:     String,
    clientSecret: String,
    client:       Client,
  ): UIO[OAuthCredentialService] =
    Ref.make(Map.empty[(UserId, String), (String, Instant)]).map { cache =>
      OAuthCredentialServiceImpl(repo, encryptor, clientId, clientSecret, client, cache)
    }

}
