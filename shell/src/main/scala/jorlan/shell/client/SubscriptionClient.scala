/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell.client

import jorlan.{AgentSessionId, ResponseChunk}
import jorlan.graphql.client.JorlanClient
import jorlan.graphql.client.JorlanClient.ToolEventResult
import jorlan.shell.ShellConfig
import jorlan.graphql.client.JorlanClientDecoders.given
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
case class EventLogEntry(
  id:          Long,
  eventType:   String,
  actorId:     Option[Long],
  sessionId:   Option[Long],
  occurredAt:  String,
  payloadJson: Option[String],
)

trait SubscriptionClient {

  /** Returns a stream of [[ResponseChunk]] tokens for the given session. */
  def agentResponseStream(sessionId: AgentSessionId): ZStream[Scope, String, ResponseChunk]

  /** Returns a stream of [[ToolEventResult]] events for the given session (SkillInvoked / SkillSucceeded). */
  def toolEventsStream(sessionId: AgentSessionId): ZStream[Scope, String, ToolEventResult.ToolEventResultView]

  /** Returns a live stream of event-log entries. */
  def eventLogTail: ZStream[Scope, String, EventLogEntry]

}

object SubscriptionClient {

  def agentResponseStream(sessionId: AgentSessionId): ZStream[SubscriptionClient & Scope, String, ResponseChunk] =
    ZStream.serviceWithStream[SubscriptionClient](_.agentResponseStream(sessionId))

  def toolEventsStream(
    sessionId: AgentSessionId,
  ): ZStream[SubscriptionClient & Scope, String, ToolEventResult.ToolEventResultView] =
    ZStream.serviceWithStream[SubscriptionClient](_.toolEventsStream(sessionId))

  def eventLogTail: ZStream[SubscriptionClient & Scope, String, EventLogEntry] =
    ZStream.serviceWithStream[SubscriptionClient](_.eventLogTail)

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
) derives JsonCodec

private case class ChunkData(
  sessionId: AgentSessionId,
  content:   String,
  finished:  Boolean,
  isError:   Boolean = false,
) derives JsonCodec

private case class ToolEventData(
  sessionId: Long,
  eventType: String,
  toolName:  String,
  payload:   String,
) derives JsonCodec

private case class AgentResponseData(agentResponseStream: ChunkData) derives JsonCodec
private case class DataPayload(data: AgentResponseData) derives JsonCodec

private case class ToolEventsData(toolEvents: ToolEventData) derives JsonCodec
private case class ToolEventsPayload(data: ToolEventsData) derives JsonCodec

private case class EventLogEntryData(
  id:          Long,
  eventType:   String,
  actorId:     Option[Long],
  agentId:     Option[Long],
  sessionId:   Option[Long],
  resource:    Option[String],
  payloadJson: Option[String],
  occurredAt:  String,
) derives JsonCodec
private case class EventLogData(eventLogTail: EventLogEntryData) derives JsonCodec
private case class EventLogPayload(data: EventLogData) derives JsonCodec

