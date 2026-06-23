/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import jorlan.db.repository.RepositoryError
import zio.test.*

import java.sql.{SQLNonTransientException, SQLTransientException}

object RepositoryErrorSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment, Any] =
    suite("RepositoryError.apply(Throwable)")(
      test("plain RuntimeException — isTransient defaults to false") {
        val cause = RuntimeException("boom")
        val err = RepositoryError(cause)
        assertTrue(err.msg == "boom", err.cause.contains(cause), !err.isTransient)
      },
      test("SQLTransientException — isTransient is true") {
        val cause = SQLTransientException("deadlock detected")
        val err = RepositoryError(cause)
        assertTrue(err.msg == "deadlock detected", err.cause.contains(cause), err.isTransient)
      },
      test("SQLNonTransientException — isTransient is false") {
        val cause = SQLNonTransientException("constraint violation")
        val err = RepositoryError(cause)
        assertTrue(err.msg == "constraint violation", err.cause.contains(cause), !err.isTransient)
      },
      test("direct constructor — message and transience as supplied") {
        val err = RepositoryError("custom msg", isTransient = true)
        assertTrue(err.msg == "custom msg", err.isTransient, err.cause.isEmpty)
      },
    )

}
