/*
 * Copyright (c) 2025 Roberto Leibman - All Rights Reserved
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
        repo <- ZIO.service[UserRepository]
        user = User(UserId.empty, "Alice", now, now, active = true)
        saved    <- repo.upsert(user)
        fetched  <- repo.getById(saved.id)
        allUsers <- repo.getAll
      } yield {
        assertTrue(saved.id.value > 0) &&
        assertTrue(fetched.isDefined) &&
        assertTrue(fetched.get.displayName == "Alice") &&
        assertTrue(allUsers.exists(_.id == saved.id))
      }
    },
    test("deactivate a user") {
      for {
        repo    <- ZIO.service[UserRepository]
        user    <- repo.upsert(User(UserId.empty, "Bob", now, now))
        _       <- repo.deactivate(user.id)
        fetched <- repo.getById(user.id)
        all     <- repo.getAll
      } yield assertTrue(fetched.exists(!_.active)) &&
        assertTrue(!all.exists(_.id == user.id))
    },
    test("channel identities") {
      for {
        repo <- ZIO.service[UserRepository]
        user <- repo.upsert(User(UserId.empty, "Carol", now, now))
        ci = ChannelIdentity(UserId.empty, user.id, ChannelType.Telegram, "@carol", verified = false, now)
        saved  <- repo.upsertChannelIdentity(ci)
        loaded <- repo.getChannelIdentities(user.id)
        _      <- repo.deleteChannelIdentity(saved.id)
        after  <- repo.getChannelIdentities(user.id)
      } yield {
        assertTrue(saved.id.value > 0) &&
        assertTrue(loaded.length == 1) &&
        assertTrue(after.isEmpty)
      }
    },
  )

  // ─── Agent ───────────────────────────────────────────────────────────────

  private val agentSuite = suite("AgentRepository")(
    test("upsert and retrieve an agent") {
      for {
        repo <- ZIO.service[AgentRepository]
        agent = Agent(AgentId.empty, "TestAgent", Some("desc"), Some("claude-3"), 1, now)
        saved   <- repo.upsert(agent)
        fetched <- repo.getById(saved.id)
        all     <- repo.getAll
      } yield {
        assertTrue(saved.id.value > 0) &&
        assertTrue(fetched.isDefined) &&
        assertTrue(all.exists(_.id == saved.id))
      }
    },
    test("agent sessions") {
      for {
        agentRepo <- ZIO.service[AgentRepository]
        userRepo  <- ZIO.service[UserRepository]
        user      <- userRepo.upsert(User(UserId.empty, "SessionUser", now, now))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "SessionAgent", None, None, 0, now))
        session = AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, now, now)
        saved    <- agentRepo.upsertSession(session)
        fetched  <- agentRepo.getSession(saved.id)
        sessions <- agentRepo.getSessionsForAgent(agent.id)
      } yield {
        assertTrue(saved.id.value > 0) &&
        assertTrue(fetched.isDefined) &&
        assertTrue(sessions.exists(_.id == saved.id))
      }
    },
  )

  // ─── Conversation ─────────────────────────────────────────────────────────

  private val conversationSuite = suite("ConversationRepository")(
    test("create conversation and add messages") {
      for {
        convRepo  <- ZIO.service[ConversationRepository]
        agentRepo <- ZIO.service[AgentRepository]
        userRepo  <- ZIO.service[UserRepository]
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
        assertTrue(conv.id.value > 0) &&
        assertTrue(messages.length == 2) &&
        assertTrue(convs.exists(_.id == conv.id))
      }
    },
  )

  // ─── Skill ────────────────────────────────────────────────────────────────

  private val skillSuite = suite("SkillRepository")(
    test("upsert skill and versions") {
      for {
        repo <- ZIO.service[SkillRepository]
        skill = Skill(SkillId.empty, "shell-exec", None, SkillTier.BuiltIn, now)
        saved <- repo.upsert(skill)
        sv <- repo.upsertVersion(SkillVersion(SkillVersionId.empty, saved.id, "1.0.0", "{}", SkillStatus.Active, now))
        versions <- repo.getVersions(saved.id)
        fetched  <- repo.getById(saved.id)
      } yield {
        assertTrue(saved.id.value > 0) &&
        assertTrue(sv.id.value > 0) &&
        assertTrue(versions.length == 1) &&
        assertTrue(fetched.isDefined)
      }
    },
    test("connector instance CRUD") {
      for {
        repo <- ZIO.service[SkillRepository]
        ci = ConnectorInstance(ConnectorInstanceId.empty, ConnectorType.Telegram, "my-bot", "{}", "active", now)
        saved  <- repo.upsertConnector(ci)
        all    <- repo.getAllConnectors
        loaded <- repo.getConnector(saved.id)
      } yield {
        assertTrue(saved.id.value > 0) &&
        assertTrue(all.exists(_.id == saved.id)) &&
        assertTrue(loaded.isDefined)
      }
    },
  )

  // ─── Memory ───────────────────────────────────────────────────────────────

  private val memorySuite = suite("MemoryRepository")(
    test("upsert and search memory records") {
      for {
        memRepo  <- ZIO.service[MemoryRepository]
        userRepo <- ZIO.service[UserRepository]
        user     <- userRepo.upsert(User(UserId.empty, "MemUser", now, now))
        record1 <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "pref.theme",
            "dark",
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
            "1.0",
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
        assertTrue(record1.id.value > 0) &&
        assertTrue(byUser.exists(_.id == record1.id)) &&
        assertTrue(shared.exists(_.key == "system.version")) &&
        assertTrue(afterDel.isEmpty)
      }
    },
  )

  // ─── EventLog ─────────────────────────────────────────────────────────────

  private val eventLogSuite = suite("EventLogRepository")(
    test("append and search events") {
      for {
        repo <- ZIO.service[EventLogRepository]
        e1   <- repo.append(EventLog(EventLogId.empty, EventType.AgentStarted, None, None, None, None, None, None, now))
        e2 <- repo.append(
          EventLog(EventLogId.empty, EventType.SkillInvoked, None, None, None, Some("skill"), None, None, now),
        )
        all   <- repo.search(None, None, None, None, 100)
        typed <- repo.search(Some(EventType.AgentStarted), None, None, None, 10)
      } yield {
        assertTrue(e1.id.value > 0) &&
        assertTrue(all.length >= 2) &&
        assertTrue(typed.forall(_.eventType == EventType.AgentStarted))
      }
    },
  )

}
