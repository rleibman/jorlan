# Weather Skill — Installation

**Skill name:** `weather`  
**Config key:** `skill.weather`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## 1. Get an API key

1. Create a free account at <https://home.openweathermap.org/users/sign_up>
2. Navigate to **API keys** in your dashboard
3. Copy your default key or generate a new one
4. Free tier includes current weather, 5-day forecast, and alerts

---

## 2. Configure the skill

### Via web UI

Navigate to **Skills → Weather → Configure** and enter:

```json
{
  "apiKey": "your-openweathermap-api-key",
  "units": "metric",
  "defaultLocation": "New York"
}
```

### Via SQL

```sql
INSERT INTO server_settings (`key`, `value`)
VALUES ('skill.weather', '{"apiKey":"your-key","units":"metric","defaultLocation":"New York"}')
ON DUPLICATE KEY UPDATE `value` = VALUES(`value`);
```

The skill enables automatically once a valid API key is stored. Restart is not required.

---

## 3. Grant capabilities

Agents need the `weather.read` capability. Grant via **Admin → Agents → \<agent\> → Capabilities**.

---

## Troubleshooting

| Symptom              | Cause                                                             |
|----------------------|-------------------------------------------------------------------|
| Skill stays disabled | API key is empty or invalid                                       |
| "401 Unauthorized"   | Wrong API key                                                     |
| "404 Not Found"      | Location name not recognised — try `"lat,lon"` format             |
| New key doesn't work | OpenWeatherMap keys take up to 2 hours to activate after creation |
