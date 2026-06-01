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

import jorlan.db.repository.RepositoryError
import zio.test.*

import java.sql.{SQLNonTransientException, SQLTransientException}

object RepositoryErrorSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment, Any] =
    suite("RepositoryError.apply(Throwable)")(
      test("plain RuntimeException — isTransient defaults to false") {
        val cause = new RuntimeException("boom")
        val err = RepositoryError(cause)
        assertTrue(err.msg == "boom", err.cause.contains(cause), !err.isTransient)
      },
      test("SQLTransientException — isTransient is true") {
        val cause = new SQLTransientException("deadlock detected")
        val err = RepositoryError(cause)
        assertTrue(err.msg == "deadlock detected", err.cause.contains(cause), err.isTransient)
      },
      test("SQLNonTransientException — isTransient is false") {
        val cause = new SQLNonTransientException("constraint violation")
        val err = RepositoryError(cause)
        assertTrue(err.msg == "constraint violation", err.cause.contains(cause), !err.isTransient)
      },
      test("direct constructor — message and transience as supplied") {
        val err = RepositoryError("custom msg", isTransient = true)
        assertTrue(err.msg == "custom msg", err.isTransient, err.cause.isEmpty)
      },
    )

}
