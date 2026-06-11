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
        val uri = new URI(s"$protocol://${ClientConfiguration.live.host}/api/jorlan/ws")
        println("Connecting to WebSocket at " + uri.toString)
        org.scalajs.dom.WebSocket(uri.toString, "graphql-ws")
      }

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

      private def attemptReconnect(): Unit = {
        if (connectionState.closed) {
          println("Not reconnecting - connection was explicitly closed")
          return
        }

        val now = Instant.now()
        val firstReconnect = connectionState.firstReconnectTime.getOrElse(now)
        val totalReconnectTime = java.time.Duration.between(firstReconnect, now).toMillis

        if (totalReconnectTime > MAX_TOTAL_RECONNECT_TIME_MS) {
          println(s"Giving up reconnection after ${totalReconnectTime / 1000 / 60} minutes")
          onClientError(Exception("Failed to reconnect after 60 minutes")).runNow()
          return
        }

        val delay = calculateBackoffDelay(connectionState.reconnectCount)
        println(s"Attempting reconnection #${connectionState.reconnectCount + 1} in ${delay}ms")

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
                            java.time.Duration.between(lastKA, Instant.now).nn.toMillis.milliseconds
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
              connectionState = connectionState.copy(lastKAOpt = Option(Instant.now()))
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
          println(s"WebSocket closed: ${e.reason}")
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
