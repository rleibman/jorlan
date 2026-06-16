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
import jorlan.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object OAuthCredentialServiceImplSpec extends ZIOSpecDefault {

  private val userId = UserId(1L)
  private val provider = "google"
  private val encryptor = OAuthCredentialEncryptor("test-key-for-credential-service-specs")

  private val sampleCred = Json.Obj(
    "access_token"  -> Json.Str("at-123"),
    "refresh_token" -> Json.Str("rt-456"),
    "token_type"    -> Json.Str("Bearer"),
    "expiry_date"   -> Json.Num(java.time.Instant.now().plusSeconds(3600).toEpochMilli.toDouble),
  )

  private def makeService(): UIO[OAuthCredentialServiceImpl] =
    for {
      store      <- Ref.make(Map.empty[(UserId, String), ExternalCredential])
      tokenCache <- Ref.make(Map.empty[(UserId, String), (String, java.time.Instant)])
    } yield {
      val fakeRepo = new InMemoryOAuthRepo(store)
      new OAuthCredentialServiceImpl(fakeRepo, encryptor, "cid", "csec", null.asInstanceOf[zio.http.Client], tokenCache)
    }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("OAuthCredentialServiceImpl")(
      test("store encrypts and load decrypts the credential") {
        for {
          svc    <- makeService()
          _      <- svc.store(userId, provider, sampleCred)
          loaded <- svc.load(userId, provider)
        } yield assertTrue(
          loaded.isDefined,
          loaded.exists(j => j.asObject.flatMap(_.get("access_token")).flatMap(_.asString).contains("at-123")),
        )
      },
      test("load returns None when no credential stored") {
        for {
          svc    <- makeService()
          loaded <- svc.load(userId, provider)
        } yield assertTrue(loaded.isEmpty)
      },
      test("revoke removes the stored credential") {
        for {
          svc    <- makeService()
          _      <- svc.store(userId, provider, sampleCred)
          _      <- svc.revoke(userId, provider)
          loaded <- svc.load(userId, provider)
        } yield assertTrue(loaded.isEmpty)
      },
      test("listProviders returns all linked providers") {
        for {
          svc  <- makeService()
          _    <- svc.store(userId, "google", sampleCred)
          _    <- svc.store(userId, "github", sampleCred)
          list <- svc.listProviders(userId)
        } yield assertTrue(list.toSet == Set("google", "github"))
      },
      test("listProviders returns empty list when no credentials stored") {
        for {
          svc  <- makeService()
          list <- svc.listProviders(userId)
        } yield assertTrue(list.isEmpty)
      },
      test("getExpiresAt returns the stored expiry") {
        val futureInstant = Instant.now().plusSeconds(3600)
        val credWithExpiry = Json.Obj(
          "access_token"  -> Json.Str("at"),
          "refresh_token" -> Json.Str("rt"),
          "expiry_date"   -> Json.Num(futureInstant.toEpochMilli.toDouble),
        )
        for {
          svc       <- makeService()
          _         <- svc.store(userId, provider, credWithExpiry)
          expiresAt <- svc.getExpiresAt(userId, provider)
        } yield assertTrue(expiresAt.isDefined)
      },
      test("refreshAccessToken returns cached token when not near expiry") {
        val futureExpiry = Instant.now().plusSeconds(3600).toEpochMilli.toDouble
        val cred = Json.Obj(
          "access_token"  -> Json.Str("cached-token"),
          "refresh_token" -> Json.Str("rt"),
          "expiry_date"   -> Json.Num(futureExpiry),
        )
        for {
          svc   <- makeService()
          _     <- svc.store(userId, provider, cred)
          token <- svc.refreshAccessToken(userId, provider)
        } yield assertTrue(token == "cached-token")
      },
    )

}

/** Simple in-memory credential store for testing. Stores each row as an [[ExternalCredential]]. */
class InMemoryOAuthRepo(
  store: Ref[Map[(UserId, String), ExternalCredential]],
) extends IOExternalCredentialRepository {

  override def upsert(
    userId:        UserId,
    provider:      String,
    encryptedData: zio.json.ast.Json,
    expiresAt:     Option[java.time.Instant],
    scopes:        Option[String],
  ): IO[JorlanError, Unit] =
    store.update(
      _ + ((userId, provider) -> ExternalCredential(
        id = ExternalCredentialId(0L),
        userId = userId,
        provider = provider,
        credentialData = encryptedData,
        expiresAt = expiresAt,
        scopes = scopes,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
      )),
    )

  override def find(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, Option[ExternalCredential]] =
    store.get.map(_.get((userId, provider)))

  override def delete(
    userId:   UserId,
    provider: String,
  ): IO[JorlanError, Unit] =
    store.update(_ - ((userId, provider)))

  override def listByUser(userId: UserId): IO[JorlanError, List[ExternalCredential]] =
    store.get.map(_.collect { case ((uid, _), cred) if uid == userId => cred }.toList)

  override def listOAuthProviders():          IO[JorlanError, List[String]] = ZIO.succeed(Nil)
  override def startOAuth(provider:  String): IO[JorlanError, Option[String]] = ZIO.none
  override def revokeOAuth(provider: String): IO[JorlanError, Unit] = ZIO.unit
  override def oauthStatus(provider: String): IO[JorlanError, Option[OAuthStatus]] = ZIO.none

}
