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

import jorlan.init.*
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.json.*

import scala.language.unsafeNulls

/** HTTP client for the first-run initialization endpoints: `GET /api/status` and `POST /api/init`. */
trait InitClient {

  def checkStatus(serverUrl: String): IO[String, ServerStatus]

  def complete(
    serverUrl:     String,
    token:         String,
    serverName:    String,
    adminEmail:    String,
    adminName:     String,
    adminPassword: String,
  ): IO[String, Unit]

}

object InitClient {

  def checkStatus(serverUrl: String): ZIO[InitClient, String, ServerStatus] =
    ZIO.serviceWithZIO[InitClient](_.checkStatus(serverUrl))

  def complete(
    serverUrl:     String,
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

  override def checkStatus(serverUrl: String): IO[String, ServerStatus] =
    basicRequest
      .get(uri"$serverUrl/api/status")
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
    serverUrl:     String,
    token:         String,
    serverName:    String,
    adminEmail:    String,
    adminName:     String,
    adminPassword: String,
  ): IO[String, Unit] = {
    val body = InitRequest(token, serverName, adminEmail, adminName, adminPassword).toJson
    basicRequest
      .post(uri"$serverUrl/api/init")
      .body(body)
      .contentType("application/json")
      .send(backend)
      .mapError(e => s"Connection error: ${e.getMessage}")
      .flatMap { resp =>
        ZIO.fail(s"Init failed (${resp.code.code}): ${resp.body.merge}").unless(resp.code.isSuccess)
      }
  }

}
