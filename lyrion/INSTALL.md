# Lyrion Music Skill — Installation

**Skill name:** `lyrion`  
**Config key:** `skill.lyrion`  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## Prerequisites

A running [Lyrion Music Server](https://lyrion.org/) instance accessible from the Jorlan server host. Lyrion runs on
port 9000 by default.

---

## Configure the skill

### Via web UI

**Skills → Lyrion → Configure:**

```json
{
  "serverUrl": "http://192.168.1.10:9000",
  "username": "",
  "password": ""
}
```

### Via SQL

```sql
INSERT INTO server_settings (`key`, `value`)
VALUES ('skill.lyrion', '{"serverUrl":"http://192.168.1.10:9000","username":"","password":""}')
ON DUPLICATE KEY UPDATE `value` = VALUES(`value`);
```

The skill enables automatically once a reachable `serverUrl` is configured.

---

## Grant capabilities

Agents need the `lyrion.control` capability.

---

## Notes

- Jorlan communicates with Lyrion via the JSON-RPC API on the same port as the web UI
- If Lyrion requires authentication, set `username` and `password` in the config
- Player IDs are MAC addresses (e.g. `aa:bb:cc:dd:ee:ff`) or player names
- Omit `playerId` in tool calls to target the first available player
