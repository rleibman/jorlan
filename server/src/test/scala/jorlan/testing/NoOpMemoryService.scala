/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.testing

import jorlan.*
import jorlan.service.*
import zio.*

/** [[MemoryService]] that does nothing — for use in unit tests that do not exercise memory. */
class NoOpMemoryService extends MemoryService {

  private val idCounter = java.util.concurrent.atomic.AtomicLong(1L)

  override def store(record: MemoryRecord): IO[JorlanError, MemoryRecord] =
    ZIO.succeed(record.copy(id = MemoryRecordId(idCounter.getAndIncrement())))

  override def query(
    scope:   MemoryScope,
    userId:  UserId,
    agentId: AgentId,
    text:    Option[String],
  ): IO[JorlanError, List[MemoryRecord]] = ZIO.succeed(List.empty)

  override def forget(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, Boolean] = ZIO.succeed(true)

  override def markShared(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord] =
    ZIO.fail(JorlanError("NoOpMemoryService.markShared: no record to return"))

  override def markPrivate(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord] =
    ZIO.fail(JorlanError("NoOpMemoryService.markPrivate: no record to return"))

  override def checkpoint(
    sessionId: AgentSessionId,
    messages:  List[Message],
    userId:    UserId,
    agentId:   AgentId,
    trigger:   CheckpointTrigger,
  ): IO[JorlanError, Unit] = ZIO.unit

  override def requestCheckpoint(
    sessionId: AgentSessionId,
    userId:    UserId,
    agentId:   AgentId,
  ): IO[JorlanError, Unit] = ZIO.unit

  override def getCheckpointPolicy: UIO[CheckpointPolicyConfig] =
    ZIO.succeed(CheckpointPolicyConfig.default)

  override def updateCheckpointPolicy(config: CheckpointPolicyConfig): IO[JorlanError, Unit] = ZIO.unit

}

object NoOpMemoryService {

  val layer: ULayer[MemoryService] = ZLayer.succeed(NoOpMemoryService())

}
