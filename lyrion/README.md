# Lyrion Music Skill

Controls playback on a [Lyrion Music Server](https://lyrion.org/) (formerly Logitech Media Server / Squeezebox Server)
via its JSON-RPC API.

**Skill name:** `lyrion`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Lets agents list players, check playback status, and control music (play, pause, stop, skip, volume, seek, shuffle,
repeat, search) on any Squeezebox-compatible device on your local network.

## Tools

| Tool             | Description                                                     |
|------------------|-----------------------------------------------------------------|
| `lyrion.players` | List all known players with power and playback state            |
| `lyrion.status`  | Get current track, artist, album, volume, position for a player |
| `lyrion.play`    | Resume or start playback                                        |
| `lyrion.pause`   | Pause playback                                                  |
| `lyrion.stop`    | Stop playback                                                   |
| `lyrion.next`    | Skip to the next track                                          |
| `lyrion.prev`    | Skip to the previous track                                      |
| `lyrion.volume`  | Set or adjust volume                                            |
| `lyrion.seek`    | Seek to a position in the current track                         |
| `lyrion.shuffle` | Toggle or set shuffle mode                                      |
| `lyrion.repeat`  | Toggle or set repeat mode                                       |
| `lyrion.search`  | Search the Lyrion library by title, artist, or album            |
| `lyrion.queue`   | Queue a track or album to play next or add to end               |

All tools take a `playerId` parameter (MAC address or player name). Omit to target the first available player.

---

## Capabilities required

| Capability       | Tools     |
|------------------|-----------|
| `lyrion.control` | All tools |

---

## Example prompts

- "What's playing on the living room speakers?"
- "Pause the music"
- "Skip to the next track"
- "Set the volume to 50"
- "Play some jazz"
- "Shuffle my playlist"
- "What players are available?"

---

## Configuration

`configKey`: `skill.lyrion`  
`configJsModule`: `jorlan-lyrion`

```json
{
  "serverUrl": "http://localhost:9000",
  "username": "",
  "password": ""
}
```

| Field       | Type   | Default                 | Description                                     |
|-------------|--------|-------------------------|-------------------------------------------------|
| `serverUrl` | string | `http://localhost:9000` | URL of the Lyrion Music Server                  |
| `username`  | string | `""`                    | HTTP basic auth username (if enabled on server) |
| `password`  | string | `""`                    | HTTP basic auth password (if enabled on server) |

See [INSTALL.md](INSTALL.md) for setup instructions.
