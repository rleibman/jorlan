/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.routes

import _root_.auth.{AuthConfig, AuthServer}
import caliban.*
import jorlan.*
import jorlan.graphql.JorlanAPI
import zio.*
import zio.http.*
import zio.nio.file.Files

/** Mounts the Jorlan GraphQL API on `/api/jorlan`.
  *
  * Routes:
  *   - `ANY /api/jorlan` — HTTP POST/GET GraphQL endpoint
  *   - `ANY /api/jorlan/ws` — WebSocket subscription endpoint
  *   - `GET /api/jorlan/graphiql` — in-browser GraphiQL IDE (dev aid)
  */
object JorlanRoutes extends AppRoutes[JorlanApiEnv, JorlanSession, JorlanError] {

  lazy private val interpreter = JorlanAPI.api.interpreter
  
  override def api: ZIO[JorlanApiEnv, JorlanError, Routes[JorlanApiEnv & JorlanSession, JorlanError]] =
    (for {
      interpreter <- interpreter
    } yield {
      val adapter: QuickAdapter[JorlanApiEnv & JorlanSession] = QuickAdapter(interpreter)
      Routes(
        Method.ANY / "api" / "jorlan" ->
          adapter.handlers.api,
        Method.ANY / "api" / "jorlan" / "ws" ->
          adapter.handlers.webSocket
            .tapAllZIO(a => ZIO.logError(s"WebSocket error: ${a.toString}"), b => ZIO.logInfo("WebSocket closed")),
        Method.ANY / "api" / "jorlan" / "graphiql" ->
          GraphiQLHandler.handler(apiPath = "/api/jorlan", wsPath = None),
      )
    }).mapError(JorlanError(_))

  override def unauth: ZIO[JorlanApiEnv, JorlanError, Routes[JorlanApiEnv, JorlanError]] =
    (for {
      interpreter <- interpreter.tapErrorCause(cause => ZIO.logCause(cause))
    } yield {
      val adapter: QuickAdapter[JorlanApiEnv & JorlanSession] = QuickAdapter(interpreter)

      Routes(
        Method.GET / "unauth" / "jorlan" / "schema" ->
          Handler.fromBody(Body.fromCharSequence(JorlanAPI.api.render)),
        Method.ANY / "unauth" / "api" / "jorlan" ->
          adapter.handlers.api
            .provideSomeLayer(ZLayer.succeed(JorlanSession.guestSession(ConnectionId.unsafeRandom))),
        Method.GET / "unauth" / "jorlan" / "ws" ->
          adapter.handlers.webSocket
            .provideSomeLayer(ZLayer.succeed(JorlanSession.websocketSession))
            .tapAllZIO(a => ZIO.logError(s"WebSocket error: ${a.toString}"), b => ZIO.logInfo("WebSocket closed")),
      )
    }).mapError(JorlanError(_))

}
