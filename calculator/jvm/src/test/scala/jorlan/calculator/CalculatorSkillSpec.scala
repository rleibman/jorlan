/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.calculator

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object CalculatorSkillSpec extends ZIOSpecDefault {

  private val skill = new CalculatorSkill()

  /** A minimal invocation context — calculator doesn't use any of it. */
  private val ctx = InvocationContext(
    actorId = UserId(1L),
    agentId = None,
    sessionId = None,
  )

  private def args(expression: String): Json =
    Json.Obj("expression" -> Json.Str(expression))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CalculatorSkillSpec")(
      test("evaluates 2 + 2 to 4.0") {
        for {
          result <- skill.invoke(ctx, "calculator.evaluate", args("2 + 2"))
        } yield result match {
          case Json.Obj(fields) =>
            val resultVal = fields.collectFirst { case ("result", Json.Num(n)) => n.doubleValue() }
            assertTrue(resultVal.exists(v => (v - 4.0).abs < 1e-9))
          case _ => assertTrue(false)
        }
      },
      test("evaluates sqrt(16) to 4.0") {
        for {
          result <- skill.invoke(ctx, "calculator.evaluate", args("sqrt(16)"))
        } yield result match {
          case Json.Obj(fields) =>
            val resultVal = fields.collectFirst { case ("result", Json.Num(n)) => n.doubleValue() }
            assertTrue(resultVal.exists(v => (v - 4.0).abs < 1e-9))
          case _ => assertTrue(false)
        }
      },
      test("evaluates a compound expression correctly") {
        // 2 + 2 * sqrt(9) = 2 + 2 * 3 = 8
        for {
          result <- skill.invoke(ctx, "calculator.evaluate", args("2 + 2 * sqrt(9)"))
        } yield result match {
          case Json.Obj(fields) =>
            val resultVal = fields.collectFirst { case ("result", Json.Num(n)) => n.doubleValue() }
            assertTrue(resultVal.exists(v => (v - 8.0).abs < 1e-9))
          case _ => assertTrue(false)
        }
      },
      test("returns error for invalid expression") {
        for {
          result <- skill.invoke(ctx, "calculator.evaluate", args("not_a_math_expression @@@ !!!"))
        } yield result match {
          case Json.Obj(fields) =>
            val hasError = fields.exists { case ("error", _) => true; case _ => false }
            assertTrue(hasError)
          case _ => assertTrue(false)
        }
      },
      test("returns error for division by zero") {
        for {
          result <- skill.invoke(ctx, "calculator.evaluate", args("1 / 0"))
        } yield result match {
          case Json.Obj(fields) =>
            // mXparser yields Infinity for 1/0, which we map to an error
            val hasError = fields.exists { case ("error", _) => true; case _ => false }
            assertTrue(hasError)
          case _ => assertTrue(false)
        }
      },
      test("fails with ValidationError when expression field is missing") {
        for {
          result <- skill.invoke(ctx, "calculator.evaluate", Json.Obj()).exit
        } yield assert(result)(fails(isSubtype[ValidationError](anything)))
      },
      test("fails with ValidationError for unknown tool") {
        for {
          result <- skill.invoke(ctx, "calculator.unknown_tool", args("2 + 2")).exit
        } yield assert(result)(fails(isSubtype[ValidationError](anything)))
      },
    )

}
