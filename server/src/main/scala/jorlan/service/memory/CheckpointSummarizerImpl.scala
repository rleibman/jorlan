/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.memory

import jorlan.*
import jorlan.domain.*
import jorlan.service.{CheckpointSummarizer, ModelGateway}
import zio.*
import zio.json.ast.Json

/** [[CheckpointSummarizer]] backed by [[ModelGateway]].
  *
  * Sends the conversation history to the LLM with a system prompt that instructs it to extract a bullet-point list of
  * facts, preferences, and decisions. Each bullet becomes one [[MemoryRecord]] with
  * `recordKey = "episodic.checkpoint"`.
  *
  * The scope and `userId`/`agentId` are supplied by the caller; the classifier runs afterward to potentially override
  * the scope.
  */
class CheckpointSummarizerImpl(modelGateway: ModelGateway) extends CheckpointSummarizer {

  private val systemPrompt =
    """You are a memory summarizer for an AI assistant system.
      |Given a conversation, extract a concise bullet-point list of:
      |- User preferences and settings mentioned
      |- Facts the user stated about themselves or their project
      |- Decisions made during the conversation
      |- Any persistent context that would be useful in future sessions
      |
      |Format: one fact per line, starting with "- ".
      |Be concise. Omit greetings, pleasantries, and transient content.
      |Return only the bullet list, nothing else.""".stripMargin

  override def summarize(
    messages: List[Message],
    userId:   UserId,
    agentId:  AgentId,
  ): IO[JorlanError, List[MemoryRecord]] = {
    if (messages.isEmpty) ZIO.succeed(Nil)
    else {
      val transcript = messages.map(m => s"${m.role}: ${m.content}").mkString("\n")
      val tempSessionId = AgentSessionId(-1L)
      modelGateway
        .streamedResponse(tempSessionId, transcript, systemPrompt)
        .runFold(StringBuilder())(
          (
            sb,
            chunk,
          ) => sb.append(chunk),
        )
        .mapError(e => JorlanError(s"Checkpoint summarization failed: ${e.msg}", Some(e)))
        .ensuring(modelGateway.invalidateSession(tempSessionId).ignore)
        .flatMap { sb =>
          val summary = sb.toString
          if (summary.isBlank) ZIO.succeed(Nil)
          else {
            Clock.instant.map { now =>
              summary.linesIterator
                .map(_.trim)
                .filter(l => l.startsWith("- ") && l.length > 2)
                .map { bullet =>
                  val content = bullet.stripPrefix("- ")
                  MemoryRecord(
                    id = MemoryRecordId.empty,
                    scope = MemoryScope.User,
                    userId = Some(userId),
                    workspaceId = None,
                    agentId = Option.when(agentId != AgentId.empty)(agentId),
                    recordKey = "episodic.checkpoint",
                    value = Json.Obj("text" -> Json.Str(content)),
                    ttl = None,
                    createdAt = now,
                    updatedAt = now,
                  )
                }.toList
            }
          }
        }
    }
  }

}
