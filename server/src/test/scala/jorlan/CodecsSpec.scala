/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import jorlan.Codecs.given
import just.semver.SemVer
import zio.*
import zio.http.MediaType
import zio.json.*
import zio.test.*

import java.net.URI
import java.security.KeyPairGenerator
import scala.language.unsafeNulls

object CodecsSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment, Any] =
    suite("Codecs")(
      semverSuite,
      mediaTypeSuite,
      uriSuite,
      publicKeySuite,
    )

  private val semverSuite = suite("SemVer codec")(
    test("encodes and decodes a valid version") {
      val v = SemVer.parse("1.2.3").toOption.get
      val json = v.toJson
      val decoded = json.fromJson[SemVer]
      assertTrue(decoded.isRight, decoded.toOption.get == v)
    },
    test("decodes common versions") {
      for {
        v100 <- ZIO.fromEither(SemVer.parse("1.0.0").left.map(e => Exception(e.toString)))
        v200 <- ZIO.fromEither(SemVer.parse("2.0.0-alpha.1").left.map(e => Exception(e.toString)))
        json100 = v100.toJson
        json200 = v200.toJson
      } yield assertTrue(
        json100.fromJson[SemVer].isRight,
        json200.fromJson[SemVer].isRight,
      )
    },
    test("fails to decode an invalid semver string") {
      val json = "\"not-a-semver\""
      assertTrue(json.fromJson[SemVer].isLeft)
    },
  )

  private val mediaTypeSuite = suite("MediaType codec")(
    test("encodes and decodes application/json") {
      val mt = MediaType.application.json
      val json = mt.toJson
      val decoded = json.fromJson[MediaType]
      assertTrue(decoded.isRight)
    },
    test("decodes text/plain") {
      val json = "\"text/plain\""
      assertTrue(json.fromJson[MediaType].isRight)
    },
    test("fails to decode an unrecognised media type") {
      val json = "\"not/a/media/type/at/all/extra\""
      assertTrue(json.fromJson[MediaType].isLeft)
    },
  )

  private val uriSuite = suite("URI codec")(
    test("encodes and decodes a valid URI") {
      val uri = URI.create("https://example.com/path?q=1")
      val json = uri.toJson
      val decoded = json.fromJson[URI]
      assertTrue(decoded.isRight, decoded.toOption.get == uri)
    },
    test("roundtrips a file URI") {
      val uri = URI.create("file:///tmp/report.pdf")
      val json = uri.toJson
      assertTrue(json.fromJson[URI].contains(uri))
    },
  )

  private val publicKeySuite = suite("PublicKey codec")(
    test("encodes and decodes an RSA public key") {
      val kpg = KeyPairGenerator.getInstance("RSA")
      kpg.initialize(2048)
      val pubKey = kpg.generateKeyPair().getPublic.nn
      val json = pubKey.toJson
      val decoded = json.fromJson[java.security.PublicKey]
      assertTrue(decoded.isRight)
    },
    test("encodes and decodes an EC public key") {
      val kpg = KeyPairGenerator.getInstance("EC")
      kpg.initialize(256)
      val pubKey = kpg.generateKeyPair().getPublic.nn
      val json = pubKey.toJson
      val decoded = json.fromJson[java.security.PublicKey]
      assertTrue(decoded.isRight)
    },
    test("fails to decode an invalid public key PEM") {
      val json = "\"-----BEGIN PUBLIC KEY-----\\nnotvalidbase64!!!\\n-----END PUBLIC KEY-----\""
      assertTrue(json.fromJson[java.security.PublicKey].isLeft)
    },
  )

}
