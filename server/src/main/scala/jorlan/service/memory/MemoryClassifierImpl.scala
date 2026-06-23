/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.memory

import jorlan.MemoryScope
import jorlan.*
import jorlan.service.MemoryClassifier
import zio.*

/** Heuristic [[MemoryClassifier]].
  *
  * Rules (applied in order, first match wins):
  *   1. PII keywords → `Private`
  *   2. Explicit sharing language → `Shared`
  *   3. Default → `User`
  */
class MemoryClassifierImpl extends MemoryClassifier {

  import MemoryClassifierImpl.*

  override def classify(content: String): UIO[MemoryScope] =
    ZIO.succeed {
      val lower = content.toLowerCase
      if (piiKeywords.exists(lower.contains)) MemoryScope.Private
      else if (sharedKeywords.exists(lower.contains)) MemoryScope.Shared
      else MemoryScope.User
    }

}

object MemoryClassifierImpl {

  /** Keywords that indicate personally-identifiable or sensitive information → classified as `Private`. */
  private val piiKeywords: Set[String] =
    Set("password", "secret", "token", "ssn", "credit card", "bank account", "private key")

  /** Keywords that indicate intent to share broadly → classified as `Shared`. */
  private val sharedKeywords: Set[String] =
    Set("everyone", "team", "share with all", "public", "shared", "for the team")

  val live: ULayer[MemoryClassifier] = ZLayer.succeed(MemoryClassifierImpl())

}
