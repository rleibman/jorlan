/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.routes

import jorlan.service.OAuthReconnectService
import jorlan.UserId
import zio.*
import zio.test.*
import zio.test.Assertion.*

object OAuthRoutesSpec extends ZIOSpecDefault {

  private val testSecret = "test-secret-key-for-jwt-signing"
  private val redirectUri = "http://localhost:8080/api/oauth/callback/google"
  private val userId = UserId(42L)
  private val provider = "google"

  private val reconnectLayer: ULayer[OAuthReconnectService] =
    ZLayer.fromZIO(OAuthReconnectService.make(testSecret, "test-client-id", redirectUri))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("OAuthReconnectService")(
      test("buildAuthUrl returns a Google authorization URL") {
        for {
          svc <- ZIO.service[OAuthReconnectService]
          url <- svc.buildAuthUrl(userId, provider)
        } yield assertTrue(
          url.startsWith("https://accounts.google.com/o/oauth2/v2/auth"),
          url.contains("response_type=code"),
          url.contains("client_id=test-client-id"),
          url.contains("access_type=offline"),
        )
      },
      test("buildAuthUrl fails for unsupported provider") {
        for {
          svc    <- ZIO.service[OAuthReconnectService]
          result <- svc.buildAuthUrl(userId, "dropbox").either
        } yield assertTrue(result.isLeft)
      },
      test("verifyAndConsume succeeds for a freshly built URL") {
        for {
          svc <- ZIO.service[OAuthReconnectService]
          url <- svc.buildAuthUrl(userId, provider)
          state = extractState(url)
          result <- svc.verifyAndConsume(state)
        } yield assertTrue(
          result._1 == userId,
          result._2 == provider,
        )
      },
      test("verifyAndConsume fails on replay (nonce consumed)") {
        for {
          svc <- ZIO.service[OAuthReconnectService]
          url <- svc.buildAuthUrl(userId, provider)
          state = extractState(url)
          _      <- svc.verifyAndConsume(state)
          second <- svc.verifyAndConsume(state).either
        } yield assertTrue(second.isLeft)
      },
      test("verifyAndConsume rejects tampered state") {
        for {
          svc <- ZIO.service[OAuthReconnectService]
          url <- svc.buildAuthUrl(userId, provider)
          state = extractState(url)
          tampered = state.split("\\.", 2) match {
            case Array(payload, sig) =>
              val decoded = new String(java.util.Base64.getUrlDecoder.decode(payload), "UTF-8")
              val modified = decoded.replace(s"${userId.value}", "999")
              val reEnc = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(modified.getBytes("UTF-8"))
              s"$reEnc.$sig"
            case _ => state
          }
          result <- svc.verifyAndConsume(tampered).either
        } yield assertTrue(result.isLeft)
      },
      test("verifyAndConsume rejects malformed state") {
        for {
          svc    <- ZIO.service[OAuthReconnectService]
          result <- svc.verifyAndConsume("nodothere").either
        } yield assertTrue(result.isLeft)
      },
    ).provideLayer(reconnectLayer)

  private def extractState(url: String): String = {
    import scala.language.unsafeNulls
    val query = url.split("\\?", 2)(1)
    query
      .split("&")
      .collectFirst {
        case p if p.startsWith("state=") =>
          java.net.URLDecoder.decode(p.stripPrefix("state="), "UTF-8")
      }
      .getOrElse(sys.error("state param not found in URL"))
  }

}
