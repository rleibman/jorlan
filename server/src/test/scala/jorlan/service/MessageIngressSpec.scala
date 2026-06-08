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
import jorlan.connector.*
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.testing.*
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.time.Instant

object MessageIngressSpec extends ZIOSpecDefault {

  private val now = Instant.parse("2026-01-01T00:00:00Z")

  private def makeMsg(
    channelUserId: String = "tg-42",
    chatRef:       String = "tg-42",
  ): InboundMessage =
    InboundMessage(
      channelType = ChannelType.Telegram,
      channelUserId = channelUserId,
      chatRef = chatRef,
      chatKind = ChatKind.Private,
      content = "hello",
      receivedAt = now,
    )

  private val knownUser = User(UserId(1L), "Alice", "alice@example.com", now, now)

  private def stubUserRepo(result: Option[User]): ULayer[UserZIORepository] =
    ZLayer.fromZIO(
      InMemoryRepositories.InMemoryUserRepo.make.flatMap { base =>
        result.fold(ZIO.unit)(u => base.upsert(u).orDie.unit).as {
          new UserZIORepository {
            override def getById(id:                  UserId) = base.getById(id)
            override def search(s:                    UserSearch) = base.search(s)
            override def upsert(user:                 User) = base.upsert(user)
            override def deactivate(id:               UserId) = base.deactivate(id)
            override def getChannelIdentities(userId: UserId) = ZIO.succeed(Nil)
            override def upsertChannelIdentity(ci:    ChannelIdentity) = ZIO.succeed(ci)
            override def deleteChannelIdentity(id:    ChannelIdentityId) = ZIO.succeed(0L)
            override def login(
              email:    String,
              password: String,
            ) = ZIO.none
            override def userByEmail(email: String) = ZIO.none
            override def changePassword(
              id: UserId,
              np: String,
            ) = ZIO.unit
            override def userByChannelIdentity(
              ct:   ChannelType,
              cuid: String,
            ) = ZIO.succeed(result.filter(_ => ct == ChannelType.Telegram))
          }
        }
      },
    )

  private val knownUserRepo:   ULayer[UserZIORepository] = stubUserRepo(Some(knownUser))
  private val unknownUserRepo: ULayer[UserZIORepository] = stubUserRepo(None)

  private val allowAll: ULayer[CapabilityEvaluator] =
    ZLayer.succeed((_: CapabilityRequest) => ZIO.succeed(EvaluationResult.ResourcePermissionAllows))

  private val denyAll: ULayer[CapabilityEvaluator] =
    ZLayer.succeed((_: CapabilityRequest) => ZIO.succeed(EvaluationResult.DefaultDeny))

  private val explicitDenyAll: ULayer[CapabilityEvaluator] =
    ZLayer.succeed((_: CapabilityRequest) => ZIO.succeed(EvaluationResult.ExplicitDeny))

  private def stubSessionMgr(agentRepo: AgentZIORepository): AgentSessionManager =
    new AgentSessionManager {
      override def createSession(
        userId:  UserId,
        modelId: Option[ModelId],
      ): IO[JorlanError, AgentSession] =
        Clock.instant.flatMap { t =>
          agentRepo
            .upsertSession(
              AgentSession(AgentSessionId.empty, AgentId(1L), userId, None, SessionStatus.Active, modelId, None, t, t),
            )
            .mapError(JorlanError(_))
        }
      override def getSession(id: AgentSessionId): IO[JorlanError, Option[AgentSession]] =
        agentRepo.getSession(id).mapError(JorlanError(_))
      override def suspendSession(id:   AgentSessionId): IO[JorlanError, AgentSession] = ZIO.fail(JorlanError("stub"))
      override def terminateSession(id: AgentSessionId): IO[JorlanError, AgentSession] = ZIO.fail(JorlanError("stub"))
      override def listSessions(
        userId:   UserId,
        page:     Int,
        pageSize: Int,
      ): IO[JorlanError, List[AgentSession]] =
        agentRepo.searchSessions(AgentSessionSearch(userId = Some(userId))).mapError(JorlanError(_))
    }

