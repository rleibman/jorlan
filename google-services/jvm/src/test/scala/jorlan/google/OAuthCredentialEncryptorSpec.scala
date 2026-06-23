/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.google

import jorlan.*
import jorlan.service.OAuthCredentialService
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object OAuthCredentialEncryptorSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("OAuthCredentialEncryptor")(
      test("encrypt and decrypt round-trip produces original plaintext") {
        val enc = OAuthCredentialEncryptor("test-secret-key-for-unit-tests-abc")
        val plaintext = """{"access_token":"tok123","refresh_token":"rtok456"}"""
        val result = for {
          encrypted <- ZIO.fromEither(enc.encrypt(plaintext))
          decrypted <- ZIO.fromEither(enc.decrypt(encrypted))
        } yield decrypted
        result.map(d => assertTrue(d == plaintext))
      },
      test("encrypt produces different ciphertext each call (random IV)") {
        val enc = OAuthCredentialEncryptor("test-secret-key-for-unit-tests-abc")
        val plaintext = """{"access_token":"tok123"}"""
        val result = for {
          enc1 <- ZIO.fromEither(enc.encrypt(plaintext))
          enc2 <- ZIO.fromEither(enc.encrypt(plaintext))
        } yield enc1 != enc2
        result.map(diff => assertTrue(diff))
      },
      test("decrypt with wrong key fails") {
        val enc1 = OAuthCredentialEncryptor("key-one-abc-def-ghij-klmn-opqr-stuv")
        val enc2 = OAuthCredentialEncryptor("key-two-abc-def-ghij-klmn-opqr-stuv")
        val plaintext = """{"access_token":"tok123"}"""
        val result = for {
          encrypted <- ZIO.fromEither(enc1.encrypt(plaintext))
          decrypted = enc2.decrypt(encrypted)
        } yield decrypted.isLeft
        result.map(failed => assertTrue(failed))
      },
      test("decrypt with tampered ciphertext returns error") {
        val enc = OAuthCredentialEncryptor("test-secret-key-for-unit-tests-abc")
        val plaintext = """{"access_token":"tok123"}"""
        val result = for {
          encrypted <- ZIO.fromEither(enc.encrypt(plaintext))
          tampered = encrypted match {
            case Json.Obj(fields) =>
              val bad = fields.map {
                case ("ciphertext", Json.Str(v)) => ("ciphertext", Json.Str(v.dropRight(4) + "XXXX"))
                case other                       => other
              }
              Json.Obj(bad)
            case other => other
          }
        } yield enc.decrypt(tampered).isLeft
        result.map(failed => assertTrue(failed))
      },
      test("decrypt with truncated IV returns error") {
        val enc = OAuthCredentialEncryptor("test-secret-key-for-unit-tests-abc")
        val plaintext = """{"access_token":"tok123"}"""
        val result = for {
          encrypted <- ZIO.fromEither(enc.encrypt(plaintext))
          truncated = encrypted match {
            case Json.Obj(fields) =>
              val bad = fields.map {
                case ("iv", Json.Str(v)) => ("iv", Json.Str(v.take(4)))
                case other               => other
              }
              Json.Obj(bad)
            case other => other
          }
        } yield enc.decrypt(truncated).isLeft
        result.map(failed => assertTrue(failed))
      },
      test("encrypt + decrypt works with JSON object payload") {
        val enc = OAuthCredentialEncryptor("another-test-key-long-enough-32chars")
        val payload = Json.Obj(
          "access_token"  -> Json.Str("my-access-token"),
          "refresh_token" -> Json.Str("my-refresh-token"),
          "token_type"    -> Json.Str("Bearer"),
          "expires_in"    -> Json.Num(3600),
        )
        val result = for {
          encrypted <- ZIO.fromEither(enc.encrypt(payload.toJson))
          decrypted <- ZIO.fromEither(enc.decrypt(encrypted))
          parsed    <- ZIO.fromEither(decrypted.fromJson[Json].left.map(e => JorlanError(e)))
        } yield parsed
        result.map(p => assertTrue(p == payload))
      },
    )

}
