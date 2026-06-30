/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object DeclarativeArgSubstitutionSpec extends ZIOSpecDefault {

  override def spec: Spec[Any, Nothing] =
    suite("DeclarativeArgSubstitutionSpec")(
      test("substitutes {{key}} placeholder for string value") {
        val result = DeclarativeArgSubstitution.substitute(
          "Hello {{name}}!",
          Json.Obj("name" -> Json.Str("World")),
        )
        assert(result)(equalTo("Hello World!"))
      },
      test("substitutes {{key}} placeholder for numeric value") {
        val result = DeclarativeArgSubstitution.substitute(
          "Count: {{n}}",
          Json.Obj("n" -> Json.Num(42)),
        )
        assert(result)(equalTo("Count: 42"))
      },
      test("substitutes {{key}} placeholder for boolean value") {
        val result = DeclarativeArgSubstitution.substitute(
          "Flag: {{flag}}",
          Json.Obj("flag" -> Json.Bool(true)),
        )
        assert(result)(equalTo("Flag: true"))
      },
      test("leaves non-scalar (object) value placeholder unchanged") {
        val result = DeclarativeArgSubstitution.substitute(
          "Val: {{obj}}",
          Json.Obj("obj" -> Json.Obj("nested" -> Json.Str("x"))),
        )
        assert(result)(equalTo("Val: {{obj}}"))
      },
      test("returns template unchanged when args is not a Json.Obj") {
        val result = DeclarativeArgSubstitution.substitute("Hello {{name}}!", Json.Str("bad"))
        assert(result)(equalTo("Hello {{name}}!"))
      },
      test("substitutes multiple placeholders in a single template") {
        val result = DeclarativeArgSubstitution.substitute(
          "{{a}} + {{b}} = {{c}}",
          Json.Obj("a" -> Json.Num(1), "b" -> Json.Num(2), "c" -> Json.Num(3)),
        )
        assert(result)(equalTo("1 + 2 = 3"))
      },
    )

}
