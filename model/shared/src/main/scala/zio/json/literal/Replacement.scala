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

/** Represents one interpolated argument in a [[json]] string literal.
  *
  * At macro expansion time a unique placeholder string is embedded in the template so the compile-time JSON parser can
  * validate the overall structure. At runtime, [[asJson]] encodes the actual Scala value via [[JsonEncoder]] and
  * [[asKey]] converts it to an object key string.
  */
final class Replacement private (
  val placeholder: String,
  val asJson:      Expr[Json],
  val asKey:       Expr[String],
)

object Replacement {

  def apply(idx: Int, arg: Expr[Any])(using q: Quotes): Replacement = {
    import q.reflect.*
    arg match {
      case '{ $a: t } =>
        val ph = s"__arg${idx}__"
        // Widen singleton/literal types (e.g. `42` → Int, `v.type` → Int) so that
        // JsonEncoder instances defined on base types are found correctly.
        val widenedTpe = TypeRepr.of[t].widen
        val jsonExpr: Expr[Json] = widenedTpe.asType match {
          case '[w] =>
            val wa      = a.asExprOf[w]
            val encoder = Expr.summon[JsonEncoder[w]].getOrElse {
              report.errorAndAbort(
                s"No JsonEncoder[${widenedTpe.show}] found for json interpolation argument at position $idx",
              )
            }
            '{
              $encoder.toJsonAST($wa) match {
                case Right(json) => json
                case Left(err)   =>
                  throw new RuntimeException(s"JsonEncoder failed for argument ${ ${ Expr(idx) } }: $err")
              }
            }
        }
        val keyExpr: Expr[String] = '{ $a.toString }
        new Replacement(ph, jsonExpr, keyExpr)
    }
  }

}
