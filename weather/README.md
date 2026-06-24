# Weather Skill

Fetches current conditions, multi-day forecasts, and severe weather alerts from
the [OpenWeatherMap API](https://openweathermap.org/api).

**Skill name:** `weather`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Answers weather questions for any location worldwide using real-time OpenWeatherMap data.

## Tools

| Tool               | Description                                    |
|--------------------|------------------------------------------------|
| `weather.current`  | Current conditions for a location              |
| `weather.forecast` | Multi-day forecast for a location              |
| `weather.alerts`   | Active weather alerts for a lat/lon coordinate |

### `weather.current`

**Input:** `{ "location": "<city name or 'lat,lon'>", "units": "metric|imperial|standard" }`

**Output:** temperature, feels_like, humidity, description, wind_speed, visibility

### `weather.forecast`

**Input:** `{ "location": "<city name or 'lat,lon'>", "days": <1-5>, "units": "metric|imperial|standard" }`

**Output:** list of `{ date, temp_min, temp_max, description }`

### `weather.alerts`

**Input:** `{ "lat": <number>, "lon": <number> }`

**Output:** list of `{ event, start, end, description }`

---

## Capabilities required

| Capability     | Tools     |
|----------------|-----------|
| `weather.read` | All tools |

---

## Example prompts

- "What's the weather like in Paris?"
- "Will it rain in New York this week?"
- "What is the forecast for London for the next 3 days?"
- "Are there any weather alerts near 40.71,-74.01?"
- "What's the temperature in Tokyo right now in Fahrenheit?"

---

## Configuration

`configKey`: `skill.weather`  
`configJsModule`: `jorlan-weather`

**Config JSON schema:**

```json
{
  "apiKey": "",
  "baseUrl": "https://api.openweathermap.org/data/2.5",
  "units": "metric",
  "defaultLocation": "New York"
}
```

| Field             | Type   | Default                                   | Description                                              |
|-------------------|--------|-------------------------------------------|----------------------------------------------------------|
| `apiKey`          | string | `""`                                      | **Required.** OpenWeatherMap API key                     |
| `baseUrl`         | string | `https://api.openweathermap.org/data/2.5` | API base URL (do not change)                             |
| `units`           | string | `"metric"`                                | Default unit system: `metric`, `imperial`, or `standard` |
| `defaultLocation` | string | `"New York"`                              | Fallback location when agents don't specify one          |

See [INSTALL.md](INSTALL.md) for setup instructions.
