/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import just.semver.{ParseError, SemVer}
import zio.http.MediaType
import zio.json.{JsonDecoder, JsonEncoder}

import java.net.URI
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, PublicKey}
import java.util.Base64
import scala.language.unsafeNulls

/** zio-json codecs for external library types that cannot carry their own codec given instances. Import these into any
  * file that uses one of these types in a `derives JsonCodec` case class.
  */
object Codecs {

  given JsonEncoder[SemVer] = JsonEncoder[String].contramap(_.render)

  given JsonDecoder[SemVer] =
    JsonDecoder[String].mapOrFail { s =>
      SemVer.parse(s).left.map(ParseError.render)
    }

  given JsonEncoder[MediaType] = JsonEncoder[String].contramap(_.fullType)

  given JsonDecoder[MediaType] =
    JsonDecoder[String].mapOrFail { s =>
      MediaType
        .forContentType(s)
        .orElse(MediaType.parseCustomMediaType(s))
        .toRight(s"Unrecognised MediaType: $s")
    }

  given JsonEncoder[URI] = JsonEncoder[String].contramap(_.toString)

  given JsonDecoder[URI] =
    JsonDecoder[String].mapOrFail { s =>
      try Right(URI.create(s))
      catch { case e: Exception => Left(e.getMessage.nn) }
    }

  given JsonEncoder[PublicKey] =
    JsonEncoder[String].contramap { key =>
      val b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded).nn
      s"-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----"
    }

  given JsonDecoder[PublicKey] =
    JsonDecoder[String].mapOrFail { pem =>
      val cleaned = pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "")
      try {
        val bytes = Base64.getDecoder.decode(cleaned.nn)
        val spec = X509EncodedKeySpec(bytes)
        List("RSA", "EC", "Ed25519").iterator
          .flatMap { alg =>
            try Some(KeyFactory.getInstance(alg).generatePublic(spec))
            catch { case _: Exception => None }
          }
          .nextOption()
          .toRight("Could not parse public key: unknown algorithm")
      } catch {
        case e: Exception => Left(e.getMessage.nn)
      }
    }

}
