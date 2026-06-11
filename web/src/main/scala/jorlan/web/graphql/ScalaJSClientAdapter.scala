/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web.graphql

import caliban.client.*
import caliban.client.CalibanClientError.DecodingError
import caliban.client.Operations.{IsOperation, RootSubscription}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import japgolly.scalajs.react.extra.TimerSupport
import japgolly.scalajs.react.{AsyncCallback, Callback}
import jorlan.domain.ConnectionId
import jorlan.web.ClientConfiguration
import jorlan.web.util.ApiClientSttp4
import org.scalajs.dom.WebSocket
import sttp.client4.*
import sttp.model.Uri
import zio.json.*
import zio.json.ast.*

import java.net.URI
import java.time.Instant
import scala.concurrent.duration.*
import scala.language.unsafeNulls

/** Handle returned by [[ScalaJSClientAdapter.makeWebSocketClient]] for managing a live GraphQL-over-WebSocket
  * subscription.
  *
  * All callbacks supplied to `makeWebSocketClient` fire on the JavaScript event loop (single-threaded) so they are safe
  * to call without synchronisation. `close()` may be called from any React callback or lifecycle hook; if the socket is
  * still connecting it schedules a retry in 1 s. After `close()` completes no further callbacks will fire.
  */
trait WebSocketHandler {

  def id: String

  def close(): Callback

}

