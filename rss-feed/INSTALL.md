# RSS Feed Skill — Installation

The RSS Feed skill is built into Jorlan and requires no external API keys or separate installation.

---

## 1. Packaging

The skill has two components that must be packaged together when distributing outside the main Jorlan assembly:

### JVM runtime

```bash
# Build the JVM jar
sbt "rssFeedSkillJVM/package"
# Output: rss-feed/jvm/target/scala-3.x/jorlan-rss-feed-jvm_3-<version>.jar
```

This jar contains `RssFeedSkill`, `RssFeedParser`, and `RssFeedPlugin`. Include it on the Jorlan server classpath.

### Scala.js config UI

```bash
# Build the JS bundle (development)
sbt "rssFeedSkillJS/fastLinkJS"

# Build the JS bundle (production)
sbt "rssFeedSkillJS/fullLinkJS"
# Output: rss-feed/js/target/scala-3.x/jorlan-rss-feed-js-*.js
```

Copy the resulting `.js` file to the Jorlan static content directory as:

```
<staticContentDir>/skills/jorlan-rss-feed-skill.js
```

The server serves this file at `/skills/jorlan-rss-feed-skill.js`, which the Skills page loads when the user opens the RSS configuration panel.

---

## 2. Enable via admin UI

Since this skill is built in, it is registered automatically on server startup. No additional step is required to enable it:

1. Navigate to **Admin → Skills**
2. Locate **rss** in the skill list
3. Toggle it **Enabled** if it is disabled
4. Click **Configure** to view and edit the saved feed list

---

## 3. Grant capabilities

No capabilities are granted by default. To allow an agent to use RSS tools:

1. Navigate to **Admin → Agents → \<agent\> → Capabilities**
2. Grant `rss.read`

---

## 4. Optional: pre-configure saved feeds

Feed URLs can be added through the agent (`rss.save_feed` tool) or through the config UI. To pre-seed feeds via the settings API, store a JSON array under the `skill.rss` key in `server_settings`:

```json
["https://feeds.bbci.co.uk/news/rss.xml", "https://news.ycombinator.com/rss"]
```

---

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Feed returns parse error | Verify the URL serves valid RSS 2.0 or Atom 1.0 XML |
| "403 Forbidden" or "401 Unauthorized" | Some feeds require authentication; the skill supports public feeds only |
| Config UI shows blank | Ensure `jorlan-rss-feed-skill.js` is in the static content `skills/` directory |
| Agent cannot call `rss.*` tools | Confirm the agent has the `rss.read` capability |
