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

import jorlan.*
import jorlan.domain.*
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
) extends OAuthCredentialService {

  override def store(userId: UserId, provider: String, plainJson: Json): IO[JorlanError, Unit] =
    for {
      encrypted <- ZIO.fromEither(encryptor.encrypt(plainJson.toJson))
      expiresAt  = extractExpiresAt(plainJson)
      scopes     = extractScopes(plainJson)
      _         <- repo.upsert(userId, provider, encrypted, expiresAt, scopes)
    } yield ()

  override def load(userId: UserId, provider: String): IO[JorlanError, Option[Json]] =
    repo.find(userId, provider).flatMap {
      case None       => ZIO.none
      case Some(cred) =>
        ZIO.fromEither(encryptor.decrypt(cred.credentialData))
          .flatMap(s => ZIO.fromEither(s.fromJson[Json].left.map(e => JorlanError(s"Failed to parse credential JSON: $e"))))
          .map(Some(_))
    }

  override def revoke(userId: UserId, provider: String): IO[JorlanError, Unit] =
    repo.delete(userId, provider)

  override def listProviders(userId: UserId): IO[JorlanError, List[String]] =
    repo.listByUser(userId).map(_.map(_.provider))

  override def refreshAccessToken(userId: UserId, provider: String): IO[JorlanError, String] =
    for {
      credOpt   <- load(userId, provider)
      cred      <- ZIO.fromOption(credOpt).orElseFail(JorlanError(s"No credentials found for provider: $provider"))
      refreshToken <- ZIO.fromEither(
        extractField(cred, "refresh_token").toRight(JorlanError(s"No refresh_token in credentials for $provider")),
      )
      newToken <- callTokenRefresh(refreshToken)
      updatedCred = mergeAccessToken(cred, newToken)
      _ <- store(userId, provider, updatedCred)
    } yield newToken

  private def extractField(json: Json, key: String): Option[String] =
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

  private def extractScopes(json: Json): Option[String] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case ("scope", Json.Str(s)) => s }
      case _                => None
    }

  private def mergeAccessToken(existing: Json, newAccessToken: String): Json =
    existing match {
      case Json.Obj(fields) =>
        Json.Obj(fields.filter(_._1 != "access_token") :+ ("access_token" -> Json.Str(newAccessToken)))
      case other => other
    }

  private case class TokenResponse(
    access_token:  String,
    expires_in:    Option[Int],
    token_type:    Option[String],
  ) derives JsonDecoder

  private def run[A](effect: ZIO[Client, JorlanError, A]): IO[JorlanError, A] =
    effect.provideEnvironment(ZEnvironment(client))

  private def callTokenRefresh(refreshToken: String): IO[JorlanError, String] = {
    val body = Body.fromString(
      s"client_id=$clientId&client_secret=$clientSecret&refresh_token=$refreshToken&grant_type=refresh_token",
    )
    val request = Request.post(
      URL.decode("https://oauth2.googleapis.com/token").toOption.getOrElse(URL.empty),
      body,
    ).addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
    run(
      Client.batched(request)
        .mapError(e => JorlanError(s"Token refresh HTTP error: $e")),
    ).flatMap(_.body.asString.mapError(e => JorlanError(s"Token refresh body error: ${e.getMessage}", Some(e))))
      .flatMap { responseBody =>
        ZIO.fromEither(
          responseBody.fromJson[TokenResponse].left.map(e => JorlanError(s"Failed to parse token response: $e")),
        ).map(_.access_token)
      }
  }

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
  ): OAuthCredentialService =
    OAuthCredentialServiceImpl(repo, encryptor, clientId, clientSecret, client)

}
