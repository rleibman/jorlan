# Web Search Skill

Provides real-time web search, news search, and URL content extraction using the [Tavily API](https://tavily.com/) — an
AI-optimised search engine.

**Skill name:** `search`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it does

Gives agents access to current information from the web, including news and the full text of web pages.

## Tools

| Tool             | Description                                            |
|------------------|--------------------------------------------------------|
| `search.web`     | Search the web and return ranked results with snippets |
| `search.news`    | Search for recent news articles                        |
| `search.extract` | Extract full text content from one or more URLs        |

### `search.web`

**Input:** `{ "query": "<search query>", "maxResults": 5, "searchDepth": "basic|advanced" }`

**Output:** list of `{ title, url, content, score }`

### `search.news`

**Input:** `{ "query": "<news topic>", "maxResults": 5 }`

**Output:** list of `{ title, url, content, score }`

### `search.extract`

**Input:** `{ "urls": ["<url1>", "<url2>"] }`

**Output:** list of `{ url, content }`

---

## Capabilities required

| Capability    | Tools     |
|---------------|-----------|
| `search.read` | All tools |

---

## Example prompts

- "Search the web for the latest news about AI"
- "What are the top results for 'best self-hosted AI tools 2026'?"
- "Find recent news about climate change"
- "Extract the content from https://example.com/article"
- "Look up the documentation for ZIO Effects"

---

## Configuration

`configKey`: `skill.search`  
`configJsModule`: `jorlan-search`

```json
{
  "apiKey": "",
  "baseUrl": "https://api.tavily.com",
  "maxResults": 5
}
```

| Field        | Type    | Default                  | Description                                 |
|--------------|---------|--------------------------|---------------------------------------------|
| `apiKey`     | string  | `""`                     | **Required.** Tavily API key                |
| `baseUrl`    | string  | `https://api.tavily.com` | API base URL (do not change)                |
| `maxResults` | integer | `5`                      | Default maximum number of results per query |

See [INSTALL.md](INSTALL.md) for setup instructions.
