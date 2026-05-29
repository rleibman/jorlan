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

import jorlan.db.TestFixtures.*
import jorlan.db.TestFixtures.given
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.service.EventLogFilter
import jorlan.*
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object RepositorySpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Repository integration tests")(
      userSuite,
      agentSuite,
      conversationSuite,
      skillSuite,
      memorySuite,
      eventLogSuite,
    ).provideLayerShared(JorlanContainer.repositoryLayer) @@ TestAspect.sequential

  // ─── User ────────────────────────────────────────────────────────────────

  private val userSuite = suite("UserRepository")(
    test("upsert and retrieve a user") {
      for {
        repo <- ZIO.service[UserZIORepository]
        user = User(UserId.empty, "Alice", None, T0, T0, active = true)
        saved    <- repo.upsert(user)
        fetched  <- repo.getById(saved.id)
        allUsers <- repo.search(UserSearch())
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
        user    <- repo.upsert(User(UserId.empty, "Bob", None, T0, T0))
        count   <- repo.deactivate(user.id)
        fetched <- repo.getById(user.id)
        all     <- repo.search(UserSearch(active = Some(true)))
      } yield assertTrue(
        count == 1L,
        fetched.exists(!_.active),
        !all.exists(_.id == user.id),
      )
    },
    test("channel identities") {
      for {
        repo <- ZIO.service[UserZIORepository]
        user <- repo.upsert(User(UserId.empty, "Carol", None, T0, T0))
        ci = ChannelIdentity(
          ChannelIdentityId.empty,
          user.id,
          ChannelType.Telegram,
          "@carol",
          verified = false,
          None,
          T0,
        )
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
        agent = Agent(AgentId.empty, "TestAgent", Some("desc"), Some(ModelId("claude-3")), 1, T0)
        saved   <- repo.upsert(agent)
        fetched <- repo.getById(saved.id)
        all     <- repo.search(AgentSearch())
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
        user      <- userRepo.upsert(User(UserId.empty, "SessionUser", None, T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "SessionAgent", None, None, 0, T0))
        session = AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, T0, T0)
        saved    <- agentRepo.upsertSession(session)
        fetched  <- agentRepo.getSession(saved.id)
        sessions <- agentRepo.searchSessions(AgentSessionSearch(agentId = Some(agent.id)))
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
        user      <- userRepo.upsert(User(UserId.empty, "ConvUser", None, T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "ConvAgent", None, None, 0, T0))
        session   <- agentRepo.upsertSession(
          AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, T0, T0),
        )
        conv     <- convRepo.create(Conversation(ConversationId.empty, session.id, T0))
        _        <- convRepo.addMessage(Message(MessageId.empty, conv.id, MessageRole.User, "Hello!", None, T0))
        _        <- convRepo.addMessage(Message(MessageId.empty, conv.id, MessageRole.Assistant, "Hi there!", None, T0))
        messages <- convRepo.searchMessages(MessageSearch(conversationId = conv.id))
        convs    <- convRepo.search(ConversationSearch(sessionId = session.id))
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
        skill = Skill(SkillId.empty, "shell-exec", None, SkillTier.BuiltIn, T0)
        saved <- repo.upsert(skill)
        sv    <- repo.upsertVersion(
          SkillVersion(SkillVersionId.empty, saved.id, "1.0.0", Json.Obj(), SkillStatus.Active, T0),
        )
        versions <- repo.searchVersions(SkillVersionSearch(skillId = saved.id))
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
        ci = ConnectorInstance(ConnectorInstanceId.empty, ConnectorType.Telegram, "my-bot", Json.Obj(), "active", T0)
        saved  <- repo.upsertConnector(ci)
        all    <- repo.searchConnectors(ConnectorSearch())
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
        user     <- userRepo.upsert(User(UserId.empty, "MemUser", None, T0, T0))
        record1  <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "pref.theme",
            Json.Str("dark"),
            None,
            T0,
            T0,
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
            T0,
            T0,
          ),
        )
        byUser   <- memRepo.search(MemorySearch(scope = MemoryScope.User, userId = Some(user.id)))
        shared   <- memRepo.search(MemorySearch(scope = MemoryScope.Shared, key = Some("system.version")))
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
        repo  <- ZIO.service[EventLogZIORepository]
        e1    <- repo.append(testEvent(EventType.AgentStarted))
        e2    <- repo.append(testEvent(EventType.SkillInvoked))
        all   <- repo.search(EventLogFilter(pageSize = 100))
        typed <- repo.search(EventLogFilter(eventType = Some(EventType.AgentStarted), pageSize = 10))
      } yield assertTrue(
        e1.id.value > 0,
        all.length >= 2,
        typed.forall(_.eventType == EventType.AgentStarted),
      )
    },
    test("search returns results in descending order") {
      val t1 = T0
      val t2 = T0.plusSeconds(10)
      for {
        repo    <- ZIO.service[EventLogZIORepository]
        _       <- repo.append(testEvent(EventType.UserConnected, occurredAt = t1))
        _       <- repo.append(testEvent(EventType.UserConnected, occurredAt = t2))
        results <- repo.search(EventLogFilter(eventType = Some(EventType.UserConnected), pageSize = 10))
        times = results.map(_.occurredAt)
      } yield assertTrue(times == times.sortWith(_.isAfter(_)))
    },
    test("filter events by session id") {
      val sid = AgentSessionId(777L)
      for {
        repo  <- ZIO.service[EventLogZIORepository]
        _     <- repo.append(testEvent(EventType.AgentStarted, sessionId = Some(sid)))
        _     <- repo.append(testEvent(EventType.AgentCompleted))
        bySid <- repo.search(EventLogFilter(sessionId = Some(sid), pageSize = 100))
      } yield assertTrue(bySid.nonEmpty, bySid.forall(_.sessionId.contains(sid)))
    },
    test("filter events by agent id") {
      val aid = AgentId(888L)
      for {
        repo  <- ZIO.service[EventLogZIORepository]
        _     <- repo.append(testEvent(EventType.AgentStarted, agentId = Some(aid)))
        _     <- repo.append(testEvent(EventType.AgentCompleted))
        byAid <- repo.search(EventLogFilter(agentId = Some(aid), pageSize = 100))
      } yield assertTrue(byAid.nonEmpty, byAid.forall(_.agentId.contains(aid)))
    },
    test("filter events by time range") {
      val t1 = T0.minusSeconds(10)
      val t2 = T0
      val t3 = T0.plusSeconds(10)
      for {
        repo   <- ZIO.service[EventLogZIORepository]
        _      <- repo.append(testEvent(EventType.MemoryWritten, occurredAt = t1))
        _      <- repo.append(testEvent(EventType.MemoryWritten, occurredAt = t2))
        _      <- repo.append(testEvent(EventType.MemoryWritten, occurredAt = t3))
        result <- repo.search(
          EventLogFilter(
            eventType = Some(EventType.MemoryWritten),
            from = Some(T0.minusSeconds(1)),
            to = Some(T0.plusSeconds(1)),
            pageSize = 100,
          ),
        )
      } yield assertTrue(
        result.exists(_.occurredAt == t2),
        result.forall(e => !e.occurredAt.isBefore(T0.minusSeconds(1)) && !e.occurredAt.isAfter(T0.plusSeconds(1))),
      )
    },
    test("pageSize caps the result set exactly") {
      for {
        repo <- ZIO.service[EventLogZIORepository]
        _    <- ZIO.foreachDiscard(1 to 5) { _ =>
          repo.append(testEvent(EventType.ApprovalRequested))
        }
        limited <- repo.search(EventLogFilter(eventType = Some(EventType.ApprovalRequested), pageSize = 2))
      } yield assertTrue(limited.length == 2)
    },
    test("replaySession returns events for session in ascending order") {
      val sid = AgentSessionId(5555L)
      for {
        repo <- ZIO.service[EventLogZIORepository]
        e1   <- repo.append(testEvent(EventType.AgentStarted, sessionId = Some(sid), occurredAt = T0))
        e2   <- repo.append(testEvent(EventType.SkillInvoked, sessionId = Some(sid), occurredAt = T0.plusSeconds(1)))
        e3   <- repo.append(testEvent(EventType.AgentCompleted, sessionId = Some(sid), occurredAt = T0.plusSeconds(2)))
        replayed <- repo.replaySession(sid)
      } yield assertTrue(
        replayed.map(_.id) == List(e1.id, e2.id, e3.id),
        replayed.map(_.occurredAt) == List(T0, T0.plusSeconds(1), T0.plusSeconds(2)),
      )
    },
  )

}
