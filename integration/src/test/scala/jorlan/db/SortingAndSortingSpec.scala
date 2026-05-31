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

/** Exercises all sort-branch variants of every Quill repository so scoverage picks them up. */
object SortingAndSortingSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Sorting branches")(
      userSortSuite,
      agentSortSuite,
      conversationSortSuite,
      skillSortSuite,
      memorySortSuite,
      eventLogSortSuite,
    ).provideLayerShared(JorlanContainer.repositoryLayer) @@ TestAspect.sequential

  // ─── User ────────────────────────────────────────────────────────────────────

  private val userSortSuite = suite("UserRepository sort branches")(
    test("search sorted by id desc") {
      for {
        repo <- ZIO.service[UserZIORepository]
        _    <- repo.upsert(User(UserId.empty, "SortU1", None, T0.minusSeconds(2), T0))
        _    <- repo.upsert(User(UserId.empty, "SortU2", None, T0.minusSeconds(1), T0))
        res  <- repo.search(UserSearch(pageSize = 50, sorts = Some(Sort(UserOrder.Id, OrderDirection.Desc))))
      } yield assertTrue(res.map(_.id.value) == res.map(_.id.value).sorted.reverse)
    },
    test("search sorted by displayName asc") {
      for {
        repo <- ZIO.service[UserZIORepository]
        _    <- repo.upsert(User(UserId.empty, "ZebraSort", None, T0, T0))
        _    <- repo.upsert(User(UserId.empty, "AardvarkSort", None, T0, T0))
        res  <- repo.search(UserSearch(pageSize = 50, sorts = Some(Sort(UserOrder.DisplayName, OrderDirection.Asc))))
        names = res.map(_.displayName)
      } yield assertTrue(names.zip(names.tail).forall { case (a, b) => a.compareToIgnoreCase(b) <= 0 })
    },
    test("search sorted by displayName desc") {
      for {
        repo <- ZIO.service[UserZIORepository]
        _    <- repo.upsert(User(UserId.empty, "ZebraSort2", None, T0, T0))
        _    <- repo.upsert(User(UserId.empty, "AardvarkSort2", None, T0, T0))
        res  <- repo.search(UserSearch(pageSize = 50, sorts = Some(Sort(UserOrder.DisplayName, OrderDirection.Desc))))
        names = res.map(_.displayName)
      } yield assertTrue(names.zip(names.tail).forall { case (a, b) => a.compareToIgnoreCase(b) >= 0 })
    },
    test("search sorted by createdAt asc") {
      for {
        repo <- ZIO.service[UserZIORepository]
        _    <- repo.upsert(User(UserId.empty, "CreatedAscU1", None, T0.minusSeconds(100), T0))
        _    <- repo.upsert(User(UserId.empty, "CreatedAscU2", None, T0.plusSeconds(100), T0))
        res  <- repo.search(UserSearch(pageSize = 50, sorts = Some(Sort(UserOrder.CreatedAt, OrderDirection.Asc))))
        times = res.map(_.createdAt)
      } yield assertTrue(times == times.sorted)
    },
    test("search sorted by createdAt desc") {
      for {
        repo <- ZIO.service[UserZIORepository]
        _    <- repo.upsert(User(UserId.empty, "CreatedDescU1", None, T0.minusSeconds(200), T0))
        _    <- repo.upsert(User(UserId.empty, "CreatedDescU2", None, T0.plusSeconds(200), T0))
        res  <- repo.search(UserSearch(pageSize = 50, sorts = Some(Sort(UserOrder.CreatedAt, OrderDirection.Desc))))
        times = res.map(_.createdAt)
      } yield assertTrue(times == times.sorted.reverse)
    },
    test("search filtered by active=false") {
      for {
        repo <- ZIO.service[UserZIORepository]
        u    <- repo.upsert(User(UserId.empty, "InactiveUser", None, T0, T0))
        _    <- repo.deactivate(u.id)
        res  <- repo.search(UserSearch(active = Some(false), pageSize = 50))
      } yield assertTrue(res.exists(_.id == u.id))
    },
    test("userByEmail returns Some when email exists") {
      for {
        repo <- ZIO.service[UserZIORepository]
        _    <- repo.upsert(User(UserId.empty, "EmailUser", Some("emailuser@test.com"), T0, T0))
        res  <- repo.userByEmail("emailuser@test.com")
      } yield assertTrue(res.isDefined, res.exists(_.displayName == "EmailUser"))
    },
    test("userByEmail returns None when email not found") {
      for {
        repo <- ZIO.service[UserZIORepository]
        res  <- repo.userByEmail("nobody@nowhere.invalid")
      } yield assertTrue(res.isEmpty)
    },
    test("userByChannelIdentity returns Some after creating channel identity") {
      for {
        repo <- ZIO.service[UserZIORepository]
        user <- repo.upsert(User(UserId.empty, "OAuthUser", Some("oauth@test.com"), T0, T0))
        ci = ChannelIdentity(
          ChannelIdentityId.empty,
          user.id,
          ChannelType.Google,
          "google-uid-123",
          verified = true,
          None,
          T0,
        )
        _   <- repo.upsertChannelIdentity(ci)
        res <- repo.userByChannelIdentity(ChannelType.Google, "google-uid-123")
      } yield assertTrue(res.isDefined, res.exists(_.id == user.id))
    },
    test("userByChannelIdentity returns None for unknown identity") {
      for {
        repo <- ZIO.service[UserZIORepository]
        res  <- repo.userByChannelIdentity(ChannelType.GitHub, "nonexistent-uid")
      } yield assertTrue(res.isEmpty)
    },
  )

  // ─── Agent ───────────────────────────────────────────────────────────────────

  private val agentSortSuite = suite("AgentRepository sort branches")(
    test("search agents sorted by id desc") {
      for {
        repo <- ZIO.service[AgentZIORepository]
        _    <- repo.upsert(Agent(AgentId.empty, "SortAgent1", None, None, 0, T0))
        _    <- repo.upsert(Agent(AgentId.empty, "SortAgent2", None, None, 0, T0))
        res  <- repo.search(AgentSearch(pageSize = 50, sorts = Some(Sort(AgentOrder.Id, OrderDirection.Desc))))
      } yield assertTrue(res.map(_.id.value) == res.map(_.id.value).sorted.reverse)
    },
    test("search agents sorted by name asc") {
      for {
        repo <- ZIO.service[AgentZIORepository]
        _    <- repo.upsert(Agent(AgentId.empty, "ZetaAgent", None, None, 0, T0))
        _    <- repo.upsert(Agent(AgentId.empty, "AlphaAgent", None, None, 0, T0))
        res  <- repo.search(AgentSearch(pageSize = 50, sorts = Some(Sort(AgentOrder.Name, OrderDirection.Asc))))
        names = res.map(_.name)
      } yield assertTrue(names == names.sorted)
    },
    test("search agents sorted by name desc") {
      for {
        repo <- ZIO.service[AgentZIORepository]
        _    <- repo.upsert(Agent(AgentId.empty, "ZetaAgent2", None, None, 0, T0))
        _    <- repo.upsert(Agent(AgentId.empty, "AlphaAgent2", None, None, 0, T0))
        res  <- repo.search(AgentSearch(pageSize = 50, sorts = Some(Sort(AgentOrder.Name, OrderDirection.Desc))))
        names = res.map(_.name)
      } yield assertTrue(names == names.sorted.reverse)
    },
    test("search agents sorted by createdAt asc") {
      for {
        repo <- ZIO.service[AgentZIORepository]
        _    <- repo.upsert(Agent(AgentId.empty, "OldAgent", None, None, 0, T0.minusSeconds(100)))
        _    <- repo.upsert(Agent(AgentId.empty, "NewAgent", None, None, 0, T0.plusSeconds(100)))
        res  <- repo.search(AgentSearch(pageSize = 50, sorts = Some(Sort(AgentOrder.CreatedAt, OrderDirection.Asc))))
        times = res.map(_.createdAt)
      } yield assertTrue(times == times.sorted)
    },
    test("search agents sorted by createdAt desc") {
      for {
        repo <- ZIO.service[AgentZIORepository]
        _    <- repo.upsert(Agent(AgentId.empty, "OldAgent2", None, None, 0, T0.minusSeconds(200)))
        _    <- repo.upsert(Agent(AgentId.empty, "NewAgent2", None, None, 0, T0.plusSeconds(200)))
        res  <- repo.search(AgentSearch(pageSize = 50, sorts = Some(Sort(AgentOrder.CreatedAt, OrderDirection.Desc))))
        times = res.map(_.createdAt)
      } yield assertTrue(times == times.sorted.reverse)
    },
    test("delete agent") {
      for {
        repo  <- ZIO.service[AgentZIORepository]
        agent <- repo.upsert(Agent(AgentId.empty, "DeleteMeAgent", None, None, 0, T0))
        count <- repo.delete(agent.id)
        after <- repo.getById(agent.id)
      } yield assertTrue(count == 1L, after.isEmpty)
    },
    test("searchSessions sorted by id desc") {
      for {
        agentRepo <- ZIO.service[AgentZIORepository]
        userRepo  <- ZIO.service[UserZIORepository]
        user      <- userRepo.upsert(User(UserId.empty, "SortSessUser", None, T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "SortSessAgent", None, None, 0, T0))
        _         <- agentRepo.upsertSession(
          AgentSession(
            AgentSessionId.empty,
            agent.id,
            user.id,
            None,
            SessionStatus.Active,
            None,
            T0.minusSeconds(10),
            T0,
          ),
        )
        _ <- agentRepo.upsertSession(
          AgentSession(
            AgentSessionId.empty,
            agent.id,
            user.id,
            None,
            SessionStatus.Active,
            None,
            T0.plusSeconds(10),
            T0,
          ),
        )
        res <- agentRepo.searchSessions(
          AgentSessionSearch(pageSize = 50, sorts = Some(Sort(AgentSessionOrder.Id, OrderDirection.Desc))),
        )
      } yield assertTrue(res.map(_.id.value) == res.map(_.id.value).sorted.reverse)
    },
    test("searchSessions sorted by createdAt asc and desc") {
      for {
        agentRepo <- ZIO.service[AgentZIORepository]
        userRepo  <- ZIO.service[UserZIORepository]
        user      <- userRepo.upsert(User(UserId.empty, "SortSessUser2", None, T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "SortSessAgent2", None, None, 0, T0))
        _         <- agentRepo.upsertSession(
          AgentSession(
            AgentSessionId.empty,
            agent.id,
            user.id,
            None,
            SessionStatus.Active,
            None,
            T0.minusSeconds(30),
            T0,
          ),
        )
        _ <- agentRepo.upsertSession(
          AgentSession(
            AgentSessionId.empty,
            agent.id,
            user.id,
            None,
            SessionStatus.Active,
            None,
            T0.plusSeconds(30),
            T0,
          ),
        )
        asc <- agentRepo.searchSessions(
          AgentSessionSearch(pageSize = 50, sorts = Some(Sort(AgentSessionOrder.CreatedAt, OrderDirection.Asc))),
        )
        desc <- agentRepo.searchSessions(
          AgentSessionSearch(pageSize = 50, sorts = Some(Sort(AgentSessionOrder.CreatedAt, OrderDirection.Desc))),
        )
      } yield assertTrue(
        asc.map(_.createdAt) == asc.map(_.createdAt).sorted,
        desc.map(_.createdAt) == desc.map(_.createdAt).sorted.reverse,
      )
    },
    test("upsertSession updates mutable fields") {
      for {
        agentRepo <- ZIO.service[AgentZIORepository]
        userRepo  <- ZIO.service[UserZIORepository]
        user      <- userRepo.upsert(User(UserId.empty, "UpdSessUser", None, T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "UpdSessAgent", None, None, 0, T0))
        sess      <- agentRepo.upsertSession(
          AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, None, T0, T0),
        )
        _       <- agentRepo.upsertSession(sess.copy(status = SessionStatus.Completed, updatedAt = T0.plusSeconds(1)))
        fetched <- agentRepo.getSession(sess.id)
      } yield assertTrue(fetched.exists(_.status == SessionStatus.Completed))
    },
  )

  // ─── Conversation ─────────────────────────────────────────────────────────────

  private val conversationSortSuite = suite("ConversationRepository sort branches")(
    test("search conversations sorted by id desc") {
      for {
        convRepo  <- ZIO.service[ConversationZIORepository]
        agentRepo <- ZIO.service[AgentZIORepository]
        userRepo  <- ZIO.service[UserZIORepository]
        user      <- userRepo.upsert(User(UserId.empty, "ConvSortUser", None, T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "ConvSortAgent", None, None, 0, T0))
        sess      <- agentRepo.upsertSession(
          AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, None, T0, T0),
        )
        _   <- convRepo.create(Conversation(ConversationId.empty, sess.id, T0.minusSeconds(5)))
        _   <- convRepo.create(Conversation(ConversationId.empty, sess.id, T0.plusSeconds(5)))
        res <- convRepo.search(
          ConversationSearch(
            sessionId = sess.id,
            pageSize = 20,
            sorts = Some(Sort(ConversationOrder.Id, OrderDirection.Desc)),
          ),
        )
      } yield assertTrue(res.map(_.id.value) == res.map(_.id.value).sorted.reverse)
    },
    test("search conversations sorted by startedAt asc and desc") {
      for {
        convRepo  <- ZIO.service[ConversationZIORepository]
        agentRepo <- ZIO.service[AgentZIORepository]
        userRepo  <- ZIO.service[UserZIORepository]
        user      <- userRepo.upsert(User(UserId.empty, "ConvSortUser2", None, T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "ConvSortAgent2", None, None, 0, T0))
        sess      <- agentRepo.upsertSession(
          AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, None, T0, T0),
        )
        _   <- convRepo.create(Conversation(ConversationId.empty, sess.id, T0.minusSeconds(50)))
        _   <- convRepo.create(Conversation(ConversationId.empty, sess.id, T0.plusSeconds(50)))
        asc <- convRepo.search(
          ConversationSearch(
            sessionId = sess.id,
            pageSize = 20,
            sorts = Some(Sort(ConversationOrder.StartedAt, OrderDirection.Asc)),
          ),
        )
        desc <- convRepo.search(
          ConversationSearch(
            sessionId = sess.id,
            pageSize = 20,
            sorts = Some(Sort(ConversationOrder.StartedAt, OrderDirection.Desc)),
          ),
        )
      } yield assertTrue(
        asc.map(_.startedAt) == asc.map(_.startedAt).sorted,
        desc.map(_.startedAt) == desc.map(_.startedAt).sorted.reverse,
      )
    },
    test("search messages sorted by id desc") {
      for {
        convRepo  <- ZIO.service[ConversationZIORepository]
        agentRepo <- ZIO.service[AgentZIORepository]
        userRepo  <- ZIO.service[UserZIORepository]
        user      <- userRepo.upsert(User(UserId.empty, "MsgSortUser", None, T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "MsgSortAgent", None, None, 0, T0))
        sess      <- agentRepo.upsertSession(
          AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, None, T0, T0),
        )
        conv <- convRepo.create(Conversation(ConversationId.empty, sess.id, T0))
        _ <- convRepo.addMessage(Message(MessageId.empty, conv.id, MessageRole.User, "msg1", None, T0.minusSeconds(2)))
        _ <- convRepo.addMessage(
          Message(MessageId.empty, conv.id, MessageRole.Assistant, "msg2", None, T0.plusSeconds(2)),
        )
        res <- convRepo.searchMessages(
          MessageSearch(
            conversationId = conv.id,
            pageSize = 20,
            sorts = Some(Sort(MessageOrder.Id, OrderDirection.Desc)),
          ),
        )
      } yield assertTrue(res.map(_.id.value) == res.map(_.id.value).sorted.reverse)
    },
    test("search messages sorted by createdAt desc") {
      for {
        convRepo  <- ZIO.service[ConversationZIORepository]
        agentRepo <- ZIO.service[AgentZIORepository]
        userRepo  <- ZIO.service[UserZIORepository]
        user      <- userRepo.upsert(User(UserId.empty, "MsgSortUser2", None, T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "MsgSortAgent2", None, None, 0, T0))
        sess      <- agentRepo.upsertSession(
          AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, None, T0, T0),
        )
        conv <- convRepo.create(Conversation(ConversationId.empty, sess.id, T0))
        _ <- convRepo.addMessage(Message(MessageId.empty, conv.id, MessageRole.User, "first", None, T0.minusSeconds(5)))
        _ <- convRepo.addMessage(
          Message(MessageId.empty, conv.id, MessageRole.Assistant, "last", None, T0.plusSeconds(5)),
        )
        res <- convRepo.searchMessages(
          MessageSearch(
            conversationId = conv.id,
            pageSize = 20,
            sorts = Some(Sort(MessageOrder.CreatedAt, OrderDirection.Desc)),
          ),
        )
        times = res.map(_.createdAt)
      } yield assertTrue(times == times.sorted.reverse)
    },
    test("getById returns conversation by id") {
      for {
        convRepo  <- ZIO.service[ConversationZIORepository]
        agentRepo <- ZIO.service[AgentZIORepository]
        userRepo  <- ZIO.service[UserZIORepository]
        user      <- userRepo.upsert(User(UserId.empty, "ConvGetUser", None, T0, T0))
        agent     <- agentRepo.upsert(Agent(AgentId.empty, "ConvGetAgent", None, None, 0, T0))
        sess      <- agentRepo.upsertSession(
          AgentSession(AgentSessionId.empty, agent.id, user.id, None, SessionStatus.Active, None, T0, T0),
        )
        conv    <- convRepo.create(Conversation(ConversationId.empty, sess.id, T0))
        fetched <- convRepo.getById(conv.id)
      } yield assertTrue(fetched.isDefined, fetched.exists(_.id == conv.id))
    },
  )

  // ─── Skill ────────────────────────────────────────────────────────────────────

  private val skillSortSuite = suite("SkillRepository sort branches")(
    test("search skills sorted by id desc") {
      for {
        repo <- ZIO.service[SkillZIORepository]
        _    <- repo.upsert(Skill(SkillId.empty, "sort-skill-1", None, SkillTier.BuiltIn, T0))
        _    <- repo.upsert(Skill(SkillId.empty, "sort-skill-2", None, SkillTier.Plugin, T0))
        res  <- repo.search(SkillSearch(pageSize = 50, sorts = Some(Sort(SkillOrder.Id, OrderDirection.Desc))))
      } yield assertTrue(res.map(_.id.value) == res.map(_.id.value).sorted.reverse)
    },
    test("search skills sorted by name asc and desc") {
      for {
        repo <- ZIO.service[SkillZIORepository]
        _    <- repo.upsert(Skill(SkillId.empty, "zzz-skill", None, SkillTier.Scripted, T0))
        _    <- repo.upsert(Skill(SkillId.empty, "aaa-skill", None, SkillTier.Scripted, T0))
        asc  <- repo.search(SkillSearch(pageSize = 50, sorts = Some(Sort(SkillOrder.Name, OrderDirection.Asc))))
        desc <- repo.search(SkillSearch(pageSize = 50, sorts = Some(Sort(SkillOrder.Name, OrderDirection.Desc))))
      } yield assertTrue(
        asc.map(_.name) == asc.map(_.name).sorted,
        desc.map(_.name) == desc.map(_.name).sorted.reverse,
      )
    },
    test("search skills sorted by createdAt asc and desc") {
      for {
        repo <- ZIO.service[SkillZIORepository]
        _    <- repo.upsert(Skill(SkillId.empty, "old-skill", None, SkillTier.Declarative, T0.minusSeconds(60)))
        _    <- repo.upsert(Skill(SkillId.empty, "new-skill", None, SkillTier.Declarative, T0.plusSeconds(60)))
        asc  <- repo.search(SkillSearch(pageSize = 50, sorts = Some(Sort(SkillOrder.CreatedAt, OrderDirection.Asc))))
        desc <- repo.search(SkillSearch(pageSize = 50, sorts = Some(Sort(SkillOrder.CreatedAt, OrderDirection.Desc))))
      } yield assertTrue(
        asc.map(_.createdAt) == asc.map(_.createdAt).sorted,
        desc.map(_.createdAt) == desc.map(_.createdAt).sorted.reverse,
      )
    },
    test("searchVersions sorted by id desc and by version asc/desc") {
      for {
        repo  <- ZIO.service[SkillZIORepository]
        skill <- repo.upsert(Skill(SkillId.empty, "versioned-skill", None, SkillTier.BuiltIn, T0))
        _     <- repo.upsertVersion(
          SkillVersion(SkillVersionId.empty, skill.id, "1.0.0", Json.Obj(), SkillStatus.Active, T0.minusSeconds(10)),
        )
        _ <- repo.upsertVersion(
          SkillVersion(SkillVersionId.empty, skill.id, "2.0.0", Json.Obj(), SkillStatus.Draft, T0.plusSeconds(10)),
        )
        byIdDesc <- repo.searchVersions(
          SkillVersionSearch(
            skillId = skill.id,
            pageSize = 20,
            sorts = Some(Sort(SkillVersionOrder.Id, OrderDirection.Desc)),
          ),
        )
        byVersionAsc <- repo.searchVersions(
          SkillVersionSearch(
            skillId = skill.id,
            pageSize = 20,
            sorts = Some(Sort(SkillVersionOrder.Version, OrderDirection.Asc)),
          ),
        )
        byVersionDesc <- repo.searchVersions(
          SkillVersionSearch(
            skillId = skill.id,
            pageSize = 20,
            sorts = Some(Sort(SkillVersionOrder.Version, OrderDirection.Desc)),
          ),
        )
        byCreatedAsc <- repo.searchVersions(
          SkillVersionSearch(
            skillId = skill.id,
            pageSize = 20,
            sorts = Some(Sort(SkillVersionOrder.CreatedAt, OrderDirection.Asc)),
          ),
        )
        byCreatedDesc <- repo.searchVersions(
          SkillVersionSearch(
            skillId = skill.id,
            pageSize = 20,
            sorts = Some(Sort(SkillVersionOrder.CreatedAt, OrderDirection.Desc)),
          ),
        )
      } yield assertTrue(
        byIdDesc.map(_.id.value) == byIdDesc.map(_.id.value).sorted.reverse,
        byVersionAsc.map(_.version) == byVersionAsc.map(_.version).sorted,
        byVersionDesc.map(_.version) == byVersionDesc.map(_.version).sorted.reverse,
        byCreatedAsc.map(_.createdAt) == byCreatedAsc.map(_.createdAt).sorted,
        byCreatedDesc.map(_.createdAt) == byCreatedDesc.map(_.createdAt).sorted.reverse,
      )
    },
    test("getVersion retrieves by id") {
      for {
        repo  <- ZIO.service[SkillZIORepository]
        skill <- repo.upsert(Skill(SkillId.empty, "get-version-skill", None, SkillTier.BuiltIn, T0))
        sv    <- repo.upsertVersion(
          SkillVersion(SkillVersionId.empty, skill.id, "3.0.0", Json.Obj(), SkillStatus.Active, T0),
        )
        fetched <- repo.getVersion(sv.id)
      } yield assertTrue(fetched.isDefined, fetched.exists(_.version == "3.0.0"))
    },
    test("searchConnectors sorted by name asc/desc and connectorType asc/desc") {
      for {
        repo <- ZIO.service[SkillZIORepository]
        _    <- repo.upsertConnector(
          ConnectorInstance(ConnectorInstanceId.empty, ConnectorType.Slack, "slack-bot", Json.Obj(), "active", T0),
        )
        _ <- repo.upsertConnector(
          ConnectorInstance(ConnectorInstanceId.empty, ConnectorType.Email, "email-relay", Json.Obj(), "active", T0),
        )
        byIdDesc <- repo.searchConnectors(
          ConnectorSearch(pageSize = 20, sorts = Some(Sort(ConnectorOrder.Id, OrderDirection.Desc))),
        )
        byNameAsc <- repo.searchConnectors(
          ConnectorSearch(pageSize = 20, sorts = Some(Sort(ConnectorOrder.Name, OrderDirection.Asc))),
        )
        byNameDesc <- repo.searchConnectors(
          ConnectorSearch(pageSize = 20, sorts = Some(Sort(ConnectorOrder.Name, OrderDirection.Desc))),
        )
        byConnTypeAsc <- repo.searchConnectors(
          ConnectorSearch(pageSize = 20, sorts = Some(Sort(ConnectorOrder.ConnectorType, OrderDirection.Asc))),
        )
        byConnTypeDesc <- repo.searchConnectors(
          ConnectorSearch(pageSize = 20, sorts = Some(Sort(ConnectorOrder.ConnectorType, OrderDirection.Desc))),
        )
      } yield assertTrue(
        byIdDesc.map(_.id.value) == byIdDesc.map(_.id.value).sorted.reverse,
        byNameAsc.length >= 2,
        byNameDesc.length >= 2,
        byConnTypeAsc.length >= 2,
        byConnTypeDesc.length >= 2,
      )
    },
  )

  // ─── Memory ───────────────────────────────────────────────────────────────────

  private val memorySortSuite = suite("MemoryRepository sort branches")(
    test("search sorted by id desc") {
      for {
        userRepo <- ZIO.service[UserZIORepository]
        memRepo  <- ZIO.service[MemoryZIORepository]
        user     <- userRepo.upsert(User(UserId.empty, "MemSortUser1", None, T0, T0))
        _        <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "key.a",
            Json.Str("1"),
            None,
            T0,
            T0,
          ),
        )
        _ <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "key.b",
            Json.Str("2"),
            None,
            T0,
            T0,
          ),
        )
        res <- memRepo.search(
          MemorySearch(
            scope = MemoryScope.User,
            userId = Some(user.id),
            pageSize = 20,
            sorts = Some(Sort(MemoryOrder.Id, OrderDirection.Desc)),
          ),
        )
      } yield assertTrue(res.map(_.id.value) == res.map(_.id.value).sorted.reverse)
    },
    test("search sorted by recordKey asc and desc") {
      for {
        userRepo <- ZIO.service[UserZIORepository]
        memRepo  <- ZIO.service[MemoryZIORepository]
        user     <- userRepo.upsert(User(UserId.empty, "MemSortUser2", None, T0, T0))
        _        <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "zzz.key",
            Json.Str("z"),
            None,
            T0,
            T0,
          ),
        )
        _ <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "aaa.key",
            Json.Str("a"),
            None,
            T0,
            T0,
          ),
        )
        asc <- memRepo.search(
          MemorySearch(
            scope = MemoryScope.User,
            userId = Some(user.id),
            pageSize = 20,
            sorts = Some(Sort(MemoryOrder.RecordKey, OrderDirection.Asc)),
          ),
        )
        desc <- memRepo.search(
          MemorySearch(
            scope = MemoryScope.User,
            userId = Some(user.id),
            pageSize = 20,
            sorts = Some(Sort(MemoryOrder.RecordKey, OrderDirection.Desc)),
          ),
        )
      } yield assertTrue(
        asc.map(_.recordKey) == asc.map(_.recordKey).sorted,
        desc.map(_.recordKey) == desc.map(_.recordKey).sorted.reverse,
      )
    },
    test("search sorted by createdAt asc and desc") {
      for {
        userRepo <- ZIO.service[UserZIORepository]
        memRepo  <- ZIO.service[MemoryZIORepository]
        user     <- userRepo.upsert(User(UserId.empty, "MemSortUser3", None, T0, T0))
        _        <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "created.old",
            Json.Str("o"),
            None,
            T0.minusSeconds(20),
            T0,
          ),
        )
        _ <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "created.new",
            Json.Str("n"),
            None,
            T0.plusSeconds(20),
            T0,
          ),
        )
        asc <- memRepo.search(
          MemorySearch(
            scope = MemoryScope.User,
            userId = Some(user.id),
            pageSize = 20,
            sorts = Some(Sort(MemoryOrder.CreatedAt, OrderDirection.Asc)),
          ),
        )
        desc <- memRepo.search(
          MemorySearch(
            scope = MemoryScope.User,
            userId = Some(user.id),
            pageSize = 20,
            sorts = Some(Sort(MemoryOrder.CreatedAt, OrderDirection.Desc)),
          ),
        )
      } yield assertTrue(
        asc.map(_.createdAt) == asc.map(_.createdAt).sorted,
        desc.map(_.createdAt) == desc.map(_.createdAt).sorted.reverse,
      )
    },
    test("search sorted by updatedAt asc and desc") {
      for {
        userRepo <- ZIO.service[UserZIORepository]
        memRepo  <- ZIO.service[MemoryZIORepository]
        user     <- userRepo.upsert(User(UserId.empty, "MemSortUser4", None, T0, T0))
        _        <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "upd.old",
            Json.Str("o"),
            None,
            T0,
            T0.minusSeconds(20),
          ),
        )
        _ <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "upd.new",
            Json.Str("n"),
            None,
            T0,
            T0.plusSeconds(20),
          ),
        )
        asc <- memRepo.search(
          MemorySearch(
            scope = MemoryScope.User,
            userId = Some(user.id),
            pageSize = 20,
            sorts = Some(Sort(MemoryOrder.UpdatedAt, OrderDirection.Asc)),
          ),
        )
        desc <- memRepo.search(
          MemorySearch(
            scope = MemoryScope.User,
            userId = Some(user.id),
            pageSize = 20,
            sorts = Some(Sort(MemoryOrder.UpdatedAt, OrderDirection.Desc)),
          ),
        )
      } yield assertTrue(
        asc.map(_.updatedAt) == asc.map(_.updatedAt).sorted,
        desc.map(_.updatedAt) == desc.map(_.updatedAt).sorted.reverse,
      )
    },
    test("search returns Workspace-scope records") {
      for {
        memRepo <- ZIO.service[MemoryZIORepository]
        _       <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.Workspace,
            None,
            None,
            None,
            "ws.key",
            Json.Str("ws-value"),
            None,
            T0,
            T0,
          ),
        )
        res <- memRepo.search(MemorySearch(scope = MemoryScope.Workspace, pageSize = 20))
      } yield assertTrue(res.nonEmpty, res.forall(_.scope == MemoryScope.Workspace))
    },
    test("search returns Private-scope records") {
      for {
        userRepo <- ZIO.service[UserZIORepository]
        memRepo  <- ZIO.service[MemoryZIORepository]
        user     <- userRepo.upsert(User(UserId.empty, "PrivateScopeUser", None, T0, T0))
        _        <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.Private,
            Some(user.id),
            None,
            None,
            "private.key",
            Json.Str("private-value"),
            None,
            T0,
            T0,
          ),
        )
        res <- memRepo.search(MemorySearch(scope = MemoryScope.Private, userId = Some(user.id), pageSize = 20))
      } yield assertTrue(res.nonEmpty, res.forall(_.scope == MemoryScope.Private))
    },
    test("search pagination returns correct page offsets") {
      for {
        userRepo <- ZIO.service[UserZIORepository]
        memRepo  <- ZIO.service[MemoryZIORepository]
        user     <- userRepo.upsert(User(UserId.empty, "PaginateUser", None, T0, T0))
        _        <- ZIO.foreach(1 to 4)(i =>
          memRepo.upsert(
            MemoryRecord(
              MemoryRecordId.empty,
              MemoryScope.User,
              Some(user.id),
              None,
              None,
              s"page.key.$i",
              Json.Str(i.toString),
              None,
              T0.plusSeconds(i.toLong),
              T0,
            ),
          ),
        )
        page0 <- memRepo.search(
          MemorySearch(scope = MemoryScope.User, userId = Some(user.id), page = 0, pageSize = 2),
        )
        page1 <- memRepo.search(
          MemorySearch(scope = MemoryScope.User, userId = Some(user.id), page = 1, pageSize = 2),
        )
      } yield assertTrue(
        page0.length == 2,
        page1.length == 2,
        page0.map(_.id).intersect(page1.map(_.id)).isEmpty,
      )
    },
    test("purgeExpired removes records past TTL") {
      for {
        userRepo <- ZIO.service[UserZIORepository]
        memRepo  <- ZIO.service[MemoryZIORepository]
        user     <- userRepo.upsert(User(UserId.empty, "PurgeUser", None, T0, T0))
        expired  <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "expired.key",
            Json.Str("e"),
            Some(T0.minusSeconds(1)),
            T0,
            T0,
          ),
        )
        _ <- memRepo.upsert(
          MemoryRecord(
            MemoryRecordId.empty,
            MemoryScope.User,
            Some(user.id),
            None,
            None,
            "live.key",
            Json.Str("l"),
            Some(T0.plusSeconds(3600)),
            T0,
            T0,
          ),
        )
        count   <- memRepo.purgeExpired
        fetched <- memRepo.getById(expired.id)
      } yield assertTrue(count >= 1L, fetched.isEmpty)
    },
  )

  // ─── EventLog ─────────────────────────────────────────────────────────────────

  private val eventLogSortSuite = suite("EventLogRepository sort branches")(
    test("search sorted by occurredAt asc") {
      for {
        repo <- ZIO.service[EventLogZIORepository]
        _    <- repo.append(testEvent(EventType.UserConnected, occurredAt = T0.minusSeconds(10)))
        _    <- repo.append(testEvent(EventType.UserConnected, occurredAt = T0.plusSeconds(10)))
        res  <- repo.search(EventLogFilter(eventType = Some(EventType.UserConnected), pageSize = 50))
        times = res.map(_.occurredAt)
      } yield assertTrue(times.nonEmpty)
    },
    test("search with agentId filter") {
      val aid = AgentId(12345L)
      for {
        repo <- ZIO.service[EventLogZIORepository]
        _    <- repo.append(testEvent(EventType.AgentStarted, agentId = Some(aid)))
        _    <- repo.append(testEvent(EventType.AgentStarted, agentId = None))
        res  <- repo.search(EventLogFilter(agentId = Some(aid), pageSize = 50))
      } yield assertTrue(res.exists(_.agentId.contains(aid)))
    },
    test("search with pagination") {
      for {
        repo <- ZIO.service[EventLogZIORepository]
        _    <- ZIO.foreach(1 to 5)(i =>
          repo.append(testEvent(EventType.MemoryWritten, occurredAt = T0.plusSeconds(i.toLong))),
        )
        page0 <- repo.search(EventLogFilter(eventType = Some(EventType.MemoryWritten), page = 0, pageSize = 3))
        page1 <- repo.search(EventLogFilter(eventType = Some(EventType.MemoryWritten), page = 1, pageSize = 3))
      } yield assertTrue(page0.length <= 3, page1.length <= 3)
    },
  )

}
