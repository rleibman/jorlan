/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package zio.json

import zio.json.literal.JsonLiteralMacros

import zio.json.*
import zio.json.ast.Json

package object literal {

  extension (inline sc: StringContext) {

    inline final def json(inline args: Any*): Json = ${ JsonLiteralMacros.jsonImpl('sc, 'args) }

  }

}
