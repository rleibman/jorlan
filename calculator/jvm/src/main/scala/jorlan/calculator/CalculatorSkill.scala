/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.calculator

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import just.semver.SemVer
import org.mariuszgromada.math.mxparser.{Expression, License}
import zio.*
import zio.json.ast.Json
import zio.json.literal.*

/** Tier 0 calculator skill — evaluates mathematical expressions via mXparser.
  *
  * Exposes a single `calculator.evaluate` tool that accepts a math expression string and returns the numeric result. No
  * capabilities are required — math is a safe, capability-free operation.
  */
class CalculatorSkill extends Skill {

  // Suppress mXparser license warnings — this project is non-commercial research/internal use.
  License.iConfirmNonCommercialUse("Jorlan")

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "calculator",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "math",
      "arithmetic",
      "calculate",
      "compute",
      "formula",
      "equation",
      "number",
      "sum",
      "product",
      "divide",
      "multiply",
      "subtract",
      "add",
      "algebra",
      "trigonometry",
      "logarithm",
      "square root",
      "percentage",
      "percent",
      "expression",
      "evaluate",
      "sine",
      "cosine",
      "tangent",
      "square root",
      "logarithm",
      "times",
      "-",
      "+",
      "*",
      "/",
      "%",
    ),
    doc = Some(
      """|## Calculator Skill
         |
         |Evaluates mathematical expressions using the mXparser library. No capabilities required.
         |
         |### Tools
         || Tool | Description | Capability |
         ||------|-------------|------------|
         || `calculator.evaluate` | Evaluate a math expression | *(none)* |
         |
         |### Configuration
         |No configuration required. The skill is always available.
         |
         |### Supported Operations
         |Arithmetic, algebra, trigonometry (`sin`, `cos`, `tan`), logarithms (`log`, `ln`), square root (`sqrt`), exponentiation (`^`), percentage (`%`), and many common math functions.""".stripMargin,
    ),
    tools = List(
      ToolDescriptor(
        name = "calculator.evaluate",
        description = "Evaluate a mathematical expression and return the numeric result. Supports arithmetic, algebra, trigonometry, logarithms, and common math functions (e.g. sqrt, sin, cos, log). Returns an error if the expression is invalid or produces an undefined result (NaN or Infinity).",
        inputSchema = json"""{"type":"object","properties":{"expression":{"type":"string","description":"The mathematical expression to evaluate, e.g. \"2 + 2 * sqrt(9)\""}},"required":["expression"]}""",
        outputSchema = json"""{"type":"object","oneOf":[{"properties":{"result":{"type":"number"},"expression":{"type":"string"}},"required":["result","expression"]},{"properties":{"error":{"type":"string"},"expression":{"type":"string"}},"required":["error","expression"]}]}""",
        requiredCapabilities = List.empty,
        examplePrompts = List(
          "What is 2 + 2?",
          "Calculate the square root of 144",
          "What is 15% of 200?",
          "Evaluate sin(pi/2)",
          "What is 2^10?",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "calculator.evaluate" =>
        val expressionStrOpt: Option[String] = args match {
          case Json.Obj(fields) => fields.collectFirst { case ("expression", Json.Str(v)) => v }
          case _                => None
        }
        expressionStrOpt match {
          case None =>
            ZIO.fail(ValidationError("missing field 'expression'"))
          case Some(expressionStr) =>
            ZIO
              .attempt {
                val expr = new Expression(expressionStr)
                val result = expr.calculate()
                if (result.isNaN || result.isInfinite) {
                  Json.Obj(
                    "error" -> Json.Str(s"Expression '$expressionStr' yielded an undefined result (${
                        if (result.isNaN) "NaN" else "Infinity"
                      })"),
                    "expression" -> Json.Str(expressionStr),
                  )
                } else {
                  Json.Obj(
                    "result"     -> Json.Num(result),
                    "expression" -> Json.Str(expressionStr),
                  )
                }
              }.mapError(e => JorlanError(s"Calculator error: ${Option(e.getMessage).getOrElse("unknown error")}"))
        }

      case other =>
        ZIO.fail(ValidationError(s"unknown tool '$other'"))
    }

}

object CalculatorSkill
