/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell.client

import jorlan.shell.ShellConfig
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.model.Header
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Thin sttp-based GraphQL client. Executes arbitrary queries/mutations against the Jorlan server and returns the raw
  * `data` JSON, failing with an error string if the server returns GraphQL errors or an HTTP error.
  *
  * P7-033: Phase 8 will add more server interactions. At that point, consider extracting a shared `JorlanHttpClient`
  * service that owns the HTTP backend and JWT lifecycle, with `AuthClient` and `GraphQLClient` becoming thin facades
  * over it.
  */
trait GraphQLClient {

  def execute(
    query:     String,
    variables: Option[Json] = None,
  ): IO[String, Json]

}

object GraphQLClient {

  def execute(
    query:     String,
    variables: Option[Json] = None,
  ): ZIO[GraphQLClient, String, Json] = ZIO.serviceWithZIO[GraphQLClient](_.execute(query, variables))

  // P7-001: Backend is acquired once at layer construction time via ZLayer.scoped.
  val live: ZLayer[ShellConfig & AuthClient, Throwable, GraphQLClient] = ZLayer.scoped {
    for {
      cfg     <- ZIO.service[ShellConfig]
      auth    <- ZIO.service[AuthClient]
      backend <- HttpClientZioBackend.scoped()
    } yield GraphQLClientImpl(cfg, auth, backend)
  }

  // Factory for tests — allows injecting a stub backend without a real HTTP server.
  private[client] def makeForTesting(
    cfg:     ShellConfig,
    auth:    AuthClient,
    backend: Backend[Task],
  ): GraphQLClient = GraphQLClientImpl(cfg, auth, backend)

}

private case class GQLRequest(
  query:     String,
  variables: Option[Json] = None,
) derives JsonEncoder

private case class GQLError(message: String) derives JsonDecoder

private case class GQLResponse(
  data:   Option[Json] = None,
  errors: Option[List[GQLError]] = None,
) derives JsonDecoder

// Made private[client] so tests can construct instances with a stub backend.
private[client] class GraphQLClientImpl(
  cfg:     ShellConfig,
  auth:    AuthClient,
  backend: Backend[Task],
) extends GraphQLClient {

  override def execute(
    query:     String,
    variables: Option[Json] = None,
  ): IO[String, Json] = {
    val body = GQLRequest(query, variables).toJson
    for {
      token <- auth.currentToken
      resp  <- basicRequest
        .post(uri"${cfg.serverUrl}/api/jorlan")
        .body(body)
        .contentType("application/json")
        .headers(token.map(t => Header("Authorization", s"Bearer $t")).toList*)
        .send(backend)
        .mapError(e => s"HTTP error on GraphQL request: ${e.getMessage}")
      data <- resp.body match {
        case Left(err)   => ZIO.fail(s"GraphQL response error (${resp.code.code}): $err")
        case Right(json) =>
          ZIO
            .fromEither(json.fromJson[GQLResponse])
            .mapError(e => s"Failed to decode GraphQL response: $e")
            .flatMap {
              case GQLResponse(_, Some(errs)) if errs.nonEmpty =>
                ZIO.fail(errs.map(_.message).mkString("; "))
              case GQLResponse(Some(data), _) =>
                ZIO.succeed(data)
              case _ =>
                ZIO.succeed(Json.Obj())
            }
      }
    } yield data
  }

}
