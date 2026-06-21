/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.httpfetch

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import just.semver.SemVer
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** Built-in skill that lets agents make HTTP GET and POST requests to external URLs.
  *
  * All requests are checked against a host allowlist stored in `server_settings` under the key `"skill.http_fetch"`.
  * Requests to hosts not in the allowlist are rejected without making a network call.
  *
  * Exposes two tools:
  *   - `http_fetch.get` — HTTP GET request
  *   - `http_fetch.post` — HTTP POST request
  *
  * Both tools require the `http_fetch.call` capability.
  *
  * @param config
  *   Parsed configuration from `server_settings`.
  * @param client
  *   ZIO-HTTP client used to make requests.
  * @param urlTransform
  *   Optional URL transformation applied before each request. Defaults to identity. Intended for test use only: tests
  *   inject a function that redirects requests to an embedded HTTP server.
  */
class HttpFetchSkill(
  config:       HttpFetchConfig,
  client:       Client,
  urlTransform: String => String = identity,
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "http_fetch",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    tools = List(
      ToolDescriptor(
        name = "http_fetch.get",
        description = "Make an HTTP GET request to a URL and return the response status, body, and content type. Only hosts in the configured allowlist are permitted.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{
               |  "type": "object",
               |  "properties": {
               |    "url": {
               |      "type": "string",
               |      "description": "Full URL to fetch, e.g. https://api.example.com/data"
               |    },
               |    "headers": {
               |      "type": "object",
               |      "description": "Optional HTTP headers as string key-value pairs"
               |    }
               |  },
               |  "required": ["url"]
               |}""".stripMargin,
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("http_fetch.call")),
        examplePrompts = List(
          "Fetch the content from https://api.example.com/status",
          "GET https://jsonplaceholder.typicode.com/todos/1",
          "What does the API at this URL return?",
        ),
      ),
      ToolDescriptor(
        name = "http_fetch.post",
        description = "Make an HTTP POST request to a URL and return the response status, body, and content type. Only hosts in the configured allowlist are permitted.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{
               |  "type": "object",
               |  "properties": {
               |    "url": {
               |      "type": "string",
               |      "description": "Full URL to POST to, e.g. https://api.example.com/data"
               |    },
               |    "body": {
               |      "type": "string",
               |      "description": "Request body to send"
               |    },
               |    "contentType": {
               |      "type": "string",
               |      "description": "Content-Type header value (default: application/json)"
               |    },
               |    "headers": {
               |      "type": "object",
               |      "description": "Optional extra HTTP headers as string key-value pairs"
               |    }
               |  },
               |  "required": ["url"]
               |}""".stripMargin,
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("http_fetch.call")),
        examplePrompts = List(
          "POST JSON data to https://api.example.com/submit",
          "Send a POST request with this body",
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
      case "http_fetch.get"  => get(args)
      case "http_fetch.post" => post(args)
      case other             => ZIO.fail(ValidationError(s"HttpFetchSkill: unknown tool '$other'"))
    }

  // ---------------------------------------------------------------------------
  // Allowlist checking
  // ---------------------------------------------------------------------------

  private def extractHost(url: String): Option[String] = {
    import scala.language.unsafeNulls
    scala.util.Try(new java.net.URI(url).getHost).toOption.flatMap(h => Option(h))
  }

  private def isHostAllowed(url: String): Boolean = {
    extractHost(url) match {
      case None       => false
      case Some(host) =>
        config.allowedHosts.exists { pattern =>
          if (pattern == "*") {
            true
          } else if (pattern.startsWith("*.")) {
            val suffix = pattern.stripPrefix("*") // e.g. ".example.com"
            host.endsWith(suffix) || host == pattern.stripPrefix("*.")
          } else {
            host == pattern
          }
        }
    }
  }

  // ---------------------------------------------------------------------------
  // Argument helpers
  // ---------------------------------------------------------------------------

  private def requireStr(
    args: Json,
    key:  String,
  ): IO[JorlanError, String] =
    args match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (`key`, Json.Str(v)) => v }
          .fold(ZIO.fail(ValidationError(s"missing required argument '$key'")): IO[JorlanError, String])(ZIO.succeed(_))
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }

  /** Extract optional headers from the `"headers"` field (object of string-string pairs). */
  private def extractHeaders(
    args: Json,
    key:  String = "headers",
  ): List[(String, String)] =
    args match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (`key`, Json.Obj(hFields)) => hFields }
          .getOrElse(Chunk.empty)
          .collect { case (k, Json.Str(v)) => (k, v) }
          .toList
      case _ => List.empty
    }

  // ---------------------------------------------------------------------------
  // Response building
  // ---------------------------------------------------------------------------

  private def buildResponse(resp: Response): IO[JorlanError, Json] =
    resp.body.asString.mapBoth(
      e => JorlanError("Failed to read response body", Some(e)),
      { raw =>
        val rawBytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val body =
          if (rawBytes.length > config.maxResponseBytes) {
            new String(
              rawBytes.take(config.maxResponseBytes),
              java.nio.charset.StandardCharsets.UTF_8,
            ) + "\n[truncated]"
          } else {
            raw
          }

        val contentType = resp
          .header(Header.ContentType)
          .map(_.mediaType.fullType)
          .getOrElse("application/octet-stream")

        Json.Obj(
          "status"      -> Json.Num(resp.status.code),
          "body"        -> Json.Str(body),
          "contentType" -> Json.Str(contentType),
        )
      },
    )

  // ---------------------------------------------------------------------------
  // Tool implementations
  // ---------------------------------------------------------------------------

  private def disallowedResponse(rawUrl: String): Json = {
    val host = extractHost(rawUrl).getOrElse(rawUrl)
    Json.Obj("error" -> Json.Str(s"Host not in allowlist: $host"))
  }

  private def get(args: Json): IO[JorlanError, Json] =
    for {
      rawUrl <- requireStr(args, "url")
      result <-
        if (!isHostAllowed(rawUrl)) {
          ZIO.succeed(disallowedResponse(rawUrl))
        } else {
          val url = urlTransform(rawUrl)
          val extraHeaders = extractHeaders(args)
          val baseReq = Request.get(url)
          val req = extraHeaders.foldLeft(baseReq) { case (r, (k, v)) =>
            r.addHeader(k, v)
          }
          client
            .batched(req)
            .mapError(e => JorlanError("HTTP GET request failed", Some(e)))
            .timeoutFail(JorlanError(s"HTTP GET request timed out after ${config.timeoutSeconds}s"))(
              Duration.fromSeconds(config.timeoutSeconds.toLong),
            )
            .flatMap(buildResponse)
        }
    } yield result

  private def post(args: Json): IO[JorlanError, Json] =
    for {
      rawUrl <- requireStr(args, "url")
      result <-
        if (!isHostAllowed(rawUrl)) {
          ZIO.succeed(disallowedResponse(rawUrl))
        } else {
          val url = urlTransform(rawUrl)
          val bodyStr = optStr(args, "body").getOrElse("")
          val contentType = optStr(args, "contentType").getOrElse("application/json")
          val extraHeaders = extractHeaders(args)
          val mediaType = MediaType
            .parseCustomMediaType(contentType)
            .getOrElse(MediaType.application.json)
          val baseReq = Request
            .post(url, Body.fromString(bodyStr))
            .addHeader(Header.ContentType(mediaType))
          val req = extraHeaders.foldLeft(baseReq) { case (r, (k, v)) =>
            r.addHeader(k, v)
          }
          client
            .batched(req)
            .mapError(e => JorlanError("HTTP POST request failed", Some(e)))
            .timeoutFail(JorlanError(s"HTTP POST request timed out after ${config.timeoutSeconds}s"))(
              Duration.fromSeconds(config.timeoutSeconds.toLong),
            )
            .flatMap(buildResponse)
        }
    } yield result

}
