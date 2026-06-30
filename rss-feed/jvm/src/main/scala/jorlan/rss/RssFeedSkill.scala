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

private case class FeedCacheEntry(
  etag:         Option[String],
  lastModified: Option[String],
  entries:      List[RssEntry],
)

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
  * @param client
  *   zio-http client for fetching feed XML
  * @param getFeeds
  *   effect that reads the saved feed list from settings
  * @param saveFeeds
  *   effect that overwrites the saved feed list in settings
  * @param feedCache
  *   per-URL ETag / Last-Modified / parsed-entries cache; avoids re-downloading unchanged feeds
  */
class RssFeedSkill(
  client:    Client,
  getFeeds:  IO[Nothing, List[String]],
  saveFeeds: List[String] => IO[Nothing, Unit],
  feedCache: Ref[Map[String, FeedCacheEntry]],
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
    doc = Some(
      """|# RSS Feed Skill
         |
         |Built-in skill for subscribing to and reading RSS and Atom news feeds (RSS 2.0 and Atom 1.0).
         |
         |## Tools
         |
         || Tool              | Description                                          | Capability  |
         ||-------------------|------------------------------------------------------|-------------|
         || `rss.fetch`       | Fetch a feed URL and return the most recent entries  | `rss.read`  |
         || `rss.list_saved`  | List all persisted feed URLs                         | `rss.read`  |
         || `rss.save_feed`   | Add a feed URL to the persisted list                 | `rss.read`  |
         || `rss.remove_feed` | Remove a feed URL from the persisted list            | `rss.read`  |
         |
         |## Setup
         |
         |No external API key required. Grant the `rss.read` capability to an agent via **Admin → Agents → Capabilities**.
         |
         |## Packaging
         |
         |The skill ships as two artifacts:
         |- **JVM runtime**: `sbt rssFeedSkillJVM/package` — include on the server classpath
         |- **Config UI**: `sbt rssFeedSkillJS/fullLinkJS` — copy output to `<staticContentDir>/skills/jorlan-rss-feed-skill.js`
         |
         |## Troubleshooting
         |
         || Symptom | Check |
         ||---------|-------|
         || Parse error | Verify the URL serves valid RSS 2.0 or Atom 1.0 XML |
         || Config UI blank | Ensure `jorlan-rss-feed-skill.js` is in the `skills/` static directory |
         || Agent cannot call tools | Confirm the agent has the `rss.read` capability |
         |""".stripMargin,
    ),
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
        requiredCapabilities = List(CapabilityName("rss.manage")),
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
        requiredCapabilities = List(CapabilityName("rss.manage")),
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
      url <- requireStr(args, "url")
      limit = int(args, "limit").getOrElse(10).max(1).min(50)
      entries <- fetchEntries(url, limit)
      json    <- ZIO
        .fromEither(entries.toJsonAST)
        .mapError(e => JorlanError(s"Failed to encode feed entries for '$url': $e"))
    } yield json

  private def fetchEntries(
    url:   String,
    limit: Int,
  ): IO[JorlanError, List[RssEntry]] =
    for {
      cached <- feedCache.get.map(_.get(url))
      baseReq = Request
        .get(url)
        .addHeader(
          Header.Accept(
            MediaType.application.`rss+xml`,
            MediaType.application.xml,
            MediaType.text.xml,
            MediaType.text.plain,
          ),
        )
      req = cached.foldLeft(baseReq) {
        (
          r,
          c,
        ) =>
          val withEtag = c.etag.fold(r)(v => r.addHeader(Header.Custom("If-None-Match", v)))
          val withLastMod =
            c.lastModified.fold(withEtag)(v => withEtag.addHeader(Header.Custom("If-Modified-Since", v)))
          withLastMod
      }
      resp    <- client.batched(req).mapError(e => JorlanError(s"HTTP error fetching '$url'", Some(e)))
      entries <-
        if (resp.status.code == 304) {
          ZIO.succeed(cached.map(_.entries).getOrElse(List.empty))
        } else if (!resp.status.isSuccess) {
          ZIO.fail(JorlanError(s"Feed '$url' returned HTTP ${resp.status.code}"))
        } else {
          for {
            body   <- resp.body.asString.mapError(e => JorlanError(s"Failed to read feed response for '$url'", Some(e)))
            parsed <- ZIO
              .fromEither(RssFeedParser.parse(body, url, limit)).mapError(msg =>
                JorlanError(s"Failed to parse feed '$url': $msg"),
              )
            etag = resp.headers.get("ETag")
            lastMod = resp.headers.get("Last-Modified")
            _ <- feedCache.update(_.updated(url, FeedCacheEntry(etag, lastMod, parsed)))
          } yield parsed
        }
    } yield entries.take(limit)

  private def listSaved(): IO[JorlanError, Json] =
    getFeeds.map { feeds =>
      Json.Arr(feeds.map(Json.Str(_))*)
    }

  private def saveFeed(args: Json): IO[JorlanError, Json] =
    for {
      url     <- requireStr(args, "url")
      current <- getFeeds
      updated = if (current.contains(url)) current else current :+ url
      _ <- saveFeeds(updated)
    } yield Json.Obj(
      "saved"      -> Json.Bool(true),
      "url"        -> Json.Str(url),
      "totalFeeds" -> Json.Num(updated.size),
    )

  private def removeFeed(args: Json): IO[JorlanError, Json] =
    for {
      url     <- requireStr(args, "url")
      current <- getFeeds
      updated = current.filterNot(_ == url)
      removed = current.size != updated.size
      _ <- saveFeeds(updated)
    } yield Json.Obj(
      "removed"    -> Json.Bool(removed),
      "url"        -> Json.Str(url),
      "totalFeeds" -> Json.Num(updated.size),
    )

}
