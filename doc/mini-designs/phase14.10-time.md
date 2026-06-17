# Phase 14.10 — Time / Timezone Skill

## Goal

Add a `time` built-in skill that gives agents the ability to query the current time, convert between
timezones, add durations to a datetime, and compute the difference between two datetimes. The skill
has zero external dependencies — it uses only `java.time` from the JDK — and registers
unconditionally (no API key, no `server_settings` entry required).

---

## SBT Module

- **Lazy val name**: `timeSkill`
- **Base directory**: `file("time-skill")`
- **Artifact name**: `jorlan-time-skill`
- **Dependencies**: `modelJVM`, `connectorApi`, `zio`, `zio-json` (no `zio-http` — no HTTP needed)
- **Added to**: `server.dependsOn(...)` and root `aggregate(...)`

Source tree: `time-skill/src/main/scala/jorlan/time/`
Test tree:   `time-skill/src/test/scala/jorlan/time/`

---

## Capability

| Capability    | ApprovalMode |
|---------------|--------------|
| `time.read`   | Persistent   |

All four tools require `time.read`. The capability is derived automatically from the `SkillRegistry`
and seeded to admin users via `InitService.topUpAdminCapabilities` — no manual addition to
`systemCapabilities` is needed. (The registry handles it.)

---

## Tools

### `time.now`

Returns the current date and time in the requested timezone.

**Input**

```json
{ "timezone": "America/New_York" }
```

`timezone` is optional; defaults to `"UTC"`. Must be a valid IANA timezone name.

**Output**

```json
{
  "datetime":  "2026-06-16T14:30:00-05:00",
  "timezone":  "America/New_York",
  "utcOffset": "-05:00",
  "dayOfWeek": "Monday",
  "timestamp": 1750089000
}
```

---

### `time.convert`

Converts an ISO 8601 datetime string from one timezone to another.

**Input**

```json
{
  "datetime":     "2026-06-16T14:30:00",
  "fromTimezone": "America/New_York",
  "toTimezone":   "Asia/Tokyo"
}
```

**Output**

```json
{
  "original":     "2026-06-16T14:30:00-05:00",
  "converted":    "2026-06-17T04:30:00+09:00",
  "fromTimezone": "America/New_York",
  "toTimezone":   "Asia/Tokyo"
}
```

---

### `time.add_duration`

Adds an ISO 8601 duration (e.g. `"PT2H30M"`, `"P1D"`) to a datetime.

**Input**

```json
{
  "datetime": "2026-06-16T14:30:00",
  "timezone": "UTC",
  "duration": "PT2H30M"
}
```

**Output**

```json
{
  "original": "2026-06-16T14:30:00Z",
  "result":   "2026-06-16T17:00:00Z",
  "timezone": "UTC",
  "duration": "PT2H30M"
}
```

---

### `time.diff`

Computes the duration between two ISO 8601 datetimes.

**Input**

```json
{
  "from":         "2026-06-16T12:00:00",
  "to":           "2026-06-16T13:30:00",
  "fromTimezone": "UTC",
  "toTimezone":   "UTC"
}
```

`fromTimezone` and `toTimezone` are optional (default `"UTC"`).

**Output**

```json
{
  "from":          "2026-06-16T12:00:00Z",
  "to":            "2026-06-16T13:30:00Z",
  "totalSeconds":  5400,
  "days":          0,
  "hours":         1,
  "minutes":       30,
  "seconds":       0,
  "humanReadable": "1 hour 30 minutes"
}
```

---

## Implementation Notes

- Use `ZoneId.of(tz)` wrapped in `ZIO.attempt(...).mapError(...)` to validate timezone names at
  runtime; invalid zone names become `JorlanError`.
- Parse datetime strings with `ZonedDateTime.parse` first; fall back to `LocalDateTime.parse` then
  attach the zone with `atZone`.
- Use `DateTimeFormatter.ISO_OFFSET_DATE_TIME` for all output serialization.
- Use `Clock.instant` (ZIO) for `time.now`; this is test-injectable via `TestClock`.
- Duration parsing uses `java.time.Duration.parse` (time-based) and `java.time.Period.parse`
  (date-based); try `Duration.parse` first, then `Period.parse` for `P…` durations without a `T`
  component.
- `time.diff` uses `java.time.Duration.between` for total seconds, then decomposes to
  days/hours/minutes/seconds.
- `import scala.language.unsafeNulls` at the top of `TimeSkill.scala` because `java.time` methods
  return `String` (non-nullable in Java but typed as `String | Null` under `-Yexplicit-nulls`).

---

## Registration

`TimeSkill` registers unconditionally in `Jorlan.scala` next to `UnitConversionSkill`:

```scala
_ <- registry.register(new UnitConversionSkill())
_ <- registry.register(new TimeSkill())
```

No config lookup, no HTTP client, no `server_settings` key needed.

---

## Tests

Minimum 11 tests in `TimeSkillSpec`:

1. `time.now` with no timezone returns UTC datetime fields
2. `time.now` with `"America/New_York"` returns correct `-05:00` or `-04:00` offset
3. `time.now` with invalid timezone fails with `JorlanError`
4. `time.convert` UTC → Tokyo correct (+9h offset)
5. `time.convert` invalid `fromTimezone` fails
6. `time.convert` invalid datetime string fails
7. `time.add_duration` adds `PT2H30M` correctly
8. `time.add_duration` adds `P1D` (one day) correctly
9. `time.diff` returns correct `totalSeconds`, `hours`, `humanReadable`
10. `time.diff` where `to` is before `from` returns negative `totalSeconds`
11. Missing required field (e.g. `datetime` for `time.convert`) fails with `JorlanError`
