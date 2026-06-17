# Phase 14.7 — Weather Skill

## Overview

Add an optional `weather` skill backed by the OpenWeatherMap REST API. The skill is registered at
startup only when a `skill.weather` JSON blob is present in the `server_settings` table, following
the same pattern as `skill.market` and `skill.lyrion`.

---

## API Endpoints Used

All calls are made to `https://api.openweathermap.org/data/2.5` (configurable for tests).

| Tool              | Endpoint                  | Notes                                          |
|-------------------|---------------------------|------------------------------------------------|
| `weather.current` | `GET /weather`            | `?q={location}&appid={apiKey}&units={units}`   |
| `weather.forecast`| `GET /forecast`           | `?q={location}&appid={apiKey}&units={units}&cnt={days*8}` |
| `weather.alerts`  | `GET /onecall`            | `?lat={lat}&lon={lon}&exclude=current,minutely,hourly,daily&appid={apiKey}` |

---

## Config Structure

Stored in `server_settings` under the key `"skill.weather"` as a JSON object.

```json
{
  "apiKey": "your-openweathermap-api-key",
  "baseUrl": "https://api.openweathermap.org/data/2.5",
  "units": "metric"
}
```

- `apiKey` — required; obtained from openweathermap.org
- `baseUrl` — default `https://api.openweathermap.org/data/2.5`; overridable for tests
- `units` — `"metric"` (°C), `"imperial"` (°F), or `"standard"` (K); default `"metric"`

No Flyway migration is needed — config lives in the existing `server_settings` table.

---

## Tools

### `weather.current`

Fetches current weather conditions for a named location.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "location": { "type": "string", "description": "City name, e.g. 'London' or 'New York,US'" },
    "units":    { "type": "string", "description": "Override units: metric | imperial | standard" }
  },
  "required": ["location"]
}
```

**Returns:** `{ temperature, feels_like, humidity, description, wind_speed, visibility }`

---

### `weather.forecast`

Returns a simplified 5-day / 3-hour forecast for a named location.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "location": { "type": "string" },
    "days":     { "type": "integer", "description": "Number of days (1–5); default 5" },
    "units":    { "type": "string" }
  },
  "required": ["location"]
}
```

**Returns:** Array of `{ date, temp_min, temp_max, description }` (one entry per forecast slot).

---

### `weather.alerts`

Returns active weather alerts for a geographic coordinate.

**Input schema:**
```json
{
  "type": "object",
  "properties": {
    "lat": { "type": "number", "description": "Latitude" },
    "lon": { "type": "number", "description": "Longitude" }
  },
  "required": ["lat", "lon"]
}
```

**Returns:** Array of `{ event, start, end, description }`, or an empty array when no alerts exist.

---

## Capability

| Capability    | Approval Mode |
|---------------|---------------|
| `weather.read`| Persistent    |

All three tools require `weather.read`. The capability is seeded for admin users via
`InitService.systemCapabilities`.

---

## SBT Module

New module: `weather` in `weather/`.

```
weather/
  src/main/scala/jorlan/weather/WeatherSkill.scala
  src/test/scala/jorlan/weather/WeatherSkillSpec.scala
```

Dependencies: `model`, `connector-api`, `zio-http`, `zio-json`.
Added to `server`'s `dependsOn` and to the root `aggregate`.

---

## Registration (Jorlan.scala)

```scala
_ <- repos.setting.get("skill.weather").flatMap {
  case Some(json) =>
    json.as[WeatherConfig] match {
      case Right(cfg) => registry.register(new WeatherSkill(cfg, httpClient, cfg.baseUrl))
      case Left(err)  => ZIO.logWarning(s"Skipping weather skill: invalid config JSON: $err")
    }
  case None =>
    ZIO.logDebug("Weather skill not configured (set skill.weather in server_settings to enable)")
}
```
