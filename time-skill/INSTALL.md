# Time Skill — Installation

**Skill name:** `time`  
**Config key:** `skill.time`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## Setup

The Time skill requires no external account. On first server start, Jorlan automatically seeds the config with the
server's system timezone.

To override the default timezone, navigate to **Skills → Time → Configure** in the web UI and set:

```json
{
  "defaultTimezone": "America/New_York"
}
```

Or insert directly:

```sql
INSERT INTO server_settings (`key`, `value`)
VALUES ('skill.time', '{"defaultTimezone":"America/New_York"}')
ON DUPLICATE KEY UPDATE `value` = VALUES(`value`);
```

## Capability grants required

Agents need the `time.read` capability to call any time tool. Grant via **Admin → Agents → \<agent\> → Capabilities**.

## IANA timezone names

Use names from the IANA Time Zone Database, e.g.:

- `UTC`, `America/New_York`, `America/Chicago`, `America/Los_Angeles`
- `Europe/London`, `Europe/Paris`, `Asia/Tokyo`, `Australia/Sydney`

Full list: <https://en.wikipedia.org/wiki/List_of_tz_database_time_zones>
