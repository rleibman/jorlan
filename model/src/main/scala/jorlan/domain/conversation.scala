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

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.Instant

enum MessageRole {

  case User, Assistant, System, Tool

}
object MessageRole {

  given JsonEncoder[MessageRole] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[MessageRole] =
    JsonDecoder[String].mapOrFail { s =>
      MessageRole.values.find(_.toString == s).toRight(s"Unknown MessageRole: $s")
    }

}

case class Conversation(
  id:        ConversationId,
  sessionId: AgentSessionId,
  startedAt: Instant,
)
object Conversation {

  given JsonEncoder[Conversation] = DeriveJsonEncoder.gen[Conversation]
  given JsonDecoder[Conversation] = DeriveJsonDecoder.gen[Conversation]

}

case class Message(
  id:             MessageId,
  conversationId: ConversationId,
  role:           MessageRole,
  content:        String,
  metadataJson:   Option[String],
  createdAt:      Instant,
)
object Message {

  given JsonEncoder[Message] = DeriveJsonEncoder.gen[Message]
  given JsonDecoder[Message] = DeriveJsonDecoder.gen[Message]

}