private class SubscriptionClientImpl(
  cfg:     ShellConfig,
  auth:    AuthClient,
  backend: WebSocketBackend[Task],
) extends SubscriptionClient {

  private val subscriptionId = "1"

  private val wsUrl: String = cfg.serverUrl
    .replaceFirst("^https://", "wss://")
    .replaceFirst("^http://", "ws://") + "/api/jorlan/ws"

  /** Common WebSocket subscription setup. Calls `parseData` for each "data" frame to produce a queue offer. */
  private def wsStream[A](
    gqlQuery:  String,
    parseData: Json => Task[Option[Either[String, A]]],
  ): ZStream[Scope, String, A] = {
    val startMsg = WsMsg(
      `type` = "start",
      id = Some(subscriptionId),
      payload = Some(Json.Obj("query" -> Json.Str(gqlQuery))),
    ).toJson

    ZStream.unwrapScoped(
      for {
        token <- auth.currentToken
        _     <- ZIO.logDebug(s"[WS] connecting to $wsUrl (auth=${token.isDefined})")
        queue <- Queue.bounded[Option[Either[String, A]]](1024)
        fiber <- basicRequest
          .get(uri"$wsUrl")
          .headers(token.map(t => Map("Authorization" -> s"Bearer $t")).getOrElse(Map.empty))
          .response(
            asWebSocketAlways[Task, Unit] { ws =>
              val sendInit = ws.sendText(WsMsg(`type` = "connection_init").toJson) *>
                ZIO.logInfo("[WS] sent connection_init")
              val pingLoop = ws
                .send(WebSocketFrame.ping).delay(15.seconds)
                .repeat(Schedule.forever)
                .unit
              for {
                fragmentBuf <- Ref.make("")
                frameLoop = ZStream
                  .repeatZIO(ws.receive())
                  .mapZIO {
                    case WebSocketFrame.Text(text, finalFragment, _) =>
                      ZIO.logDebug(s"[WS] text frame: finalFragment=$finalFragment len=${text.length}") *>
                        (if (!finalFragment) {
                           fragmentBuf.update(_ + text)
                         } else {
                           fragmentBuf.getAndSet("").flatMap { prev =>
                             val fullText = prev + text
                             if (fullText.isEmpty) {
                               ZIO.logWarning("[WS] received empty text frame — ignoring")
                             } else {
                               ZIO
                                 .fromEither(fullText.fromJson[WsMsg])
                                 .mapError(e =>
                                   RuntimeException(
                                     s"JSON parse failed (len=${fullText.length}): $e — text=${fullText.take(200)}",
                                   ),
                                 )
                                 .flatMap {
                                   case WsMsg("connection_ack", _, _) =>
                                     ZIO.logInfo("[WS] connection_ack → sending start") *>
                                       ws.sendText(startMsg) *>
                                       ZIO.logDebug(s"[WS] start sent: $startMsg")
                                   case WsMsg("data", _, Some(p)) =>
                                     parseData(p).flatMap {
                                       case Some(item) => queue.offer(Some(item)).unit
                                       case None       => ZIO.unit
                                     }
                                   case WsMsg("complete", Some(`subscriptionId`), _) =>
                                     ZIO.logInfo("[WS] server sent complete — subscription stream ended") *>
                                       queue.offer(None).unit
                                   case WsMsg("error", _, Some(e)) =>
                                     ZIO.logWarning(s"[WS] error frame: $e") *>
                                       queue.offer(Some(Left(s"GQL error: $e"))).unit
                                   case other =>
                                     ZIO.logDebug(s"[WS] unhandled message type: ${other.`type`}")
                                 }
                             }
                           }
                         })
                    case _: WebSocketFrame.Close =>
                      ZIO.logInfo("[WS] close frame received") *> queue.offer(None).unit
                    case _: WebSocketFrame.Pong =>
                      ZIO.logDebug("[WS] pong received")
                    case other =>
                      ZIO.logInfo(s"[WS] unexpected non-text frame: $other")
                  }
                  .runDrain
                _ <- (sendInit *> frameLoop.race(pingLoop))
                  .ensuring(ws.close().ignore)
              } yield ()
            },
          )
          .send(backend)
          .tapError(e => ZIO.logError(s"[WS] request failed: ${e.getMessage}"))
          .mapError(e => s"WebSocket request failed: ${e.getMessage}")
          .forkScoped
        _ <- fiber.await.flatMap {
          case Exit.Failure(cause) =>
            val errMsg = cause.failureOption.getOrElse(cause.prettyPrint)
            ZIO.logError(s"[WS] fiber exited with failure: ${cause.prettyPrint}") *>
              queue.offer(Some(Left(errMsg))).unit
          case Exit.Success(_) =>
            ZIO.logDebug("[WS] fiber exited cleanly") *>
              queue.offer(None).unit
        }.forkScoped
      } yield ZStream
        .repeatZIO(queue.take)
        .collectWhile { case Some(v) => v }
        .flatMap {
          case Left(err) => ZStream.fail(err)
          case Right(v)  => ZStream.succeed(v)
        },
    )
  }

  override def agentResponseStream(sessionId: AgentSessionId): ZStream[Scope, String, ResponseChunk] = {
    val gqlQuery = JorlanClient.Subscriptions
      .agentResponseStream(sessionId)(JorlanClient.ResponseChunk.view)
      .toGraphQL(useVariables = false)
      .query

    wsStream[ResponseChunk](
      gqlQuery,
      p =>
        ZIO
          .fromEither(p.toJson.fromJson[DataPayload])
          .tapError(e => ZIO.logWarning(s"[WS] DataPayload decode failed: $e"))
          .mapError(RuntimeException(_))
          .map { dp =>
            val cd = dp.data.agentResponseStream
            Some(Right(ResponseChunk(cd.sessionId, cd.content, cd.finished, cd.isError)))
          },
    )
  }

  override def toolEventsStream(
    sessionId: AgentSessionId,
  ): ZStream[Scope, String, ToolEventResult.ToolEventResultView] = {
    val gqlQuery = JorlanClient.Subscriptions
      .toolEvents(sessionId)(JorlanClient.ToolEventResult.view)
      .toGraphQL(useVariables = false)
      .query

    wsStream[ToolEventResult.ToolEventResultView](
      gqlQuery,
      p =>
        ZIO
          .fromEither(p.toJson.fromJson[ToolEventsPayload])
          .tapError(e => ZIO.logWarning(s"[WS] ToolEventsPayload decode failed: $e"))
          .mapError(RuntimeException(_))
          .map { dp =>
            val td = dp.data.toolEvents
            Some(Right(ToolEventResult.ToolEventResultView(td.sessionId, td.eventType, td.toolName, td.payload)))
          },
    )
  }

  override def eventLogTail: ZStream[Scope, String, EventLogEntry] = {
    val gqlQuery = JorlanClient.Subscriptions
      .eventLogTail(JorlanClient.EventLogJson.view)
      .toGraphQL(useVariables = false)
      .query

    wsStream[EventLogEntry](
      gqlQuery,
      p =>
        ZIO
          .fromEither(p.toJson.fromJson[EventLogPayload])
          .tapError(e => ZIO.logWarning(s"[WS] EventLogPayload decode failed: $e"))
          .mapError(RuntimeException(_))
          .map { ep =>
            val e = ep.data.eventLogTail
            Some(Right(EventLogEntry(e.id, e.eventType, e.actorId, e.sessionId, e.occurredAt, e.payloadJson)))
          },
    )
  }

}
