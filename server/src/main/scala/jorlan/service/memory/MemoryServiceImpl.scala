/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.memory

import jorlan.*
import jorlan.db.repository.ZIORepositories
import jorlan.*
import jorlan.service.*
import zio.*
import zio.json.*
import zio.json.ast.Json

class MemoryServiceImpl(
  repo:         ZIORepositories,
  accessPolicy: MemoryAccessPolicy,
  summarizer:   CheckpointSummarizer,
  classifier:   MemoryClassifier,
  policyRef:    Ref[CheckpointPolicyConfig],
) extends MemoryService {

  override def store(record: MemoryRecord): IO[JorlanError, MemoryRecord] =
    repo.memory.upsert(record).mapError(JorlanError(_))

  override def query(
    scope:   MemoryScope,
    userId:  UserId,
    agentId: AgentId,
    text:    Option[String] = None,
  ): IO[JorlanError, List[MemoryRecord]] = {
    val userFilter = scope match {
      case MemoryScope.Shared | MemoryScope.Workspace => None
      case _                                          => Some(userId)
    }
    repo.memory
      .search(MemorySearch(scope = scope, userId = userFilter, textSearch = text))
      .mapError(JorlanError(_))
      .flatMap(records => accessPolicy.filter(userId, agentId, records))
  }

  override def forget(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, Boolean] =
    repo.memory
      .getById(id)
      .mapError(JorlanError(_))
      .flatMap {
        case None    => ZIO.succeed(false)
        case Some(r) =>
          ZIO.unless(r.userId.contains(requestingUserId))(
            ZIO.fail(JorlanError(s"Access denied: cannot delete another user's memory record")),
          ) *> repo.memory.delete(id).mapBoth(JorlanError(_), _ > 0L)
      }

  override def markShared(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord] =
    rescope(id, requestingUserId, MemoryScope.Shared)

  override def markPrivate(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord] =
    rescope(id, requestingUserId, MemoryScope.Private)

  private def rescope(
    id:               MemoryRecordId,
    requestingUserId: UserId,
    newScope:         MemoryScope,
  ): IO[JorlanError, MemoryRecord] =
    repo.memory
      .getById(id)
      .mapError(JorlanError(_))
      .flatMap(ZIO.fromOption(_).orElseFail(JorlanError(s"MemoryRecord $id not found")))
      .flatMap { r =>
        ZIO.unless(r.userId.contains(requestingUserId))(
          ZIO.fail(JorlanError(s"Access denied: cannot modify another user's memory record")),
        ) *>
          repo.memory.updateScope(id, newScope).mapBoth(JorlanError(_), _ => r.copy(scope = newScope))
      }

  override def checkpoint(
    sessionId: AgentSessionId,
    messages:  List[Message],
    userId:    UserId,
    agentId:   AgentId,
    trigger:   CheckpointTrigger,
  ): IO[JorlanError, Unit] =
    for {
      config  <- policyRef.get
      should  <- CheckpointPolicy.fromConfig(config).shouldCheckpoint(trigger, None)
      records <- ZIO.when(should)(summarizer.summarize(messages, userId, agentId))
      _       <- ZIO.foreachParDiscard(records.getOrElse(List.empty)) { record =>
        val contentText = record.value.asObject
          .flatMap(_.get("text"))
          .flatMap(_.asString)
          .getOrElse(record.value.toString)
        classifier.classify(contentText).flatMap { scope =>
          repo.memory.upsert(record.copy(scope = scope)).mapError(JorlanError(_)).unit
        }
      }
    } yield ()

  override def requestCheckpoint(
    sessionId: AgentSessionId,
    userId:    UserId,
    agentId:   AgentId,
  ): IO[JorlanError, Unit] =
    for {
      convOpt <- repo.conversation
        .search(
          ConversationSearch(
            sessionId = sessionId,
            pageSize = 1,
            sorts = Some(Sort(ConversationOrder.StartedAt, OrderDirection.Desc)),
          ),
        )
        .mapError(JorlanError(_))
      messages <- convOpt.headOption match {
        case None    => ZIO.succeed(List.empty)
        case Some(c) =>
          repo.conversation
            .searchMessages(
              MessageSearch(
                conversationId = c.id,
                pageSize = 100,
                sorts = Some(Sort(MessageOrder.CreatedAt, OrderDirection.Asc)),
              ),
            )
            .mapError(JorlanError(_))
      }
      _ <- checkpoint(sessionId, messages, userId, agentId, CheckpointTrigger.UserRequest)
    } yield ()

  override def getCheckpointPolicy: UIO[CheckpointPolicyConfig] = policyRef.get

  override def updateCheckpointPolicy(config: CheckpointPolicyConfig): IO[JorlanError, Unit] =
    policyRef.set(config) *>
      config.toJsonAST.fold(
        err => ZIO.fail(JorlanError(s"Failed to serialise CheckpointPolicyConfig: $err")),
        json => repo.setting.set(CheckpointPolicyConfig.serverSettingsKey, json).mapError(JorlanError(_)),
      )

}

object MemoryServiceImpl {

  val live: URLayer[ZIORepositories & ModelGateway, MemoryService] =
    ZLayer.fromZIO(
      for {
        repo        <- ZIO.service[ZIORepositories]
        gateway     <- ZIO.service[ModelGateway]
        savedConfig <- repo.setting
          .get(CheckpointPolicyConfig.serverSettingsKey)
          .mapError(JorlanError(_))
          .map {
            case Some(json) => json.as[CheckpointPolicyConfig].getOrElse(CheckpointPolicyConfig.default)
            case None       => CheckpointPolicyConfig.default
          }.orElseSucceed(CheckpointPolicyConfig.default)
        policyRef <- Ref.make(savedConfig)
      } yield MemoryServiceImpl(
        repo,
        MemoryAccessPolicyImpl(),
        CheckpointSummarizerImpl(gateway),
        MemoryClassifierImpl(),
        policyRef,
      ),
    )

}
