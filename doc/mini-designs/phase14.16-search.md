# Phase 14.16 — Web Search Skill (Tavily)

## Overview

Add a `search` SBT module that exposes three tools for the Tavily web search API:
`search.web`, `search.news`, and `search.extract`. The skill is optional — it is
only registered when `skill.search` is present in `server_settings`.

## SBT Module

- Module name: `jorlan-search`
- Source directory: `search/`
- Dependencies: `modelJVM`, `connectorApi`, `zio`, `zio-json`, `zio-http`
- Added to `server.dependsOn(...)` list
- Added to `root.aggregate(...)` list

## Configuration

Stored in `server_settings` under key `"skill.search"` as JSON:

```json
{
  "apiKey": "tvly-...",
  "baseUrl": "https://api.tavily.com",
  "maxResults": 5
}
```

`baseUrl` is overridable for testing against a local embedded HTTP server.

## Capability

`search.read` — `ApprovalMode.Persistent` — added to `systemCapabilities` in `InitService`.

## Tools

### `search.web`
- Args: `{ query: String, maxResults?: Int, searchDepth?: String }`
- Calls `POST /search` with `topic: "general"`
- `searchDepth`: `"basic"` (default) or `"advanced"`
- Returns: JSON array of `{ title, url, content, score }`
- Requires: `search.read`

### `search.news`
- Args: `{ query: String, maxResults?: Int, days?: Int }`
- Calls `POST /search` with `topic: "news"`
- Optional `days` field limits recency
- Returns: same format as `search.web`
- Requires: `search.read`

### `search.extract`
- Args: `{ urls: List[String] }`
- Calls `POST /extract`
- Returns: JSON array of `{ url, content }`
- Requires: `search.read`

## HTTP Transport

Uses `zio-http` `Client.batched` with JSON POST bodies, matching the
`MarketDataSkill` pattern. No external library dependency for Tavily.

## Registration

In `Jorlan.scala`, inside `registerBuiltInSkills`, after the lyrion block:

```scala
_ <- repos.setting.get("skill.search").flatMap {
  case Some(json) =>
    json.as[SearchConfig] match {
      case Right(cfg) => registry.register(new SearchSkill(cfg, httpClient))
      case Left(err)  => ZIO.logWarning(s"Skipping search skill: invalid config: $err")
    }
  case None =>
    ZIO.logDebug("Search skill not configured (set skill.search in server_settings to enable)")
}
```

## No Flyway Migration Needed

The skill uses only the existing `server_settings` key-value store. No new tables.
