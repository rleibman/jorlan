/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import zio.json.ast.Json
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

/** The role of the author of a [[Message]] — mirrors the LLM chat-completion role vocabulary. */
enum MessageRole derives JsonCodec {

  case User, Assistant, System, Tool

}

/** An ordered thread of [[Message]]s within an [[AgentSession]]. A session may contain multiple conversations (e.g.
  * after context-window truncation and restart).
  */
case class Conversation(
  id:        ConversationId,
  sessionId: AgentSessionId,
  startedAt: Instant,
) derives JsonCodec

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
) derives JsonCodec
