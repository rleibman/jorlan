/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.memory

import jorlan.*
import jorlan.service.{CheckpointSummarizer, ModelGateway}
import zio.*
import zio.json.ast.Json

/** [[CheckpointSummarizer]] backed by [[ModelGateway]].
  *
  * Sends the conversation history to the LLM with a system prompt that instructs it to extract a bullet-point list of
  * typed facts. Each bullet type drives an [[importance]] score, and the content hash drives the `recordKey` so that
  * identical facts extracted in later checkpoints overwrite (rather than duplicate) earlier ones.
  *
  * Bullet format: `- [TYPE] content`, where TYPE is one of:
  *   - PREF — user preference → importance 8
  *   - FACT — stated fact about the user/project → importance 7
  *   - DECISION — decision made during the conversation → importance 6
  *   - CONTEXT — transient session context → importance 4
  *   - (untagged) → importance 5
  */
class CheckpointSummarizerImpl(modelGateway: ModelGateway) extends CheckpointSummarizer {

  private val systemPrompt =
    """You are a memory summarizer for an AI assistant system.
      |Given a conversation, extract a concise bullet-point list of facts about the USER ONLY.
      |
      |Tag each bullet with one of these types:
      |- [PREF]     User preferences and settings (language, timezone, communication style)
      |- [FACT]     Facts the user stated about themselves, their project, or their domain
      |- [DECISION] Decisions the USER made during the conversation
      |- [CONTEXT]  Short-lived session context (current task, immediate next steps)
      |
      |Format: one fact per line, starting with "- [TYPE] ".
      |Be concise. Omit greetings, pleasantries, and transient chit-chat.
      |Return only the bullet list, nothing else.
      |
      |STRICT RULES — violating these produces bad memory:
      |- DO NOT record what the assistant said, suggested, or recommended.
      |- DO NOT record phrases like "the assistant suggested", "Claude recommended", "I was told".
      |- DO NOT record current task state or next steps ("currently working on", "next step is").
      |- DO NOT record observations about the conversation itself.
      |- Only record facts, preferences, and decisions explicitly stated or made by the USER.
      |- If a bullet would violate these rules, omit it entirely.""".stripMargin

  private val typeImportance: Map[String, Int] = Map(
    "PREF"     -> 8,
    "FACT"     -> 7,
    "DECISION" -> 6,
    "CONTEXT"  -> 4,
  )

  private val tagPattern = """^\[([A-Z]+)\]\s+(.+)$""".r

  private def importanceFor(tag: String): Int = typeImportance.getOrElse(tag.toUpperCase, 5)

  private def contentKey(
    userId:  UserId,
    content: String,
  ): String = {
    val normalised = content.trim.toLowerCase.replaceAll("\\s+", " ")
    val hash = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(s"${userId.value}|$normalised".getBytes("UTF-8"))
      .take(8)
      .map("%02x".format(_))
      .mkString
    s"chk.$hash"
  }

  override def summarize(
    messages: List[Message],
    userId:   UserId,
    agentId:  AgentId,
  ): IO[JorlanError, List[MemoryRecord]] = {
    if (messages.isEmpty) ZIO.succeed(List.empty)
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
          if (summary.isBlank) ZIO.succeed(List.empty)
          else {
            Clock.instant.map { now =>
              summary.linesIterator
                .map(_.trim)
                .filter(l => l.startsWith("- ") && l.length > 2)
                .map { bullet =>
                  val stripped = bullet.stripPrefix("- ")
                  val (content, importance) = tagPattern.findFirstMatchIn(stripped) match {
                    case Some(m) =>
                      val tag = Option(m.group(1)).getOrElse("")
                      val text = Option(m.group(2)).getOrElse(stripped)
                      (text, importanceFor(tag))
                    case None => (stripped, 5)
                  }
                  val ttl: Option[java.time.Instant] = importance match {
                    case i if i <= 4 => Some(now.plusSeconds(4L * 60 * 60))
                    case 6           => Some(now.plusSeconds(7L * 24 * 60 * 60))
                    case 7           => Some(now.plusSeconds(365L * 24 * 60 * 60))
                    case _           => None
                  }
                  MemoryRecord(
                    id = MemoryRecordId.empty,
                    scope = MemoryScope.User,
                    userId = Some(userId),
                    workspaceId = None,
                    agentId = Option.when(agentId != AgentId.empty)(agentId),
                    recordKey = contentKey(userId, content),
                    value = Json.Obj("text" -> Json.Str(content)),
                    ttl = ttl,
                    createdAt = now,
                    updatedAt = now,
                    importance = importance,
                  )
                }
                .filter(_.importance >= 4)
                .toList
                .sortBy(-_.importance)
                .take(10)
            }
          }
        }
    }
  }

}
