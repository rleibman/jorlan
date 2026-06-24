/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.*
import zio.*
import zio.json.*

/** When a checkpoint should be triggered. */
enum CheckpointTrigger {

  case SessionEnd, TimedInterval, UserRequest, BeforeExternalEffect

}

/** Which triggers are active for checkpointing.
  *
  * @param onSessionEnd
  *   fire when the agent session ends (always on by default)
  * @param onUserRequest
  *   fire when the user explicitly requests a checkpoint (always on by default)
  * @param timedIntervalTurns
  *   if `Some(n)`, fire after every n turns within a session; `None` disables timed checkpoints
  * @param beforeExternalEffect
  *   fire before every tool invocation; generates a safety snapshot; off by default (expensive)
  */
case class CheckpointPolicyConfig(
  onSessionEnd:         Boolean = true,
  onUserRequest:        Boolean = true,
  timedIntervalTurns:   Option[Int] = Some(10),
  beforeExternalEffect: Boolean = false,
) derives JsonCodec

object CheckpointPolicyConfig {

  val default: CheckpointPolicyConfig = CheckpointPolicyConfig()

  val serverSettingsKey: String = "checkpoint.policy"

}

/** Decides whether a checkpoint should be committed for a given trigger event and session context.
  *
  * @param trigger
  *   the event that initiated the checkpoint decision
  * @param session
  *   the current agent session, if available — allows policies to inspect message count, session age, etc.
  */
trait CheckpointPolicy {

  def shouldCheckpoint(
    trigger: CheckpointTrigger,
    session: Option[AgentSession] = None,
  ): UIO[Boolean]

}

object CheckpointPolicy {

  /** Always commits on [[CheckpointTrigger.SessionEnd]]; ignores other triggers and session metadata (safe default). */
  val onSessionEnd: CheckpointPolicy = (
    trigger,
    _,
  ) => ZIO.succeed(trigger == CheckpointTrigger.SessionEnd)

  def fromConfig(config: CheckpointPolicyConfig): CheckpointPolicy =
    (
      trigger,
      _,
    ) =>
      ZIO.succeed(trigger match {
        case CheckpointTrigger.SessionEnd           => config.onSessionEnd
        case CheckpointTrigger.UserRequest          => config.onUserRequest
        case CheckpointTrigger.TimedInterval        => config.timedIntervalTurns.isDefined
        case CheckpointTrigger.BeforeExternalEffect => config.beforeExternalEffect
      })

}

/** Compresses a list of conversation [[Message]]s into a list of [[MemoryRecord]] summaries using the LLM. Returns an
  * empty list when `messages` is empty or the LLM produces no parseable bullet points.
  */
trait CheckpointSummarizer {

  /** @param messages
    *   the conversation messages to summarize (returns `List.empty` immediately if empty)
    * @param userId
    *   owner of the resulting [[MemoryRecord]]s
    * @param agentId
    *   agent that generated the conversation
    * @return
    *   one [[MemoryRecord]] per extracted bullet point, scope defaults to `User` before classification
    */
  def summarize(
    messages: List[Message],
    userId:   UserId,
    agentId:  AgentId,
  ): IO[JorlanError, List[MemoryRecord]]

}

/** Assigns a [[MemoryScope]] to a [[MemoryRecord]] based on content heuristics. Rules (first match wins): PII keywords
  * → `Private`; sharing language → `Shared`; default → `User`.
  */
trait MemoryClassifier {

  /** @param content
    *   plain-text content to classify (not JSON-serialized)
    * @return
    *   the most restrictive [[MemoryScope]] that applies
    */
  def classify(content: String): UIO[MemoryScope]

}

/** Filters a list of [[MemoryRecord]]s to those visible to the requesting user+agent pair. Visibility rules: `User`
  * requires userId match; `Private` requires both userId and agentId; `Shared` is always visible; `Workspace` is
  * deny-by-default until membership checks are implemented.
  */
trait MemoryAccessPolicy {

  /** @param requestingUserId
    *   the authenticated user requesting the records
    * @param agentId
    *   the agent session context (used for `Private` scope checks)
    * @param records
    *   all candidate records (typically already filtered by scope at the DB level)
    * @return
    *   only the records the requesting user is allowed to see
    */
  def filter(
    requestingUserId: UserId,
    agentId:          AgentId,
    records:          List[MemoryRecord],
  ): UIO[List[MemoryRecord]]

}

/** High-level memory service used by [[AgentRunner]] and the GraphQL API.
  *
  * Wraps [[jorlan.MemoryRepository]] with access-policy enforcement and checkpoint orchestration.
  */
trait MemoryService {

  /** Store a [[MemoryRecord]] directly (bypasses [[CheckpointSummarizer]]). Returns the stored record with its assigned
    * id.
    */
  def store(record: MemoryRecord): IO[JorlanError, MemoryRecord]

