/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import jorlan.db.repository.*
import jorlan.domain.*
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object RepositorySpec extends ZIOSpecDefault {

  private val now = Instant.now()

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Repository integration tests")(
      userSuite,
      agentSuite,
      conversationSuite,
      skillSuite,
      memorySuite,
      eventLogSuite,
    ).provideLayerShared(JorlanContainer.repositoryLayer)

  // ─── User ────────────────────────────────────────────────────────────────

  private val userSuite = suite("UserRepository")(
    test("upsert and retrieve a user") {
      for {
        repo <- ZIO.service[UserZIORepository]
        user = User(UserId.empty, "Alice", now, now, active = true)
        saved    <- repo.upsert(user)
        fetched  <- repo.getById(saved.id)
        allUsers <- repo.getAll
      } yield {
        assertTrue(
          saved.id.value > 0,
          fetched.isDefined,
          fetched.exists(_.displayName == "Alice"),
          allUsers.exists(_.id == saved.id),
        )
      }
    },
    test("deactivate a user") {
      for {
        repo    <- ZIO.service[UserZIORepository]
        user    <- repo.upsert(User(UserId.empty, "Bob", now, now))
        count   <- repo.deactivate(user.id)
        fetched <- repo.getById(user.id)
        all     <- repo.getAll
      } yield assertTrue(
        count == 1L,
        fetched.exists(!_.active),
        !all.exists(_.id == user.id),
      )
    },
    test("channel identities") {
      for {
        repo <- ZIO.service[UserZIORepository]
        user <- repo.upsert(User(UserId.empty, "Carol", now, now))
        ci = ChannelIdentity(ChannelIdentityId.empty, user.id, ChannelType.Telegram, "@carol", verified = false, now)
        saved  <- repo.upsertChannelIdentity(ci)
        loaded <- repo.getChannelIdentities(user.id)
        _      <- repo.deleteChannelIdentity(saved.id)
        after  <- repo.getChannelIdentities(user.id)
      } yield {
        assertTrue(
          saved.id.value > 0,
          loaded.length == 1,
          after.isEmpty,
        )
      }
    },
  )

  // ─── Agent ───────────────────────────────────────────────────────────────

  private val agentSuite = suite("AgentRepository")(
    test("upsert and retrieve an agent") {
      for {
        repo <- ZIO.service[AgentZIORepository]
        agent = Agent(AgentId.empty, "TestAgent", Some("desc"), Some(ModelId("claude-3")), 1, now)
        saved   <- repo.upsert(agent)
        fetched <- repo.getById(saved.id)
        all     <- repo.getAll
      } yield {
        assertTrue(
          saved.id.value > 0,
          fetched.isDefined,
          all.exists(_.id == saved.id),
        )
      }
    },
    test("agent sessions") {
      for {
        agentRepo <- ZIO.service[AgentZIORepository]
        userRepo  <- ZIO.service[UserZIORepository]
        user      <- userRepo.upsert(User(UserId.empty, "SessionUser", now, now))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "SessionAgent", None, None, 0, now))
        session = AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, now, now)
        saved    <- agentRepo.upsertSession(session)
        fetched  <- agentRepo.getSession(saved.id)
        sessions <- agentRepo.getSessionsForAgent(agent.id)
      } yield {
        assertTrue(
          saved.id.value > 0,
          fetched.isDefined,
          sessions.exists(_.id == saved.id),
        )
      }
    },
  )

  // ─── Conversation ─────────────────────────────────────────────────────────

  private val conversationSuite = suite("ConversationRepository")(
    test("create conversation and add messages") {
      for {
        convRepo  <- ZIO.service[ConversationZIORepository]
        agentRepo <- ZIO.service[AgentZIORepository]
        userRepo  <- ZIO.service[UserZIORepository]
        user      <- userRepo.upsert(User(UserId.empty, "ConvUser", now, now))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "ConvAgent", None, None, 0, now))
        session <- agentRepo.upsertSession(
          AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, now, now),
        )
        conv <- convRepo.create(Conversation(ConversationId.empty, session.id, now))
        msg1 <- convRepo.addMessage(Message(MessageId.empty, conv.id, MessageRole.User, "Hello!", None, now))
        msg2 <- convRepo.addMessage(Message(MessageId.empty, conv.id, MessageRole.Assistant, "Hi there!", None, now))
        messages <- convRepo.getMessages(conv.id)
        convs    <- convRepo.getBySession(session.id)
      } yield {
        assertTrue(
          conv.id.value > 0,
          messages.length == 2,
          convs.exists(_.id == conv.id),
        )
      }
    },
  )

  // ─── Skill ────────────────────────────────────────────────────────────────

  private val skillSuite = suite("SkillRepository")(
    test("upsert skill and versions") {
      for {
        repo <- ZIO.service[SkillZIORepository]
        skill = Skill(SkillId.empty, "shell-exec", None, SkillTier.BuiltIn, now)
        saved <- repo.upsert(skill)
        sv <- repo.upsertVersion(
          SkillVersion(SkillVersionId.empty, saved.id, "1.0.0", Json.Obj(), SkillStatus.Active, now),
        )
        versions <- repo.getVersions(saved.id)
        fetched  <- repo.getById(saved.id)
      } yield {
        assertTrue(
          saved.id.value > 0,
          sv.id.value > 0,
          versions.length == 1,
          fetched.isDefined,
        )
      }
    },
    test("connector instance CRUD") {
      for {
        repo <- ZIO.service[SkillZIORepository]
        ci = ConnectorInstance(ConnectorInstanceId.empty, ConnectorType.Telegram, "my-bot", Json.Obj(), "active", now)
        saved  <- repo.upsertConnector(ci)
        all    <- repo.getAllConnectors
        loaded <- repo.getConnector(saved.id)
      } yield {
        assertTrue(
          saved.id.value > 0,
          all.exists(_.id == saved.id),
          loaded.isDefined,
        )
      }
    },
  )

  // ─── Memory ───────────────────────────────────────────────────────────────

  private val memorySuite = suite("MemoryRepository")(
    test("upsert and search memory records") {
      for {
        memRepo  <- ZIO.service[MemoryZIORepository]
        userRepo <- ZIO.service[UserZIORepository]
        user     <- userRepo.upsert(User(UserId.empty, "MemUser", now, now))
        record1 <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "pref.theme",
            Json.Str("dark"),
            None,
            now,
            now,
          ),
        )
        record2 <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.Shared,
            None,
            None,
            None,
            "system.version",
            Json.Str("1.0"),
            None,
            now,
            now,
          ),
        )
        byUser   <- memRepo.search(MemoryScope.User, Some(user.id), None, None, None)
        shared   <- memRepo.search(MemoryScope.Shared, None, None, None, Some("system.version"))
        _        <- memRepo.delete(record1.id)
        afterDel <- memRepo.getById(record1.id)
      } yield {
        assertTrue(
          record1.id.value > 0,
          byUser.exists(_.id == record1.id),
          shared.exists(_.recordKey == "system.version"),
          afterDel.isEmpty,
        )
      }
    },
  )

  // ─── EventLog ─────────────────────────────────────────────────────────────

  private val eventLogSuite = suite("EventLogRepository")(
    test("append and search events") {
      for {
        repo <- ZIO.service[EventLogZIORepository]
        e1 <- repo.append(
          EventLog[AgentId](EventLogId.empty, EventType.AgentStarted, None, None, None, None, None, now),
        )
        e2 <- repo.append(
          EventLog[SkillId](EventLogId.empty, EventType.SkillInvoked, None, None, None, None, None, now),
        )
        all   <- repo.search(None, None, None, None, None, 100)
        typed <- repo.search(Some(EventType.AgentStarted), None, None, None, None, 10)
      } yield {
        assertTrue(
          e1.id.value > 0,
          all.length >= 2,
          typed.forall(_.eventType == EventType.AgentStarted),
        )
      }
    },
    test("filter events by session id") {
      for {
        repo <- ZIO.service[EventLogZIORepository]
        sid = AgentSessionId(777L)
        _ <- repo.append(
          EventLog[AgentId](EventLogId.empty, EventType.AgentStarted, None, None, Some(sid), None, None, now),
        )
        _ <- repo.append(
          EventLog[AgentId](EventLogId.empty, EventType.AgentCompleted, None, None, None, None, None, now),
        )
        bySid <- repo.search(None, None, Some(sid), None, None, 100)
      } yield assertTrue(bySid.nonEmpty, bySid.forall(_.sessionId.contains(sid)))
    },
    test("filter events by agent id") {
      for {
        repo <- ZIO.service[EventLogZIORepository]
        aid = AgentId(888L)
        _ <- repo.append(
          EventLog[AgentId](EventLogId.empty, EventType.AgentStarted, None, Some(aid), None, None, None, now),
        )
        _ <- repo.append(
          EventLog[AgentId](EventLogId.empty, EventType.AgentCompleted, None, None, None, None, None, now),
        )
        byAid <- repo.search(None, Some(aid), None, None, None, 100)
      } yield assertTrue(byAid.nonEmpty, byAid.forall(_.agentId.contains(aid)))
    },
    test("limit caps the result set") {
      for {
        repo <- ZIO.service[EventLogZIORepository]
        _ <- ZIO.foreach(1 to 5) { _ =>
          repo.append(EventLog[AgentId](EventLogId.empty, EventType.SystemAlert, None, None, None, None, None, now))
        }
        limited <- repo.search(Some(EventType.SystemAlert), None, None, None, None, 2)
      } yield assertTrue(limited.length <= 2)
    },
  )

}
