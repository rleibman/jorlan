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

enum SessionStatus {

  case Created, Active, Paused, Blocked, Completed, Failed, Cancelled

}
object SessionStatus {

  given JsonEncoder[SessionStatus] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[SessionStatus] =
    JsonDecoder[String].mapOrFail { s =>
      SessionStatus.values.find(_.toString == s).toRight(s"Unknown SessionStatus: $s")
    }

}

case class Agent(
  id:           AgentId,
  name:         String,
  description:  Option[String],
  defaultModel: Option[String],
  trustLevel:   Int = 0,
  createdAt:    Instant,
)
object Agent {

  given JsonEncoder[Agent] = DeriveJsonEncoder.gen[Agent]
  given JsonDecoder[Agent] = DeriveJsonDecoder.gen[Agent]

}

case class AgentSession(
  id:          AgentSessionId,
  agentId:     AgentId,
  userId:      UserId,
  workspaceId: Option[WorkspaceId],
  status:      SessionStatus,
  createdAt:   Instant,
  updatedAt:   Instant,
)
object AgentSession {

  given JsonEncoder[AgentSession] = DeriveJsonEncoder.gen[AgentSession]
  given JsonDecoder[AgentSession] = DeriveJsonDecoder.gen[AgentSession]

}