  private class RecordingAgentRunner(dispatched: Ref[List[(AgentSessionId, String)]]) extends AgentRunner {

    override def processMessage(
      sessionId: AgentSessionId,
      content:   String,
      actorId:   Option[UserId],
    ): IO[JorlanError, Unit] =
      dispatched.update(_ :+ (sessionId, content))
    override def subscribeToSession(
      sessionId:    AgentSessionId,
      connectionId: ConnectionId,
    ): UIO[ZStream[Any, Nothing, ResponseChunk]] =
      ZIO.succeed(ZStream.empty)

  }

  private type Env = UserZIORepository & AgentZIORepository & EventLogZIORepository & CapabilityEvaluator

  private def baseLayer(
    userRepo: ULayer[UserZIORepository],
    cap:      ULayer[CapabilityEvaluator],
  ): ULayer[Env] =
    userRepo ++ InMemoryRepositories.InMemoryAgentRepo.layer ++ InMemoryRepositories.InMemoryEventLogRepo.layer ++ cap

  private def buildIngress(
    userRepo:   UserZIORepository,
    agentRepo:  AgentZIORepository,
    eventLog:   EventLogZIORepository,
    evaluator:  CapabilityEvaluator,
    dispatched: Ref[List[(AgentSessionId, String)]],
  ): MessageIngressImpl =
    MessageIngressImpl(
      userRepo,
      agentRepo,
      eventLog,
      evaluator,
      stubSessionMgr(agentRepo),
      RecordingAgentRunner(dispatched),
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MessageIngressImpl")(
      test("dispatches known user's message to AgentRunner") {
        for {
          dispatched <- Ref.make(List.empty[(AgentSessionId, String)])
          userRepo   <- ZIO.service[UserZIORepository]
          agentRepo  <- ZIO.service[AgentZIORepository]
          eventLog   <- ZIO.service[EventLogZIORepository]
          evaluator  <- ZIO.service[CapabilityEvaluator]
          ingress = buildIngress(userRepo, agentRepo, eventLog, evaluator, dispatched)
          _      <- ingress.receive(makeMsg())
          result <- dispatched.get
        } yield assertTrue(result.nonEmpty, result.head._2 == "hello")
      }.provide(baseLayer(knownUserRepo, allowAll)),
      test("drops message when sender is unrecognized (Reject policy)") {
        for {
          dispatched <- Ref.make(List.empty[(AgentSessionId, String)])
          userRepo   <- ZIO.service[UserZIORepository]
          agentRepo  <- ZIO.service[AgentZIORepository]
          eventLog   <- ZIO.service[EventLogZIORepository]
          evaluator  <- ZIO.service[CapabilityEvaluator]
          ingress = buildIngress(userRepo, agentRepo, eventLog, evaluator, dispatched)
          _      <- ingress.receive(makeMsg(channelUserId = "unknown-9999", chatRef = "unknown-9999"))
          result <- dispatched.get
        } yield assertTrue(result.isEmpty)
      }.provide(baseLayer(unknownUserRepo, allowAll)),
      test("drops message when capability gate denies (DefaultDeny)") {
        for {
          dispatched <- Ref.make(List.empty[(AgentSessionId, String)])
          userRepo   <- ZIO.service[UserZIORepository]
          agentRepo  <- ZIO.service[AgentZIORepository]
          eventLog   <- ZIO.service[EventLogZIORepository]
          evaluator  <- ZIO.service[CapabilityEvaluator]
          ingress = buildIngress(userRepo, agentRepo, eventLog, evaluator, dispatched)
          _      <- ingress.receive(makeMsg())
          result <- dispatched.get
        } yield assertTrue(result.isEmpty)
      }.provide(baseLayer(knownUserRepo, denyAll)),
      test("drops message and logs event when capability gate ExplicitDeny") {
        for {
          dispatched <- Ref.make(List.empty[(AgentSessionId, String)])
          userRepo   <- ZIO.service[UserZIORepository]
          agentRepo  <- ZIO.service[AgentZIORepository]
          eventLog   <- ZIO.service[EventLogZIORepository]
          evaluator  <- ZIO.service[CapabilityEvaluator]
          ingress = buildIngress(userRepo, agentRepo, eventLog, evaluator, dispatched)
          _          <- ingress.receive(makeMsg())
          dispatches <- dispatched.get
          eventRepo  <- ZIO.service[EventLogZIORepository]
          logged     <- eventRepo.search(jorlan.service.EventLogFilter()).mapError(JorlanError(_))
        } yield assertTrue(dispatches.isEmpty, logged.nonEmpty)
      }.provide(baseLayer(knownUserRepo, explicitDenyAll)),
      test("resolveOrCreateSession reuses session for same chatRef") {
        for {
          dispatched <- Ref.make(List.empty[(AgentSessionId, String)])
          userRepo   <- ZIO.service[UserZIORepository]
          agentRepo  <- ZIO.service[AgentZIORepository]
          eventLog   <- ZIO.service[EventLogZIORepository]
          evaluator  <- ZIO.service[CapabilityEvaluator]
          ingress = buildIngress(userRepo, agentRepo, eventLog, evaluator, dispatched)
          _   <- ingress.receive(makeMsg(chatRef = "chat-1"))
          _   <- ingress.receive(makeMsg(chatRef = "chat-1"))
          all <- dispatched.get
        } yield {
          val sessionIds = all.map(_._1)
          assertTrue(
            sessionIds.length == 2,
            sessionIds.distinct.length == 1,
          )
        }
      }.provide(baseLayer(knownUserRepo, allowAll)),
      test("event log records inbound receipt with resolved sessionId") {
        for {
          dispatched <- Ref.make(List.empty[(AgentSessionId, String)])
          userRepo   <- ZIO.service[UserZIORepository]
          agentRepo  <- ZIO.service[AgentZIORepository]
          eventLog   <- ZIO.service[EventLogZIORepository]
          evaluator  <- ZIO.service[CapabilityEvaluator]
          ingress = buildIngress(userRepo, agentRepo, eventLog, evaluator, dispatched)
          _   <- ingress.receive(makeMsg())
          log <- eventLog.search(jorlan.service.EventLogFilter()).mapError(JorlanError(_))
        } yield {
          val entry = log.find(_.eventType == EventType.UserMessageReceived)
          assertTrue(
            entry.isDefined,
            entry.exists(_.sessionId.isDefined),
            entry.exists(_.actorId.isDefined),
          )
        }
      }.provide(baseLayer(knownUserRepo, allowAll)),
      test("Quarantine policy drops message but still logs event") {
        for {
          dispatched <- Ref.make(List.empty[(AgentSessionId, String)])
          userRepo   <- ZIO.service[UserZIORepository]
          agentRepo  <- ZIO.service[AgentZIORepository]
          eventLog   <- ZIO.service[EventLogZIORepository]
          evaluator  <- ZIO.service[CapabilityEvaluator]
          ingress = buildIngress(userRepo, agentRepo, eventLog, evaluator, dispatched)
          _ <- ingress.receive(
            makeMsg(channelUserId = "unknown-999", chatRef = "unknown-999"),
            UnrecognizedIdentityPolicy.Quarantine,
          )
          dispatches <- dispatched.get
          logged     <- eventLog.search(jorlan.service.EventLogFilter()).mapError(JorlanError(_))
        } yield assertTrue(dispatches.isEmpty, logged.nonEmpty)
      }.provide(baseLayer(unknownUserRepo, allowAll)),
    )

}
