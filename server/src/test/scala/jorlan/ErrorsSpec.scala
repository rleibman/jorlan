/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import zio.test.*

import java.nio.file.Paths

object ErrorsSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment, Any] =
    suite("JorlanError and NotFoundError")(
      test("JorlanError.apply(Throwable) wraps a plain exception") {
        val cause = RuntimeException("boom")
        val err = JorlanError(cause)
        assertTrue(err.msg == "boom", err.cause.contains(cause))
      },
      test("JorlanError.apply(Throwable) returns the original when cause is already a JorlanError") {
        val original = JorlanError("original")
        val wrapped = JorlanError(original: Throwable)
        assertTrue(wrapped eq original)
      },
      test("JorlanError.apply(String) creates error with message") {
        val err = JorlanError("simple message")
        assertTrue(err.msg == "simple message", err.cause.isEmpty, !err.isTransient)
      },
      test("JorlanError.apply(msg, cause, isTransient) with plain cause") {
        val cause = IllegalStateException("state")
        val err = JorlanError("wrapped", Some(cause), isTransient = true)
        assertTrue(err.msg == "wrapped", err.cause.contains(cause), err.isTransient)
      },
      test("JorlanError.apply(msg, cause) wraps a JorlanError cause, preserving outer message") {
        val inner = JorlanError("inner error")
        val err = JorlanError("outer", Some(inner))
        assertTrue(err.msg == "outer", err.cause.contains(inner))
      },
      test("JorlanError.apply(msg, None) has no cause") {
        val err = JorlanError("no cause", None)
        assertTrue(err.cause.isEmpty)
      },
      test("NotFoundError.apply(path, message) creates error") {
        val path = Paths.get("/tmp/missing.txt")
        val err = NotFoundError(path, "file not found")
        assertTrue(err.path == path, err.msg == "file not found", err.cause.isEmpty)
      },
      test("NotFoundError.apply with cause and isTransient") {
        val path = Paths.get("/var/data")
        val cause = RuntimeException("io error")
        val err = NotFoundError(path, "data missing", Some(cause), isTransient = true)
        assertTrue(err.path == path, err.cause.contains(cause), err.isTransient)
      },
      test("JorlanError is an Exception with getMessage") {
        val err = JorlanError("test msg")
        assertTrue(err.getMessage == "test msg")
      },
    )

}
