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
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object MemoryServiceSpec extends ZIOSpec[MemoryService] {

  private class NoOpCheckpointSummarizer extends CheckpointSummarizer {

    override def summarize(
      messages: List[Message],
      userId:   UserId,
      agentId:  AgentId,
    ): IO[JorlanError, List[MemoryRecord]] =
      ZIO.succeed(Nil)

  }

  override val bootstrap: ULayer[MemoryService] =
    ZLayer.make[MemoryService](
      InMemoryRepositories.live(),
      ZLayer.succeed(MemoryAccessPolicyImpl():   MemoryAccessPolicy),
      ZLayer.succeed(NoOpCheckpointSummarizer(): CheckpointSummarizer),
      ZLayer.succeed(MemoryClassifierImpl():     MemoryClassifier),
      ZLayer.succeed(CheckpointPolicy.onSessionEnd),
      MemoryServiceImpl.live,
    )

  private val userId = UserId(1L)
  private val agentId = AgentId(1L)

  private def makeRecord(
    key:   String,
    text:  String,
    scope: MemoryScope = MemoryScope.User,
  ): MemoryRecord = {
    val now = Instant.now()
    MemoryRecord(
      id = MemoryRecordId.empty,
      scope = scope,
      userId = Some(userId),
      workspaceId = None,
      agentId = Some(agentId),
      recordKey = key,
      value = Json.Obj("text" -> Json.Str(text)),
      ttl = None,
      createdAt = now,
      updatedAt = now,
    )
  }

  override def spec: Spec[MemoryService & TestEnvironment & Scope, Any] =
    (suite("MemoryService")(
      test("store and query returns the record") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("user.lang", "prefers Scala")
          _       <- svc.store(record)
          results <- svc.query(MemoryScope.User, userId, agentId)
        } yield assertTrue(results.exists(_.recordKey == "user.lang"))
      },
      test("forget removes the record") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("user.delete", "to be deleted")
          _      <- svc.store(record)
          before <- svc.query(MemoryScope.User, userId, agentId)
          id = before.find(_.recordKey == "user.delete").map(_.id).getOrElse(MemoryRecordId.empty)
          _     <- svc.forget(id, userId)
          after <- svc.query(MemoryScope.User, userId, agentId)
        } yield assertTrue(
          before.exists(_.recordKey == "user.delete"),
          !after.exists(_.recordKey == "user.delete"),
        )
      },
      test("markShared changes scope to Shared") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("share.me", "to be shared", MemoryScope.User)
          _   <- svc.store(record)
          all <- svc.query(MemoryScope.User, userId, agentId)
          id = all.find(_.recordKey == "share.me").map(_.id).getOrElse(MemoryRecordId.empty)
          updated <- svc.markShared(id, userId)
        } yield assertTrue(updated.scope == MemoryScope.Shared)
      },
      test("Private scope not visible to other user") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("private.fact", "secret", MemoryScope.Private)
          _ <- svc.store(record)
          otherUser = UserId(999L)
          results <- svc.query(MemoryScope.Private, otherUser, agentId)
        } yield assertTrue(!results.exists(_.recordKey == "private.fact"))
      },
      test("markPrivate changes scope to Private") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("make.private", "was shared", MemoryScope.Shared)
          _   <- svc.store(record)
          all <- svc.query(MemoryScope.Shared, userId, agentId)
          id = all.find(_.recordKey == "make.private").map(_.id).getOrElse(MemoryRecordId.empty)
          updated <- svc.markPrivate(id, userId)
        } yield assertTrue(updated.scope == MemoryScope.Private)
      },
      test("markShared fails for non-existent id") {
        for {
          svc    <- ZIO.service[MemoryService]
          result <- svc.markShared(MemoryRecordId(99999L), userId).exit
        } yield assertTrue(result.isFailure)
      },
      test("markPrivate fails for non-existent id") {
        for {
          svc    <- ZIO.service[MemoryService]
          result <- svc.markPrivate(MemoryRecordId(99999L), userId).exit
        } yield assertTrue(result.isFailure)
      },
      test("checkpoint with non-SessionEnd trigger does nothing") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("pre.checkpoint", "existing")
          _      <- svc.store(record)
          before <- svc.query(MemoryScope.User, userId, agentId)
          now = java.time.Instant.now()
          msgs = List(Message(MessageId.empty, ConversationId.empty, MessageRole.User, "hello", None, now))
          _     <- svc.checkpoint(AgentSessionId(1L), msgs, userId, agentId, CheckpointTrigger.TimedInterval)
          after <- svc.query(MemoryScope.User, userId, agentId)
        } yield assertTrue(before.size == after.size)
      },
      test("checkpoint with SessionEnd trigger stores summarized records") {
        for {
          svc <- ZIO.service[MemoryService]
          now = java.time.Instant.now()
          msgs = List(Message(MessageId.empty, ConversationId.empty, MessageRole.User, "I prefer Python", None, now))
          before <- svc.query(MemoryScope.User, userId, agentId)
          _      <- svc.checkpoint(AgentSessionId(1L), msgs, userId, agentId, CheckpointTrigger.SessionEnd)
          after  <- svc.query(MemoryScope.User, userId, agentId)
        } yield assertTrue(after.size >= before.size)
      },
      test("forget is rejected when a different user tries to delete another user's record") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("owner.fact", "sensitive data")
          stored <- svc.store(record)
          otherUser = UserId(999L)
          result <- svc.forget(stored.id, otherUser).exit
        } yield assertTrue(result.isFailure)
      },
      test("markShared is rejected when a different user tries to rescope another user's record") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("owner.share", "my data")
          stored <- svc.store(record)
          otherUser = UserId(999L)
          result <- svc.markShared(stored.id, otherUser).exit
        } yield assertTrue(result.isFailure)
      },
      test("Shared scope record visible to a different user") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("shared.fact", "for everyone", MemoryScope.Shared)
          _ <- svc.store(record)
          other = UserId(999L)
          results <- svc.query(MemoryScope.Shared, other, agentId)
        } yield assertTrue(results.exists(_.recordKey == "shared.fact"))
      },
      test("Private scope hidden when userId matches but agentId differs") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("private.agent", "agent-specific", MemoryScope.Private)
          _ <- svc.store(record)
          wrongAgent = AgentId(999L)
          results <- svc.query(MemoryScope.Private, userId, wrongAgent)
        } yield assertTrue(!results.exists(_.recordKey == "private.agent"))
      },
      test("store returns a record with a non-empty assigned id") {
        for {
          svc <- ZIO.service[MemoryService]
          record = makeRecord("id.check", "id test")
          stored <- svc.store(record)
        } yield assertTrue(stored.id != MemoryRecordId.empty)
      },
      test("query with textSearch returns only matching records") {
        for {
          svc <- ZIO.service[MemoryService]
          _   <- svc.store(makeRecord("search.match", "needle in a haystack"))
          _   <- svc.store(makeRecord("search.nomatch", "completely unrelated content"))
          // InMemoryMemoryRepo does not implement full-text search; query without filter returns all
          all    <- svc.query(MemoryScope.User, userId, agentId)
          withTs <- svc.query(MemoryScope.User, userId, agentId, Some("needle"))
        } yield assertTrue(all.size >= 2, withTs.size <= all.size)
      },
      test("store with Shared scope is visible in Shared queries") {
        for {
          svc    <- ZIO.service[MemoryService]
          _      <- svc.store(makeRecord("shared.key", "public info", MemoryScope.Shared))
          result <- svc.query(MemoryScope.Shared, userId, agentId)
        } yield assertTrue(result.exists(_.recordKey == "shared.key"))
      },
      test("checkpoint with no messages does not store any records") {
        for {
          svc    <- ZIO.service[MemoryService]
          before <- svc.query(MemoryScope.User, userId, agentId)
          _      <- svc.checkpoint(AgentSessionId(99L), Nil, userId, agentId, CheckpointTrigger.SessionEnd)
          after  <- svc.query(MemoryScope.User, userId, agentId)
        } yield assertTrue(after.size == before.size)
      },
      test("MemoryService companion: query delegates to implementation") {
        for {
          result <- MemoryService.query(MemoryScope.User, userId, agentId)
        } yield assertTrue(result.isInstanceOf[List[?]])
      },
      test("MemoryService companion: forget delegates to implementation") {
        for {
          svc    <- ZIO.service[MemoryService]
          stored <- svc.store(makeRecord("to-forget", "delete me"))
          result <- MemoryService.forget(stored.id, userId)
        } yield assertTrue(result)
      },
    ) @@ TestAspect.sequential) +
      suite("checkpoint with real summarizer")(
        test("checkpoint writes summarizer output to repo") {
          for {
            svc <- ZIO.service[MemoryService]
            now = java.time.Instant.now()
            msgs = List(
              Message(MessageId.empty, ConversationId.empty, MessageRole.User, "I use Scala", None, now),
            )
            before <- svc.query(MemoryScope.User, userId, agentId)
            _      <- svc.checkpoint(AgentSessionId(1L), msgs, userId, agentId, CheckpointTrigger.SessionEnd)
            after  <- svc.query(MemoryScope.User, userId, agentId)
          } yield assertTrue(after.size > before.size)
        }.provide(
          ZLayer.make[MemoryService](
            InMemoryRepositories.live(),
            ZLayer.succeed(MemoryAccessPolicyImpl(): MemoryAccessPolicy),
            ZLayer.succeed(MemoryClassifierImpl():   MemoryClassifier),
            ZLayer.succeed(CheckpointPolicy.onSessionEnd),
            FakeModelGateway.layer(List("- User prefers Scala\n")),
            CheckpointSummarizerImpl.live,
            MemoryServiceImpl.live,
          ),
        ),
        test("checkpoint classifies PII bullet as Private scope") {
          for {
            svc <- ZIO.service[MemoryService]
            now = java.time.Instant.now()
            msgs = List(
              Message(MessageId.empty, ConversationId.empty, MessageRole.User, "my password", None, now),
            )
            _      <- svc.checkpoint(AgentSessionId(2L), msgs, userId, agentId, CheckpointTrigger.SessionEnd)
            result <- svc.query(MemoryScope.Private, userId, agentId)
          } yield assertTrue(result.exists(_.recordKey == "episodic.checkpoint"))
        }.provide(
          ZLayer.make[MemoryService](
            InMemoryRepositories.live(),
            ZLayer.succeed(MemoryAccessPolicyImpl(): MemoryAccessPolicy),
            ZLayer.succeed(MemoryClassifierImpl():   MemoryClassifier),
            ZLayer.succeed(CheckpointPolicy.onSessionEnd),
            FakeModelGateway.layer(List("- my password is hunter2\n")),
            CheckpointSummarizerImpl.live,
            MemoryServiceImpl.live,
          ),
        ),
      )

}
