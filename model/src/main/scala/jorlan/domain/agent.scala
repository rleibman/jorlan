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

import zio.json.{JsonDecoder, JsonEncoder}

import java.time.Instant

/** Lifecycle state of an [[AgentSession]]. */
enum SessionStatus derives JsonEncoder, JsonDecoder {

  case Created, Active, Paused, Blocked, Completed, Failed, Cancelled

}

/** An autonomous program definition that can invoke skills on behalf of users.
  *
  * @param defaultModel
  *   LLM model identifier to use when no model is specified at invocation time (e.g. `llama3`).
  * @param trustLevel
  *   0 = fully untrusted (default for agent-authored skills); higher values unlock additional capabilities without
  *   explicit approval. Controlled by an administrator.
  */
case class Agent(
  id:           AgentId,
  name:         String,
  description:  Option[String],
  defaultModel: Option[ModelId],
  trustLevel:   Int = 0,
  createdAt:    Instant,
) derives JsonEncoder, JsonDecoder

/** A single runtime instance of an [[Agent]] executing on behalf of a [[jorlan.domain.User]]. Each session maintains
  * its own conversation context window and execution state.
  */
case class AgentSession(
  id:          AgentSessionId,
  agentId:     AgentId,
  userId:      UserId,
  workspaceId: Option[WorkspaceId],
  status:      SessionStatus,
  createdAt:   Instant,
  updatedAt:   Instant,
) derives JsonEncoder, JsonDecoder
