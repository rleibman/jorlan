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

import jorlan.domain.{AgentSessionId, ResponseChunk}
import jorlan.graphql.client.JorlanClient
import jorlan.shell.ShellConfig
import jorlan.shell.client.JorlanClientDecoders.*
import sttp.client4.*
import sttp.client4.ws.async.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.ws.WebSocketFrame
import sttp.client4.WebSocketBackend
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

import scala.language.unsafeNulls

/** Implements the subscriptions-transport-ws protocol over sttp WebSocket to stream [[ResponseChunk]] tokens from the
  * server.
  *
  * A single [[Backend]] is acquired once at layer construction time (via [[SubscriptionClient.live]]) and reused across
  * all subscriptions — avoiding the P7-001 pattern of creating a new thread pool per call.
  *
  * Protocol sequence: connection_init → connection_ack → start → data* → complete
  */
trait SubscriptionClient {

  /** Returns a stream of [[ResponseChunk]] tokens for the given session.
    *
    * The stream completes when the server sends a `finished=true` chunk or a graphql-ws `complete` frame, and fails
    * with a descriptive string on protocol or network error.
    *
    * @param sessionId
    *   The session to subscribe to.
    */
  def agentResponseStream(sessionId: AgentSessionId): ZStream[Scope, String, ResponseChunk]

}

object SubscriptionClient {

  def agentResponseStream(sessionId: AgentSessionId): ZStream[SubscriptionClient & Scope, String, ResponseChunk] =
    ZStream.serviceWithStream[SubscriptionClient](_.agentResponseStream(sessionId))

  val live: ZLayer[ShellConfig & AuthClient, Throwable, SubscriptionClient] = ZLayer.scoped {
    for {
      cfg     <- ZIO.service[ShellConfig]
      auth    <- ZIO.service[AuthClient]
      backend <- HttpClientZioBackend.scoped()
    } yield SubscriptionClientImpl(cfg, auth, backend)
  }

}

private case class WsMsg(
  `type`:  String,
  id:      Option[String] = None,
  payload: Option[Json] = None,
) derives JsonEncoder, JsonDecoder

private case class ChunkData(
  sessionId: AgentSessionId,
  content:   String,
  finished:  Boolean,
  isError:   Boolean = false,
) derives JsonDecoder

private case class AgentResponseData(agentResponseStream: ChunkData) derives JsonDecoder
private case class DataPayload(data: AgentResponseData) derives JsonDecoder

private class SubscriptionClientImpl(
  cfg:     ShellConfig,
  auth:    AuthClient,
  backend: WebSocketBackend[Task],
) extends SubscriptionClient {

  private val subscriptionId = "1"

  override def agentResponseStream(sessionId: AgentSessionId): ZStream[Scope, String, ResponseChunk] = {
    val wsUrl = cfg.serverUrl
      .replaceFirst("^https://", "wss://")
      .replaceFirst("^http://", "ws://") + "/api/jorlan/ws"

    // Use SelectionBuilder to generate the subscription query — avoids manual string construction.
    val gqlQuery = JorlanClient.Subscriptions
      .agentResponseStream(sessionId)(JorlanClient.ResponseChunk.view)
      .toGraphQL(useVariables = false)
      .query

    // subscriptions-transport-ws protocol uses "start" (not the newer "subscribe").
    val startMsg = WsMsg(
      `type` = "start",
      id = Some(subscriptionId),
      payload = Some(Json.Obj("query" -> Json.Str(gqlQuery))),
    ).toJson

    ZStream.unwrapScoped(
      for {
        token <- auth.currentToken
        _     <- ZIO.logDebug(s"[WS] connecting to $wsUrl (auth=${token.isDefined})")
        queue <- Queue.bounded[Option[Either[String, ResponseChunk]]](1024)
        fiber <- basicRequest
          .get(uri"$wsUrl")
          .headers(token.map(t => Map("Authorization" -> s"Bearer $t")).getOrElse(Map.empty))
          .response(
            asWebSocketAlways[Task, Unit] { ws =>
              val sendInit = ws.sendText(WsMsg(`type` = "connection_init").toJson) *>
                ZIO.logDebug("[WS] sent connection_init")
              val frameLoop = ZStream
                .repeatZIO(ws.receive())
                .mapZIO {
                  case WebSocketFrame.Text(text, _, _) =>
                    ZIO.logDebug(s"[WS] frame: $text") *>
                      ZIO
                        .fromEither(text.fromJson[WsMsg])
                        .mapError(new RuntimeException(_))
                        .flatMap {
                          case WsMsg("connection_ack", _, _) =>
                            ZIO.logDebug("[WS] connection_ack → sending start") *>
                              ws.sendText(startMsg) *>
                              ZIO.logDebug(s"[WS] start sent: $startMsg")
                          // subscriptions-transport-ws uses "data" (not the newer "next").
                          case WsMsg("data", _, Some(p)) =>
                            ZIO.logDebug(s"[WS] data payload: ${p.toJson}") *>
                              ZIO
                                .fromEither(p.toJson.fromJson[DataPayload])
                                .tapError(e => ZIO.logWarning(s"[WS] DataPayload decode failed: $e"))
                                .mapError(new RuntimeException(_))
                                .flatMap { dp =>
                                  val cd = dp.data.agentResponseStream
                                  ZIO.logDebug(
                                    s"[WS] chunk: session=${cd.sessionId.value} content='${cd.content}' finished=${cd.finished} isError=${cd.isError}",
                                  ) *>
                                    queue
                                      .offer(
                                        Some(
                                          Right(ResponseChunk(cd.sessionId, cd.content, cd.finished, cd.isError)),
                                        ),
                                      ).unit
                                }
                          case WsMsg("complete", Some(`subscriptionId`), _) =>
                            ZIO.logDebug("[WS] complete") *> queue.offer(None).unit
                          case WsMsg("error", _, Some(e)) =>
                            ZIO.logWarning(s"[WS] error frame: $e") *>
                              queue.offer(Some(Left(s"GQL error: $e"))).unit
                          case other =>
                            ZIO.logDebug(s"[WS] unhandled message type: ${other.`type`}")
                        }
                  case _: WebSocketFrame.Close =>
                    ZIO.logDebug("[WS] close frame") *> queue.offer(None).unit
                  case other =>
                    ZIO.logDebug(s"[WS] non-text frame: $other")
                }
                .runDrain
              sendInit *> frameLoop
            },
          )
          .send(backend)
          .tapError(e => ZIO.logError(s"[WS] request failed: ${e.getMessage}"))
          .mapError(e => s"WebSocket request failed: ${e.getMessage}")
          .forkScoped
        _ <- fiber.await.flatMap {
          case Exit.Failure(cause) => ZIO.logError(s"[WS] fiber exited with failure: ${cause.prettyPrint}")
          case Exit.Success(_)     => ZIO.logDebug("[WS] fiber exited cleanly")
        }.forkScoped
      } yield ZStream
        .repeatZIO(queue.take)
        .collectWhile { case Some(v) => v }
        .flatMap {
          case Left(err)    => ZStream.fail(err)
          case Right(chunk) => ZStream.succeed(chunk)
        }
        .takeUntil(_.finished),
    )
  }

}
