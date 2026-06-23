# Time Skill

Provides timezone-aware date and time operations using the Java `java.time` library — no external API required.

**Skill name:** `time`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Answers time and date questions, converts between timezones, adds durations to datetimes, and computes intervals.

## Tools

| Tool                | Description                                               |
|---------------------|-----------------------------------------------------------|
| `time.now`          | Return current date and time in a given IANA timezone     |
| `time.convert`      | Convert an ISO 8601 datetime from one timezone to another |
| `time.add_duration` | Add an ISO 8601 duration to a datetime                    |
| `time.diff`         | Calculate the duration between two datetimes              |

### `time.now`

**Input:** `{ "timezone": "<IANA tz>" }` (optional — defaults to server default)

**Example:** `{ "timezone": "America/New_York" }` →
`{ "time": "14:32:00", "date": "2026-06-23", "datetime": "2026-06-23T14:32:00-04:00", "timezone": "America/New_York" }`

### `time.convert`

**Input:** `{ "datetime": "<ISO 8601>", "from_timezone": "<IANA tz>", "to_timezone": "<IANA tz>" }`

### `time.add_duration`

**Input:** `{ "datetime": "<ISO 8601>", "duration": "<ISO 8601 duration>", "timezone": "<IANA tz>" }`

**Duration examples:** `PT2H30M` (2h 30min), `P1D` (1 day), `P1Y2M` (1 year 2 months)

### `time.diff`

**Input:** `{ "start": "<ISO 8601>", "end": "<ISO 8601>", "timezone": "<IANA tz>" }`

**Output:**
`{ "totalSeconds": <n>, "days": <n>, "hours": <n>, "minutes": <n>, "seconds": <n>, "summary": "<human-readable>" }`

---

## Capabilities required

| Capability  | Tools     |
|-------------|-----------|
| `time.read` | All tools |

---

## Example prompts

- "What time is it in Tokyo?"
- "What is 9 AM EST in London time?"
- "What time will it be in 2 hours and 30 minutes?"
- "How many days until December 25th?"
- "What day of the week was January 1, 2000?"

---

## Configuration

`configKey`: `skill.time`  
`configJsModule`: `jorlan-time`

The skill reads its default timezone from the `skill.time` key in `server_settings`. If not set, Jorlan seeds it
automatically from the server's system timezone on first start.

**Config JSON schema:**

```json
{
  "defaultTimezone": "UTC"
}
```

| Field             | Type   | Default | Description                                                       |
|-------------------|--------|---------|-------------------------------------------------------------------|
| `defaultTimezone` | string | `"UTC"` | IANA timezone name used when agents omit the `timezone` parameter |

The config is editable via **Skills → Time → Configure** in the web UI.
