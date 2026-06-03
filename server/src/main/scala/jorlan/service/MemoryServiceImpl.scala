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
import jorlan.db.repository.MemoryZIORepository
import jorlan.domain.*
import zio.*

class MemoryServiceImpl(
  memoryRepo:   MemoryZIORepository,
  accessPolicy: MemoryAccessPolicy,
  summarizer:   CheckpointSummarizer,
  classifier:   MemoryClassifier,
  policy:       CheckpointPolicy,
) extends MemoryService {

  override def store(record: MemoryRecord): IO[JorlanError, MemoryRecord] =
    memoryRepo.upsert(record).mapError(JorlanError(_))

  override def query(
    scope:   MemoryScope,
    userId:  UserId,
    agentId: AgentId,
    text:    Option[String] = None,
  ): IO[JorlanError, List[MemoryRecord]] = {
    // For Shared/Workspace scopes, skip the DB-level userId filter — the access policy enforces visibility.
    val userFilter = scope match {
      case MemoryScope.Shared | MemoryScope.Workspace => None
      case _                                          => Some(userId)
    }
    memoryRepo
      .search(MemorySearch(scope = scope, userId = userFilter, textSearch = text))
      .mapError(JorlanError(_))
      .flatMap(records => accessPolicy.filter(userId, agentId, records))
  }

  override def forget(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, Boolean] =
    memoryRepo
      .getById(id)
      .mapError(JorlanError(_))
      .flatMap {
        case None    => ZIO.succeed(false)
        case Some(r) =>
          ZIO.unless(r.userId.contains(requestingUserId))(
            ZIO.fail(JorlanError(s"Access denied: cannot delete another user's memory record")),
          ) *> memoryRepo.delete(id).mapError(JorlanError(_)).map(_ > 0L)
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
    memoryRepo
      .getById(id)
      .mapError(JorlanError(_))
      .flatMap(ZIO.fromOption(_).orElseFail(JorlanError(s"MemoryRecord $id not found")))
      .flatMap { r =>
        ZIO.unless(r.userId.contains(requestingUserId))(
          ZIO.fail(JorlanError(s"Access denied: cannot modify another user's memory record")),
        ) *>
          memoryRepo.updateScope(id, newScope).mapError(JorlanError(_)).as(r.copy(scope = newScope))
      }

  override def checkpoint(
    sessionId: AgentSessionId,
    messages:  List[Message],
    userId:    UserId,
    agentId:   AgentId,
    trigger:   CheckpointTrigger,
  ): IO[JorlanError, Unit] =
    for {
      should  <- policy.shouldCheckpoint(trigger, None)
      records <- ZIO.when(should)(summarizer.summarize(messages, userId, agentId))
      _       <- ZIO.foreachParDiscard(records.getOrElse(Nil)) { record =>
        val contentText = record.value.asObject
          .flatMap(_.get("text"))
          .flatMap(_.asString)
          .getOrElse(record.value.toString)
        classifier.classify(contentText).flatMap { scope =>
          memoryRepo.upsert(record.copy(scope = scope)).mapError(JorlanError(_)).unit
        }
      }
    } yield ()

}

object MemoryServiceImpl {

  val live: URLayer[
    MemoryZIORepository & MemoryAccessPolicy & CheckpointSummarizer & MemoryClassifier & CheckpointPolicy,
    MemoryService,
  ] =
    ZLayer.fromFunction(new MemoryServiceImpl(_, _, _, _, _))

}
