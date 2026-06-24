# Market Data Skill — Installation

**Skill name:** `market`  
**Config key:** `skill.market`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## 1. Get an API key

1. Register for a free key at <https://www.alphavantage.co/support/#api-key>
2. Free tier: 25 requests/day, real-time quotes, search, news

---

## 2. Configure the skill

### Via web UI

**Skills → Market Data → Configure:**

```json
{
  "apiKey": "your-alpha-vantage-api-key"
}
```

### Via SQL

```sql
INSERT INTO server_settings (`key`, `value`)
VALUES ('skill.market', '{"apiKey":"your-key"}')
ON DUPLICATE KEY UPDATE `value` = VALUES(`value`);
```

The skill enables automatically. No restart required.

---

## 3. Grant capabilities

Agents need the `market.read` capability.

---

## Rate limits (free tier)

- 25 API calls / day
- Real-time data (15-min delay for some exchanges)

Upgrade at <https://www.alphavantage.co/premium/>
