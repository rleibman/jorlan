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
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.test.*

object MemorySkillSpec extends ZIOSpecDefault {

  private val userId = UserId(1L)
  private val agentId = AgentId(1L)

  private val layers: ULayer[MemorySkill] = {
    val memRepo = InMemoryRepositories.InMemoryMemoryRepo.layer
    val policy = ZLayer.succeed(new MemoryAccessPolicyImpl(): MemoryAccessPolicy)
    val summarizer = ZLayer.succeed(
      new CheckpointSummarizer {
        override def summarize(
          messages: List[Message],
          userId:   UserId,
          agentId:  AgentId,
        ): IO[JorlanError, List[MemoryRecord]] = ZIO.succeed(Nil)
      }: CheckpointSummarizer,
    )
    val classifier = ZLayer.succeed(new MemoryClassifierImpl(): MemoryClassifier)
    val cpPolicy = ZLayer.succeed(CheckpointPolicy.onSessionEnd)
    val svcLayer = (memRepo ++ policy ++ summarizer ++ classifier ++ cpPolicy) >>> MemoryServiceImpl.live
    svcLayer >>> MemorySkill.live
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MemorySkill")(
      test("remember stores and is retrievable via search") {
        for {
          skill   <- ZIO.service[MemorySkill]
          _       <- skill.remember("user.lang", "prefers Scala", MemoryScope.User, userId, agentId)
          results <- skill.search("prefers Scala", MemoryScope.User, userId, agentId)
        } yield assertTrue(results.exists(_.recordKey == "user.lang"))
      },
      test("forget removes a record") {
        for {
          skill  <- ZIO.service[MemorySkill]
          _      <- skill.remember("remove.me", "temporary", MemoryScope.User, userId, agentId)
          before <- skill.search("temporary", MemoryScope.User, userId, agentId)
          id = before.find(_.recordKey == "remove.me").map(_.id).getOrElse(MemoryRecordId.empty)
          _     <- skill.forget(id, userId)
          after <- skill.search("temporary", MemoryScope.User, userId, agentId)
        } yield assertTrue(
          before.exists(_.recordKey == "remove.me"),
          !after.exists(_.recordKey == "remove.me"),
        )
      },
      test("markShared changes scope from User to Shared") {
        for {
          skill   <- ZIO.service[MemorySkill]
          _       <- skill.remember("to.share", "shared fact", MemoryScope.User, userId, agentId)
          records <- skill.search("shared fact", MemoryScope.User, userId, agentId)
          id = records.find(_.recordKey == "to.share").map(_.id).getOrElse(MemoryRecordId.empty)
          updated <- skill.markShared(id, userId)
        } yield assertTrue(updated.scope == MemoryScope.Shared)
      },
      test("markPrivate changes scope from User to Private") {
        for {
          skill   <- ZIO.service[MemorySkill]
          _       <- skill.remember("to.private", "private fact", MemoryScope.User, userId, agentId)
          records <- skill.search("private fact", MemoryScope.User, userId, agentId)
          id = records.find(_.recordKey == "to.private").map(_.id).getOrElse(MemoryRecordId.empty)
          updated <- skill.markPrivate(id, userId)
        } yield assertTrue(updated.scope == MemoryScope.Private)
      },
      test("search returns empty list when no matching records") {
        for {
          skill   <- ZIO.service[MemorySkill]
          results <- skill.search("nonexistent_xyz_string", MemoryScope.User, userId, agentId)
        } yield assertTrue(results.isEmpty)
      },
    ).provide(layers)

}
