/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.rss

import jorlan.JorlanError
import jorlan.connector.{Skill, SkillPlugin}
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

/** ServiceLoader entry point for the RSS feed skill plugin. */
class RssFeedPlugin extends SkillPlugin {

  override def create(
    client:     Client,
    getSetting: String => UIO[Option[Json]],
    setSetting: (String, Json) => UIO[Unit],
  ): IO[JorlanError, Skill] =
    ZIO.succeed(
      RssFeedSkill(
        client = client,
        getFeeds = getSetting("skill.rss")
          .map(_.flatMap(_.as[List[String]].toOption).getOrElse(List.empty)),
        saveFeeds = feeds => setSetting("skill.rss", Json.Arr(feeds.map(Json.Str(_))*)),
      ),
    )

}
