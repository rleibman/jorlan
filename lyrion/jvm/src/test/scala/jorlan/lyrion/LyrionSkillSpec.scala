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
    """{
      |  "id":1,
      |  "result":{
      |    "players_loop":[
      |      {"playerid":"aa:bb:cc:dd:ee:ff","name":"Living Room","power":1,"isplaying":1},
      |      {"playerid":"11:22:33:44:55:66","name":"Kitchen","power":0,"isplaying":0}
      |    ]
      |  }
      |}""".stripMargin,
  )

  /** Generic success response for commands that just need a result object. */
  private val okResponse = Response.json("""{"id":1,"result":{}}""")

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
    )

}
