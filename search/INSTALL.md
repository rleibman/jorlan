# Web Search Skill — Installation

**Skill name:** `search`  
**Config key:** `skill.search`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## 1. Get an API key

1. Sign up at <https://tavily.com>
2. Copy your API key from the dashboard
3. Free tier: 1,000 searches/month

---

## 2. Configure the skill

### Via web UI

**Skills → Search → Configure:**

```json
{
  "apiKey": "tvly-your-tavily-api-key",
  "maxResults": 5
}
```

### Via SQL

```sql
INSERT INTO server_settings (`key`, `value`)
VALUES ('skill.search', '{"apiKey":"tvly-your-key","maxResults":5}')
ON DUPLICATE KEY UPDATE `value` = VALUES(`value`);
```

The skill enables automatically. No restart required.

---

## 3. Grant capabilities

Agents need the `search.read` capability.

---

## Notes

- `searchDepth: "advanced"` uses more API credits but returns better results
- `search.extract` fetches full page content; large pages may be truncated
