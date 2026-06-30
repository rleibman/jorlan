/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.rss

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.http.*
import zio.http.ZClient
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object RssFeedSkillSpec extends ZIOSpecDefault {

  private val dummyCtx = InvocationContext(actorId = UserId(1L), agentId = None, sessionId = None)

  private val sampleRss =
    """|<?xml version="1.0" encoding="UTF-8"?>
       |<rss version="2.0">
       |  <channel>
       |    <title>Test Feed</title>
       |    <item>
       |      <title>First Article</title>
       |      <link>https://example.com/1</link>
       |      <description>Content of first article</description>
       |      <pubDate>Thu, 01 Jan 2026 12:00:00 GMT</pubDate>
       |    </item>
       |    <item>
       |      <title>Second Article</title>
       |      <link>https://example.com/2</link>
       |      <description>Content of second article</description>
       |      <pubDate>Fri, 02 Jan 2026 12:00:00 GMT</pubDate>
       |    </item>
       |  </channel>
       |</rss>""".stripMargin

  private val sampleAtom =
    """|<?xml version="1.0" encoding="UTF-8"?>
       |<feed xmlns="http://www.w3.org/2005/Atom">
       |  <title>Atom Test Feed</title>
       |  <entry>
       |    <title>Atom Entry One</title>
       |    <link href="https://example.com/atom/1" rel="alternate"/>
       |    <summary>Summary of entry one</summary>
       |    <published>2026-01-01T12:00:00Z</published>
       |  </entry>
       |</feed>""".stripMargin

  private def makeSkill(
    feedsRef: Ref[List[String]],
    client:   Client,
  ): UIO[RssFeedSkill] =
    Ref.make(Map.empty[String, FeedCacheEntry]).map { cache =>
      RssFeedSkill(
        client = client,
        getFeeds = feedsRef.get,
        saveFeeds = feeds => feedsRef.set(feeds),
        feedCache = cache,
      )
    }

  private def stubClient(
    responseBody: String,
    status:       Status = Status.Ok,
  ): Client = {
    val driver = new ZClient.Driver[Any, Scope, Throwable] {
      override def request(
        version:        Version,
        method:         Method,
        url:            URL,
        headers:        Headers,
        body:           Body,
        sslConfig:      Option[ClientSSLConfig],
        proxy:          Option[Proxy],
      )(implicit trace: Trace,
      ): ZIO[Any & Scope, Throwable, Response] =
        ZIO.succeed(Response(status = status, body = Body.fromString(responseBody)))

      override def socket[Env1 <: Any](
        version: Version,
        url:     URL,
        headers: Headers,
        app:     WebSocketApp[Env1],
      )(implicit
        trace: Trace,
        ev:    Scope =:= Scope,
      ): ZIO[Env1 & Scope, Throwable, Response] =
        ZIO.fail(new Exception("websocket not supported in stub"))
    }
    ZClient.fromDriver(driver)
  }

  override def spec =
    suite("RssFeedSkillSpec")(
      suite("RssFeedParser")(
        test("parses RSS 2.0 feed correctly") {
          val entries = RssFeedParser.parse(sampleRss, "https://example.com/feed.xml")
          assertTrue(
            entries.isRight,
            entries.exists(_.size == 2),
            entries.exists(_.head.title == "First Article"),
            entries.exists(_.head.link == "https://example.com/1"),
            entries.exists(_.head.feedUrl == "https://example.com/feed.xml"),
          )
        },
        test("parses Atom 1.0 feed correctly") {
          val entries = RssFeedParser.parse(sampleAtom, "https://example.com/atom.xml")
          assertTrue(
            entries.isRight,
            entries.exists(_.size == 1),
            entries.exists(_.head.title == "Atom Entry One"),
            entries.exists(_.head.link == "https://example.com/atom/1"),
            entries.exists(_.head.pubDate == "2026-01-01T12:00:00Z"),
          )
        },
        test("respects limit") {
          val entries = RssFeedParser.parse(sampleRss, "https://example.com/feed.xml", limit = 1)
          assertTrue(entries.exists(_.size == 1))
        },
        test("returns error for invalid XML") {
          val result = RssFeedParser.parse("not xml at all <broken", "https://example.com")
          assertTrue(result.isLeft)
        },
        test("returns error for non-feed XML root") {
          val result = RssFeedParser.parse("<html><body>not a feed</body></html>", "https://example.com")
          assertTrue(result.isLeft)
        },
      ),
      suite("rss.list_saved")(
        test("returns empty list initially") {
          for {
            feedsRef <- Ref.make(List.empty[String])
            skill    <- makeSkill(feedsRef, null.asInstanceOf[Client])
            result   <- skill.invoke(dummyCtx, "rss.list_saved", Json.Obj())
          } yield assertTrue(result == Json.Arr())
        },
        test("returns saved feeds") {
          for {
            feedsRef <- Ref.make(List("https://example.com/feed.xml"))
            skill    <- makeSkill(feedsRef, null.asInstanceOf[Client])
            result   <- skill.invoke(dummyCtx, "rss.list_saved", Json.Obj())
          } yield assertTrue(result == Json.Arr(Json.Str("https://example.com/feed.xml")))
        },
      ),
      suite("rss.save_feed")(
        test("adds a new feed URL") {
          for {
            feedsRef <- Ref.make(List.empty[String])
            skill    <- makeSkill(feedsRef, null.asInstanceOf[Client])
            result   <- skill
              .invoke(dummyCtx, "rss.save_feed", Json.Obj("url" -> Json.Str("https://example.com/feed.xml")))
            feeds <- feedsRef.get
          } yield assertTrue(
            feeds == List("https://example.com/feed.xml"),
            result match {
              case Json.Obj(fields) => fields.exists { case ("saved", Json.Bool(true)) => true; case _ => false }
              case _                => false
            },
          )
        },
        test("does not duplicate an already-saved feed") {
          for {
            feedsRef <- Ref.make(List("https://example.com/feed.xml"))
            skill    <- makeSkill(feedsRef, null.asInstanceOf[Client])
            _ <- skill.invoke(dummyCtx, "rss.save_feed", Json.Obj("url" -> Json.Str("https://example.com/feed.xml")))
            feeds <- feedsRef.get
          } yield assertTrue(feeds.size == 1)
        },
      ),
      suite("rss.remove_feed")(
        test("removes an existing feed URL") {
          for {
            feedsRef <- Ref.make(List("https://example.com/feed.xml", "https://other.com/rss"))
            skill    <- makeSkill(feedsRef, null.asInstanceOf[Client])
            result   <- skill
              .invoke(dummyCtx, "rss.remove_feed", Json.Obj("url" -> Json.Str("https://example.com/feed.xml")))
            feeds <- feedsRef.get
          } yield assertTrue(
            feeds == List("https://other.com/rss"),
            result match {
              case Json.Obj(fields) => fields.exists { case ("removed", Json.Bool(true)) => true; case _ => false }
              case _                => false
            },
          )
        },
        test("remove of non-existent URL is a no-op") {
          for {
            feedsRef <- Ref.make(List("https://example.com/feed.xml"))
            skill    <- makeSkill(feedsRef, null.asInstanceOf[Client])
            result   <- skill
              .invoke(dummyCtx, "rss.remove_feed", Json.Obj("url" -> Json.Str("https://nonexistent.com/rss")))
            feeds <- feedsRef.get
          } yield assertTrue(
            feeds == List("https://example.com/feed.xml"),
            result match {
              case Json.Obj(fields) => fields.exists { case ("removed", Json.Bool(false)) => true; case _ => false }
              case _                => false
            },
          )
        },
      ),
      suite("rss.fetch")(
        test("fetches and parses an RSS feed") {
          for {
            feedsRef <- Ref.make(List.empty[String])
            client = stubClient(sampleRss)
            skill  <- makeSkill(feedsRef, client)
            result <- skill.invoke(dummyCtx, "rss.fetch", Json.Obj("url" -> Json.Str("https://example.com/feed.xml")))
          } yield assertTrue(result match {
            case Json.Arr(entries) => entries.size == 2
            case _                 => false
          })
        },
        test("respects limit arg") {
          for {
            feedsRef <- Ref.make(List.empty[String])
            client = stubClient(sampleRss)
            skill  <- makeSkill(feedsRef, client)
            result <- skill.invoke(
              dummyCtx,
              "rss.fetch",
              Json.Obj("url" -> Json.Str("https://example.com/feed.xml"), "limit" -> Json.Num(1)),
            )
          } yield assertTrue(result match {
            case Json.Arr(entries) => entries.size == 1
            case _                 => false
          })
        },
        test("fails with JorlanError on HTTP non-2xx") {
          for {
            feedsRef <- Ref.make(List.empty[String])
            client = stubClient("Not Found", Status.NotFound)
            skill  <- makeSkill(feedsRef, client)
            result <- skill
              .invoke(dummyCtx, "rss.fetch", Json.Obj("url" -> Json.Str("https://example.com/feed.xml"))).either
          } yield assertTrue(result.isLeft)
        },
        test("fails with JorlanError when body is not valid XML") {
          for {
            feedsRef <- Ref.make(List.empty[String])
            client = stubClient("not xml")
            skill  <- makeSkill(feedsRef, client)
            result <- skill
              .invoke(dummyCtx, "rss.fetch", Json.Obj("url" -> Json.Str("https://example.com/feed.xml"))).either
          } yield assertTrue(result.isLeft)
        },
        test("fails when url arg is missing") {
          for {
            feedsRef <- Ref.make(List.empty[String])
            client = stubClient(sampleRss)
            skill  <- makeSkill(feedsRef, client)
            result <- skill.invoke(dummyCtx, "rss.fetch", Json.Obj()).either
          } yield assertTrue(result.isLeft)
        },
      ),
      test("unknown tool returns error") {
        for {
          feedsRef <- Ref.make(List.empty[String])
          skill    <- makeSkill(feedsRef, null.asInstanceOf[Client])
          result   <- skill.invoke(dummyCtx, "rss.nonexistent", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
    )

}
