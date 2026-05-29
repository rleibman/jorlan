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

/** Lifecycle state of an [[AgentSession]].
  *
  *   - `Created` — session record exists but execution has not started.
  *   - `Active` — the agent is running and accepting messages.
  *   - `Paused` — execution is suspended; the [[SessionHub]] slot is retained for resumption.
  *   - `Blocked` — the agent is waiting for human approval before proceeding.
  *   - `Completed` — the session ended normally.
  *   - `Failed` — the session ended due to an unrecoverable error.
  *   - `Cancelled` — the session was explicitly cancelled by the user or an orchestrator.
  */
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
  *
  * @param agentId
  *   The agent definition driving this session.
  * @param userId
  *   The user on whose behalf the agent is executing.
  * @param workspaceId
  *   Optional workspace scope; `None` for sessions without a file-system context.
  * @param modelId
  *   The LLM model used for this session. Overrides the agent's `defaultModel` when set.
  */
case class AgentSession(
  id:          AgentSessionId,
  agentId:     AgentId,
  userId:      UserId,
  workspaceId: Option[WorkspaceId],
  status:      SessionStatus,
  modelId:     Option[ModelId],
  createdAt:   Instant,
  updatedAt:   Instant,
) derives JsonEncoder, JsonDecoder

/** A single streamed token (or completion sentinel) from an agent response.
  *
  * @param sessionId
  *   The session this chunk belongs to; used by the GraphQL subscription resolver to route to the right subscriber.
  * @param content
  *   The token text. Empty when `finished` is `true`.
  * @param finished
  *   When `true`, the stream is complete and `content` is empty. Consumers must close the subscription on receipt.
  * @param isError
  *   When `true` alongside `finished = true`, the stream ended due to a model or runtime error and `content` carries
  *   the error message. Clients should display this as an error rather than a normal completion.
  */
case class ResponseChunk(
  sessionId: AgentSessionId,
  content:   String,
  finished:  Boolean,
  isError:   Boolean = false,
) derives JsonEncoder, JsonDecoder
