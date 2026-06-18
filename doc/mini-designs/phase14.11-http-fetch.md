# Phase 14.11 — HTTP Fetch Skill Mini-Design

## Goal

Add an `http_fetch` skill that lets agents make HTTP GET and POST requests to external URLs. All requests are
gated by a capability-checked host allowlist stored in `server_settings`.

## SBT Module

- **Lazy val name**: `httpFetchSkill`
- **Module name**: `"jorlan-http-fetch"`
- **Base directory**: `file("http-fetch")`
- **Dependencies**: `modelJVM`, `connectorApi`, `zio`, `zio-json`, `zio-http`
- Added to `server.dependsOn(...)` and the root `aggregate(...)` list

Sources in: `http-fetch/src/main/scala/jorlan/httpfetch/`

## Configuration

Config key in `server_settings`: `"skill.http_fetch"`

```json
{
  "allowedHosts": ["api.example.com", "*.openai.com"],
  "maxResponseBytes": 524288,
  "timeoutSeconds": 30
}
```

- `allowedHosts` — list of exact hostnames or glob patterns (`*.example.com`). Empty list = deny all.
  Use `["*"]` to allow all hosts.
- `maxResponseBytes` — maximum response body size in bytes (default 512 KB = 524288)
- `timeoutSeconds` — request timeout in seconds (default 30)

## Tools

### `http_fetch.get`

Input schema:
```json
{
  "type": "object",
  "properties": {
    "url":     { "type": "string", "description": "Full URL to fetch" },
    "headers": { "type": "object", "description": "Optional HTTP headers as key-value pairs" }
  },
  "required": ["url"]
}
```

- Validates the request URL host against the allowlist
- Adds any provided headers to the request
- Returns `{ "status": Int, "body": String, "contentType": String }`
- Truncates `body` at `maxResponseBytes` and appends `\n[truncated]` if exceeded
- Requires capability `http_fetch.call`

### `http_fetch.post`

Input schema:
```json
{
  "type": "object",
  "properties": {
    "url":         { "type": "string", "description": "Full URL to POST to" },
    "body":        { "type": "string", "description": "Request body" },
    "contentType": { "type": "string", "description": "Content-Type header (default: application/json)" },
    "headers":     { "type": "object", "description": "Optional extra HTTP headers as key-value pairs" }
  },
  "required": ["url"]
}
```

- Same allowlist check as GET
- Sends body with the specified `contentType` (default `application/json`)
- Returns the same `{ "status", "body", "contentType" }` response format
- Requires capability `http_fetch.call`

## Capability

- **Name**: `http_fetch.call`
- Added to both `systemCapabilities` and `perInvocationCapabilities` in `InitService`.
+
+Skill capabilities are derived automatically from `ToolDescriptor.requiredCapabilities` via `SkillRegistry` (so `http_fetch.call` does not need to be added to `systemCapabilities`). If `http_fetch.call` must require per-invocation approval, it must be added to `perInvocationCapabilities` in `InitService`.
## Host Allowlist Matching

```
isHostAllowed(url):
  extract host from url via java.net.URI
  for each pattern in config.allowedHosts:
    if pattern == "*"  → allow all
    if pattern starts with "*."  → match host ending with pattern.stripPrefix("*")
                                    OR exact match of pattern.stripPrefix("*.")
    else → exact match
  deny if no pattern matched
```

Examples:
- Pattern `"*.example.com"` matches `api.example.com`, `foo.example.com`, `example.com`
- Pattern `"api.example.com"` matches only `api.example.com`
- Pattern `"*"` matches everything
- Empty `allowedHosts` list → denies all

## Testability

`HttpFetchSkill` accepts a `urlTransform: String => String` constructor parameter (default `identity`).
Tests inject a function that replaces scheme+host with the embedded test server URL, so actual HTTP calls
go to `localhost` rather than real external hosts.

## Error Handling

| Scenario | Behaviour |
|---|---|
| Host not in allowlist | `ZIO.succeed(Json.Obj("error" -> Json.Str(...)))` — no request made |
| Missing required `url` arg | `ZIO.fail(ValidationError(...))` |
| HTTP 4xx/5xx | Return response as-is (`{ status, body, contentType }`) — agent decides |
| Network / IO error | `ZIO.fail(JorlanError(...))` |
| Unknown tool | `ZIO.fail(ValidationError(...))` |

## Wiring

In `Jorlan.scala`, after the Lyrion block in `registerBuiltInSkills`:

```scala
_ <- repos.setting.get("skill.http_fetch").flatMap {
  case Some(json) =>
    json.as[HttpFetchConfig] match {
      case Right(cfg) => registry.register(new HttpFetchSkill(cfg, httpClient))
      case Left(err)  => ZIO.logWarning(s"Skipping http_fetch skill: invalid config: $err")
    }
  case None =>
    ZIO.logDebug("HTTP fetch skill not configured (set skill.http_fetch in server_settings to enable)")
}
```
