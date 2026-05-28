/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.graphql

import caliban.*
import jorlan.*
import jorlan.graphql.JorlanAPI.JorlanApiEnv
import zio.*
import zio.http.*

/** Mounts the Jorlan GraphQL API on `/api/jorlan`.
  *
  * Routes:
  *   - `ANY /api/jorlan` — HTTP POST/GET GraphQL endpoint
  *   - `ANY /api/jorlan/ws` — WebSocket subscription endpoint
  *   - `GET /api/jorlan/graphiql` — in-browser GraphiQL IDE (dev aid)
  */
object JorlanRoutes {

  def routes: ZIO[JorlanApiEnv, CalibanError, Routes[JorlanApiEnv & JorlanSession, Nothing]] =
    JorlanAPI.api.interpreter.map { interp =>
      val adapter = QuickAdapter(interp)
      Routes(
        Method.ANY / "api" / "jorlan" ->
          adapter.handlers.api,
        Method.ANY / "api" / "jorlan" / "ws" ->
          adapter.handlers.webSocket,
        Method.GET / "api" / "jorlan" / "graphiql" ->
          GraphiQLHandler.handler(apiPath = "/api/jorlan", wsPath = Some("/api/jorlan/ws")),
      )
    }

}
