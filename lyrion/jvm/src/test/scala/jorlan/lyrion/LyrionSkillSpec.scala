/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.lyrion

import jorlan.*
import jorlan.connector.InvocationContext
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object LyrionSkillSpec extends ZIOSpecDefault {

  private val testPlayerId = "aa:bb:cc:dd:ee:ff"

  private val dummyUserId = UserId(1L)

  private val ctx = InvocationContext(
    actorId = dummyUserId,
    agentId = None,
    sessionId = None,
  )

  /** Build a [[LyrionSkill]] pointing at an in-process test server on the given port. */
  private def makeSkill(port: Int)(client: Client): LyrionSkill =
    LyrionSkill(
      LyrionConfig(serverUrl = s"http://localhost:$port"),
      client,
    )

  // ─── Stub responses ───────────────────────────────────────────────────────────

  /** Response for `players` listing two players. */
  private val playersResponse = Response.json(
    """{"id":1,"result":{"players_loop":[{"playerid":"aa:bb:cc:dd:ee:ff","name":"Living Room","power":1,"isplaying":1},{"playerid":"11:22:33:44:55:66","name":"Kitchen","power":0,"isplaying":0}]}}""",
  )

  /** Generic success response for commands that just need a result object. */
  private val okResponse = Response.json("""{"id":1,"result":{}}""")

  private val genresResponse = Response.json(
    """{
      |  "id":1,
      |  "result":{
      |    "genres_loop":[
      |      {"id":"1","genre":"Jazz"},
      |      {"id":"2","genre":"Rock"},
      |      {"id":"3","genre":"R&B"}
      |    ]
      |  }
      |}""".stripMargin,
  )

  private val artistsResponse = Response.json(
    """{
      |  "id":1,
      |  "result":{
      |    "artists_loop":[
      |      {"id":"42","artist":"Prince"},
      |      {"id":"43","artist":"Miles Davis"}
      |    ]
      |  }
      |}""".stripMargin,
  )

  private val albumsResponse = Response.json(
    """{
      |  "id":1,
      |  "result":{
      |    "albums_loop":[
      |      {"id":"10","album":"Purple Rain","artist":"Prince","year":"1984"},
      |      {"id":"11","album":"Around the World in a Day","artist":"Prince","year":"1985"}
      |    ]
      |  }
      |}""".stripMargin,
  )

  private val songsResponse = Response.json(
    """{
      |  "id":1,
      |  "result":{
      |    "titles_loop":[
      |      {"id":"100","title":"Purple Rain","artist":"Prince","album":"Purple Rain","tracknum":"1"},
      |      {"id":"101","title":"When Doves Cry","artist":"Prince","album":"Purple Rain","tracknum":"2"}
      |    ]
      |  }
      |}""".stripMargin,
  )

  private val alarmsResponse = Response.json(
    """{
      |  "id":1,
      |  "result":{
      |    "alarms_loop":[
      |      {"id":"alarm1","time":25200,"dow":"12345","enabled":1,"repeat":1,"volume":-1},
      |      {"id":"alarm2","time":28800,"dow":"06","enabled":0,"repeat":1,"volume":60}
      |    ]
      |  }
      |}""".stripMargin,
  )

  private val alarmCreateResponse = Response.json(
    """{"id":1,"result":{"id":"alarm3"}}""",
  )

  private val searchResponse = Response.json(
    """{
      |  "id":1,
      |  "result":{
      |    "artists_loop":[{"id":"42","artist":"Prince"}],
      |    "albums_loop":[{"id":"10","album":"Purple Rain","artist":"Prince"}],
      |    "tracks_loop":[{"id":"100","title":"Purple Rain","artist":"Prince","album":"Purple Rain"}]
      |  }
      |}""".stripMargin,
  )

  private val statusResponse = Response.json(
    """{
      |  "id":1,
      |  "result":{
      |    "mode":"play",
      |    "title":"Purple Rain",
      |    "artist":"Prince",
      |    "album":"Purple Rain",
      |    "_mixer volume":75,
      |    "mixer volume":75
      |  }
      |}""".stripMargin,
  )

  private val playlistResponse = Response.json(
    """{
      |  "id":1,
      |  "result":{
      |    "playlist_loop":[
      |      {"title":"Purple Rain","artist":"Prince","album":"Purple Rain","duration":8.75},
      |      {"title":"When Doves Cry","artist":"Prince","album":"Purple Rain","duration":5.87}
      |    ]
      |  }
      |}""".stripMargin,
  )

  /** Response for volume set. */
  private def volumeResponse(level: Int) = Response.json(s"""{"id":1,"result":{"_mixer volume":$level}}""")

  // ─── Test suites ──────────────────────────────────────────────────────────────

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("LyrionSkill")(
      test("lyrion.players returns list of two players from stub response") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(playersResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.players", Json.Obj())
        } yield result match {
          case Json.Arr(players) =>
            assertTrue(players.length == 2) &&
            assertTrue(
              players.exists {
                case Json.Obj(fields) =>
                  fields.exists { case ("name", Json.Str("Living Room")) => true; case _ => false }
                case _ => false
              },
            ) &&
            assertTrue(
              players.exists {
                case Json.Obj(fields) =>
                  fields.exists { case ("name", Json.Str("Kitchen")) => true; case _ => false }
                case _ => false
              },
            )
          case other => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.volume clamps level of -5 to 0") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(volumeResponse(0))))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj("playerId" -> Json.Str(testPlayerId), "level" -> Json.Num(-5))
          result <- skill.invoke(ctx, "lyrion.volume", args)
        } yield result match {
          case Json.Obj(fields) =>
            fields.collectFirst { case ("volume", Json.Num(n)) => n.intValue } match {
              case Some(v) => assertTrue(v == 0)
              case None    => assertTrue(false)
            }
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.volume clamps level of 150 to 100") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(volumeResponse(100))))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj("playerId" -> Json.Str(testPlayerId), "level" -> Json.Num(150))
          result <- skill.invoke(ctx, "lyrion.volume", args)
        } yield result match {
          case Json.Obj(fields) =>
            fields.collectFirst { case ("volume", Json.Num(n)) => n.intValue } match {
              case Some(v) => assertTrue(v == 100)
              case None    => assertTrue(false)
            }
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.play returns ok:true") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj("playerId" -> Json.Str(testPlayerId))
          result <- skill.invoke(ctx, "lyrion.play", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.unknown tool fails with ValidationError") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.unknown", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.browse.genres returns genre list") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(genresResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.browse.genres", Json.Obj())
        } yield result match {
          case Json.Arr(genres) =>
            assertTrue(genres.length == 3) &&
            assertTrue(genres.exists {
              case Json.Obj(f) => f.exists { case ("name", Json.Str("Jazz")) => true; case _ => false }
              case _           => false
            })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.browse.artists returns artist list") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(artistsResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.browse.artists", Json.Obj())
        } yield result match {
          case Json.Arr(artists) =>
            assertTrue(artists.length == 2) &&
            assertTrue(artists.exists {
              case Json.Obj(f) => f.exists { case ("name", Json.Str("Prince")) => true; case _ => false }
              case _           => false
            })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.browse.albums returns album list") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(albumsResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.browse.albums", Json.Obj())
        } yield result match {
          case Json.Arr(albums) =>
            assertTrue(albums.length == 2) &&
            assertTrue(albums.exists {
              case Json.Obj(f) => f.exists { case ("name", Json.Str("Purple Rain")) => true; case _ => false }
              case _           => false
            })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.browse.songs returns song list") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(songsResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj("albumId" -> Json.Str("10"))
          result <- skill.invoke(ctx, "lyrion.browse.songs", args)
        } yield result match {
          case Json.Arr(songs) =>
            assertTrue(songs.length == 2) &&
            assertTrue(songs.exists {
              case Json.Obj(f) => f.exists { case ("title", Json.Str("Purple Rain")) => true; case _ => false }
              case _           => false
            })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.search returns artists albums and songs") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(searchResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj("query" -> Json.Str("Prince"))
          result <- skill.invoke(ctx, "lyrion.search", args)
        } yield result match {
          case Json.Obj(fields) =>
            val artists = fields.collectFirst { case ("artists", Json.Arr(a)) => a }.getOrElse(Nil)
            val albums = fields.collectFirst { case ("albums", Json.Arr(a)) => a }.getOrElse(Nil)
            val songs = fields.collectFirst { case ("songs", Json.Arr(s)) => s }.getOrElse(Nil)
            assertTrue(artists.nonEmpty) && assertTrue(albums.nonEmpty) && assertTrue(songs.nonEmpty)
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.queue with mode:load returns ok:true") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "type"     -> Json.Str("artist"),
            "id"       -> Json.Str("42"),
            "mode"     -> Json.Str("load"),
          )
          result <- skill.invoke(ctx, "lyrion.queue", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.alarm.list returns formatted alarm entries") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(alarmsResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj("playerId" -> Json.Str(testPlayerId))
          result <- skill.invoke(ctx, "lyrion.alarm.list", args)
        } yield result match {
          case Json.Arr(alarms) =>
            assertTrue(alarms.length == 2) &&
            assertTrue(alarms.exists {
              case Json.Obj(f) =>
                f.exists { case ("time", Json.Str("07:00")) => true; case _ => false } &&
                f.exists { case ("id", Json.Str("alarm1")) => true; case _ => false }
              case _ => false
            })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.alarm.create with weekdays shortcut sends correct dow") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(alarmCreateResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "time"     -> Json.Str("07:30"),
            "days"     -> Json.Str("weekdays"),
            "enabled"  -> Json.Bool(true),
            "repeat"   -> Json.Bool(true),
          )
          result <- skill.invoke(ctx, "lyrion.alarm.create", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false }) &&
            assertTrue(fields.exists { case ("time", Json.Str("07:30")) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.alarm.create with explicit days array") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(alarmCreateResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "time"     -> Json.Str("08:00"),
            "days"     -> Json.Arr(Json.Str("Mon"), Json.Str("Fri")),
          )
          result <- skill.invoke(ctx, "lyrion.alarm.create", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.alarm.create rejects invalid time format") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(alarmCreateResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "time"     -> Json.Str("7am"),
            "days"     -> Json.Str("daily"),
          )
          result <- skill.invoke(ctx, "lyrion.alarm.create", args).either
        } yield assertTrue(result.isLeft)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.alarm.update enables existing alarm") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "id"       -> Json.Str("alarm2"),
            "enabled"  -> Json.Bool(true),
          )
          result <- skill.invoke(ctx, "lyrion.alarm.update", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false }) &&
            assertTrue(fields.exists { case ("id", Json.Str("alarm2")) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.alarm.update with no fields fails") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj("playerId" -> Json.Str(testPlayerId), "id" -> Json.Str("alarm1"))
          result <- skill.invoke(ctx, "lyrion.alarm.update", args).either
        } yield assertTrue(result.isLeft)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.alarm.delete returns ok:true") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj("playerId" -> Json.Str(testPlayerId), "id" -> Json.Str("alarm1"))
          result <- skill.invoke(ctx, "lyrion.alarm.delete", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.queue with unknown type fails") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "type"     -> Json.Str("mood"),
            "id"       -> Json.Str("42"),
          )
          result <- skill.invoke(ctx, "lyrion.queue", args).either
        } yield assertTrue(result.isLeft)
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.pause returns ok:true") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.pause", Json.Obj("playerId" -> Json.Str(testPlayerId)))
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.stop returns ok:true") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.stop", Json.Obj("playerId" -> Json.Str(testPlayerId)))
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.next returns ok:true") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.next", Json.Obj("playerId" -> Json.Str(testPlayerId)))
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.previous returns ok:true") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.previous", Json.Obj("playerId" -> Json.Str(testPlayerId)))
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.status returns playback info") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(statusResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.status", Json.Obj("playerId" -> Json.Str(testPlayerId)))
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("title", Json.Str("Purple Rain")) => true; case _ => false }) &&
            assertTrue(fields.exists { case ("artist", Json.Str("Prince")) => true; case _ => false }) &&
            assertTrue(fields.exists { case ("mode", Json.Str("play")) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.playlist returns track list") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(playlistResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.invoke(ctx, "lyrion.playlist", Json.Obj("playerId" -> Json.Str(testPlayerId)))
        } yield result match {
          case Json.Arr(tracks) =>
            assertTrue(tracks.length == 2) &&
            assertTrue(tracks.exists {
              case Json.Obj(f) => f.exists { case ("title", Json.Str("Purple Rain")) => true; case _ => false }
              case _           => false
            })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.alarm.create with weekends shortcut sends correct dow") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(alarmCreateResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "time"     -> Json.Str("10:00"),
            "days"     -> Json.Str("weekends"),
            "enabled"  -> Json.Bool(true),
          )
          result <- skill.invoke(ctx, "lyrion.alarm.create", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false }) &&
            assertTrue(fields.exists { case ("time", Json.Str("10:00")) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.alarm.create with daily shortcut") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(alarmCreateResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "time"     -> Json.Str("06:00"),
            "days"     -> Json.Str("daily"),
          )
          result <- skill.invoke(ctx, "lyrion.alarm.create", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.queue with album type returns ok:true") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "type"     -> Json.Str("album"),
            "id"       -> Json.Str("10"),
          )
          result <- skill.invoke(ctx, "lyrion.queue", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.queue with genre type returns ok:true") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "type"     -> Json.Str("genre"),
            "id"       -> Json.Str("1"),
          )
          result <- skill.invoke(ctx, "lyrion.queue", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.queue with song type returns ok:true") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "type"     -> Json.Str("song"),
            "id"       -> Json.Str("100"),
          )
          result <- skill.invoke(ctx, "lyrion.queue", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.browse.artists filtered by genreId") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(artistsResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj("genreId" -> Json.Str("1"))
          result <- skill.invoke(ctx, "lyrion.browse.artists", args)
        } yield result match {
          case Json.Arr(artists) => assertTrue(artists.nonEmpty)
          case _                 => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.browse.albums filtered by artistId") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(albumsResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj("artistId" -> Json.Str("42"))
          result <- skill.invoke(ctx, "lyrion.browse.albums", args)
        } yield result match {
          case Json.Arr(albums) => assertTrue(albums.nonEmpty)
          case _                => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("lyrion.alarm.update with time change") {
        for {
          port   <- Server.install(Routes(Method.ANY / trailing -> handler(okResponse)))
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          args = Json.Obj(
            "playerId" -> Json.Str(testPlayerId),
            "id"       -> Json.Str("alarm1"),
            "time"     -> Json.Str("08:30"),
          )
          result <- skill.invoke(ctx, "lyrion.alarm.update", args)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("ok", Json.Bool(true)) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
      test("dashboardData returns players with enriched status") {
        for {
          responseRef <- Ref.make(0)
          port        <- Server.install(
            Routes(
              Method.ANY / trailing -> Handler.fromFunctionZIO[Request] { _ =>
                responseRef.getAndUpdate(_ + 1).map {
                  case 0 => playersResponse
                  case _ => statusResponse
                }
              },
            ),
          )
          client <- ZIO.service[Client]
          skill = makeSkill(port)(client)
          result <- skill.dashboardData(ctx)
        } yield result match {
          case Json.Obj(fields) =>
            assertTrue(fields.exists { case ("players", _) => true; case _ => false })
          case _ => assertTrue(false)
        }
      }.provide(Server.defaultWith(_.port(0)), Client.default),
    )

}
