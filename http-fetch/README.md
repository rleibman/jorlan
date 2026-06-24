# HTTP Fetch Skill

Capability-gated HTTP GET and POST requests to external URLs. An allowlist controls which hosts agents are permitted to
reach.

**Skill name:** `http_fetch`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Allows agents to fetch content from the web or call REST APIs, subject to an administrator-configured host allowlist.

## Tools

| Tool              | Description                                                        |
|-------------------|--------------------------------------------------------------------|
| `http_fetch.get`  | HTTP GET a URL; returns status, body, content-type                 |
| `http_fetch.post` | HTTP POST to a URL with a body; returns status, body, content-type |

### `http_fetch.get`

**Input:**

```json
{
  "url": "https://api.example.com/data",
  "headers": {
    "Accept": "application/json"
  }
}
```

**Output:** `{ "status": 200, "body": "...", "contentType": "application/json" }`

### `http_fetch.post`

**Input:**

```json
{
  "url": "https://api.example.com/submit",
  "body": "{\"key\":\"value\"}",
  "contentType": "application/json",
  "headers": {}
}
```

---

## Capabilities required

| Capability        | Tools     |
|-------------------|-----------|
| `http_fetch.call` | All tools |

---

## Security

All requests are checked against the `allowedHosts` list before dispatch. An empty list **denies all** requests. Use
`["*"]` to allow all hosts (not recommended for production).

---

## Example prompts

- "Fetch the content of https://example.com"
- "Call the GitHub API to get my profile"
- "POST this JSON to https://hooks.example.com/notify"
- "Download the RSS feed from https://news.example.com/feed.xml"

---

## Configuration

`configKey`: `skill.httpFetch`  
`configJsModule`: `jorlan-http-fetch`

```json
{
  "allowedHosts": [
    "api.example.com",
    "*.githubusercontent.com"
  ],
  "maxResponseBytes": 524288,
  "timeoutSeconds": 30
}
```

| Field              | Type     | Default  | Description                                                                                  |
|--------------------|----------|----------|----------------------------------------------------------------------------------------------|
| `allowedHosts`     | string[] | `[]`     | Allowed host patterns. Glob `*.example.com` supported. `["*"]` = allow all. Empty = deny all |
| `maxResponseBytes` | integer  | `524288` | Max response body size (bytes). Default: 512 KB                                              |
| `timeoutSeconds`   | integer  | `30`     | Request timeout in seconds                                                                   |

See [INSTALL.md](INSTALL.md) for setup instructions.
