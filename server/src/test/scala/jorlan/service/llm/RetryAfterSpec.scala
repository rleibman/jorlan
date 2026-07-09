/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.llm

import zio.*
import zio.test.*

/** Covers [[RetryAfter]] parsing of real provider rate-limit messages (Groq and Gemini formats). */
object RetryAfterSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("RetryAfter")(
      test("parses Groq seconds-only hint (with 1s buffer)") {
        val msg =
          """{"error":{"message":"Rate limit reached for model `llama-3.3-70b-versatile` on tokens per minute (TPM): Limit 12000, Used 11295, Requested 1834. Please try again in 5.645s.","type":"tokens"}}"""
        assertTrue(RetryAfter.parse(msg).contains(Duration.fromMillis(6645L)))
      },
      test("parses Groq minutes+seconds hint") {
        val msg = "Rate limit reached on tokens per day (TPD): Limit 100000. Please try again in 1m16.032s."
        assertTrue(RetryAfter.parse(msg).contains(Duration.fromMillis(60000L + 16032L + 1000L)))
      },
      test("parses Gemini retry hint") {
        val msg = "You exceeded your current quota. Please retry in 55.396537719s."
        // 55.396537719s rounds up to 55397 ms, plus the 1s buffer
        assertTrue(RetryAfter.parse(msg).contains(Duration.fromMillis(55397L + 1000L)))
      },
      test("returns None when the message has no hint") {
        assertTrue(RetryAfter.parse("HTTP 429 Too Many Requests").isEmpty)
      },
    )

}
