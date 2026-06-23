/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package zio.json.literal

import scala.language.unsafeNulls
import scala.quoted.*
import zio.json.*
import zio.json.ast.Json

/** Compile-time implementation of the [[json]] string interpolator.
  *
  * Strategy:
  *   1. Each interpolated argument is assigned a unique placeholder string (e.g. `"__arg0__"`).
  *   2. The template is assembled with those quoted placeholders and parsed with zio-json's own decoder, giving
  *      compile-time JSON structure validation for free.
  *   3. The resulting [[Json]] AST is walked recursively; wherever a placeholder is found (as a string value or as an
  *      object key) it is replaced with a splice of the runtime [[JsonEncoder]]-encoded argument.
  */
object JsonLiteralMacros {

  def jsonImpl(
    sc:      Expr[StringContext],
    args:    Expr[Seq[Any]],
  )(using q: Quotes,
  ): Expr[Json] = {
    import q.reflect.*

    val stringParts: Seq[String] = sc match {
      case '{ StringContext($parts*) } => parts.valueOrAbort
    }

    val replacements: Seq[Replacement] = args match {
      case Varargs(argExprs) => argExprs.zipWithIndex.map { case (arg, i) => Replacement(i, arg) }
      case _                 => report.errorAndAbort("Invalid arguments for json interpolation.")
    }

    // Build a valid JSON string by wrapping each placeholder in quotes
    val jsonString = (stringParts.init zip replacements.map(_.placeholder))
      .foldLeft("") { case (acc, (part, ph)) => s"""$acc$part"$ph"""" } + stringParts.last

    // Validate structure at compile time; then walk the AST to splice in runtime expressions
    jsonString.fromJson[Json] match {
      case Left(err)   => report.errorAndAbort(s"Invalid JSON in json interpolation: $err")
      case Right(json) => walkJson(json, replacements)
    }
  }

  private def walkJson(
    json:         Json,
    replacements: Seq[Replacement],
  )(using q:      Quotes,
  ): Expr[Json] = {
    import q.reflect.*
    json match {
      case Json.Null    => '{ Json.Null }
      case Json.Bool(b) =>
        val bExpr = Expr(b)
        '{ Json.Bool($bExpr) }
      case Json.Num(n) =>
        val s = Expr(n.toPlainString)
        '{ Json.Num(new java.math.BigDecimal($s)) }
      case Json.Str(s) =>
        replacements
          .find(_.placeholder == s)
          .fold {
            val se = Expr(s)
            '{ Json.Str($se) }
          }(_.asJson)
      case Json.Arr(elems) =>
        val elemExprs = elems.map(walkJson(_, replacements)).toList
        '{ Json.Arr(zio.Chunk[Json](${ Varargs(elemExprs) }*)) }
      case Json.Obj(fields) =>
        val fieldExprs: List[Expr[(String, Json)]] = fields.map { case (k, v) =>
          val key = replacements.find(_.placeholder == k).fold(Expr(k))(_.asKey)
          val value = walkJson(v, replacements)
          '{ ($key, $value) }
        }.toList
        '{ Json.Obj(zio.Chunk[(String, Json)](${ Varargs(fieldExprs) }*)) }
    }
  }

}
