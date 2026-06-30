/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.lyrion

import jorlan.*
import jorlan.connector.{HasDashboardData, HasValidation, InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import just.semver.SemVer
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

/** Skill that interfaces with a Lyrion Music Server via its JSON-RPC API.
  *
  * Exposes tools for listing players, checking playback status, controlling playback (play/pause/stop), adjusting
  * volume, navigating tracks, and browsing the current playlist.
  *
  * All tools require the `lyrion.control` capability.
  *
  * https://lyrion.org/reference/cli
  *
  * @param settings
  *   connection parameters for the Lyrion server
  * @param client
  *   the zio-http [[Client]] used for all outbound requests
  * @param lastPlayer
  *   tracks the MAC address of the most recently used player so subsequent calls can omit it
  */
class LyrionSkill(
  settings:   LyrionConfig,
  client:     Client,
  lastPlayer: Ref[Option[String]],
) extends Skill with HasDashboardData with HasValidation {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "lyrion",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "music",
      "audio",
      "Lyrion",
      "LMS",
      "play",
      "playlist",
      "album",
      "artist",
      "song",
      "track",
      "media",
      "Squeezebox",
      "streaming",
      "volume",
      "pause",
      "skip",
      "speaker",
      "stop",
      "genre",
      "search",
      "jazz",
      "rock",
      "pop",
      "classical",
      "queue",
      "shuffle",
      "random",
      "alarm",
    ),
    configKey = Some("skill.lyrion"),
    configJsModule = Some("jorlan-lyrion"),
    doc = Some(
      """|## Lyrion Skill
         |
         |Controls a Lyrion Music Server (LMS) via its JSON-RPC API.
         |
         |### Tools
         || Tool | Description | Capability |
         ||------|-------------|------------|
         || `lyrion.players` | List all players | `lyrion.control` |
         || `lyrion.status` | Get playback status | `lyrion.control` |
         || `lyrion.play` | Start/resume playback | `lyrion.control` |
         || `lyrion.pause` | Pause/unpause playback | `lyrion.control` |
         || `lyrion.stop` | Stop playback | `lyrion.control` |
         || `lyrion.volume` | Set or adjust volume | `lyrion.control` |
         || `lyrion.next` | Skip to next track | `lyrion.control` |
         || `lyrion.prev` | Go to previous track | `lyrion.control` |
         || `lyrion.playlist` | Browse current playlist | `lyrion.control` |
         |
         |### Setup
         |1. Ensure Lyrion Music Server is running and accessible from the Jorlan server.
         |2. Enable `lyrion` in Admin → Skills and add a `skill.lyrion` entry in Server Settings:
         |   - `host`: hostname or IP of the LMS server
         |   - `port`: JSON-RPC port (default 9000)
         |3. Grant the `lyrion.control` capability to agents.""".stripMargin,
    ),
    tools = List(
      ToolDescriptor(
        name = "lyrion.players",
        description = "List all Lyrion music players known to the server, including their power and playback state.",
        inputSchema = json"""{"type":"object","properties":{}}""",
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
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address (e.g. aa:bb:cc:dd:ee:ff) or player name. Omit to use the last player."}}}""",
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
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."}}}""",
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
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."}}}""",
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
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."}}}""",
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
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."},"level":{"type":"integer","description":"Volume level 0-100","minimum":0,"maximum":100}},"required":["level"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."}}}""",
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
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."}}}""",
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
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."}}}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "What's in the playlist?",
          "Show me the queue",
          "List all songs in the current playlist",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.search",
        description = "Search the music library for artists, albums, songs, and genres matching a text query. Returns IDs that can be passed to lyrion.queue.",
        inputSchema = json"""{"type":"object","properties":{"query":{"type":"string","description":"Search text, e.g. 'Prince', 'Thriller', 'jazzy piano'"},"count":{"type":"integer","description":"Max results per category (default 10)"}},"required":["query"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Search for Prince in my music library",
          "Find albums by Miles Davis",
          "Search for jazz tracks",
          "What music do I have by Beethoven?",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.browse.genres",
        description = "List all music genres available in the library with their IDs.",
        inputSchema = json"""{"type":"object","properties":{}}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "What genres are in my music library?",
          "List all music genres",
          "What kinds of music do I have?",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.browse.artists",
        description = "List artists in the library, optionally filtered by genre ID.",
        inputSchema = json"""{"type":"object","properties":{"genreId":{"type":"string","description":"Filter by genre ID (from lyrion.browse.genres)"},"count":{"type":"integer","description":"Max results (default 100)"}},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "List jazz artists",
          "Show me rock artists in my library",
          "Who are all the artists in my collection?",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.browse.albums",
        description = "List albums, optionally filtered by artist ID or genre ID.",
        inputSchema = json"""{"type":"object","properties":{"artistId":{"type":"string","description":"Filter by artist ID (from lyrion.search or lyrion.browse.artists)"},"genreId":{"type":"string","description":"Filter by genre ID (from lyrion.browse.genres)"},"count":{"type":"integer","description":"Max results (default 50)"}},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "List albums by Prince",
          "Show me jazz albums",
          "What albums do I have?",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.browse.songs",
        description = "List songs, optionally filtered by album ID or artist ID.",
        inputSchema = json"""{"type":"object","properties":{"albumId":{"type":"string","description":"Filter by album ID (from lyrion.browse.albums)"},"artistId":{"type":"string","description":"Filter by artist ID"},"count":{"type":"integer","description":"Max results (default 50)"}},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "List tracks on Purple Rain",
          "Show songs by Miles Davis",
          "What songs are on that album?",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.queue",
        description = "Load music into a player's queue and start playing. Use 'type' to specify what to play (artist/album/genre/song) and 'id' from a previous search or browse result. Set 'mode' to 'load' (replace queue and play immediately, default) or 'add' (append to queue).",
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."},"type":{"type":"string","enum":["artist","album","genre","song"],"description":"Type of music to queue"},"id":{"type":"string","description":"ID of the artist, album, genre, or song (from lyrion.search or lyrion.browse.*)"},"mode":{"type":"string","enum":["load","add"],"description":"'load' replaces the queue and plays immediately; 'add' appends without interrupting"}},"required":["type","id"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Play something by Prince",
          "Play some jazz music",
          "Queue up some sexy R&B",
          "Put on the Purple Rain album",
          "Add some Miles Davis to the queue",
          "Play a random jazz artist",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.alarm.list",
        description = "List all alarms configured for a player.",
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."}}}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "What alarms are set in the bedroom?",
          "Show me the music alarms",
          "List alarms for the living room player",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.alarm.create",
        description = "Create a new alarm on a player. 'time' is HH:MM (24-hour). 'days' is an array of day names (Mon Tue Wed Thu Fri Sat Sun) or the shortcut strings 'daily', 'weekdays', or 'weekends'. 'repeat' defaults to true.",
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."},"time":{"type":"string","description":"Alarm time in HH:MM 24-hour format, e.g. '07:30'"},"days":{"oneOf":[{"type":"array","items":{"type":"string","enum":["Mon","Tue","Wed","Thu","Fri","Sat","Sun"]}},{"type":"string","enum":["daily","weekdays","weekends"]}],"description":"Days to trigger the alarm"},"enabled":{"type":"boolean","description":"Whether the alarm is active (default true)"},"repeat":{"type":"boolean","description":"Whether the alarm repeats (default true)"},"volume":{"type":"integer","description":"Override volume 0-100, or -1 to use current volume (default -1)"}},"required":["time","days"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Set a wake-up alarm at 7am on weekdays",
          "Create a music alarm for 8:30 every day",
          "Wake me with music at 6:45 on Monday and Friday",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.alarm.update",
        description =
          "Update an existing alarm. Only the fields you provide are changed. Use lyrion.alarm.list to get alarm IDs.",
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."},"id":{"type":"string","description":"Alarm ID (from lyrion.alarm.list)"},"time":{"type":"string","description":"New alarm time in HH:MM 24-hour format"},"days":{"oneOf":[{"type":"array","items":{"type":"string","enum":["Mon","Tue","Wed","Thu","Fri","Sat","Sun"]}},{"type":"string","enum":["daily","weekdays","weekends"]}],"description":"New days setting"},"enabled":{"type":"boolean","description":"Enable or disable the alarm"},"repeat":{"type":"boolean","description":"Whether the alarm repeats"},"volume":{"type":"integer","description":"Volume override 0-100 or -1"}},"required":["id"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Move the 7am alarm to 7:30",
          "Disable the weekday alarm",
          "Change the alarm to ring on weekends too",
        ),
      ),
      ToolDescriptor(
        name = "lyrion.alarm.delete",
        description = "Delete an alarm from a player. Use lyrion.alarm.list to get alarm IDs.",
        inputSchema = json"""{"type":"object","properties":{"playerId":{"type":"string","description":"Player MAC address or name. Omit to use the last player."},"id":{"type":"string","description":"Alarm ID (from lyrion.alarm.list)"}},"required":["id"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("lyrion.control")),
        examplePrompts = List(
          "Delete the 7am alarm",
          "Remove all my alarms",
          "Cancel the weekday music alarm",
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

    val effect: ZIO[Client & Scope, JorlanError, Json] = Client
      .streaming(request)
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

    ZIO.scoped(effect).provideEnvironment(ZEnvironment(client))
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

  /** Extract a named array field from a JSON object, returning List.empty if absent or non-array. */
  private def arrField(
    obj: Json,
    key: String,
  ): List[Json] =
    obj match {
      case Json.Obj(fields) =>
        fields.collectFirst { case (`key`, Json.Arr(elems)) => elems.toList }.getOrElse(List.empty)
      case _ => List.empty
    }

  /** Extract a required string arg from the invocation args JSON. */
  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "lyrion.players"        => listPlayers()
      case "lyrion.status"         => requirePlayerId(args).flatMap(playerStatus)
      case "lyrion.play"           => requirePlayerId(args).flatMap(play)
      case "lyrion.pause"          => requirePlayerId(args).flatMap(pause)
      case "lyrion.stop"           => requirePlayerId(args).flatMap(stop)
      case "lyrion.volume"         => invokeVolume(args)
      case "lyrion.next"           => requirePlayerId(args).flatMap(next)
      case "lyrion.previous"       => requirePlayerId(args).flatMap(previous)
      case "lyrion.playlist"       => requirePlayerId(args).flatMap(playlist)
      case "lyrion.search"         => search(args)
      case "lyrion.browse.genres"  => browseGenres()
      case "lyrion.browse.artists" => browseArtists(args)
      case "lyrion.browse.albums"  => browseAlbums(args)
      case "lyrion.browse.songs"   => browseSongs(args)
      case "lyrion.queue"          => queue(args)
      case "lyrion.alarm.list"     => requirePlayerId(args).flatMap(alarmList)
      case "lyrion.alarm.create"   => alarmCreate(args)
      case "lyrion.alarm.update"   => alarmUpdate(args)
      case "lyrion.alarm.delete"   => alarmDelete(args)
      case other                   => ZIO.fail(ValidationError(s"lyrion: unknown tool '$other'"))
    }

  private val macPattern = "^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$".r.pattern

  private def listPlayersRaw(): IO[JorlanError, List[Json]] =
    rpc("", List(Json.Str("players"), Json.Num(0), Json.Num(100))).map(arrField(_, "players_loop"))

  /** Resolve a player reference (MAC address or display name) to a MAC address.
    *
    * If the input is already a valid MAC, it is returned as-is. Otherwise, `listPlayers` is called and the first player
    * whose name contains the query (case-insensitive) is used.
    */
  private def resolvePlayer(playerIdOrName: String): IO[JorlanError, String] =
    if (macPattern.matcher(playerIdOrName).matches()) {
      ZIO.succeed(playerIdOrName)
    } else {
      listPlayersRaw().flatMap { players =>
        val lower = playerIdOrName.toLowerCase
        players
          .find(p => strField(p, "name").toLowerCase.contains(lower))
          .map(p => ZIO.succeed(strField(p, "playerid")))
          .getOrElse(ZIO.fail(JorlanError(s"No player found matching '$playerIdOrName'")))
      }
    }

  /** Extract and resolve `playerId` from args, falling back to the last used player. Updates `lastPlayer` on success.
    */
  private def requirePlayerId(args: Json): IO[JorlanError, String] = {
    val fromArgs = args match {
      case Json.Obj(fields) =>
        fields.collectFirst { case ("playerId", Json.Str(v)) if v.nonEmpty => v }
      case _ => None
    }
    val raw = fromArgs match {
      case Some(v) => ZIO.succeed(v)
      case None    =>
        lastPlayer.get.flatMap {
          case Some(id) => ZIO.succeed(id)
          case None     =>
            ZIO.fail(
              JorlanError(
                "No player specified and no player has been used yet. Please specify a player by name or MAC address.",
              ),
            )
        }
    }
    raw.flatMap(resolvePlayer).tap(mac => lastPlayer.set(Some(mac)))
  }

  private def listPlayers(): IO[JorlanError, Json] =
    listPlayersRaw().map { players =>
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
      playerId <- requirePlayerId(args)
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

  private def search(args: Json): IO[JorlanError, Json] =
    for {
      query <- requireStr(args, "query")
      count = int(args, "count").getOrElse(10)
      encoded = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
      result <- rpc("", List(Json.Str("search"), Json.Num(0), Json.Num(count * 3), Json.Str(s"term:$query")))
    } yield {
      val artists = arrField(result, "artists_loop").map { a =>
        Json.Obj(
          "type" -> Json.Str("artist"),
          "id"   -> Json.Str(strField(a, "id")),
          "name" -> Json.Str(strField(a, "artist")),
        )
      }
      val albums = arrField(result, "albums_loop").map { a =>
        Json.Obj(
          "type"   -> Json.Str("album"),
          "id"     -> Json.Str(strField(a, "id")),
          "name"   -> Json.Str(strField(a, "album")),
          "artist" -> Json.Str(strField(a, "artist")),
        )
      }
      val songs = arrField(result, "tracks_loop").take(count).map { s =>
        Json.Obj(
          "type"   -> Json.Str("song"),
          "id"     -> Json.Str(strField(s, "id")),
          "name"   -> Json.Str(strField(s, "title")),
          "artist" -> Json.Str(strField(s, "artist")),
          "album"  -> Json.Str(strField(s, "album")),
        )
      }
      Json.Obj(
        "query"   -> Json.Str(query),
        "artists" -> Json.Arr(artists.take(count)*),
        "albums"  -> Json.Arr(albums.take(count)*),
        "songs"   -> Json.Arr(songs*),
      )
    }

  private def browseGenres(): IO[JorlanError, Json] =
    rpc("", List(Json.Str("genres"), Json.Num(0), Json.Num(200))).map { result =>
      Json.Arr(
        arrField(result, "genres_loop").map { g =>
          Json.Obj("id" -> Json.Str(strField(g, "id")), "name" -> Json.Str(strField(g, "genre")))
        }*,
      )
    }

  private def browseArtists(args: Json): IO[JorlanError, Json] = {
    val count = int(args, "count").getOrElse(100)
    val filters = str(args, "genreId").map(id => List(Json.Str(s"genre_id:$id"))).getOrElse(List.empty)
    rpc("", List(Json.Str("artists"), Json.Num(0), Json.Num(count)) ++ filters).map { result =>
      Json.Arr(
        arrField(result, "artists_loop").map { a =>
          Json.Obj("id" -> Json.Str(strField(a, "id")), "name" -> Json.Str(strField(a, "artist")))
        }*,
      )
    }
  }

  private def browseAlbums(args: Json): IO[JorlanError, Json] = {
    val count = int(args, "count").getOrElse(50)
    val filters = List(
      str(args, "artistId").map(id => Json.Str(s"artist_id:$id")),
      str(args, "genreId").map(id => Json.Str(s"genre_id:$id")),
    ).flatten
    rpc("", List(Json.Str("albums"), Json.Num(0), Json.Num(count), Json.Str("tags:la")) ++ filters).map { result =>
      Json.Arr(
        arrField(result, "albums_loop").map { a =>
          Json.Obj(
            "id"     -> Json.Str(strField(a, "id")),
            "name"   -> Json.Str(strField(a, "album")),
            "artist" -> Json.Str(strField(a, "artist")),
            "year"   -> Json.Str(strField(a, "year")),
          )
        }*,
      )
    }
  }

  private def browseSongs(args: Json): IO[JorlanError, Json] = {
    val count = int(args, "count").getOrElse(50)
    val filters = List(
      str(args, "albumId").map(id => Json.Str(s"album_id:$id")),
      str(args, "artistId").map(id => Json.Str(s"artist_id:$id")),
    ).flatten
    rpc("", List(Json.Str("songs"), Json.Num(0), Json.Num(count), Json.Str("tags:alta")) ++ filters).map { result =>
      Json.Arr(
        arrField(result, "titles_loop").map { s =>
          Json.Obj(
            "id"       -> Json.Str(strField(s, "id")),
            "title"    -> Json.Str(strField(s, "title")),
            "artist"   -> Json.Str(strField(s, "artist")),
            "album"    -> Json.Str(strField(s, "album")),
            "tracknum" -> Json.Str(strField(s, "tracknum")),
          )
        }*,
      )
    }
  }

  private def queue(args: Json): IO[JorlanError, Json] =
    for {
      playerId <- requirePlayerId(args)
      itemType <- requireStr(args, "type")
      id       <- requireStr(args, "id")
      mode = str(args, "mode").getOrElse("load")
      validMode <- ZIO
        .fromOption(Some(mode).filter(m => m == "load" || m == "add"))
        .orElseFail(ValidationError(s"lyrion.queue: mode must be 'load' or 'add', got '$mode'"))
      idParam <- itemType match {
        case "artist" => ZIO.succeed(s"artist_id:$id")
        case "album"  => ZIO.succeed(s"album_id:$id")
        case "genre"  => ZIO.succeed(s"genre_id:$id")
        case "song"   => ZIO.succeed(s"track_id:$id")
        case other    =>
          ZIO.fail(ValidationError(s"lyrion.queue: unknown type '$other'; expected artist|album|genre|song"))
      }
      _ <- rpc(playerId, List(Json.Str("playlistcontrol"), Json.Str(s"cmd:$validMode"), Json.Str(idParam)))
    } yield Json.Obj(
      "ok"       -> Json.Bool(true),
      "playerId" -> Json.Str(playerId),
      "type"     -> Json.Str(itemType),
      "id"       -> Json.Str(id),
      "mode"     -> Json.Str(validMode),
    )

  /** Parse "HH:MM" into seconds since midnight. */
  private def parseTimeHHMM(t: String): IO[JorlanError, Int] =
    t.split(":").toList match {
      case hStr :: mStr :: Nil =>
        ZIO
          .attempt(hStr.toInt * 3600 + mStr.toInt * 60)
          .mapError(_ => ValidationError(s"Invalid time '$t'; expected HH:MM 24-hour format"))
          .flatMap { secs =>
            if (secs < 0 || secs >= 86400) ZIO.fail(ValidationError(s"Time '$t' out of range"))
            else ZIO.succeed(secs)
          }
      case _ => ZIO.fail(ValidationError(s"Invalid time '$t'; expected HH:MM 24-hour format"))
    }

  /** Format seconds since midnight as "HH:MM". */
  private def formatTimeHHMM(secs: Int): String = {
    val h = secs / 3600
    val m = (secs % 3600) / 60
    f"$h%02d:$m%02d"
  }

  /** Parse a days specification into Lyrion's `dow` string.
    *
    * Lyrion uses a string of digits where each digit is the day index: 0=Sunday, 1=Monday, 2=Tuesday, 3=Wednesday,
    * 4=Thursday, 5=Friday, 6=Saturday.
    */
  private def parseDays(args: Json): IO[JorlanError, String] = {
    val dayMap = Map(
      "Sun" -> "0",
      "Mon" -> "1",
      "Tue" -> "2",
      "Wed" -> "3",
      "Thu" -> "4",
      "Fri" -> "5",
      "Sat" -> "6",
    )
    args match {
      case Json.Obj(fields) =>
        fields.collectFirst { case ("days", v) => v } match {
          case None                       => ZIO.succeed("0123456") // default: every day
          case Some(Json.Str("daily"))    => ZIO.succeed("0123456")
          case Some(Json.Str("weekdays")) => ZIO.succeed("12345")
          case Some(Json.Str("weekends")) => ZIO.succeed("06")
          case Some(Json.Arr(dayArr))     =>
            val days = dayArr.collect { case Json.Str(d) => dayMap.getOrElse(d, "") }.filter(_.nonEmpty).sorted
            if (days.isEmpty) ZIO.fail(ValidationError("No valid day names provided"))
            else ZIO.succeed(days.mkString)
          case Some(other) => ZIO.fail(ValidationError(s"Invalid 'days' value: $other"))
        }
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }
  }

  /** Convert a Lyrion dow string (e.g. "0123456") back to human-readable day names. */
  private def formatDow(dow: String): List[String] = {
    val names = Map("0" -> "Sun", "1" -> "Mon", "2" -> "Tue", "3" -> "Wed", "4" -> "Thu", "5" -> "Fri", "6" -> "Sat")
    dow.split("").toList.flatMap(names.get)
  }

  private def alarmList(playerId: String): IO[JorlanError, Json] =
    rpc(
      playerId,
      List(Json.Str("alarms"), Json.Num(0), Json.Num(100), Json.Str("filter:defined"), Json.Str("tags:all")),
    ).map { result =>
      Json.Arr(
        arrField(result, "alarms_loop").map { a =>
          val timeSecs = numField(a, "time").intValue
          val dow = strField(a, "dow")
          Json.Obj(
            "id"      -> Json.Str(strField(a, "id")),
            "time"    -> Json.Str(formatTimeHHMM(timeSecs)),
            "days"    -> Json.Arr(formatDow(dow).map(Json.Str(_))*),
            "enabled" -> Json.Bool(boolField(a, "enabled")),
            "repeat"  -> Json.Bool(boolField(a, "repeat")),
            "volume"  -> Json.Num(numField(a, "volume")),
          )
        }*,
      )
    }

  private def alarmCreate(args: Json): IO[JorlanError, Json] =
    for {
      playerId <- requirePlayerId(args)
      timeStr  <- requireStr(args, "time")
      timeSecs <- parseTimeHHMM(timeStr)
      dow      <- parseDays(args)
      enabled = int(args, "enabled")
        .map(_ != 0).orElse(args match {
          case Json.Obj(f) => f.collectFirst { case ("enabled", Json.Bool(b)) => b }
          case _           => None
        }).getOrElse(true)
      repeat = int(args, "repeat")
        .map(_ != 0).orElse(args match {
          case Json.Obj(f) => f.collectFirst { case ("repeat", Json.Bool(b)) => b }
          case _           => None
        }).getOrElse(true)
      volume = int(args, "volume").getOrElse(-1)
      params = List(
        Json.Str("alarm"),
        Json.Str("add"),
        Json.Str(s"time:$timeSecs"),
        Json.Str(s"dow:$dow"),
        Json.Str(s"enabled:${if (enabled) 1 else 0}"),
        Json.Str(s"repeat:${if (repeat) 1 else 0}"),
        Json.Str(s"volume:$volume"),
      )
      result <- rpc(playerId, params)
    } yield Json.Obj(
      "ok"      -> Json.Bool(true),
      "id"      -> Json.Str(strField(result, "id")),
      "time"    -> Json.Str(timeStr),
      "days"    -> Json.Arr(formatDow(dow).map(Json.Str(_))*),
      "enabled" -> Json.Bool(enabled),
      "repeat"  -> Json.Bool(repeat),
    )

  private def alarmUpdate(args: Json): IO[JorlanError, Json] =
    for {
      playerId     <- requireStr(args, "playerId")
      id           <- requireStr(args, "id")
      timeParamOpt <- str(args, "time")
        .map(t => parseTimeHHMM(t).map(s => Some(s"time:$s")))
        .getOrElse(ZIO.succeed(None))
      dowParamOpt <- args match {
        case Json.Obj(f) if f.exists { case ("days", _) => true; case _ => false } =>
          parseDays(args).map(d => Some(s"dow:$d"))
        case _ => ZIO.succeed(None)
      }
      enabledParamOpt = args match {
        case Json.Obj(f) =>
          f.collectFirst {
            case ("enabled", Json.Bool(b)) => s"enabled:${if (b) 1 else 0}"
            case ("enabled", Json.Num(n))  => s"enabled:${n.intValue}"
          }
        case _ => None
      }
      repeatParamOpt = args match {
        case Json.Obj(f) =>
          f.collectFirst {
            case ("repeat", Json.Bool(b)) => s"repeat:${if (b) 1 else 0}"
            case ("repeat", Json.Num(n))  => s"repeat:${n.intValue}"
          }
        case _ => None
      }
      volumeParamOpt = int(args, "volume").map(v => s"volume:$v")
      updateParams = List(
        Some(s"id:$id"),
        timeParamOpt,
        dowParamOpt,
        enabledParamOpt,
        repeatParamOpt,
        volumeParamOpt,
      ).flatten
      _ <-
        if (updateParams.size <= 1) ZIO.fail(ValidationError("lyrion.alarm.update: no fields to update"))
        else ZIO.unit
      _ <- rpc(playerId, Json.Str("alarm") :: Json.Str("update") :: updateParams.map(Json.Str(_)))
    } yield Json.Obj("ok" -> Json.Bool(true), "id" -> Json.Str(id))

  private def alarmDelete(args: Json): IO[JorlanError, Json] =
    for {
      playerId <- requirePlayerId(args)
      id       <- requireStr(args, "id")
      _        <- rpc(playerId, List(Json.Str("alarm"), Json.Str("delete"), Json.Str(s"id:$id")))
    } yield Json.Obj("ok" -> Json.Bool(true), "id" -> Json.Str(id))

  override def dashboardData(ctx: InvocationContext): IO[JorlanError, Json] =
    listPlayers()
      .flatMap {
        case Json.Arr(players) =>
          ZIO
            .foreach(players) {
              case p @ Json.Obj(fields) =>
                val playerId = fields.collectFirst { case ("id", Json.Str(id)) => id }.getOrElse("")
                if (playerId.isEmpty) ZIO.succeed(p)
                else
                  playerStatus(playerId)
                    .map { status =>
                      val statusFields: scala.collection.immutable.List[(String, Json)] = status match {
                        case Json.Obj(sf) => sf.toList
                        case _            => scala.collection.immutable.List.empty
                      }
                      Json.Obj((fields.toList ++ statusFields)*)
                    }
                    .catchAll(_ => ZIO.succeed(p))
              case other => ZIO.succeed(other)
            }
            .map(enriched => Json.Obj("players" -> Json.Arr(enriched*)))
        case other => ZIO.succeed(Json.Obj("players" -> Json.Arr()))
      }
      .catchAll(e => ZIO.succeed(Json.Obj("error" -> Json.Str(e.msg), "players" -> Json.Arr())))

  override def validate(): IO[JorlanError, SkillValidationResult] =
    listPlayers()
      .as(SkillValidationResult(ok = true, message = s"OK — connected to Lyrion server at ${settings.serverUrl}"))
      .catchAll(e =>
        ZIO.succeed(
          SkillValidationResult(ok = false, message = s"Cannot reach Lyrion server at ${settings.serverUrl}: ${e.msg}"),
        ),
      )

}

object LyrionSkill {

  def make(
    settings: LyrionConfig,
    client:   Client,
  ): UIO[LyrionSkill] =
    Ref.make(Option.empty[String]).map(ref => new LyrionSkill(settings, client, ref))

}
