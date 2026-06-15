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

case class LoginRequest(
  email:        String,
  password:     String,
  connectionId: Option[String] = None,
) derives JsonEncoder

case class LoginResult(
  token:       String,
  displayName: String,
  email:       Option[String],
)

/** HTTP client for the zio-auth endpoints (`POST /login`, `GET /api/whoami`, `GET /refresh`). The JWT is held in a
  * `Ref` and reused for all subsequent requests via [[GraphQLClient]]. A refresh token (from the `Set-Cookie` response
  * on login) is stored separately and used to silently renew the access token before falling back to a full re-login.
  */
trait AuthClient {

  def login(
    email:    String,
    password: String,
  ):                IO[String, LoginResult]
  def whoAmI:       IO[String, String]
  def refresh:      IO[String, String]
  def currentToken: UIO[Option[String]]

}

object AuthClient {

  def login(
    email:    String,
    password: String,
  ): ZIO[AuthClient, String, LoginResult] = ZIO.serviceWithZIO[AuthClient](_.login(email, password))

  def whoAmI:  ZIO[AuthClient, String, String] = ZIO.serviceWithZIO[AuthClient](_.whoAmI)
  def refresh: ZIO[AuthClient, String, String] = ZIO.serviceWithZIO[AuthClient](_.refresh)

  def currentToken: URIO[AuthClient, Option[String]] = ZIO.serviceWithZIO[AuthClient](_.currentToken)

  // P7-001: Backend is acquired once at layer construction time via ZLayer.scoped.
  // This avoids creating a new HttpClient thread pool on every request.
  val live: ZLayer[ShellConfig, Throwable, AuthClient] = ZLayer.scoped {
    for {
      cfg             <- ZIO.service[ShellConfig]
      tokenRef        <- Ref.make(Option.empty[String])
      refreshTokenRef <- Ref.make(Option.empty[String])
      backend         <- HttpClientZioBackend.scoped()
    } yield AuthClientImpl(cfg, tokenRef, refreshTokenRef, backend)
  }

  // Factory for tests — allows injecting a stub backend without a real HTTP server.
  private[client] def makeForTesting(
    cfg:             ShellConfig,
    tokenRef:        Ref[Option[String]],
    refreshTokenRef: Ref[Option[String]],
    backend:         Backend[Task],
  ): AuthClient = AuthClientImpl(cfg, tokenRef, refreshTokenRef, backend)

}

// Made private[client] so tests can construct instances with a stub backend.
private[client] class AuthClientImpl(
  cfg:             ShellConfig,
  tokenRef:        Ref[Option[String]],
  refreshTokenRef: Ref[Option[String]],
  backend:         Backend[Task],
) extends AuthClient {

  override def login(
    email:    String,
    password: String,
  ): IO[String, LoginResult] = {
    val body = LoginRequest(email, password).toJson
    basicRequest
      .post(uri"${cfg.serverUrl}/login")
      .body(body)
      .contentType("application/json")
      .send(backend)
      .mapError(e => s"HTTP error during login: ${e.getMessage}")
      .flatMap { resp =>
        if (resp.code.isSuccess) {
          for {
            token <- ZIO
              .fromOption(resp.header("Authorization").map(_.stripPrefix("Bearer ")))
              .orElseFail("Login succeeded but no Authorization header in response")
            json <- ZIO.fromEither(resp.body).mapError(err => s"Login response body error: $err")
            u    <- ZIO
              .fromEither(json.fromJson[UserPayload])
              .mapError(e => s"Failed to decode user from login response: $e")
            _ <- tokenRef.set(Some(token))
            // Extract refresh token from Set-Cookie header (value is the cookie content after the name=).
            _ <- ZIO.foreachDiscard(
              resp
                .headers("Set-Cookie")
                .collectFirst {
                  case h if h.startsWith("X-Refresh-Token=") =>
                    h.stripPrefix("X-Refresh-Token=").takeWhile(_ != ';')
                },
            )(rt => refreshTokenRef.set(Some(rt)))
          } yield LoginResult(token, u.displayName, u.email)
        } else {
          ZIO.fail(s"Login failed (${resp.code.code}): ${resp.body.merge}")
        }
      }
  }

  override def whoAmI: IO[String, String] = {
    for {
      token <- tokenRef.get
      resp  <- basicRequest
        .get(uri"${cfg.serverUrl}/api/whoami")
        .headers(token.map(t => Header("Authorization", s"Bearer $t")).toList*)
        .send(backend)
        .mapError(e => s"HTTP error on whoami: ${e.getMessage}")
      // P7-011: Validate HTTP response code, not just body.
      result <-
        if (resp.code.isSuccess) {
          resp.body match {
            case Left(err)   => ZIO.fail(s"whoami error (${resp.code.code}): $err")
            case Right(body) => ZIO.succeed(body)
          }
        } else {
          ZIO.fail(s"whoami failed (${resp.code.code}): ${resp.body.merge}")
        }
    } yield result
  }

  override def refresh: IO[String, String] = {
    for {
      rt   <- refreshTokenRef.get
      _    <- ZIO.fromOption(rt).orElseFail("No refresh token available")
      resp <- basicRequest
        .get(uri"${cfg.serverUrl}/refresh")
        .header("Cookie", s"X-Refresh-Token=${rt.get}")
        .send(backend)
        .mapError(e => s"HTTP error on refresh: ${e.getMessage}")
      result <-
        if (resp.code.isSuccess) {
          ZIO
            .fromOption(resp.header("Authorization").map(_.stripPrefix("Bearer ")))
            .orElseFail("Refresh succeeded but no Authorization header in response")
            .flatMap { newToken =>
              tokenRef.set(Some(newToken)) *>
                ZIO.foreachDiscard(
                  resp
                    .headers("Set-Cookie")
                    .collectFirst {
                      case h if h.startsWith("X-Refresh-Token=") =>
                        h.stripPrefix("X-Refresh-Token=").takeWhile(_ != ';')
                    },
                )(rt => refreshTokenRef.set(Some(rt))) *>
                ZIO.succeed(newToken)
            }
        } else {
          ZIO.fail(s"refresh failed (${resp.code.code}): ${resp.body.merge}")
        }
    } yield result
  }

  override def currentToken: UIO[Option[String]] = tokenRef.get

}

private case class UserPayload(
  displayName: String,
  email:       Option[String] = None,
) derives JsonDecoder