  /** Query memory records visible to `userId`/`agentId` under the given scope.
    *
    * @param scope
    *   only records with this scope are returned; use separate calls to merge multiple scopes
    * @param userId
    *   the requesting user — DB-level filter for `User`/`Private`; access policy enforces all scopes
    * @param agentId
    *   required for `Private` scope enforcement in [[MemoryAccessPolicy]]
    * @param text
    *   optional free-text filter applied in-memory after the DB fetch (not a SQL predicate)
    */
  def query(
    scope:   MemoryScope,
    userId:  UserId,
    agentId: AgentId,
    text:    Option[String] = None,
  ): IO[JorlanError, List[MemoryRecord]]

  /** Delete a memory record by id. Returns `true` if deleted, `false` if no record with that id exists. Fails if
    * `requestingUserId` does not own the record.
    */
  def forget(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, Boolean]

  /** Re-scope a record to [[MemoryScope.Shared]]. Fails if `requestingUserId` does not own the record. */
  def markShared(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord]

  /** Re-scope a record to [[MemoryScope.Private]]. Fails if `requestingUserId` does not own the record. */
  def markPrivate(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord]

  /** Run the checkpoint pipeline for a session: summarize messages → classify → store.
    *
    * Only executes if [[CheckpointPolicy.shouldCheckpoint]] returns `true`.
    */
  def checkpoint(
    sessionId: AgentSessionId,
    messages:  List[Message],
    userId:    UserId,
    agentId:   AgentId,
    trigger:   CheckpointTrigger,
  ): IO[JorlanError, Unit]

  /** Immediately run a [[CheckpointTrigger.UserRequest]] checkpoint for the given session, loading the most recent
    * conversation messages from the conversation repository. No-ops if the policy has `onUserRequest = false`.
    */
  def requestCheckpoint(
    sessionId: AgentSessionId,
    userId:    UserId,
    agentId:   AgentId,
  ): IO[JorlanError, Unit]

  /** Return the current [[CheckpointPolicyConfig]]. */
  def getCheckpointPolicy: UIO[CheckpointPolicyConfig]

  /** Persist and apply a new [[CheckpointPolicyConfig]]. */
  def updateCheckpointPolicy(config: CheckpointPolicyConfig): IO[JorlanError, Unit]

  /** Find memories semantically similar to `queryText` using vector similarity.
    *
    * Returns an empty list if the embedding service is unavailable rather than failing. Results are not filtered by
    * [[MemoryAccessPolicy]] — caller is responsible for only querying scopes the user is permitted to read.
    */
  def semanticQuery(
    scope:     MemoryScope,
    userId:    UserId,
    agentId:   AgentId,
    queryText: String,
    limit:     Int = 5,
  ): IO[JorlanError, List[MemoryRecord]]

}

object MemoryService {

  def store(record: MemoryRecord): ZIO[MemoryService, JorlanError, MemoryRecord] =
    ZIO.serviceWithZIO[MemoryService](_.store(record))

  def query(
    scope:   MemoryScope,
    userId:  UserId,
    agentId: AgentId,
    text:    Option[String] = None,
  ): ZIO[MemoryService, JorlanError, List[MemoryRecord]] =
    ZIO.serviceWithZIO[MemoryService](_.query(scope, userId, agentId, text))

  def forget(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): ZIO[MemoryService, JorlanError, Boolean] =
    ZIO.serviceWithZIO[MemoryService](_.forget(id, requestingUserId))

  def markShared(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): ZIO[MemoryService, JorlanError, MemoryRecord] =
    ZIO.serviceWithZIO[MemoryService](_.markShared(id, requestingUserId))

  def markPrivate(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): ZIO[MemoryService, JorlanError, MemoryRecord] =
    ZIO.serviceWithZIO[MemoryService](_.markPrivate(id, requestingUserId))

  def checkpoint(
    sessionId: AgentSessionId,
    messages:  List[Message],
    userId:    UserId,
    agentId:   AgentId,
    trigger:   CheckpointTrigger,
  ): ZIO[MemoryService, JorlanError, Unit] =
    ZIO.serviceWithZIO[MemoryService](_.checkpoint(sessionId, messages, userId, agentId, trigger))

  def requestCheckpoint(
    sessionId: AgentSessionId,
    userId:    UserId,
    agentId:   AgentId,
  ): ZIO[MemoryService, JorlanError, Unit] =
    ZIO.serviceWithZIO[MemoryService](_.requestCheckpoint(sessionId, userId, agentId))

  def getCheckpointPolicy: ZIO[MemoryService, Nothing, CheckpointPolicyConfig] =
    ZIO.serviceWithZIO[MemoryService](_.getCheckpointPolicy)

  def updateCheckpointPolicy(config: CheckpointPolicyConfig): ZIO[MemoryService, JorlanError, Unit] =
    ZIO.serviceWithZIO[MemoryService](_.updateCheckpointPolicy(config))

}
