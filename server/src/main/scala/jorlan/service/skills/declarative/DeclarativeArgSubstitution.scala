/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import zio.json.ast.Json

/** Shared `{{paramName}}` substitution for declarative skill executors. */
object DeclarativeArgSubstitution {

  def substitute(
    template: String,
    args:     Json,
  ): String =
    args match {
      case Json.Obj(fields) =>
        fields.foldLeft(template) {
          case (t, (key, Json.Str(v)))  => t.replace(s"{{$key}}", v)
          case (t, (key, Json.Num(n)))  => t.replace(s"{{$key}}", n.toString)
          case (t, (key, Json.Bool(b))) => t.replace(s"{{$key}}", b.toString)
          case (t, _)                   => t
        }
      case _ => template
    }

}
