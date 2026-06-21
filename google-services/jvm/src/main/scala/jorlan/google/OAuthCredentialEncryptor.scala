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

import jorlan.JorlanError
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** Encrypts and decrypts OAuth credential JSON using AES-256-GCM with a random 96-bit IV per encryption.
  *
  * Key derivation: `SHA-256("jorlan-external-credentials" ++ secret)` truncated to 32 bytes. The secret is currently
  * shared with the JWT signing key (known limitation — see P13-003 for the planned fix using a dedicated env var and
  * HKDF derivation). A new `SecureRandom` instance is created once at construction and reused for all IV generation.
  */
class OAuthCredentialEncryptor(secretKey: String) {

  private val keyBytes:     Array[Byte] = deriveKey(secretKey)
  private val secureRandom: SecureRandom = new SecureRandom()

  private def deriveKey(secret: String): Array[Byte] = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update("jorlan-external-credentials".getBytes("UTF-8"))
    md.digest(secret.getBytes("UTF-8")).take(32)
  }

  def encrypt(plaintext: String): Either[JorlanError, Json] =
    try {
      val iv = new Array[Byte](12)
      secureRandom.nextBytes(iv)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv))
      val ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"))
      val result = Json.Obj(
        "iv"         -> Json.Str(Base64.getEncoder.encodeToString(iv)),
        "ciphertext" -> Json.Str(Base64.getEncoder.encodeToString(ciphertext)),
      )
      Right(result)
    } catch {
      case e: Exception => Left(JorlanError(s"Encryption failed: ${e.getMessage}", Some(e)))
    }

  def decrypt(encrypted: Json): Either[JorlanError, String] =
    try {
      def field(key: String): String =
        encrypted match {
          case Json.Obj(fs) => fs.collectFirst { case (`key`, Json.Str(v)) => v }.getOrElse("")
          case _            => ""
        }
      val iv = Base64.getDecoder.decode(field("iv"))
      val ciphertext = Base64.getDecoder.decode(field("ciphertext"))
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv))
      Right(new String(cipher.doFinal(ciphertext), "UTF-8"))
    } catch {
      case e: Exception => Left(JorlanError(s"Decryption failed: ${e.getMessage}", Some(e)))
    }

}
