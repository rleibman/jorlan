/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.connector.*
import jorlan.db.repository.{AgentZIORepository, EventLogZIORepository, UserZIORepository}
import jorlan.domain.*
import zio.*

/** Connector-agnostic ingress pipeline.
  *
  * For each [[InboundMessage]]:
  *   1. Resolves the sender to a canonical [[User]] via [[UserZIORepository.userByChannelIdentity]].
  *   2. Applies [[UnrecognizedIdentityPolicy]] if identity is not found: `Reject` drops and logs; `Quarantine` is
  *      log-only for Phase 11 (no persistence).
  *   3. Gates on the `agent.message` capability via [[CapabilityEvaluator]].
  *   4. Resolves or creates the [[AgentSession]] for `(user, chatRef, channelType)` — one durable session per chat.
  *   5. Dispatches to [[AgentRunner.processMessage]].
  *   6. Writes an inbound receipt to the event log with the resolved session reference.
  *
  * Note: [[UnrecognizedIdentityPolicy]] is enforced from the connector's configuration passed to [[receive]]. The
  * `Quarantine` case is log-only in Phase 11; persistent quarantine table is deferred.
  */
class MessageIngressImpl(
  userRepo:    UserZIORepository,
  agentRepo:   AgentZIORepository,
  eventLog:    EventLogZIORepository,
  evaluator:   CapabilityEvaluator,
  sessionMgr:  AgentSessionManager,
  agentRunner: AgentRunner,
) extends MessageIngress {

  private val agentMessageCapability = CapabilityName("agent.message")

  override def receive(
    msg:                InboundMessage,
    unrecognizedPolicy: UnrecognizedIdentityPolicy = UnrecognizedIdentityPolicy.Reject,
  ): IO[JorlanError, Unit] =
    for {
      userOpt <- userRepo
        .userByChannelIdentity(msg.channelType, msg.channelUserId)
        .mapError(JorlanError(_))
      _ <- userOpt match {
        case None       => handleUnrecognized(msg, unrecognizedPolicy)
        case Some(user) => handleKnown(msg, user)
      }
    } yield ()

  private def handleUnrecognized(
    msg:    InboundMessage,
    policy: UnrecognizedIdentityPolicy,
  ): IO[JorlanError, Unit] =
    policy match {
      case UnrecognizedIdentityPolicy.Reject =>
        ZIO.logWarning(
          s"[ingress:${msg.channelType}] Unrecognized sender ${msg.channelUserId} in chat ${msg.chatRef} — rejected",
        ) *> logInboundEvent(msg, actorId = None, sessionId = None)
      case UnrecognizedIdentityPolicy.Quarantine =>
        ZIO.logWarning(
          s"[ingress:${msg.channelType}] Unrecognized sender ${msg.channelUserId} in chat ${msg.chatRef} — quarantined (log-only Phase 11)",
        ) *> logInboundEvent(msg, actorId = None, sessionId = None)
    }

  private def handleKnown(
    msg:  InboundMessage,
    user: User,
  ): IO[JorlanError, Unit] =
    for {
      evalResult <- evaluator.evaluate(
        CapabilityRequest(
          capability = agentMessageCapability,
          requestorId = user.id,
          agentId = None,
          sessionId = None,
          resourceConstraints = None,
        ),
      )
      _ <- evalResult match {
        case EvaluationResult.ExplicitDeny =>
          ZIO.logWarning(
            s"[ingress:${msg.channelType}] User ${user.id} explicitly denied capability $agentMessageCapability — message dropped",
          ) *> logInboundEvent(msg, actorId = Some(user.id), sessionId = None)
        case EvaluationResult.DefaultDeny =>
          ZIO.logWarning(
            s"[ingress:${msg.channelType}] User ${user.id} denied by default for capability $agentMessageCapability — message dropped",
          )
        case _ =>
          for {
            sessionId <- resolveOrCreateSession(user, msg)
            _         <- agentRunner.processMessage(sessionId, msg.content, Some(user.id))
            _         <- logInboundEvent(msg, actorId = Some(user.id), sessionId = Some(sessionId))
          } yield ()
      }
    } yield ()

  private def resolveOrCreateSession(
    user: User,
    msg:  InboundMessage,
  ): IO[JorlanError, AgentSessionId] =
    agentRepo
      .searchSessions(
        AgentSessionSearch(
          userId = Some(user.id),
          chatRef = Some(msg.chatRef),
          pageSize = 1,
        ),
      )
      .mapError(JorlanError(_))
      .flatMap { sessions =>
        sessions.find(s => s.status != SessionStatus.Completed && s.status != SessionStatus.Cancelled) match {
          case Some(existing) => ZIO.succeed(existing.id)
          case None           =>
            sessionMgr
              .createSession(user.id, modelId = None)
              .flatMap { session =>
                agentRepo
                  .upsertSession(session.copy(chatRef = Some(msg.chatRef)))
                  .mapError(JorlanError(_))
                  .map(_.id)
              }
        }
      }

  private def logInboundEvent(
    msg:       InboundMessage,
    actorId:   Option[UserId],
    sessionId: Option[AgentSessionId],
  ): UIO[Unit] =
    Clock.instant.flatMap { now =>
      eventLog
        .append(
          EventLog[String](
            id = EventLogId.empty,
            eventType = EventType.UserMessageReceived,
            actorId = actorId,
            agentId = None,
            sessionId = sessionId,
            resource = None,
            payloadJson = None,
            occurredAt = now,
          ),
        )
        .ignore
    }

}

object MessageIngressImpl {

  val live: URLayer[
    UserZIORepository & AgentZIORepository & EventLogZIORepository & CapabilityEvaluator & AgentSessionManager &
      AgentRunner,
    MessageIngress,
  ] =
    ZLayer.fromFunction(MessageIngressImpl(_, _, _, _, _, _))

}
