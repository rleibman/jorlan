/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.rss

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import just.semver.SemVer
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

/** Built-in skill for reading RSS and Atom news feeds.
  *
  * Tools:
  *   - `rss.fetch` — fetch and parse a feed URL, returning recent entries
  *   - `rss.list_saved` — list feed URLs persisted in server_settings
  *   - `rss.save_feed` — add a feed URL to the persisted list
  *   - `rss.remove_feed` — remove a feed URL from the persisted list
  *
  * No external API key required. Feed list is stored under the `skill.rss` server_settings key.
  *
  * @param client zio-http client for fetching feed XML
  * @param getFeeds effect that reads the saved feed list from settings
  * @param saveFeeds effect that overwrites the saved feed list in settings
  */
class RssFeedSkill(
  client:    Client,
  getFeeds:  IO[Nothing, List[String]],
  saveFeeds: List[String] => IO[Nothing, Unit],
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "rss",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "rss",
      "atom",
      "feed",
      "news",
      "articles",
      "headlines",
      "blog",
      "podcast",
      "subscribe",
      "updates",
      "latest",
      "recent",
      "read",
      "syndication",
      "entries",
    ),
    configKey = None,
    configJsModule = Some("jorlan-rss-feed"),
    tools = List(
      ToolDescriptor(
        name = "rss.fetch",
        description = "Fetch an RSS or Atom feed from a URL and return the most recent entries (title, link, description, pubDate). Supports both RSS 2.0 and Atom 1.0.",
        inputSchema = json"""{"type":"object","properties":{"url":{"type":"string","description":"The RSS or Atom feed URL to fetch"},"limit":{"type":"integer","description":"Maximum number of entries to return (default: 10, max: 50)"}},"required":["url"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("rss.read")),
        examplePrompts = List(
          "Get the latest news from https://feeds.bbci.co.uk/news/rss.xml",
          "Fetch the Hacker News top stories feed",
          "Show me recent articles from the ZIO blog",
        ),
      ),
      ToolDescriptor(
        name = "rss.list_saved",
        description = "List all feed URLs that have been saved for tracking.",
        inputSchema = json"""{"type":"object","properties":{},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("rss.read")),
        examplePrompts = List(
          "What RSS feeds are saved?",
          "Show me my watched feeds",
          "List all tracked news feeds",
        ),
      ),
      ToolDescriptor(
        name = "rss.save_feed",
        description = "Save a feed URL to the tracked feed list so it can be easily fetched later.",
        inputSchema = json"""{"type":"object","properties":{"url":{"type":"string","description":"The RSS or Atom feed URL to save"}},"required":["url"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("rss.read")),
        examplePrompts = List(
          "Save https://feeds.bbci.co.uk/news/rss.xml as a watched feed",
          "Add the Hacker News feed to my list",
          "Track this RSS feed",
        ),
      ),
      ToolDescriptor(
        name = "rss.remove_feed",
        description = "Remove a feed URL from the tracked feed list.",
        inputSchema = json"""{"type":"object","properties":{"url":{"type":"string","description":"The RSS or Atom feed URL to remove"}},"required":["url"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("rss.read")),
        examplePrompts = List(
          "Remove https://feeds.bbci.co.uk/news/rss.xml from my feeds",
          "Stop tracking the Hacker News feed",
          "Delete this feed from my list",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "rss.fetch"       => fetchFeed(args)
      case "rss.list_saved"  => listSaved()
      case "rss.save_feed"   => saveFeed(args)
      case "rss.remove_feed" => removeFeed(args)
      case other             => ZIO.fail(ValidationError(s"RssFeedSkill: unknown tool '$other'"))
    }

  private def fetchFeed(args: Json): IO[JorlanError, Json] =
    for {
      url   <- fieldStr(args, "url")
      limit <- fieldIntOpt(args, "limit").map(_.getOrElse(10).max(1).min(50))
      xml   <- fetchXml(url)
      entries <- ZIO
        .fromEither(RssFeedParser.parse(xml, url, limit))
        .mapError(msg => JorlanError(s"Failed to parse feed '$url': $msg"))
    } yield entries.toJson.fromJson[Json].getOrElse(Json.Arr())

  private def listSaved(): IO[JorlanError, Json] =
    getFeeds.map { feeds =>
      Json.Arr(feeds.map(Json.Str(_))*)
    }

  private def saveFeed(args: Json): IO[JorlanError, Json] =
    for {
      url     <- fieldStr(args, "url")
      current <- getFeeds
      updated = if (current.contains(url)) current else current :+ url
      _       <- saveFeeds(updated)
    } yield Json.Obj(
      "saved"    -> Json.Bool(true),
      "url"      -> Json.Str(url),
      "totalFeeds" -> Json.Num(updated.size),
    )

  private def removeFeed(args: Json): IO[JorlanError, Json] =
    for {
      url     <- fieldStr(args, "url")
      current <- getFeeds
      updated = current.filterNot(_ == url)
      removed = current.size != updated.size
      _       <- saveFeeds(updated)
    } yield Json.Obj(
      "removed"    -> Json.Bool(removed),
      "url"        -> Json.Str(url),
      "totalFeeds" -> Json.Num(updated.size),
    )

  private def fetchXml(url: String): IO[JorlanError, String] =
    client
      .batched(Request.get(url).addHeader(Header.Accept(MediaType.application.`rss+xml`, MediaType.application.xml, MediaType.text.xml, MediaType.text.plain)))
      .mapError(e => JorlanError(s"HTTP error fetching '$url'", Some(e)))
      .flatMap { resp =>
        resp.body.asString
          .mapError(e => JorlanError(s"Failed to read feed response body for '$url'", Some(e)))
          .flatMap { body =>
            if (!resp.status.isSuccess)
              ZIO.fail(JorlanError(s"Feed '$url' returned HTTP ${resp.status.code}"))
            else
              ZIO.succeed(body)
          }
      }

  private def fieldStr(
    args: Json,
    name: String,
  ): IO[JorlanError, String] =
    args match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (`name`, Json.Str(v)) => v }
          .fold(ZIO.fail(ValidationError(s"missing required field '$name'")): IO[JorlanError, String])(ZIO.succeed(_))
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }

  private def fieldIntOpt(
    args: Json,
    name: String,
  ): IO[JorlanError, Option[Int]] =
    args match {
      case Json.Obj(fields) =>
        fields.collectFirst { case (`name`, v) => v } match {
          case None              => ZIO.none
          case Some(Json.Num(n)) =>
            ZIO
              .attempt(n.intValueExact()).mapBoth(_ => ValidationError(s"field '$name' must be an integer"), Some(_))
          case Some(_) => ZIO.fail(ValidationError(s"field '$name' must be an integer"))
        }
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }

}
