# RSS Feed Skill

Built-in skill for subscribing to and reading RSS and Atom news feeds.

**Skill name:** `rss`  
**Tier:** Built-in  
**GitHub:** <https://github.com/rleibman/jorlan>

---

## What it provides

| Tool               | Description                                                       | Capability  |
|--------------------|-------------------------------------------------------------------|-------------|
| `rss.fetch`        | Fetch an RSS or Atom feed URL and return the most recent entries  | `rss.read`  |
| `rss.list_saved`   | List all feed URLs persisted in settings                          | `rss.read`  |
| `rss.save_feed`    | Add a feed URL to the persisted list                              | `rss.read`  |
| `rss.remove_feed`  | Remove a feed URL from the persisted list                         | `rss.read`  |

Supports both **RSS 2.0** and **Atom 1.0** formats.

---

## Example prompts

- "Get the latest news from https://feeds.bbci.co.uk/news/rss.xml"
- "Fetch the Hacker News top stories feed"
- "What RSS feeds are saved?"
- "Add the ZIO blog feed to my tracked list"
- "Remove https://example.com/feed from my feeds"

---

## Capabilities

| Capability  | Required for                                                 |
|-------------|--------------------------------------------------------------|
| `rss.read`  | All tools — fetching, listing, saving, and removing feeds    |

Grant via **Admin → Agents → \<agent\> → Capabilities**.

---

## Module structure

This skill ships as two artifacts:

| Artifact | Purpose |
|----------|---------|
| `jorlan-rss-feed-jvm` | JVM runtime — feed fetcher and parser; included in the Jorlan server assembly |
| `jorlan-rss-feed-js`  | Scala.js config UI — served at `/skills/jorlan-rss-feed-skill.js` |

See [INSTALL.md](INSTALL.md) for packaging and setup instructions.
