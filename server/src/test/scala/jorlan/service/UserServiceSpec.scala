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
import jorlan.db.repository.{RepositoryError, RepositoryTask, UserZIORepository}
import jorlan.domain.*
import jorlan.service.TestFixtures.*
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object UserServiceSpec extends ZIOSpecDefault {

  private class InMemoryUserRepo(
    idGen: Ref[Long],
    store: Ref[Map[Long, User]],
  ) extends UserZIORepository {

    override def getById(id: UserId): RepositoryTask[Option[User]] =
      store.get.map(_.get(id.value))

    override def search(s: UserSearch): RepositoryTask[List[User]] =
      store.get.map { users =>
        users.values.toList
          .filter(u => s.active.forall(_ == u.active))
      }

    override def upsert(user: User): RepositoryTask[User] =
      for {
        id <- if (user.id == UserId.empty) idGen.updateAndGet(_ + 1) else ZIO.succeed(user.id.value)
        saved = user.copy(id = UserId(id))
        _ <- store.update(_.updated(id, saved))
      } yield saved

    override def deactivate(id: UserId): RepositoryTask[Long] =
      store.modify { m =>
        m.get(id.value) match {
          case None    => (0L, m)
          case Some(u) => (1L, m.updated(id.value, u.copy(active = false)))
        }
      }

    override def getChannelIdentities(userId: UserId): RepositoryTask[List[ChannelIdentity]] =
      ZIO.succeed(Nil)

    override def upsertChannelIdentity(ci: ChannelIdentity): RepositoryTask[ChannelIdentity] =
      ZIO.die(new RuntimeException("not implemented"))

    override def deleteChannelIdentity(id: ChannelIdentityId): RepositoryTask[Long] =
      ZIO.die(new RuntimeException("not implemented"))

    override def login(
      email:    String,
      password: String,
    ): RepositoryTask[Option[User]] = ZIO.succeed(None)

    override def userByEmail(email: String): RepositoryTask[Option[User]] =
      store.get.map(_.values.find(_.email.contains(email)))

    override def changePassword(
      id:          UserId,
      newPassword: String,
    ): RepositoryTask[Unit] = ZIO.unit

    override def userByChannelIdentity(
      channelType:   ChannelType,
      channelUserId: String,
    ): RepositoryTask[Option[User]] = ZIO.succeed(None)

  }

  private object InMemoryUserRepo {

    def make: UIO[InMemoryUserRepo] =
      for {
        idGen <- Ref.make(0L)
        store <- Ref.make(Map.empty[Long, User])
      } yield new InMemoryUserRepo(idGen, store)

  }

  private class InMemoryEventLogRepo(
    idGen: Ref[Long],
    store: Ref[List[EventLog[Json]]],
  ) extends jorlan.db.repository.EventLogZIORepository {

    override def append[R: zio.json.JsonEncoder](event: EventLog[R]): RepositoryTask[EventLog[R]] =
      for {
        nextId <- idGen.updateAndGet(_ + 1)
        saved = event.copy(id = EventLogId(nextId))
        _ <- store.update(saved.copy(resource = None) :: _)
      } yield saved

    override def search(filter: jorlan.service.EventLogFilter): RepositoryTask[List[EventLog[Json]]] =
      store.get.map(_.filter(e => filter.eventType.forall(_ == e.eventType)))

    override def replaySession(
      sessionId: AgentSessionId,
      limit:     Int,
    ): RepositoryTask[List[EventLog[Json]]] = ZIO.succeed(Nil)

  }

  private object InMemoryEventLogRepo {

    def make: UIO[InMemoryEventLogRepo] =
      for {
        idGen <- Ref.make(0L)
        store <- Ref.make(List.empty[EventLog[Json]])
      } yield new InMemoryEventLogRepo(idGen, store)

  }

  private def freshLayers: ULayer[UserService & EventLogService] = {
    val repoLayer: ULayer[jorlan.db.repository.UserZIORepository] =
      ZLayer(InMemoryUserRepo.make.map(r => r: jorlan.db.repository.UserZIORepository))
    val logRepoLayer: ULayer[jorlan.db.repository.EventLogZIORepository] =
      ZLayer(InMemoryEventLogRepo.make.map(r => r: jorlan.db.repository.EventLogZIORepository))
    val eventLogLayer: ULayer[EventLogService] = logRepoLayer >>> EventLogServiceImpl.live
    (repoLayer ++ eventLogLayer) >>> UserServiceImpl.live ++ eventLogLayer
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("UserService")(
      test("createUser assigns a new id and logs UserCreated") {
        for {
          svc    <- ZIO.service[UserService]
          log    <- ZIO.service[EventLogService]
          user   <- svc.createUser("Alice", Some("alice@example.com"))
          events <- log.query(EventLogFilter(eventType = Some(EventType.UserCreated)))
        } yield assertTrue(
          user.id.value > 0L,
          user.displayName == "Alice",
          events.exists(e => e.eventType == EventType.UserCreated),
        )
      }.provide(freshLayers),
      test("createUser without actorId still logs the event") {
        for {
          svc    <- ZIO.service[UserService]
          log    <- ZIO.service[EventLogService]
          _      <- svc.createUser("Bob", None)
          events <- log.query(EventLogFilter(eventType = Some(EventType.UserCreated)))
        } yield assertTrue(events.nonEmpty, events.head.actorId.isEmpty)
      }.provide(freshLayers),
      test("createUser with actorId records the actor") {
        val actorId = UserId(42L)
        for {
          svc    <- ZIO.service[UserService]
          log    <- ZIO.service[EventLogService]
          _      <- svc.createUser("Carol", Some("carol@example.com"), actorId = Some(actorId))
          events <- log.query(EventLogFilter(eventType = Some(EventType.UserCreated)))
        } yield assertTrue(events.exists(_.actorId.contains(actorId)))
      }.provide(freshLayers),
      test("updateUser updates fields and logs UserUpdated") {
        for {
          svc     <- ZIO.service[UserService]
          log     <- ZIO.service[EventLogService]
          user    <- svc.createUser("Dave", Some("dave@example.com"))
          updated <- svc.updateUser(user.id, "David", Some("david@example.com"), active = true)
          events  <- log.query(EventLogFilter(eventType = Some(EventType.UserUpdated)))
        } yield assertTrue(
          updated.displayName == "David",
          updated.email.contains("david@example.com"),
          events.exists(_.eventType == EventType.UserUpdated),
        )
      }.provide(freshLayers),
      test("updateUser fails with JorlanError when user not found") {
        for {
          svc    <- ZIO.service[UserService]
          result <- svc.updateUser(UserId(999L), "Ghost", None, active = true).exit
        } yield assertTrue(result.isFailure)
      }.provide(freshLayers),
      test("getById returns None for unknown user") {
        for {
          svc    <- ZIO.service[UserService]
          result <- svc.getById(UserId(999L))
        } yield assertTrue(result.isEmpty)
      }.provide(freshLayers),
      test("getById returns the user after creation") {
        for {
          svc   <- ZIO.service[UserService]
          user  <- svc.createUser("Eve", None)
          found <- svc.getById(user.id)
        } yield assertTrue(found.contains(user))
      }.provide(freshLayers),
      test("search returns active users when filtered") {
        for {
          svc    <- ZIO.service[UserService]
          u1     <- svc.createUser("Active", Some("a@x.com"))
          u2     <- svc.createUser("Inactive", Some("i@x.com"))
          _      <- svc.updateUser(u2.id, "Inactive", Some("i@x.com"), active = false)
          active <- svc.search(UserSearch(active = Some(true)))
        } yield assertTrue(active.exists(_.id == u1.id), !active.exists(_.id == u2.id))
      }.provide(freshLayers),
    )

}
