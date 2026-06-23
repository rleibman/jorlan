# HTTP Fetch Skill — Installation

**Skill name:** `http_fetch`  
**Config key:** `skill.httpFetch`  
**GitHub:** <https://github.com/rleibman/jorlan>

No external account is required. The skill is disabled until configured with at least one allowed host.

---

## Configure the skill

### Via web UI

**Skills → HTTP Fetch → Configure:**

```json
{
  "allowedHosts": [
    "api.github.com",
    "*.example.com"
  ],
  "maxResponseBytes": 524288,
  "timeoutSeconds": 30
}
```

### Via SQL

```sql
INSERT INTO server_settings (`key`, `value`)
VALUES ('skill.httpFetch', '{"allowedHosts":["api.github.com"],"maxResponseBytes":524288,"timeoutSeconds":30}')
ON DUPLICATE KEY UPDATE `value` = VALUES(`value`);
```

The skill enables automatically. No restart required.

---

## Grant capabilities

Agents need the `http_fetch.call` capability.

---

## Allowed host patterns

| Pattern           | Matches                      |
|-------------------|------------------------------|
| `api.example.com` | Exact hostname only          |
| `*.example.com`   | Any subdomain of example.com |
| `*`               | All hosts (use with caution) |

An empty `allowedHosts` list denies all requests, effectively disabling the tool even when the skill is enabled.
