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
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** Configuration for the Lyrion Music Server connection.
  *
  * @param serverUrl
  *   Base URL of the Lyrion Music Server (formerly Logitech Media Server / Squeezebox Server)
  * @param username
  *   Username for HTTP basic auth (currently unused; reserved for future support)
  * @param password
  *   Password for HTTP basic auth (currently unused; reserved for future support)
  */
case class LyrionSettings(
  serverUrl: String = "http://localhost:9000",
  username:  String = "",
  password:  String = "",
)

object LyrionSettings {

  given JsonDecoder[LyrionSettings] = DeriveJsonDecoder.gen[LyrionSettings]

}

/** Skill that interfaces with a Lyrion Music Server via its JSON-RPC API.
  *
  * Exposes tools for listing players, checking playback status, controlling playback (play/pause/stop), adjusting
  * volume, navigating tracks, and browsing the current playlist.
  *
  * All tools require the `lyrion.control` capability.
  *
  * @param settings
  *   connection parameters for the Lyrion server
  * @param client
  *   the zio-http [[Client]] used for all outbound requests
  */
class LyrionSkill(
  settings: LyrionSettings,
  client:   Client,
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "lyrion",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "lyrion.players",
        description = "List all Lyrion music players known to the server, including their power and playback state.",
        inputSchema = Json.decoder.decodeJson("""{"type":"object","properties":{}}""").getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "What music players do I have?",
          "List all Squeezebox players",
          "Show me my Lyrion devices",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.status",
        description = "Get the current playback status of a player, including track title, artist, album, and volume.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address (e.g. aa:bb:cc:dd:ee:ff)"}},"required":["playerId"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "What is playing in the living room?",
          "What song is on?",
          "Show playback status for player aa:bb:cc:dd:ee:ff",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.play",
        description = "Resume or start playback on a player.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address"}},"required":["playerId"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Play music in the living room",
          "Resume playback",
          "Start the music on player aa:bb:cc:dd:ee:ff",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.pause",
        description = "Pause playback on a player.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address"}},"required":["playerId"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Pause the music",
          "Pause playback on the kitchen player",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.stop",
        description = "Stop playback on a player.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address"}},"required":["playerId"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Stop the music",
          "Stop playback on the bedroom player",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.volume",
        description = "Set the volume on a player. Level is an integer from 0 (mute) to 100 (maximum).",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address"},"level":{"type":"integer","description":"Volume level 0-100","minimum":0,"maximum":100}},"required":["playerId","level"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Set the volume to 50 in the living room",
          "Turn the music up to 80",
          "Lower the volume to 30",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.next",
        description = "Skip to the next track in the playlist.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address"}},"required":["playerId"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Skip this song",
          "Next track",
          "Play the next song",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.previous",
        description = "Go back to the previous track in the playlist.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address"}},"required":["playerId"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Previous track",
          "Go back a song",
          "Play the previous song",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.playlist",
        description = "List the current playlist for a player.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address"}},"required":["playerId"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "What's in the playlist?",
          "Show me the queue",
          "List all songs in the current playlist",
        ),
      ),
    ),
  )

  /** Send a JSON-RPC request to the Lyrion server and return the `result` field of the response. */
  private def rpc(
    playerId: String,
    command:  List[Json],
  ): IO[JorlanError, Json] = {
    val body = Json.Obj(
      "id"     -> Json.Num(1),
      "method" -> Json.Str("slim.request"),
      "params" -> Json.Arr(Json.Str(playerId), Json.Arr(command*)),
    )
    val url = s"${settings.serverUrl}/jsonrpc.js"
    val request = Request
      .post(url, Body.fromString(body.toJson))
      .addHeader(Header.ContentType(MediaType.application.json))

    val effect: ZIO[Client, JorlanError, Json] = Client
      .batched(request)
      .mapError(e => JorlanError(s"Lyrion HTTP request failed: $e"))
      .flatMap { resp =>
        resp.body.asString
          .mapError(e => JorlanError(s"Lyrion: failed to read response body: $e"))
          .flatMap { bodyStr =>
            if (!resp.status.isSuccess) {
              ZIO.fail(JorlanError(s"Lyrion server returned ${resp.status.code}: $bodyStr"))
            } else {
              ZIO
                .fromEither(Json.decoder.decodeJson(bodyStr))
                .mapError(e => JorlanError(s"Lyrion: JSON parse error: $e"))
                .flatMap {
                  case Json.Obj(fields) =>
                    fields.collectFirst { case ("result", v) => v } match {
                      case Some(result) => ZIO.succeed(result)
                      case None         => ZIO.fail(JorlanError(s"Lyrion: no 'result' field in response: $bodyStr"))
                    }
                  case _ => ZIO.fail(JorlanError(s"Lyrion: unexpected response shape: $bodyStr"))
                }
            }
          }
      }

    effect.provideEnvironment(ZEnvironment(client))
  }

  /** Extract a named string field from a JSON object, returning empty string if absent. */
  private def strField(
    obj: Json,
    key: String,
  ): String =
    obj match {
      case Json.Obj(fields) =>
        fields
          .collectFirst {
            case (`key`, Json.Str(v))  => v
            case (`key`, Json.Num(n))  => n.toString
            case (`key`, Json.Bool(b)) => b.toString
          }.getOrElse("")
      case _ => ""
    }

  /** Extract a named number field from a JSON object, returning 0 if absent or non-numeric. */
  private def numField(
    obj: Json,
    key: String,
  ): Double =
    obj match {
      case Json.Obj(fields) =>
        fields.collectFirst { case (`key`, Json.Num(n)) => n.doubleValue }.getOrElse(0.0)
      case _ => 0.0
    }

  /** Extract a named boolean-ish field (0/1 int or real bool) from a JSON object. */
  private def boolField(
    obj: Json,
    key: String,
  ): Boolean =
    obj match {
      case Json.Obj(fields) =>
        fields
          .collectFirst {
            case (`key`, Json.Bool(b)) => b
            case (`key`, Json.Num(n))  => n.intValue != 0
            case (`key`, Json.Str(s))  => s == "1" || s.equalsIgnoreCase("true")
          }.getOrElse(false)
      case _ => false
    }

  /** Extract a named array field from a JSON object, returning Nil if absent or non-array. */
  private def arrField(
    obj: Json,
    key: String,
  ): List[Json] =
    obj match {
      case Json.Obj(fields) =>
        fields.collectFirst { case (`key`, Json.Arr(elems)) => elems.toList }.getOrElse(Nil)
      case _ => Nil
    }

  /** Extract a required string arg from the invocation args JSON. */
  private def requireStr(
    args: Json,
    name: String,
  ): IO[JorlanError, String] =
    args match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (`name`, Json.Str(v)) => v }
          .fold(ZIO.fail(ValidationError(s"missing required argument '$name'")): IO[JorlanError, String])(
            ZIO.succeed(_),
          )
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "lyrion.players"  => listPlayers()
      case "lyrion.status"   => requireStr(args, "playerId").flatMap(playerStatus)
      case "lyrion.play"     => requireStr(args, "playerId").flatMap(play)
      case "lyrion.pause"    => requireStr(args, "playerId").flatMap(pause)
      case "lyrion.stop"     => requireStr(args, "playerId").flatMap(stop)
      case "lyrion.volume"   => invokeVolume(args)
      case "lyrion.next"     => requireStr(args, "playerId").flatMap(next)
      case "lyrion.previous" => requireStr(args, "playerId").flatMap(previous)
      case "lyrion.playlist" => requireStr(args, "playerId").flatMap(playlist)
      case other             => ZIO.fail(ValidationError(s"lyrion: unknown tool '$other'"))
    }

  private def listPlayers(): IO[JorlanError, Json] =
    rpc("", List(Json.Str("players"), Json.Num(0), Json.Num(100))).map { result =>
      val players = arrField(result, "players_loop")
      Json.Arr(players.map { p =>
        Json.Obj(
          "id"        -> Json.Str(strField(p, "playerid")),
          "name"      -> Json.Str(strField(p, "name")),
          "power"     -> Json.Bool(boolField(p, "power")),
          "isPlaying" -> Json.Bool(boolField(p, "isplaying")),
        )
      }*)
    }

  private def playerStatus(playerId: String): IO[JorlanError, Json] =
    rpc(playerId, List(Json.Str("status"), Json.Str("-"), Json.Num(1), Json.Str("tags:aAltu"))).map { result =>
      val volumeResult = numField(result, "_mixer volume")
      val volume = if (volumeResult == 0.0) numField(result, "mixer volume") else volumeResult
      Json.Obj(
        "mode"   -> Json.Str(strField(result, "mode")),
        "title"  -> Json.Str(strField(result, "title")),
        "artist" -> Json.Str(strField(result, "artist")),
        "album"  -> Json.Str(strField(result, "album")),
        "volume" -> Json.Num(volume),
      )
    }

  private def play(playerId: String): IO[JorlanError, Json] =
    rpc(playerId, List(Json.Str("play"))).as(Json.Obj("ok" -> Json.Bool(true)))

  private def pause(playerId: String): IO[JorlanError, Json] =
    rpc(playerId, List(Json.Str("pause"), Json.Num(1))).as(Json.Obj("ok" -> Json.Bool(true)))

  private def stop(playerId: String): IO[JorlanError, Json] =
    rpc(playerId, List(Json.Str("stop"))).as(Json.Obj("ok" -> Json.Bool(true)))

  private def invokeVolume(args: Json): IO[JorlanError, Json] = {
    val levelOpt: Option[Int] = args match {
      case Json.Obj(fields) => fields.collectFirst { case ("level", Json.Num(n)) => n.intValue }
      case _                => None
    }
    for {
      playerId <- requireStr(args, "playerId")
      rawLevel <- ZIO
        .fromOption(levelOpt)
        .orElseFail(ValidationError("lyrion.volume: 'level' (integer) is required"))
      clampedLevel = math.max(0, math.min(100, rawLevel))
      result <- setVolume(playerId, clampedLevel)
    } yield result
  }

  private def setVolume(
    playerId: String,
    level:    Int,
  ): IO[JorlanError, Json] =
    rpc(playerId, List(Json.Str("mixer"), Json.Str("volume"), Json.Str(level.toString)))
      .as(Json.Obj("volume" -> Json.Num(level)))

  private def next(playerId: String): IO[JorlanError, Json] =
    rpc(playerId, List(Json.Str("playlist"), Json.Str("index"), Json.Str("+1")))
      .as(Json.Obj("ok" -> Json.Bool(true)))

  private def previous(playerId: String): IO[JorlanError, Json] =
    rpc(playerId, List(Json.Str("playlist"), Json.Str("index"), Json.Str("-1")))
      .as(Json.Obj("ok" -> Json.Bool(true)))

  private def playlist(playerId: String): IO[JorlanError, Json] =
    rpc(playerId, List(Json.Str("status"), Json.Num(0), Json.Num(100), Json.Str("tags:aAltu"))).map { result =>
      val tracks = arrField(result, "playlist_loop")
      Json.Arr(tracks.zipWithIndex.map { case (t, idx) =>
        Json.Obj(
          "index"    -> Json.Num(idx),
          "title"    -> Json.Str(strField(t, "title")),
          "artist"   -> Json.Str(strField(t, "artist")),
          "album"    -> Json.Str(strField(t, "album")),
          "duration" -> Json.Num(numField(t, "duration")),
        )
      }*)
    }

}

object LyrionSkill
