/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.routes

import jorlan.UserId
import zio.*
import zio.test.*
import zio.test.Assertion.*

object OAuthRoutesSpec extends ZIOSpecDefault {

  private val secret = "test-secret-key-for-jwt-signing"
  private val userId = UserId(42L)
  private val provider = "google"

  private def freshStore: UIO[NonceStore] = Ref.make(Map.empty[String, Long])

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("OAuthRoutes")(
      suite("buildStateJwt / verifyStateJwt")(
        test("round-trip produces a valid token") {
          for {
            store <- freshStore
            token <- OAuthRoutes.buildStateJwt(userId, provider, secret, store)
            now   <- Clock.instant
            result = OAuthRoutes.verifyStateJwt(token, secret, now.getEpochSecond)
          } yield assertTrue(
            result.isRight,
            result.exists(_.userId == userId.value),
            result.exists(_.provider == provider),
          )
        },
        test("token with tampered payload is rejected") {
          for {
            store <- freshStore
            token <- OAuthRoutes.buildStateJwt(userId, provider, secret, store)
            now   <- Clock.instant
            tampered = token.split("\\.").toList match {
              case payload :: sig :: Nil =>
                val decoded = new String(java.util.Base64.getUrlDecoder.decode(payload), "UTF-8")
                val modified = decoded.replace(s"${userId.value}", "999")
                val reEnc = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(modified.getBytes("UTF-8"))
                s"$reEnc.$sig"
              case _ => token
            }
            result = OAuthRoutes.verifyStateJwt(tampered, secret, now.getEpochSecond)
          } yield assertTrue(result.isLeft)
        },
        test("token signed with wrong secret is rejected") {
          for {
            store <- freshStore
            token <- OAuthRoutes.buildStateJwt(userId, provider, secret, store)
            now   <- Clock.instant
            result = OAuthRoutes.verifyStateJwt(token, "wrong-secret", now.getEpochSecond)
          } yield assertTrue(result.isLeft)
        },
        test("expired token is rejected") {
          for {
            store         <- freshStore
            token         <- OAuthRoutes.buildStateJwt(userId, provider, secret, store)
            futureSeconds <- Clock.instant.map(_.getEpochSecond + 1860L)
            result = OAuthRoutes.verifyStateJwt(token, secret, futureSeconds)
          } yield assertTrue(result.isLeft, result.left.exists(_.contains("expired")))
        },
        test("token with missing dot is rejected") {
          for {
            now <- Clock.instant
            result = OAuthRoutes.verifyStateJwt("nodothere", secret, now.getEpochSecond)
          } yield assertTrue(result.isLeft)
        },
        test("token with unknown provider round-trips") {
          for {
            store <- freshStore
            token <- OAuthRoutes.buildStateJwt(userId, "dropbox", secret, store)
            now   <- Clock.instant
            result = OAuthRoutes.verifyStateJwt(token, secret, now.getEpochSecond)
          } yield assertTrue(result.isRight, result.exists(_.provider == "dropbox"))
        },
        test("nonce is stored when token is built") {
          for {
            store    <- freshStore
            _        <- OAuthRoutes.buildStateJwt(userId, provider, secret, store)
            storeMap <- store.get
          } yield assertTrue(storeMap.nonEmpty)
        },
        test("verifyAndConsumeStateJwt consumes the nonce on first use") {
          for {
            store  <- freshStore
            token  <- OAuthRoutes.buildStateJwt(userId, provider, secret, store)
            now    <- Clock.instant
            result <- OAuthRoutes.verifyAndConsumeStateJwt(token, secret, now.getEpochSecond, store)
            second <- OAuthRoutes.verifyAndConsumeStateJwt(token, secret, now.getEpochSecond, store).either
          } yield assertTrue(
            result.userId == userId.value,
            second.isLeft,
            second.left.exists(_.contains("already used")),
          )
        },
      ),
    )

}
