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
import jorlan.domain.*
import zio.*
import zio.json.ast.Json

/** Tier 0 memory skill — explicit agent-directed memory operations.
  *
  * These bypass [[CheckpointSummarizer]] and write/read directly via [[MemoryService]]. Intended to be invoked from the
  * GraphQL API (user shell commands) and, in Phase 12, from the ReAct tool-calling loop.
  */
class MemorySkill(memoryService: MemoryService) {

  /** Store a fact directly into memory with the caller-supplied scope.
    *
    * Unlike the checkpoint pipeline, the scope is **not** re-classified by [[MemoryClassifier]]; the caller is trusted
    * to supply the correct scope. This is intentional for explicit user-directed stores.
    */
  def remember(
    key:     String,
    text:    String,
    scope:   MemoryScope,
    userId:  UserId,
    agentId: AgentId,
  ): IO[JorlanError, MemoryRecord] =
    Clock.instant.flatMap { now =>
      val record = MemoryRecord(
        id = MemoryRecordId.empty,
        scope = scope,
        userId = Some(userId),
        workspaceId = None,
        agentId = Option.when(agentId != AgentId.empty)(agentId),
        recordKey = key,
        value = Json.Obj("text" -> Json.Str(text)),
        ttl = None,
        createdAt = now,
        updatedAt = now,
      )
      memoryService.store(record)
    }

  def search(
    text:    String,
    scope:   MemoryScope,
    userId:  UserId,
    agentId: AgentId,
  ): IO[JorlanError, List[MemoryRecord]] =
    memoryService.query(scope, userId, agentId, Some(text))

  def forget(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, Boolean] =
    memoryService.forget(id, requestingUserId)

  def markShared(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord] =
    memoryService.markShared(id, requestingUserId)

  def markPrivate(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord] =
    memoryService.markPrivate(id, requestingUserId)

}

object MemorySkill {

  val live: URLayer[MemoryService, MemorySkill] =
    ZLayer.fromFunction(MemorySkill(_))

}
