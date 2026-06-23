/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.search

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import just.semver.SemVer
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

/** Built-in skill for web search via the Tavily API.
  *
  * Exposes three tools:
  *   - `search.web` — general web search
  *   - `search.news` — recent news search
  *   - `search.extract` — full text extraction from URLs
  *
  * All tools require the `search.read` capability. The skill is optional — it is only registered when a `skill.search`
  * entry is present in `server_settings`.
  */
class SearchSkill(
  config: SearchConfig,
  client: Client,
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "search",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "search",
      "web",
      "internet",
      "find",
      "lookup",
      "browse",
      "query",
      "information",
      "news",
      "research",
      "results",
      "articles",
      "links",
      "Tavily",
      "web search",
      "online",
      "current",
      "today",
      "latest",
      "recent",
      "real-time",
      "now",
      "live",
      "up-to-date",
      "price",
      "closing",
      "market",
      "stock",
      "index",
    ),
    configKey = Some("skill.search"),
    configJsModule = Some("jorlan-search"),
    tools = List(
      ToolDescriptor(
        name = "search.web",
        description = "Search the web for up-to-date information. Returns a list of relevant results with title, URL, content snippet, and relevance score.",
        inputSchema = json"""{"type":"object","properties":{"query":{"type":"string","description":"The search query"},"maxResults":{"type":"integer","description":"Maximum number of results (default: 5)"},"searchDepth":{"type":"string","description":"Search depth: basic (default) or advanced (uses more credits)"}},"required":["query"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("search.read")),
        examplePrompts = List(
          "Search the web for recent news about ZIO",
          "Find information about Scala 3 features",
          "What are the latest developments in AI?",
          "What did the S&P 500 close at today?",
          "What is the current price of gold?",
          "What happened in the news today?",
        ),
      ),
      ToolDescriptor(
        name = "search.news",
        description = "Search for recent news articles on a topic. Returns a list of news results with title, URL, content snippet, and relevance score.",
        inputSchema = json"""{"type":"object","properties":{"query":{"type":"string","description":"The news search query"},"maxResults":{"type":"integer","description":"Maximum number of results (default: 5)"},"days":{"type":"integer","description":"Limit results to the past N days"}},"required":["query"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("search.read")),
        examplePrompts = List(
          "What are the latest news about OpenAI?",
          "Find recent articles about climate change",
          "Show me tech news from the past week",
        ),
      ),
      ToolDescriptor(
        name = "search.extract",
        description = "Extract the full text content from one or more URLs. Returns a list of URL/content pairs.",
        inputSchema = json"""{"type":"object","properties":{"urls":{"type":"array","items":{"type":"string"},"description":"List of URLs to extract content from"}},"required":["urls"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("search.read")),
        examplePrompts = List(
          "Extract the content from https://example.com",
          "Read the full text of these articles: [url1, url2]",
          "Get the content from this blog post",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "search.web"     => webSearch(args)
      case "search.news"    => newsSearch(args)
      case "search.extract" => extract(args)
      case other            => ZIO.fail(ValidationError(s"SearchSkill: unknown tool '$other'"))
    }

  private def field(
    args: Json,
    name: String,
  ): IO[JorlanError, String] =
    args match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (`name`, Json.Str(v)) => v }
          .fold(ZIO.fail(ValidationError(s"missing required field '$name'")): IO[JorlanError, String])(ZIO.succeed(_))
      case _ => ZIO.fail(ValidationError("args must be a JSON object"))
    }

  def postJson(
    path: String,
    body: Json,
  ): IO[JorlanError, Json] =
    client
      .batched(
        Request
          .post(s"${config.baseUrl}$path", Body.fromString(body.toString))
          .addHeader(Header.ContentType(MediaType.application.json))
          .addHeader(Header.Accept(MediaType.application.json)),
      )
      .mapError(e => JorlanError(s"Search HTTP request failed: ${e.getMessage}"))
      .flatMap { resp =>
        resp.body.asString
          .mapError(e => JorlanError(s"Failed to read search response: ${e.getMessage}"))
          .flatMap { respBody =>
            if (!resp.status.isSuccess)
              ZIO.fail(JorlanError(s"Tavily API HTTP ${resp.status.code}: $respBody"))
            else
              ZIO
                .fromEither(Json.decoder.decodeJson(respBody))
                .mapError(e => JorlanError(s"Tavily API JSON parse error: $e"))
          }
      }

  private def webSearch(args: Json): IO[JorlanError, Json] =
    for {
      query <- field(args, "query")
      maxResults = optInt(args, "maxResults").getOrElse(config.maxResults)
      searchDepth = optStr(args, "searchDepth").getOrElse("basic")
      requestBody = Json.Obj(
        "api_key"      -> Json.Str(config.apiKey),
        "query"        -> Json.Str(query),
        "search_depth" -> Json.Str(searchDepth),
        "max_results"  -> Json.Num(maxResults),
        "topic"        -> Json.Str("general"),
      )
      raw <- postJson("/search", requestBody)
      results = extractSearchResults(raw)
    } yield results

  private def newsSearch(args: Json): IO[JorlanError, Json] =
    for {
      query <- field(args, "query")
      maxResults = optInt(args, "maxResults").getOrElse(config.maxResults)
      days = optInt(args, "days")
      baseFields = List(
        "api_key"      -> Json.Str(config.apiKey),
        "query"        -> Json.Str(query),
        "search_depth" -> Json.Str("basic"),
        "max_results"  -> Json.Num(maxResults),
        "topic"        -> Json.Str("news"),
      )
      allFields = days.fold(baseFields)(d => baseFields :+ ("days" -> Json.Num(d)))
      requestBody = Json.Obj(allFields*)
      raw <- postJson("/search", requestBody)
      results = extractSearchResults(raw)
    } yield results

  private def extractSearchResults(raw: Json): Json =
    raw match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case ("results", Json.Arr(items)) => items }
          .map { items =>
            Json.Arr(
              items.collect { case Json.Obj(item) =>
                def sfield(key: String): String =
                  item
                    .collectFirst {
                      case (`key`, Json.Str(v))  => v
                      case (`key`, Json.Num(n))  => n.toString
                      case (`key`, Json.Bool(b)) => b.toString
                    }.getOrElse("")

                def nfield(key: String): Double =
                  item.collectFirst { case (`key`, Json.Num(n)) => n.doubleValue }.getOrElse(0.0)

                Json.Obj(
                  "title"   -> Json.Str(sfield("title")),
                  "url"     -> Json.Str(sfield("url")),
                  "content" -> Json.Str(sfield("content")),
                  "score"   -> Json.Num(nfield("score")),
                )
              }*,
            )
          }
          .getOrElse(Json.Arr())
      case _ => Json.Arr()
    }

  private def extract(args: Json): IO[JorlanError, Json] =
    for {
      urls = strList(args, "urls")
      requestBody = Json.Obj(
        "api_key" -> Json.Str(config.apiKey),
        "urls"    -> Json.Arr(urls.map(Json.Str(_))*),
      )
      raw <- postJson("/extract", requestBody)
      results = extractExtractResults(raw)
    } yield results

  private def extractExtractResults(raw: Json): Json =
    raw match {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case ("results", Json.Arr(items)) => items }
          .map { items =>
            Json.Arr(
              items.collect { case Json.Obj(item) =>
                def sfield(key: String): String =
                  item
                    .collectFirst {
                      case (`key`, Json.Str(v))  => v
                      case (`key`, Json.Num(n))  => n.toString
                      case (`key`, Json.Bool(b)) => b.toString
                    }.getOrElse("")

                Json.Obj(
                  "url"     -> Json.Str(sfield("url")),
                  "content" -> Json.Str(sfield("raw_content")),
                )
              }*,
            )
          }
          .getOrElse(Json.Arr())
      case _ => Json.Arr()
    }

}
