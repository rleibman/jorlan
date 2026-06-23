/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.init

import jorlan.*
import jorlan.db.repository.{ZIORepositories, ZIOServerSettingsRepository}
import jorlan.routes.AppRoutes
import zio.http.{Method, Response, Routes, handler}
import zio.json.*
import zio.json.ast.Json
import zio.{Clock, ZIO}

import java.util.concurrent.TimeUnit

/** `GET /api/status` — available in both initialized and setup-mode servers. Reads state dynamically per request. */
case class StatusRoutes(startTime: Long) extends AppRoutes[ZIORepositories, Any, JorlanError] {

  override def api: ZIO[ZIORepositories, JorlanError, Routes[ZIORepositories, JorlanError]] =
    for {
      repo <- ZIO.service[ZIORepositories]
    } yield Routes(Method.GET / "api" / "status" -> handler {
      for {
        (initializedJson, nameJson) <-
          repo.setting.get(ZIOServerSettingsRepository.InitializedKey) <&>
            repo.setting.get(ZIOServerSettingsRepository.ServerNameKey)
        initialized = initializedJson.collect { case Json.Bool(v) => v }.getOrElse(false)
        name = nameJson.collect { case Json.Str(s) => s }.getOrElse("Jorlan")
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        status = ServerStatus(
          initialized,
          jorlan.BuildInfo.version,
          jorlan.BuildInfo.buildTime,
          name,
          now - startTime,
        )
      } yield Response.json(status.toJson)
    })

}
