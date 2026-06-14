/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web.util

import caliban.client.CalibanClientError
import japgolly.scalajs.react.{AsyncCallback, Callback}
import jorlan.ConnectionId
import jorlan.web.JorlanWebApp
import org.scalajs.dom.window
import sttp.client4.*
import sttp.client4.fetch.FetchBackend
import sttp.model.*
import zio.json.*

import scala.concurrent.Future
import scala.language.unsafeNulls

object ApiClientSttp4 {

  private def encodeConnectionId[ConnectionId: JsonEncoder](connectionId: ConnectionId): String = {
    java.util.Base64.getEncoder.encodeToString(connectionId.toJson.getBytes)
  }
  // All of this goes away once caliban supports sttp4

  import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

  private def asyncJwtToken: AsyncCallback[Option[String]] =
    AsyncCallback.pure(Option(window.localStorage.getItem("jwtToken")))

  private val backend: WebSocketBackend[Future] = FetchBackend()

  private case class ZioJsonException(error: String) extends Exception

  private object JsonInput {

    def sanitize[T: IsOption]: String => String = { s =>
      if (implicitly[IsOption[T]].isOption && s.trim.isEmpty) "null" else s
    }

  }

  def asJson[B: {JsonDecoder, IsOption}]: ResponseAs[Either[ResponseException[String], B]] =
    asString.mapWithMetadata(ResponseAs.deserializeRightWithError(deserializeJson))

  private def deserializeJson[B: {JsonDecoder, IsOption}]: String => Either[Exception, B] =
    JsonInput.sanitize[B].andThen(_.fromJson[B].left.map(ZioJsonException(_)))

  def withAuthOptional[A](
    request: Request[Either[CalibanClientError, A]],
  ): AsyncCallback[Either[CalibanClientError, A]] = {
    def doCall(tokOpt: Option[String]): AsyncCallback[Response[Either[CalibanClientError, A]]] = {
      AsyncCallback.fromFuture {
        val reqWithConnectionId = request.header("X-Connection-Id", encodeConnectionId(JorlanWebApp.connectionId))
        val finalReq = tokOpt.fold(reqWithConnectionId)(tok => reqWithConnectionId.auth.bearer(tok))
        finalReq.send(backend)
      }
    }

    for {
      tokOpt   <- asyncJwtToken
      response <- doCall(tokOpt)
    } yield response.body
  }

  /** Executes a Caliban GraphQL request with JWT Bearer authentication.
    *
    * Reads the JWT from `localStorage["jwtToken"]`. If the token is expired the client attempts a silent refresh via
    * `GET /api/auth/refresh`; the new token replaces the stored one and the original request is retried. If the server
    * returns 401 (definitively invalid) the stored token is removed so the next render redirects to login. Transient
    * non-2xx responses (500, 503, 429 …) do **not** clear the token — the session is preserved and the error is
    * surfaced to the caller.
    *
    * @param onAuthError
    *   called when there is no token or a refresh fails; the default reloads the page so `LoginRouter` re-gates.
    */
  def withAuth[A](
    request:     Request[Either[CalibanClientError, A]],
    onAuthError: String => AsyncCallback[Any] = msg =>
      AsyncCallback.pure {
        window.console.log(msg)
        window.location.reload()
      },
  ): AsyncCallback[Either[CalibanClientError, A]] = {
    def doCall(tok: String): AsyncCallback[Response[Either[CalibanClientError, A]]] = {
      AsyncCallback.fromFuture {
        request
          .header("X-Connection-Id", encodeConnectionId(JorlanWebApp.connectionId))
          .auth
          .bearer(tok)
          .send(backend)
      }
    }

    for {
      tokOpt      <- asyncJwtToken
      responseOpt <- AsyncCallback.traverseOption(tokOpt)(doCall)
      withRefresh <- responseOpt match {
        case Some(response)
            if response.code == StatusCode.Unauthorized && response.body.left.exists(
              _.getMessage.contains("token_expired"),
            ) =>
          Callback.log("Refreshing token").asAsyncCallback >>
            (for {
              refreshResponse <-
                AsyncCallback.fromFuture(
                  basicRequest
                    .get(uri"/api/auth/refresh")
                    .response(asString)
                    .send(backend),
                )
              retried <- refreshResponse.code match {
                case c if c.isSuccess =>
                  refreshResponse.header(HeaderNames.Authorization) match {
                    case Some(authHeader) =>
                      val newToken = authHeader.stripPrefix("Bearer ")
                      window.localStorage.setItem("jwtToken", newToken)
                      doCall(newToken).map(_.body)
                    case None =>
                      val msg = "Server said refresh was ok, but didn't return a token"
                      onAuthError(msg) >> AsyncCallback.pure(
                        Left(CalibanClientError.CommunicationError(msg): CalibanClientError),
                      )
                  }
                case c =>
                  val msg = s"Trying to get Refresh token got $c"
                  onAuthError(msg) >> AsyncCallback.pure(Left(CalibanClientError.CommunicationError(msg)))
              }
            } yield retried)
        case None =>
          val msg = "No token set, please log in"
          onAuthError(msg) >> AsyncCallback.pure(Left(CalibanClientError.CommunicationError(msg)))
        case Some(other) if other.code == StatusCode.Unauthorized =>
          // Token is definitively invalid — clear it so the user is redirected to login
          AsyncCallback.pure {
            // Clear the token
            window.localStorage.removeItem("jwtToken")
          } >>
            AsyncCallback.pure(other.body)
        case Some(other) if !other.code.isSuccess =>
          // Transient server error — do NOT clear the token; return the error body
          AsyncCallback.pure(other.body)
        case Some(other) =>
          AsyncCallback.pure(other.body)
      }
    } yield withRefresh
  }

}
