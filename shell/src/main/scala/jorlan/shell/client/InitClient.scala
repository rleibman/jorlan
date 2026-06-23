/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell.client

import jorlan.init.*
import jorlan.shell.ServerUrl
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.json.*

import scala.language.unsafeNulls

/** HTTP client for the first-run initialization endpoints: `GET /api/status` and `POST /api/init`. */
trait InitClient {

  /** Fetches `GET /api/status` from `serverUrl` and decodes the response.
    *
    * @return
    *   the decoded [[ServerStatus]], or a human-readable error string (not a typed [[jorlan.JorlanError]]) on network
    *   failure, non-2xx response, or JSON decode failure.
    */
  def checkStatus(serverUrl: ServerUrl): IO[String, ServerStatus]

  /** Posts `POST /api/init` with the given setup parameters.
    *
    * @return
    *   `Unit` on success (HTTP 2xx), or a human-readable error string on failure. The error channel carries plain
    *   strings rather than typed [[jorlan.JorlanError]]s because this client crosses the HTTP boundary.
    */
  def complete(
    serverUrl:     ServerUrl,
    token:         String,
    serverName:    String,
    adminEmail:    String,
    adminName:     String,
    adminPassword: String,
  ): IO[String, Unit]

}

object InitClient {

  def checkStatus(serverUrl: ServerUrl): ZIO[InitClient, String, ServerStatus] =
    ZIO.serviceWithZIO[InitClient](_.checkStatus(serverUrl))

  def complete(
    serverUrl:     ServerUrl,
    token:         String,
    serverName:    String,
    adminEmail:    String,
    adminName:     String,
    adminPassword: String,
  ): ZIO[InitClient, String, Unit] =
    ZIO.serviceWithZIO[InitClient](_.complete(serverUrl, token, serverName, adminEmail, adminName, adminPassword))

  val live: ZLayer[Any, Throwable, InitClient] =
    ZLayer.scoped(HttpClientZioBackend.scoped().map(InitClientImpl(_)))

  private[client] def makeForTesting(backend: Backend[Task]): InitClient = InitClientImpl(backend)

}

private class InitClientImpl(backend: Backend[Task]) extends InitClient {

  override def checkStatus(serverUrl: ServerUrl): IO[String, ServerStatus] =
    basicRequest
      .get(uri"${serverUrl.value}/api/status")
      .readTimeout(scala.concurrent.duration.FiniteDuration(5, java.util.concurrent.TimeUnit.SECONDS))
      .send(backend)
      .mapError(e => s"Connection error: ${e.getMessage}")
      .flatMap { resp =>
        if (resp.code.isSuccess) {
          ZIO
            .fromEither(resp.body)
            .mapError(err => s"Status response error: $err")
            .flatMap(body =>
              ZIO
                .fromEither(body.fromJson[ServerStatus])
                .mapError(e => s"Failed to decode server status: $e"),
            )
        } else {
          ZIO.fail(s"Server status failed (${resp.code.code}): ${resp.body.merge}")
        }
      }

  override def complete(
    serverUrl:     ServerUrl,
    token:         String,
    serverName:    String,
    adminEmail:    String,
    adminName:     String,
    adminPassword: String,
  ): IO[String, Unit] = {
    val body = InitRequest(token, serverName, adminEmail, adminName, adminPassword).toJson
    basicRequest
      .post(uri"${serverUrl.value}/api/init")
      .body(body)
      .contentType("application/json")
      .send(backend)
      .mapError(e => s"Connection error: ${e.getMessage}")
      .flatMap { resp =>
        ZIO.fail(s"Init failed (${resp.code.code}): ${resp.body.merge}").unless(resp.code.isSuccess).unit
      }
  }

}
