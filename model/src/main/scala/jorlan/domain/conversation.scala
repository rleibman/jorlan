/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.domain

import zio.json.ast.Json
import zio.json.{JsonDecoder, JsonEncoder}

import java.time.Instant

/** The role of the author of a [[Message]] — mirrors the LLM chat-completion role vocabulary. */
enum MessageRole derives JsonEncoder, JsonDecoder {

  case User, Assistant, System, Tool

}

/** An ordered thread of [[Message]]s within an [[AgentSession]]. A session may contain multiple conversations (e.g.
  * after context-window truncation and restart).
  */
case class Conversation(
  id:        ConversationId,
  sessionId: AgentSessionId,
  startedAt: Instant,
) derives JsonEncoder, JsonDecoder

/** One LLM exchange unit within a [[Conversation]].
  *
  * @param metadataJson
  *   Optional JSON carrying provider-specific metadata (e.g. token counts, finish reason, tool call IDs).
  */
case class Message(
  id:             MessageId,
  conversationId: ConversationId,
  role:           MessageRole,
  content:        String,
  metadataJson:   Option[Json],
  createdAt:      Instant,
) derives JsonEncoder, JsonDecoder
