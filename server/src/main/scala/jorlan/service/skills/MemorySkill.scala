/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import ai.{EmbeddingModel, EmbeddingStore}
import dev.langchain4j.store.embedding.{EmbeddingSearchRequest, filter as lc4jFilter}
import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.*
import jorlan.service.MemoryService
import jorlan.service.skills.{MemorySkill, SkillRegistry}
import just.semver.SemVer
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

import scala.jdk.CollectionConverters.*

/** Tier 0 memory skill — explicit agent-directed memory operations.
  *
  * These bypass [[CheckpointSummarizer]] and write/read directly via [[MemoryService]]. Intended to be invoked from the
  * GraphQL API (user shell commands) and from the ReAct tool-calling loop via [[SkillRegistry]].
  */
class MemorySkill(
  memoryService:  MemoryService,
  embeddingStore: EmbeddingStore,
  embeddingModel: EmbeddingModel,
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = MemorySkill.skillName,
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "memory",
      "remember",
      "recall",
      "store",
      "fact",
      "knowledge",
      "note",
      "record",
      "forget",
      "retrieve",
      "context",
      "history",
      "save",
      "persist",
    ),
    tools = List(
      ToolDescriptor(
        name = "memory.remember",
        description = "Store a named fact or piece of information into agent memory for later recall.",
        inputSchema = json"""{"type":"object","properties":{"key":{"type":"string","description":"Short unique name for this memory"},"text":{"type":"string","description":"The content to remember"},"scope":{"type":"string","enum":["private","user"],"description":"Scope: private (current session only) or user (persists across sessions)"}},"required":["key","text"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("memory.write")),
        examplePrompts = List(
          "Remember that my preferred language is Spanish",
          "Store the fact that my birthday is March 15",
          "Don't forget that I prefer dark mode in all apps",
        ),
      ),
      ToolDescriptor(
        name = "memory.search",
        description = "Search stored memories for facts matching the given text.",
        inputSchema = json"""{"type":"object","properties":{"text":{"type":"string","description":"Search query"},"scope":{"type":"string","enum":["private","user"],"description":"Scope to search in"}},"required":["text"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("memory.read")),
        examplePrompts = List(
          "What do you remember about my preferences?",
          "Do you know anything about my birthday?",
          "Look up what I told you about my project deadlines",
        ),
      ),
      ToolDescriptor(
        name = "memory.forget",
        description = "Delete a specific memory record by its ID.",
        inputSchema = json"""{"type":"object","properties":{"id":{"type":"string","description":"ID of the memory record to delete"}},"required":["id"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("boolean")),
        requiredCapabilities = List(CapabilityName("memory.write")),
        examplePrompts = List(
          "Forget what you remember about my old address",
          "Delete memory record 42",
          "Remove that note about my old phone number",
        ),
      ),
      ToolDescriptor(
        name = "memory.mark_shared",
        description = "Promote a memory record to shared scope so other agents can read it.",
        inputSchema = json"""{"type":"object","properties":{"id":{"type":"string","description":"ID of the memory record"}},"required":["id"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("memory.write")),
        examplePrompts = List(
          "Share memory 42 with all agents",
          "Make that team standup note visible to other agents",
        ),
      ),
      ToolDescriptor(
        name = "memory.mark_private",
        description = "Demote a memory record back to private scope.",
        inputSchema = json"""{"type":"object","properties":{"id":{"type":"string","description":"ID of the memory record"}},"required":["id"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("memory.write")),
        examplePrompts = List(
          "Make memory 42 private again",
          "That shared note about the API key should be private",
        ),
      ),
      ToolDescriptor(
        name = "memory.search_semantic",
        description = "Search memories by meaning rather than exact keywords. Use when you need to find memories about a topic without knowing the exact wording stored.",
        inputSchema = json"""{"type":"object","properties":{"query":{"type":"string","description":"Natural language description of what you are looking for"},"limit":{"type":"integer","description":"Maximum number of results to return (default 5)"}},"required":["query"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("memory.read")),
        examplePrompts = List(
          "Do you know anything about my food preferences?",
          "What did I tell you about the project timeline?",
          "Find anything related to my work schedule",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] = {
    def field(name: String): IO[JorlanError, String] =
      args match {
        case Json.Obj(fields) =>
          fields
            .collectFirst { case (`name`, Json.Str(v)) => v }
            .fold(ZIO.fail(ValidationError(s"missing field '$name'")): IO[JorlanError, String])(ZIO.succeed(_))
        case _ => ZIO.fail(ValidationError("args must be a JSON object"))
      }

    def scopeFor(raw: Option[String]): MemoryScope =
      raw match {
        case Some("user")   => MemoryScope.User
        case Some("shared") => MemoryScope.Shared
        case _              => MemoryScope.Private
      }

    def optField(name: String): Option[String] =
      args match {
        case Json.Obj(fields) => fields.collectFirst { case (`name`, Json.Str(v)) => v }
        case _                => None
      }

    tool match {
      case "memory.remember" =>
        for {
          key  <- field("key")
          text <- field("text")
          scope = scopeFor(optField("scope"))
          agentId = ctx.agentId.getOrElse(AgentId.empty)
          rec <- remember(key, text, scope, ctx.actorId, agentId)
        } yield Json.Obj(
          "id"    -> Json.Str(rec.id.value.toString),
          "key"   -> Json.Str(rec.recordKey),
          "scope" -> Json.Str(rec.scope.toString),
        )

      case "memory.search" =>
        for {
          text <- field("text")
          scope = scopeFor(optField("scope"))
          agentId = ctx.agentId.getOrElse(AgentId.empty)
          recs <- search(text, scope, ctx.actorId, agentId)
        } yield Json.Arr(recs.map { r =>
          Json.Obj(
            "id"    -> Json.Str(r.id.value.toString),
            "key"   -> Json.Str(r.recordKey),
            "value" -> r.value,
          )
        }*)

      case "memory.forget" =>
        for {
          idStr <- field("id")
          id    <- ZIO
            .fromOption(idStr.toLongOption.map(MemoryRecordId(_)))
            .orElseFail(ValidationError(s"invalid memory id: $idStr"))
          ok <- forget(id, ctx.actorId)
        } yield Json.Bool(ok)

      case "memory.mark_shared" =>
        for {
          idStr <- field("id")
          id    <- ZIO
            .fromOption(idStr.toLongOption.map(MemoryRecordId(_)))
            .orElseFail(ValidationError(s"invalid memory id: $idStr"))
          rec <- markShared(id, ctx.actorId)
        } yield Json.Obj("id" -> Json.Str(rec.id.value.toString), "scope" -> Json.Str(rec.scope.toString))

      case "memory.mark_private" =>
        for {
          idStr <- field("id")
          id    <- ZIO
            .fromOption(idStr.toLongOption.map(MemoryRecordId(_)))
            .orElseFail(ValidationError(s"invalid memory id: $idStr"))
          rec <- markPrivate(id, ctx.actorId)
        } yield Json.Obj("id" -> Json.Str(rec.id.value.toString), "scope" -> Json.Str(rec.scope.toString))

      case "memory.search_semantic" =>
        for {
          query <- field("query")
          limit = optField("limit").flatMap(_.toIntOption).getOrElse(5)
          results <- searchSemantic(query, ctx.actorId, limit)
        } yield Json.Arr(results.map(Json.Str(_))*)

      case other =>
        ZIO.fail(ValidationError(s"unknown tool '$other'"))
    }
  }

  /** Store a fact directly into memory with the caller-supplied scope.
    *
    * Unlike the checkpoint pipeline, the scope is **not** re-classified by [[MemoryClassifier]]; the caller is trusted
    * to supply the correct scope. This is intentional for explicit user-directed stores.
    */
  def remember(
    key:     String,
    text:    String,
    scope:   MemoryScope,
    userId:  UserId,
    agentId: AgentId,
  ): IO[JorlanError, MemoryRecord] =
    Clock.instant.flatMap { now =>
      val record = MemoryRecord(
        id = MemoryRecordId.empty,
        scope = scope,
        userId = Some(userId),
        workspaceId = None,
        agentId = Option.when(agentId != AgentId.empty)(agentId),
        recordKey = key,
        value = Json.Obj("text" -> Json.Str(text)),
        ttl = None,
        createdAt = now,
        updatedAt = now,
      )
      memoryService.store(record)
    }

  def search(
    text:    String,
    scope:   MemoryScope,
    userId:  UserId,
    agentId: AgentId,
  ): IO[JorlanError, List[MemoryRecord]] =
    memoryService.query(scope, userId, agentId, Some(text))

  def forget(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, Boolean] =
    memoryService.forget(id, requestingUserId)

  def markShared(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord] =
    memoryService.markShared(id, requestingUserId)

  def markPrivate(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord] =
    memoryService.markPrivate(id, requestingUserId)

  def searchSemantic(
    query:  String,
    userId: UserId,
    limit:  Int,
  ): IO[JorlanError, List[String]] = {
    import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder
    ZIO
      .attemptBlocking {
        val queryEmbedding = embeddingModel.embed(query).content()
        val filter = MetadataFilterBuilder.metadataKey("userId").isEqualTo(userId.value.toString)
        val request = EmbeddingSearchRequest
          .builder()
          .queryEmbedding(queryEmbedding)
          .maxResults(limit)
          .filter(filter)
          .build()
        embeddingStore.search(request).matches().asScala.toList.map(_.embedded().text())
      }.mapError(e => JorlanError(e.getMessage.nn))
  }

}

object MemorySkill {

  final val skillName = "memory"

}
