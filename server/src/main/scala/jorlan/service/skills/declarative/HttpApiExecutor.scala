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
    val url = DeclarativeArgSubstitution.substitute(config.url, args)

    val method = config.method.toUpperCase match {
      case "GET"    => Method.GET
      case "POST"   => Method.POST
      case "PUT"    => Method.PUT
      case "DELETE" => Method.DELETE
      case "PATCH"  => Method.PATCH
      case _        => Method.GET
    }

    val bodyContent: Body = config.bodyTemplate match {
      case Some(tmpl) => Body.fromString(DeclarativeArgSubstitution.substitute(tmpl, args))
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
        .timeoutFail(JorlanError(s"HTTP request to $url timed out after 30 seconds"))(30.seconds)
        .flatMap { resp =>
          if (!resp.status.isSuccess) {
            resp.body.asString
              .mapError(e => JorlanError(s"HTTP request to $url failed with ${resp.status.code}: ${e.getMessage}"))
              .flatMap { body =>
                ZIO.fail(JorlanError(s"HTTP request to $url failed with status ${resp.status.code}: $body"))
              }
          } else {
            resp.body.asString.map { bodyStr =>
              config.responseJsonPath match {
                case Some(path) =>
                  bodyStr
                    .fromJson[Json].toOption
                    .flatMap(extractJsonPath(_, path))
                    .getOrElse(Json.Str(bodyStr))
                case None =>
                  bodyStr.fromJson[Json] match {
                    case Right(json) => json
                    case Left(_)     => Json.Str(bodyStr)
                  }
              }
            }
          }
        }
        .mapError(e =>
          e match {
            case je: JorlanError => je
            case _ => JorlanError(s"HTTP request to $url failed: ${Option(e.getMessage).getOrElse(e.toString)}")
          },
        )
    }
  }

  private def extractJsonPath(
    json: Json,
    path: String,
  ): Option[Json] = {
    val parts = path.split('.').filter(_.nonEmpty).toList
    parts.foldLeft(Option(json)) {
      case (Some(Json.Obj(fields)), key) => fields.collectFirst { case (`key`, v) => v }
      case _                             => None
    }
  }

}
