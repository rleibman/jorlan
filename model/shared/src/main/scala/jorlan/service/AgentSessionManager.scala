/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.*
import zio.*

/** High-level session lifecycle service.
  *
  * Manages session creation, status transitions, and clean-up of associated in-process resources ([[SessionHub]] slots,
  * [[ModelGateway]] chat memory). Writes an event log entry for every state transition.
  */
trait AgentSessionManager {

  /** Create a new [[AgentSession]] for `userId`.
    *
    * Auto-creates a "Jorlan Interactive" default agent if none exists. Writes a [[EventType.SessionCreated]] event and
    * pre-allocates the [[SessionHub]] slot so the subscription is ready before the first message arrives.
    *
    * @param userId
    *   The authenticated user starting the session.
    * @param modelId
    *   Optional model override; if `None` the agent's `defaultModel` is used at inference time.
    */
  def createSession(
    userId:  UserId,
    modelId: Option[ModelId],
  ): IO[JorlanError, AgentSession]

  /** Look up a session by ID. Returns `None` if the session does not exist. */
  def getSession(id: AgentSessionId): IO[JorlanError, Option[AgentSession]]

  /** Transition the session to [[SessionStatus.Paused]]. The [[SessionHub]] slot is retained. */
  def suspendSession(id: AgentSessionId): IO[JorlanError, AgentSession]

  /** Transition the session to [[SessionStatus.Completed]] and release its [[SessionHub]] slot. */
  def terminateSession(id: AgentSessionId): IO[JorlanError, AgentSession]

  /** List sessions for `userId` with pagination.
    *
    * @param userId
    *   The user whose sessions to list.
    * @param page
    *   Zero-based page index.
    * @param pageSize
    *   Maximum number of results per page.
    */
  def listSessions(
    userId:   UserId,
    page:     Int,
    pageSize: Int,
  ): IO[JorlanError, List[AgentSession]]

}

object AgentSessionManager
