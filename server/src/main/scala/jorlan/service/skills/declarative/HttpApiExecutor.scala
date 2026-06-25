/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import jorlan.JorlanError
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** Executes a [[HttpApiExecutorConfig]] tool by making an HTTP request. Substitutes `{{paramName}}` tokens in the URL
  * and body template from the invocation arguments.
  */
object HttpApiExecutor {

  def execute(
    config: HttpApiExecutorConfig,
    args:   Json,
    client: Client,
  ): IO[JorlanError, Json] = {
    val url = substitute(config.url, args)

    val method = config.method.toUpperCase match {
      case "GET"    => Method.GET
      case "POST"   => Method.POST
      case "PUT"    => Method.PUT
      case "DELETE" => Method.DELETE
      case "PATCH"  => Method.PATCH
      case _        => Method.GET
    }

    val bodyContent: Body = config.bodyTemplate match {
      case Some(tmpl) => Body.fromString(substitute(tmpl, args))
      case None       => Body.empty
    }

    val baseHeaders = Headers(
      config.headers.map { case (k, v) => Header.Custom(k, v) }.toList,
    )

    ZIO.fromEither(URL.decode(url).left.map(e => JorlanError(s"Invalid URL '$url': $e"))).flatMap { parsedUrl =>
      val req = Request(
        url = parsedUrl,
        method = method,
        headers = baseHeaders,
        body = bodyContent,
      )
      client
        .batched(req)
        .flatMap { resp =>
          resp.body.asString.map { bodyStr =>
            bodyStr.fromJson[Json] match {
              case Right(json) => json
              case Left(_)     => Json.Str(bodyStr)
            }
          }
        }
        .mapError(e => JorlanError(s"HTTP request to $url failed: ${Option(e.getMessage).getOrElse(e.toString)}"))
    }
  }

  private def substitute(
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
