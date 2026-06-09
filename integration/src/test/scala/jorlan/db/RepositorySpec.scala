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

import jorlan.*
import jorlan.db.TestFixtures.{*, given}
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.service.EventLogFilter
import zio.*
import zio.json.ast.Json
import zio.test.*

object RepositorySpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Repository integration tests")(
      userSuite,
      agentSuite,
      conversationSuite,
      skillSuite,
      memorySuite,
      eventLogSuite,
    ).provideShared(JorlanContainer.repositoryLayer) @@ TestAspect.sequential

  // ─── User ────────────────────────────────────────────────────────────────

  private val userSuite = suite("UserRepository")(
    test("upsert and retrieve a user") {
      for {
        repo <- ZIO.serviceWith[ZIORepositories](_.user)
        user = User(UserId.empty, "Alice", "", T0, T0)
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
        repo    <- ZIO.serviceWith[ZIORepositories](_.user)
        user    <- repo.upsert(User(UserId.empty, "Bob", "", T0, T0))
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
        repo <- ZIO.serviceWith[ZIORepositories](_.user)
        user <- repo.upsert(User(UserId.empty, "Carol", "", T0, T0))
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
    test("userByChannelIdentity resolves a known Telegram user") {
      for {
        repo <- ZIO.serviceWith[ZIORepositories](_.user)
        user <- repo.upsert(User(UserId.empty, "Diana", "diana@example.com", T0, T0))
        ci = ChannelIdentity(ChannelIdentityId.empty, user.id, ChannelType.Telegram, "777", verified = true, None, T0)
        _     <- repo.upsertChannelIdentity(ci)
        found <- repo.userByChannelIdentity(ChannelType.Telegram, "777")
        miss  <- repo.userByChannelIdentity(ChannelType.Telegram, "999")
      } yield assertTrue(
        found.exists(_.id == user.id),
        miss.isEmpty,
      )
    },
  )

  // ─── Agent ───────────────────────────────────────────────────────────────

  private val agentSuite = suite("AgentRepository")(
    test("upsert and retrieve an agent") {
      for {
        repo <- ZIO.serviceWith[ZIORepositories](_.agent)
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
        agentRepo <- ZIO.serviceWith[ZIORepositories](_.agent)
        userRepo  <- ZIO.serviceWith[ZIORepositories](_.user)
        user      <- userRepo.upsert(User(UserId.empty, "SessionUser", "", T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "SessionAgent", None, None, 0, T0))
        session = AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, None, None, T0, T0)
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
    test("session chatRef persists and is searchable") {
      for {
        agentRepo <- ZIO.serviceWith[ZIORepositories](_.agent)
        userRepo  <- ZIO.serviceWith[ZIORepositories](_.user)
        user      <- userRepo.upsert(User(UserId.empty, "ChatRefUser", "", T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "ChatRefAgent", None, None, 0, T0))
        session = AgentSession(
          AgentSessionId.empty,
          agent.id,
          user.id,
          None,
          SessionStatus.Active,
          None,
          Some("telegram-chat-42"),
          T0,
          T0,
        )
        saved   <- agentRepo.upsertSession(session)
        fetched <- agentRepo.getSession(saved.id)
        byRef   <- agentRepo.searchSessions(
          AgentSessionSearch(userId = Some(user.id), chatRef = Some("telegram-chat-42")),
        )
        noMatch <- agentRepo.searchSessions(AgentSessionSearch(userId = Some(user.id), chatRef = Some("other-ref")))
      } yield assertTrue(
        fetched.exists(_.chatRef.contains("telegram-chat-42")),
        byRef.exists(_.id == saved.id),
        noMatch.isEmpty,
      )
    },
  )

  // ─── Conversation ─────────────────────────────────────────────────────────

  private val conversationSuite = suite("ConversationRepository")(
    test("create conversation and add messages") {
      for {
        convRepo  <- ZIO.serviceWith[ZIORepositories](_.conversation)
        agentRepo <- ZIO.serviceWith[ZIORepositories](_.agent)
        userRepo  <- ZIO.serviceWith[ZIORepositories](_.user)
        user      <- userRepo.upsert(User(UserId.empty, "ConvUser", "", T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "ConvAgent", None, None, 0, T0))
        session   <- agentRepo.upsertSession(
          AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, None, None, T0, T0),
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
        repo <- ZIO.serviceWith[ZIORepositories](_.skill)
        skill = SkillRecord(SkillId.empty, "shell-exec", None, SkillTier.BuiltIn, T0)
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
        repo <- ZIO.serviceWith[ZIORepositories](_.skill)
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
        memRepo  <- ZIO.serviceWith[ZIORepositories](_.memory)
        userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
        user     <- userRepo.upsert(User(UserId.empty, "MemUser", "", T0, T0))
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
        repo  <- ZIO.serviceWith[ZIORepositories](_.eventLog)
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
        repo    <- ZIO.serviceWith[ZIORepositories](_.eventLog)
        _       <- repo.append(testEvent(EventType.UserConnected, occurredAt = t1))
        _       <- repo.append(testEvent(EventType.UserConnected, occurredAt = t2))
        results <- repo.search(EventLogFilter(eventType = Some(EventType.UserConnected), pageSize = 10))
        times = results.map(_.occurredAt)
      } yield assertTrue(times == times.sortWith(_.isAfter(_)))
    },
    test("filter events by session id") {
      val sid = AgentSessionId(777L)
      for {
        repo  <- ZIO.serviceWith[ZIORepositories](_.eventLog)
        _     <- repo.append(testEvent(EventType.AgentStarted, sessionId = Some(sid)))
        _     <- repo.append(testEvent(EventType.AgentCompleted))
        bySid <- repo.search(EventLogFilter(sessionId = Some(sid), pageSize = 100))
      } yield assertTrue(bySid.nonEmpty, bySid.forall(_.sessionId.contains(sid)))
    },
    test("filter events by agent id") {
      val aid = AgentId(888L)
      for {
        repo  <- ZIO.serviceWith[ZIORepositories](_.eventLog)
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
        repo   <- ZIO.serviceWith[ZIORepositories](_.eventLog)
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
        repo <- ZIO.serviceWith[ZIORepositories](_.eventLog)
        _    <- ZIO.foreachDiscard(1 to 5) { _ =>
          repo.append(testEvent(EventType.ApprovalRequested))
        }
        limited <- repo.search(EventLogFilter(eventType = Some(EventType.ApprovalRequested), pageSize = 2))
      } yield assertTrue(limited.length == 2)
    },
    test("replaySession returns events for session in ascending order") {
      val sid = AgentSessionId(5555L)
      for {
        repo <- ZIO.serviceWith[ZIORepositories](_.eventLog)
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
