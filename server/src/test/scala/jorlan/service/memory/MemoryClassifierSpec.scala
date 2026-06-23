/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.memory

import jorlan.MemoryScope
import jorlan.service.memory.MemoryClassifierImpl
import zio.test.*

object MemoryClassifierSpec extends ZIOSpecDefault {

  private val classifier = MemoryClassifierImpl()

  override def spec: Spec[Any, Nothing] =
    suite("MemoryClassifier")(
      test("PII keywords → Private") {
        for {
          s1 <- classifier.classify("my password is hunter2")
          s2 <- classifier.classify("the secret token is abc123")
          s3 <- classifier.classify("here is my private key")
        } yield assertTrue(s1 == MemoryScope.Private, s2 == MemoryScope.Private, s3 == MemoryScope.Private)
      },
      test("sharing keywords → Shared") {
        for {
          s1 <- classifier.classify("this should be shared with everyone")
          s2 <- classifier.classify("share with all members please")
          s3 <- classifier.classify("public information about the project")
        } yield assertTrue(s1 == MemoryScope.Shared, s2 == MemoryScope.Shared, s3 == MemoryScope.Shared)
      },
      test("neutral content → User") {
        for {
          s1 <- classifier.classify("user prefers Python over Java")
          s2 <- classifier.classify("the project is called Jorlan")
          s3 <- classifier.classify("decided to use ZIO for effects")
        } yield assertTrue(s1 == MemoryScope.User, s2 == MemoryScope.User, s3 == MemoryScope.User)
      },
    )

}
