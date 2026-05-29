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
import jorlan.shell.ShellConfig
import sttp.client4.*
import sttp.client4.ws.async.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.ws.WebSocketFrame
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

import scala.language.unsafeNulls

/** Implements the graphql-ws protocol over sttp WebSocket to stream [[ResponseChunk]] tokens from the server.
  *
  * Protocol sequence: connection_init → connection_ack → subscribe → next* → complete
  */
object SubscriptionClient {

  private case class WsMsg(
    `type`:  String,
    id:      Option[String] = None,
    payload: Option[Json] = None,
  ) derives JsonEncoder, JsonDecoder

  private case class ChunkData(
    sessionId: AgentSessionId,
    content:   String,
    finished:  Boolean,
  ) derives JsonDecoder

  private case class ResponseData(agentResponseStream: ChunkData) derives JsonDecoder
  private case class NextPayload(data: ResponseData) derives JsonDecoder

  private val subscriptionId = "1"

  /** Returns a stream of [[ResponseChunk]] tokens for the given session.
    *
    * The stream completes when the server sends a `finished=true` chunk or a graphql-ws `complete` frame, and fails
    * with a descriptive string on protocol or network error.
    *
    * @param sessionId
    *   The session to subscribe to.
    * @param cfg
    *   Shell configuration supplying the server URL (converted to `ws://` or `wss://`).
    * @param auth
    *   Auth client providing the current Bearer token for the WebSocket handshake.
    */
  def agentResponseStream(
    sessionId: AgentSessionId,
    cfg:       ShellConfig,
    auth:      AuthClient,
  ): ZStream[Scope, String, ResponseChunk] = {
    val wsUrl = cfg.serverUrl
      .replaceFirst("^https://", "wss://")
      .replaceFirst("^http://", "ws://") + "/api/jorlan/ws"

    val subscribeMsg = WsMsg(
      `type` = "subscribe",
      id = Some(subscriptionId),
      payload = Some(
        Json.Obj(
          "query" -> Json.Str(
            s"""subscription { agentResponseStream(sessionId: ${sessionId.value}) { sessionId content finished } }""",
          ),
        ),
      ),
    ).toJson

    ZStream.unwrapScoped(
      for {
        token   <- auth.currentToken
        backend <- HttpClientZioBackend.scoped().mapError(e => s"WS backend error: ${e.getMessage}")
        queue   <- Queue.unbounded[Option[Either[String, ResponseChunk]]]
        _       <- basicRequest
          .get(uri"$wsUrl")
          .headers(token.map(t => Map("Authorization" -> s"Bearer $t")).getOrElse(Map.empty))
          .response(
            asWebSocketAlways[Task, Unit] { ws =>
              val sendInit = ws.sendText(WsMsg(`type` = "connection_init").toJson)
              val frameLoop = ZStream
                .repeatZIO(ws.receive())
                .mapZIO {
                  case WebSocketFrame.Text(text, _, _) =>
                    ZIO
                      .fromEither(text.fromJson[WsMsg])
                      .mapError(new RuntimeException(_))
                      .flatMap {
                        case WsMsg("connection_ack", _, _) => ws.sendText(subscribeMsg)
                        case WsMsg("next", _, Some(p))     =>
                          ZIO
                            .fromEither(p.toJson.fromJson[NextPayload])
                            .mapError(new RuntimeException(_))
                            .flatMap { np =>
                              val cd = np.data.agentResponseStream
                              queue.offer(
                                Some(Right(ResponseChunk(cd.sessionId, cd.content, cd.finished))),
                              )
                            }
                            .unit
                        case WsMsg("complete", Some(`subscriptionId`), _) =>
                          queue.offer(None).unit
                        case WsMsg("error", _, Some(e)) =>
                          queue.offer(Some(Left(s"GQL error: $e"))).unit
                        case _ => ZIO.unit
                      }
                  case _: WebSocketFrame.Close => queue.offer(None).unit
                  case _ => ZIO.unit
                }
                .runDrain
              sendInit *> frameLoop
            },
          )
          .send(backend)
          .mapError(e => s"WebSocket request failed: ${e.getMessage}")
          .forkScoped
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