case class ScalaJSClientAdapter(
  serverUri:    Uri,
  connectionId: ConnectionId,
) extends TimerSupport {

  private def requestToJson(q: GraphQLRequest): Option[Json] = {
    val jsonStr = writeToString(q)(using GraphQLRequest.jsonEncoder)
    jsonStr.fromJson[Json].toOption
  }

  given JsonEncoder[GQLOperationMessage] = DeriveJsonEncoder.gen[GQLOperationMessage]
  given JsonDecoder[GQLOperationMessage] = DeriveJsonDecoder.gen[GQLOperationMessage]

  def asyncCalibanCallWithAuth[Origin, A](
    selectionBuilder: SelectionBuilder[Origin, A],
  )(using ev:         IsOperation[Origin],
  ): AsyncCallback[A] = {
    ApiClientSttp4
      .withAuth(selectionBuilder.toRequest(serverUri), connectionId)
      .flatMap {
        case Left(exception) => AsyncCallback.throwException(exception)
        case Right(value)    => AsyncCallback.pure(value)
      }
  }

  import GQLOperationMessage.*

  case class GQLOperationMessage(
    `type`:  String,
    id:      Option[String] = None,
    payload: Option[Json] = None,
  )

  object GQLOperationMessage {

    val GQL_CONNECTION_INIT = "connection_init"
    val GQL_START = "start"
    val GQL_STOP = "stop"
    val GQL_CONNECTION_TERMINATE = "connection_terminate"
    val GQL_COMPLETE = "complete"
    val GQL_CONNECTION_ACK = "connection_ack"
    val GQL_CONNECTION_ERROR = "connection_error"
    val GQL_CONNECTION_KEEP_ALIVE = "ka"
    val GQL_DATA = "data"
    val GQL_ERROR = "error"
    val GQL_UNKNOWN = "unknown"

  }

  /** Opens a GraphQL-over-WebSocket subscription using the `graphql-ws` protocol.
    *
    * The returned [[WebSocketHandler]] holds the live socket reference. Callers must call `handler.close()` when the
    * subscribing component unmounts to prevent connection leaks.
    *
    * Lifecycle: onConnecting → (socket opens) → onConnected → onData* → onDisconnected. If the socket drops
    * unexpectedly and `reconnect = true`, the sequence retries with exponential backoff up to `reconnectionAttempts`
    * times within the first hour.
    *
    * @param webSocket
    *   Inject an existing `WebSocket` (useful in tests); `None` opens a new one.
    * @param query
    *   The Caliban subscription `SelectionBuilder` to execute.
    * @param operationId
    *   GQL `id` field sent in `start`/`stop` messages — must be unique per open subscription.
    * @param socketConnectionId
    *   Logical label for this subscription; echoed in the `WebSocketHandler.id` result.
    * @param onData
    *   Fired for each `GQL_DATA` frame; second arg is `None` if the payload could not be decoded.
    * @param connectionParams
    *   Optional `payload` sent in the `connection_init` message (e.g. auth metadata).
    * @param timeout
    *   How long after the last keep-alive ping before a stale connection triggers a reconnect.
    * @param reconnect
    *   Whether to attempt reconnection on unexpected close or error.
    * @param reconnectionAttempts
    *   Maximum number of reconnect attempts before calling `onClientError`.
    * @param onConnected
    *   Fired on the first successful `connection_ack`.
    * @param onReconnected
    *   Fired on each subsequent `connection_ack` after a reconnect.
    * @param onReconnecting
    *   Fired just before each reconnect attempt.
    * @param onConnecting
    *   Fired when the underlying `WebSocket.onopen` event fires.
    * @param onDisconnected
    *   Fired when the server sends `GQL_COMPLETE` or the socket closes cleanly.
    * @param onKeepAlive
    *   Fired for each `ka` (keep-alive) frame.
    * @param onServerError
    *   Fired for `connection_error` or `error` frames from the server.
    * @param onClientError
    *   Fired for decoding errors, network errors, or reconnect-budget exhaustion.
    * @param now
    *   Clock supplier injected for testability; defaults to `Instant.now()`. All reconnect timing reads this instead of
    *   calling `Instant.now()` directly so that tests can substitute a deterministic clock.
    */
  def makeWebSocketClient[A](
    webSocket:            Option[WebSocket],
    query:                SelectionBuilder[RootSubscription, A],
    operationId:          String,
    socketConnectionId:   String,
    onData:               (String, Option[A]) => Callback,
    connectionParams:     Option[Json] = None,
    timeout:              Duration = 8.minutes,
    reconnect:            Boolean = true,
    reconnectionAttempts: Int = 3,
    onConnected:          (String, Option[Json]) => Callback = {
      (
        _,
        _,
      ) => Callback.empty
    },
    onReconnected: (String, Option[Json]) => Callback = {
      (
        _,
        _,
      ) => Callback.empty
    },
    onReconnecting: String => Callback = { _ => Callback.empty },
    onConnecting:   Callback = Callback.empty,
    onDisconnected: (String, Option[Json]) => Callback = {
      (
        _,
        _,
      ) => Callback.empty
    },
    onKeepAlive:   Option[Json] => Callback = { _ => Callback.empty },
    onServerError: (String, Option[Json]) => Callback = {
      (
        _,
        _,
      ) => Callback.empty
    },
    onClientError: Throwable => Callback = { _ => Callback.empty },
    now:           () => Instant = () => Instant.now(),
  ): WebSocketHandler =
    new WebSocketHandler {

      override val id: String = socketConnectionId

      def GQLConnectionInit(): GQLOperationMessage =
        GQLOperationMessage(GQL_CONNECTION_INIT, Option(operationId), connectionParams)

      def GQLStart(q: GraphQLRequest): GQLOperationMessage =
        GQLOperationMessage(GQL_START, Option(operationId), payload = requestToJson(q))

      def GQLStop(): GQLOperationMessage = GQLOperationMessage(GQL_STOP, Option(operationId))

      def GQLConnectionTerminate(): GQLOperationMessage =
        GQLOperationMessage(GQL_CONNECTION_TERMINATE, Option(operationId))

      private val graphql: GraphQLRequest = query.toGraphQL()

      private def newSocket(): WebSocket = {
        val protocol = if (org.scalajs.dom.window.location.protocol == "https:") "wss" else "ws"
        val uri = new URI(s"$protocol://${ClientConfiguration.host}/api/jorlan/ws")
        Callback.log("Connecting to WebSocket at " + uri.toString).runNow()
        org.scalajs.dom.WebSocket(uri.toString, "graphql-ws")
      }

      // JS is single-threaded: var is safe here; there are no concurrent writers.
      var socket: WebSocket = webSocket.getOrElse(newSocket())

      case class ConnectionState(
        lastKAOpt:          Option[Instant] = None,
        kaIntervalOpt:      Option[Int] = None,
        firstConnection:    Boolean = true,
        reconnectCount:     Int = 0,
        closed:             Boolean = false,
        firstReconnectTime: Option[Instant] = None,
        reconnectTimeoutId: Option[Int] = None,
      )

      private var connectionState: ConnectionState = ConnectionState()

      private val MAX_RECONNECT_DELAY_MS = 10 * 60 * 1000
      private val MAX_TOTAL_RECONNECT_TIME_MS = 60 * 60 * 1000
      private val BASE_RECONNECT_DELAY_MS = 1000

      private def calculateBackoffDelay(attemptNumber: Int): Int = {
        val exponentialDelay = (BASE_RECONNECT_DELAY_MS * scala.math.pow(2, attemptNumber)).toInt
        scala.math.min(exponentialDelay, MAX_RECONNECT_DELAY_MS)
      }

      private def attemptReconnect(): Unit =
        if (connectionState.closed) {
          Callback.log("Not reconnecting - connection was explicitly closed").runNow()
        } else {
          val currentTime = now()
          val firstReconnect = connectionState.firstReconnectTime.getOrElse(currentTime)
          val totalReconnectTime = java.time.Duration.between(firstReconnect, currentTime).toMillis

          if (totalReconnectTime > MAX_TOTAL_RECONNECT_TIME_MS) {
            Callback.log(s"Giving up reconnection after ${totalReconnectTime / 1000 / 60} minutes").runNow()
            onClientError(Exception("Failed to reconnect after 60 minutes")).runNow()
          } else {
            val delay = calculateBackoffDelay(connectionState.reconnectCount)
            Callback.log(s"Attempting reconnection #${connectionState.reconnectCount + 1} in ${delay}ms").runNow()

            connectionState = connectionState.copy(
              firstReconnectTime = Some(firstReconnect),
              reconnectCount = connectionState.reconnectCount + 1,
            )

            val timeoutId = org.scalajs.dom.window.setTimeout(
              { () =>
                onReconnecting(operationId).runNow()
                val ws = newSocket()
                socket = ws
                setupSocketHandlers(ws)
              },
              delay,
            )

            connectionState = connectionState.copy(reconnectTimeoutId = Some(timeoutId))
          }
        }

      def doConnect(): Unit = {
        if (!connectionState.closed) {
          val sendMe = GQLConnectionInit()
          socket.send(sendMe.toJson)
        }
      }

      def setupSocketHandlers(ws: WebSocket): Unit = {
        ws.onmessage = { (e: org.scalajs.dom.MessageEvent) =>
          val strMsg = e.data.toString
          val msg: Either[String, GQLOperationMessage] = strMsg.fromJson[GQLOperationMessage]
          msg match {
            case Right(GQLOperationMessage(GQL_COMPLETE, id, payload)) =>
              connectionState.kaIntervalOpt.foreach(id => org.scalajs.dom.window.clearInterval(id))
              onDisconnected(id.getOrElse(""), payload).runNow()
            case Right(GQLOperationMessage(GQL_CONNECTION_ACK, id, payload)) =>
              if (connectionState.firstConnection) {
                onConnected(id.getOrElse(""), payload).runNow()
                connectionState = connectionState.copy(firstConnection = false, reconnectCount = 0)
              } else onReconnected(id.getOrElse(""), payload).runNow()
              ws.send(GQLStart(graphql).toJson)
            case Right(GQLOperationMessage(GQL_CONNECTION_ERROR, id, payload)) =>
              onServerError(id.getOrElse(""), payload).runNow()
            case Right(GQLOperationMessage(GQL_CONNECTION_KEEP_ALIVE, id, payload)) =>
              connectionState = connectionState.copy(reconnectCount = 0)
              if (connectionState.lastKAOpt.isEmpty) {
                connectionState = connectionState.copy(kaIntervalOpt =
                  Option(
                    org.scalajs.dom.window.setInterval(
                      () => {
                        connectionState.lastKAOpt.map { lastKA =>
                          val timeFromLastKA =
                            java.time.Duration.between(lastKA, now()).toMillis.milliseconds
                          if (timeFromLastKA > timeout) {
                            if (reconnect && connectionState.reconnectCount <= reconnectionAttempts) {
                              connectionState =
                                connectionState.copy(reconnectCount = connectionState.reconnectCount + 1)
                              onReconnecting(id.getOrElse("")).runNow()
                              doConnect()
                            }
                          }
                        }
                      },
                      timeout.toMillis.toDouble,
                    ),
                  ),
                )
              }
              connectionState = connectionState.copy(lastKAOpt = Option(now()))
              onKeepAlive(payload).runNow()
            case Right(GQLOperationMessage(GQL_DATA, id, payloadOpt)) =>
              if (!connectionState.closed) {
                connectionState = connectionState.copy(reconnectCount = 0)
                val res = for {
                  payload <- payloadOpt.toRight(DecodingError("No payload"))
                  payloadStr = payload.toJson
                  (result, _, _) <- query.decode(payloadStr)
                } yield result

                res match {
                  case Right(data) => onData(id.getOrElse(""), Option(data)).runNow()
                  case Left(error) =>
                    error.printStackTrace()
                    onClientError(error).runNow()
                }
              }
            case Right(GQLOperationMessage(GQL_ERROR, id, payload)) =>
              onServerError(id.getOrElse(""), payload).runNow()
            case Right(GQLOperationMessage(_, id, payload)) =>
              onServerError(id.getOrElse(""), payload).runNow()
            case Left(error) =>
              onClientError(DecodingError(error)).runNow()
          }
        }

        ws.onerror = { (e: org.scalajs.dom.Event) =>
          onClientError(Exception(s"WebSocket error: $e")).runNow()
          if (!connectionState.closed) attemptReconnect()
        }

        ws.onopen = { (_: org.scalajs.dom.Event) =>
          onConnecting.runNow()
          connectionState = connectionState.copy(reconnectCount = 0, firstReconnectTime = None)
          doConnect()
        }

        ws.onclose = { (e: org.scalajs.dom.CloseEvent) =>
          Callback.log(s"WebSocket closed: ${e.reason}").runNow()
          if (!connectionState.closed) attemptReconnect()
        }
      }

      setupSocketHandlers(socket)

      override def close(): Callback = {
        if (socket.readyState == WebSocket.CONNECTING) {
          Callback.log("Socket connecting, retrying close in 1s") >>
            setTimeoutMs(close(), 1000)
        } else if (socket.readyState == WebSocket.OPEN) {
          Callback.log(s"Closing WebSocket subscription") >> Callback {
            connectionState = connectionState.copy(closed = true)
            connectionState.reconnectTimeoutId.foreach(id => org.scalajs.dom.window.clearTimeout(id))
            connectionState.kaIntervalOpt.foreach(id => org.scalajs.dom.window.clearInterval(id))
            socket.send(GQLStop().toJson)
            socket.send(GQLConnectionTerminate().toJson)
            socket.close()
          }
        } else
          Callback.log("WebSocket already closed")
      }

    }

}
