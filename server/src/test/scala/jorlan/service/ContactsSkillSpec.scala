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
import jorlan.connector.InvocationContext
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object ContactsSkillSpec extends ZIOSpecDefault {

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

  /** User repo with seeded users + channel identities. */
  private class FakeUserRepo(
    users:     Map[Long, User],
    seedIds:   Map[Long, List[ChannelIdentity]],
    byChannel: Map[(ChannelType, String), User],
    ciIdGen:   Ref[Long],
    ciStore:   Ref[Map[Long, ChannelIdentity]],
  ) extends ZIOUserRepository {

    override def getById(id: UserId):   RepositoryTask[Option[User]] = ZIO.succeed(users.get(id.value))
    override def search(s: UserSearch): RepositoryTask[List[User]] =
      ZIO.succeed(users.values.toList.filter(u => s.active.forall(_ == u.active)))
    override def upsert(user:   User):                 RepositoryTask[User] = ZIO.die(RuntimeException("stub"))
    override def deactivate(id: UserId):               RepositoryTask[Long] = ZIO.die(RuntimeException("stub"))
    override def getChannelIdentities(userId: UserId): RepositoryTask[List[ChannelIdentity]] =
      ciStore.get.map { fromStore =>
        val fromSeed = seedIds.getOrElse(userId.value, Nil)
        fromSeed ++ fromStore.values.filter(_.userId == userId).toList
      }
    override def upsertChannelIdentity(ci: ChannelIdentity): RepositoryTask[ChannelIdentity] =
      ciIdGen.updateAndGet(_ + 1).flatMap { id =>
        val saved = ci.copy(id = ChannelIdentityId(id))
        ciStore.update(_.updated(id, saved)).as(saved)
      }
    override def deleteChannelIdentity(id: ChannelIdentityId): RepositoryTask[Long] =
      ciStore.modify(m => (if (m.contains(id.value)) 1L else 0L, m - id.value))
    override def login(
      email:    String,
      password: String,
    ):                                       RepositoryTask[Option[User]] = ZIO.none
    override def userByEmail(email: String): RepositoryTask[Option[User]] = ZIO.none
    override def changePassword(
      id:          UserId,
      newPassword: String,
    ): RepositoryTask[Unit] = ZIO.unit
    override def userByChannelIdentity(
      channelType:   ChannelType,
      channelUserId: String,
    ): RepositoryTask[Option[User]] = ZIO.succeed(byChannel.get((channelType, channelUserId)))

  }

  private def fakeUserRepo(
    users:     Map[Long, User],
    seedIds:   Map[Long, List[ChannelIdentity]] = Map.empty,
    byChannel: Map[(ChannelType, String), User] = Map.empty,
  ): UIO[ZIOUserRepository] =
    (Ref.make(0L) <*> Ref.make(Map.empty[Long, ChannelIdentity])).map { case (gen, store) =>
      FakeUserRepo(users, seedIds, byChannel, gen, store)
    }

  /** Get a `ZIORepositories` from a layer and build a `ContactsSkill` from it. */
  private def buildSkill(repoLayer: ULayer[ZIORepositories]): URIO[Scope, ContactsSkill] =
    repoLayer.build.map(env => new ContactsSkill(env.get[ZIORepositories]))

  private def mkArgs(pairs: (String, String)*): Json =
    Json.Obj(pairs.map { case (k, v) => k -> Json.Str(v) }*)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ContactsSkill")(
      // ─── contacts.find ─────────────────────────────────────────────────────────
      test("contacts.find returns matching users (case-insensitive substring)") {
        val users = Map(
          1L -> makeUser(1L, "Alice Smith"),
          2L -> makeUser(2L, "Bob Jones"),
          3L -> makeUser(3L, "Alicia Keys"),
        )
        val ids = Map(1L -> List(makeCi(1L, ChannelType.Telegram, "tg-1")))
        for {
          userRepo <- fakeUserRepo(users, ids)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "contacts.find", mkArgs("name" -> "ali"))
        } yield result match {
          case Json.Arr(elems) =>
            val names = elems.collect { case Json.Obj(fs) =>
              fs.collectFirst { case ("displayName", Json.Str(n)) => n }
            }.flatten
            assertTrue(elems.length == 2, names.contains("Alice Smith"), names.contains("Alicia Keys"))
          case _ => assertTrue(false)
        }
      },
      test("contacts.find returns empty array when no match") {
        val users = Map(1L -> makeUser(1L, "Bob Jones"))
        for {
          userRepo <- fakeUserRepo(users)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "contacts.find", mkArgs("name" -> "xyz"))
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.isEmpty)
          case _               => assertTrue(false)
        }
      },
      test("contacts.find fails when name is missing") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "contacts.find", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("contacts.find includes identities in results") {
        val user = makeUser(4L, "Dave")
        val ids = Map(4L -> List(makeCi(4L, ChannelType.Telegram, "tg-4"), makeCi(4L, ChannelType.Slack, "sl-4")))
        for {
          userRepo <- fakeUserRepo(Map(4L -> user), ids)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "contacts.find", mkArgs("name" -> "dave"))
        } yield result match {
          case Json.Arr(Seq(Json.Obj(fields))) =>
            fields.collectFirst { case ("identities", Json.Arr(ids)) => ids } match {
              case Some(ids) => assertTrue(ids.length == 2)
              case None      => assertTrue(false)
            }
          case _ => assertTrue(false)
        }
      },
      // ─── identity.resolve ──────────────────────────────────────────────────────
      test("identity.resolve returns user when channel identity matches") {
        val user = makeUser(5L, "Charlie")
        val byChannel = Map((ChannelType.Telegram, "tg-5") -> user)
        for {
          userRepo <- fakeUserRepo(Map(5L -> user), byChannel = byChannel)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill
            .invoke(ctx, "identity.resolve", mkArgs("channelType" -> "telegram", "channelUserId" -> "tg-5"))
        } yield result match {
          case Json.Obj(fields) =>
            val foundVal = fields.collectFirst { case ("found", Json.Bool(b)) => b }
            val nameVal = fields.collectFirst { case ("displayName", Json.Str(n)) => n }
            assertTrue(foundVal.contains(true), nameVal.contains("Charlie"))
          case _ => assertTrue(false)
        }
      },
      test("identity.resolve returns found=false when no match") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(
            ctx,
            "identity.resolve",
            mkArgs("channelType" -> "telegram", "channelUserId" -> "nobody"),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val foundVal = fields.collectFirst { case ("found", Json.Bool(b)) => b }
            assertTrue(foundVal.contains(false))
          case _ => assertTrue(false)
        }
      },
      test("identity.resolve fails with unknown channelType") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill
            .invoke(ctx, "identity.resolve", mkArgs("channelType" -> "carrier-pigeon", "channelUserId" -> "x"))
            .either
        } yield assertTrue(result.isLeft)
      },
      test("identity.resolve fails when channelType is missing") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "identity.resolve", mkArgs("channelUserId" -> "x")).either
        } yield assertTrue(result.isLeft)
      },
      // ─── identity.link ─────────────────────────────────────────────────────────
      test("identity.link creates a channel identity") {
        val user = makeUser(7L, "Dana")
        for {
          userRepo <- fakeUserRepo(Map(7L -> user))
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(
            ctx,
            "identity.link",
            mkArgs("userId" -> "7", "channelType" -> "Telegram", "channelUserId" -> "tg-7"),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val channelUserId = fields.collectFirst { case ("channelUserId", Json.Str(c)) => c }
            val channelType = fields.collectFirst { case ("channelType", Json.Str(t)) => t }
            assertTrue(channelUserId.contains("tg-7"), channelType.contains("Telegram"))
          case _ => assertTrue(false)
        }
      },
      test("identity.link fails when userId is non-numeric") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill
            .invoke(
              ctx,
              "identity.link",
              mkArgs("userId" -> "notanumber", "channelType" -> "Telegram", "channelUserId" -> "x"),
            )
            .either
        } yield assertTrue(result.isLeft)
      },
      test("identity.link fails when required fields are missing") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "identity.link", mkArgs("userId" -> "1")).either
        } yield assertTrue(result.isLeft)
      },
      // ─── identity.listAliases ──────────────────────────────────────────────────
      test("identity.listAliases returns all identities for a user") {
        val user = makeUser(9L, "Eve")
        val ids = Map(
          9L -> List(makeCi(9L, ChannelType.Telegram, "tg-9"), makeCi(9L, ChannelType.Slack, "slack-9")),
        )
        for {
          userRepo <- fakeUserRepo(Map(9L -> user), ids)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "identity.listAliases", mkArgs("userId" -> "9"))
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.length == 2)
          case _               => assertTrue(false)
        }
      },
      test("identity.listAliases returns empty for user with no identities") {
        val user = makeUser(10L, "Frank")
        for {
          userRepo <- fakeUserRepo(Map(10L -> user))
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "identity.listAliases", mkArgs("userId" -> "10"))
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.isEmpty)
          case _               => assertTrue(false)
        }
      },
      test("identity.listAliases fails when userId is missing") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "identity.listAliases", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("identity.listAliases fails when userId is non-numeric") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "identity.listAliases", mkArgs("userId" -> "abc")).either
        } yield assertTrue(result.isLeft)
      },
      // ─── unknown tool ──────────────────────────────────────────────────────────
      test("invoke unknown tool fails") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "contacts.unknown", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      // ─── identity.resolve missing channelUserId ────────────────────────────────
      test("identity.resolve fails when channelUserId is missing") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "identity.resolve", mkArgs("channelType" -> "telegram")).either
        } yield assertTrue(result.isLeft)
      },
      // ─── identity.link missing / invalid fields ────────────────────────────────
      test("identity.link fails when userId is missing") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill
            .invoke(ctx, "identity.link", mkArgs("channelType" -> "Telegram", "channelUserId" -> "x"))
            .either
        } yield assertTrue(result.isLeft)
      },
      test("identity.link fails when channelUserId is missing") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill.invoke(ctx, "identity.link", mkArgs("userId" -> "1", "channelType" -> "Telegram")).either
        } yield assertTrue(result.isLeft)
      },
      test("identity.link fails for unknown channelType") {
        for {
          userRepo <- fakeUserRepo(Map.empty)
          skill    <- buildSkill(InMemoryRepositories.live(userRepoOpt = Some(userRepo)))
          result   <- skill
            .invoke(
              ctx,
              "identity.link",
              mkArgs("userId" -> "1", "channelType" -> "carrier-pigeon", "channelUserId" -> "x"),
            )
            .either
        } yield assertTrue(result.isLeft)
      },
    )

}
