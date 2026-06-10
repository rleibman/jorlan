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
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object NotificationRouterSpec extends ZIOSpecDefault {

  private val ctx = InvocationContext(UserId(1L), None, None)
  private val now = Instant.now()

  private def makeUser(
    id:      Long,
    display: String,
  ): User =
    User(UserId(id), display, s"user$id@example.com", now, now)

  private def makeCi(
    userId:        Long,
    channelType:   ChannelType,
    channelUserId: String,
  ): ChannelIdentity =
    ChannelIdentity(ChannelIdentityId.empty, UserId(userId), channelType, channelUserId, verified = true, None, now)

  /** A fake [[ConnectorSkill]] that records all `invoke` calls. */
  private def fakeConnector(
    ct:  ConnectorType,
    ref: Ref[List[(String, Json)]],
  ): ConnectorSkill =
    new ConnectorSkill {
      override val connectorType: ConnectorType = ct
      override val instanceId:    ConnectorInstanceId = ConnectorInstanceId(1L)
      override val descriptor:    SkillDescriptor = SkillDescriptor(ct.toString.toLowerCase, SkillTier.BuiltIn, Nil)
      override def start:         IO[JorlanError, Unit] = ZIO.unit
      override def stop:          IO[JorlanError, Unit] = ZIO.unit
      override def invoke(
        ctx:  InvocationContext,
        tool: String,
        args: Json,
      ): IO[JorlanError, Json] =
        ref.update(_ :+ (tool -> args)) *> ZIO.succeed(Json.Str("ok"))
    }

  /** A user repo whose channel identities come from a fixed map. */
  private def userRepoWithIdentities(
    users:      Map[Long, User],
    identities: Map[Long, List[ChannelIdentity]],
  ): ZIOUserRepository =
    new ZIOUserRepository {
      override def getById(id:    UserId):               RepositoryTask[Option[User]] = ZIO.succeed(users.get(id.value))
      override def search(s:      UserSearch):           RepositoryTask[List[User]] = ZIO.succeed(users.values.toList)
      override def upsert(user:   User):                 RepositoryTask[User] = ZIO.die(RuntimeException("stub"))
      override def deactivate(id: UserId):               RepositoryTask[Long] = ZIO.die(RuntimeException("stub"))
      override def getChannelIdentities(userId: UserId): RepositoryTask[List[ChannelIdentity]] =
        ZIO.succeed(identities.getOrElse(userId.value, Nil))
      override def upsertChannelIdentity(ci: ChannelIdentity): RepositoryTask[ChannelIdentity] =
        ZIO.die(RuntimeException("stub"))
      override def deleteChannelIdentity(id: ChannelIdentityId): RepositoryTask[Long] =
        ZIO.die(RuntimeException("stub"))
      override def login(
        email:    String,
        password: String,
      ): RepositoryTask[Option[User]] =
        ZIO.die(RuntimeException("stub"))
      override def userByEmail(email: String): RepositoryTask[Option[User]] = ZIO.none
      override def changePassword(
        id:          UserId,
        newPassword: String,
      ): RepositoryTask[Unit] =
        ZIO.die(RuntimeException("stub"))
      override def userByChannelIdentity(
        channelType:   ChannelType,
        channelUserId: String,
      ): RepositoryTask[Option[User]] = ZIO.none
    }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("NotificationRouter")(
      test("notifyUser succeeds via Telegram identity") {
        for {
          callsRef <- Ref.make(List.empty[(String, Json)])
          connector = fakeConnector(ConnectorType.Telegram, callsRef)
          user = makeUser(1L, "Alice")
          ci = makeCi(1L, ChannelType.Telegram, "telegram-123")
          repo = InMemoryRepositories.live(
            userRepoOpt = Some(userRepoWithIdentities(Map(1L -> user), Map(1L -> List(ci)))),
          )
          result <- NotificationRouter
            .notifyUser(UserId(1L), "hello Alice", ctx)
            .provide(
              NotificationRouter.live,
              repo,
              ZLayer.succeed(ConnectorManager.fromSkills(List(connector))),
            )
          calls <- callsRef.get
        } yield assertTrue(
          result == Json.Str("ok"),
          calls.length == 1,
          calls.head._1 == "telegram.send_message",
        )
      },
      test("notifyUser returns error when user has no channel identities") {
        for {
          user = makeUser(2L, "Bob")
          repo = InMemoryRepositories.live(
            userRepoOpt = Some(userRepoWithIdentities(Map(2L -> user), Map.empty)),
          )
          result <- NotificationRouter
            .notifyUser(UserId(2L), "hello Bob", ctx)
            .provide(
              NotificationRouter.live,
              repo,
              ZLayer.succeed(ConnectorManager.empty),
            )
        } yield assertTrue(result.toString.contains("Error:"))
      },
      test("notifyUser prefers Telegram over other channels") {
        for {
          callsRef <- Ref.make(List.empty[(String, Json)])
          tgConnector = fakeConnector(ConnectorType.Telegram, callsRef)
          user = makeUser(3L, "Carol")
          ciSlack = makeCi(3L, ChannelType.Slack, "slack-xyz")
          ciTg = makeCi(3L, ChannelType.Telegram, "tg-999")
          repo = InMemoryRepositories.live(
            userRepoOpt = Some(userRepoWithIdentities(Map(3L -> user), Map(3L -> List(ciSlack, ciTg)))),
          )
          result <- NotificationRouter
            .notifyUser(UserId(3L), "hey Carol", ctx)
            .provide(
              NotificationRouter.live,
              repo,
              ZLayer.succeed(ConnectorManager.fromSkills(List(tgConnector))),
            )
          calls <- callsRef.get
        } yield assertTrue(
          result == Json.Str("ok"),
          calls.exists(_._1 == "telegram.send_message"),
        )
      },
      test("notifyChannel returns error when no connector registered for type") {
        for {
          repo = InMemoryRepositories.live()
          result <- NotificationRouter
            .notifyChannel("slack-abc", ChannelType.Slack, "test message", ctx)
            .provide(
              NotificationRouter.live,
              repo,
              ZLayer.succeed(ConnectorManager.empty),
            )
        } yield assertTrue(result.toString.contains("Error:"))
      },
      test("notifyChannel returns error for unmapped channel type (Shell)") {
        for {
          repo = InMemoryRepositories.live()
          result <- NotificationRouter
            .notifyChannel("user1", ChannelType.Shell, "msg", ctx)
            .provide(
              NotificationRouter.live,
              repo,
              ZLayer.succeed(ConnectorManager.empty),
            )
        } yield assertTrue(result.toString.contains("Error:"))
      },
      test("notifyChannel invokes connector's send_message tool") {
        for {
          callsRef <- Ref.make(List.empty[(String, Json)])
          connector = fakeConnector(ConnectorType.Telegram, callsRef)
          repo = InMemoryRepositories.live()
          result <- NotificationRouter
            .notifyChannel("tg-123", ChannelType.Telegram, "direct message", ctx)
            .provide(
              NotificationRouter.live,
              repo,
              ZLayer.succeed(ConnectorManager.fromSkills(List(connector))),
            )
          calls <- callsRef.get
        } yield assertTrue(
          result == Json.Str("ok"),
          calls.length == 1,
          calls.head._1 == "telegram.send_message",
        )
      },
    )

}
