/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.db.repository.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object UserManagementSkillSpec extends ZIOSpecDefault {

  private val ctx = InvocationContext(UserId(1L), None, None)
  private val now = Instant.now()

  private def makeUser(
    id:      Long,
    display: String,
  ): User =
    User(UserId(id), display, s"user$id@example.com", now, now)

  private def buildSkill(reposLayer: ULayer[ZIORepositories]): URIO[Scope, UserManagementSkill] =
    reposLayer.build.map(env => new UserManagementSkill(env.get[ZIORepositories]))

  private def mkArgs(pairs: (String, Json)*): Json =
    Json.Obj(pairs*)

  private def strArgs(pairs: (String, String)*): Json =
    Json.Obj(pairs.map { case (k, v) => k -> Json.Str(v) }*)

  private def intArg(
    key:   String,
    value: Long,
  ): (String, Json) = key -> Json.Num(value)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("UserManagementSkill")(
      // ─── user_mgmt.list_users ──────────────────────────────────────────────────
      test("list_users returns all users when no filter given") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          _     <- repos.user.upsert(makeUser(0L, "Alice"))
          _     <- repos.user.upsert(makeUser(0L, "Bob"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.list_users", Json.Obj())
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.length == 2)
          case _               => assertTrue(false)
        }
      },
      test("list_users filters by nameContains") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          _     <- repos.user.upsert(makeUser(0L, "Alice"))
          _     <- repos.user.upsert(makeUser(0L, "Bob"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.list_users", strArgs("nameContains" -> "Ali"))
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.length == 1)
          case _               => assertTrue(false)
        }
      },
      // ─── user_mgmt.get_user ────────────────────────────────────────────────────
      test("get_user returns user JSON for existing ID") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Charlie"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.get_user", mkArgs(intArg("userId", created.id.value)))
        } yield result match {
          case Json.Obj(fields) =>
            val nameVal = fields.collectFirst { case ("displayName", Json.Str(n)) => n }
            assertTrue(nameVal.contains("Charlie"))
          case _ => assertTrue(false)
        }
      },
      test("get_user returns empty object for non-existent ID") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.get_user", mkArgs(intArg("userId", 9999L)))
        } yield result match {
          case Json.Obj(fields) => assertTrue(fields.isEmpty)
          case _                => assertTrue(false)
        }
      },
      // ─── user_mgmt.create_user ────────────────────────────────────────────────
      test("create_user creates and returns a user") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.create_user",
            strArgs("displayName" -> "Dana", "email" -> "dana@example.com", "password" -> "strongpassword123"),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val nameVal = fields.collectFirst { case ("displayName", Json.Str(n)) => n }
            val emailVal = fields.collectFirst { case ("email", Json.Str(e)) => e }
            assertTrue(nameVal.contains("Dana"), emailVal.contains("dana@example.com"))
          case _ => assertTrue(false)
        }
      },
      test("create_user fails when displayName is missing") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill
            .invoke(ctx, "user_mgmt.create_user", strArgs("email" -> "x@x.com", "password" -> "strongpassword123"))
            .either
        } yield assertTrue(result.isLeft)
      },
      // ─── user_mgmt.deactivate_user ────────────────────────────────────────────
      test("deactivate_user marks user inactive") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Eve"))
          skill = new UserManagementSkill(repos)
          result  <- skill.invoke(ctx, "user_mgmt.deactivate_user", mkArgs(intArg("userId", created.id.value)))
          fetched <- repos.user.getById(created.id)
        } yield result match {
          case Json.Obj(fields) =>
            val successVal = fields.collectFirst { case ("success", Json.Bool(b)) => b }
            assertTrue(successVal.contains(true), fetched.exists(!_.active))
          case _ => assertTrue(false)
        }
      },
      // ─── user_mgmt.list_roles ─────────────────────────────────────────────────
      test("list_roles returns all roles") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          _     <- repos.permission.upsertRole(Role(RoleId.empty, "admin", Some("Administrators")))
          _     <- repos.permission.upsertRole(Role(RoleId.empty, "viewer", None))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.list_roles", Json.Obj())
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.length == 2)
          case _               => assertTrue(false)
        }
      },
      // ─── user_mgmt.create_role ────────────────────────────────────────────────
      test("create_role creates and returns a role") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.create_role",
            strArgs("name" -> "moderator", "description" -> "Can moderate content"),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val nameVal = fields.collectFirst { case ("name", Json.Str(n)) => n }
            assertTrue(nameVal.contains("moderator"))
          case _ => assertTrue(false)
        }
      },
      test("create_role fails when name is missing") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.create_role", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      // ─── user_mgmt.list_grants ────────────────────────────────────────────────
      test("list_grants returns grants for user") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Frank"))
          _       <- repos.permission.upsertCapabilityGrant(
            CapabilityGrant(
              id = CapabilityGrantId.empty,
              capability = CapabilityName("memory.write"),
              scopeJson = None,
              granteeId = created.id.value,
              granteeType = GranteeType.User,
              grantorId = None,
              approvalMode = ApprovalMode.Persistent,
              expiresAt = None,
              resourceConstraints = None,
              createdAt = now,
            ),
          )
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.list_grants", mkArgs(intArg("userId", created.id.value)))
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.length == 1)
          case _               => assertTrue(false)
        }
      },
      // ─── user_mgmt.grant_capability ───────────────────────────────────────────
      test("grant_capability creates a capability grant") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Grace"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.grant_capability",
            mkArgs(intArg("userId", created.id.value), ("capability", Json.Str("shell.execute"))),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val capVal = fields.collectFirst { case ("capability", Json.Str(c)) => c }
            assertTrue(capVal.contains("shell.execute"))
          case _ => assertTrue(false)
        }
      },
      // ─── missing required arg ─────────────────────────────────────────────────
      test("invoke unknown tool fails with JorlanError") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.unknown_tool", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("get_user fails when userId is missing") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.get_user", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      // ─── user_mgmt.update_user ────────────────────────────────────────────────
      test("update_user changes display name") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "OldName"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.update_user",
            mkArgs(intArg("userId", created.id.value), ("displayName", Json.Str("NewName"))),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val nameVal = fields.collectFirst { case ("displayName", Json.Str(n)) => n }
            assertTrue(nameVal.contains("NewName"))
          case _ => assertTrue(false)
        }
      },
      test("update_user changes email") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Henry"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.update_user",
            mkArgs(intArg("userId", created.id.value), ("email", Json.Str("henry_new@example.com"))),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val emailVal = fields.collectFirst { case ("email", Json.Str(e)) => e }
            assertTrue(emailVal.contains("henry_new@example.com"))
          case _ => assertTrue(false)
        }
      },
      test("update_user fails for non-existent user") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.update_user", mkArgs(intArg("userId", 9999L))).either
        } yield assertTrue(result.isLeft)
      },
      test("update_user fails when userId is missing") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.update_user", strArgs("displayName" -> "X")).either
        } yield assertTrue(result.isLeft)
      },
      // ─── user_mgmt.assign_role / revoke_role ──────────────────────────────────
      test("assign_role assigns a role to a user") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Irene"))
          role    <- repos.permission.upsertRole(Role(RoleId.empty, "editor", None))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.assign_role",
            mkArgs(intArg("userId", created.id.value), intArg("roleId", role.id.value)),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val successVal = fields.collectFirst { case ("success", Json.Bool(b)) => b }
            assertTrue(successVal.contains(true))
          case _ => assertTrue(false)
        }
      },
      test("assign_role persists and searchRoles returns the assigned role") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Jack"))
          role    <- repos.permission.upsertRole(Role(RoleId.empty, "viewer", None))
          skill = new UserManagementSkill(repos)
          _ <- skill.invoke(
            ctx,
            "user_mgmt.assign_role",
            mkArgs(intArg("userId", created.id.value), intArg("roleId", role.id.value)),
          )
          roles <- repos.permission.searchRoles(RoleSearch(userId = Some(created.id)))
        } yield assertTrue(roles.exists(_.id == role.id))
      },
      test("assign_role fails when userId is missing") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          role  <- repos.permission.upsertRole(Role(RoleId.empty, "reader", None))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.assign_role", mkArgs(intArg("roleId", role.id.value))).either
        } yield assertTrue(result.isLeft)
      },
      test("revoke_role removes role from user") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Karen"))
          role    <- repos.permission.upsertRole(Role(RoleId.empty, "admin", None))
          _       <- repos.permission.assignRole(created.id, role.id)
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.revoke_role",
            mkArgs(intArg("userId", created.id.value), intArg("roleId", role.id.value)),
          )
          rolesBefore <- repos.permission.searchRoles(RoleSearch(userId = Some(created.id)))
        } yield result match {
          case Json.Obj(fields) =>
            val successVal = fields.collectFirst { case ("success", Json.Bool(b)) => b }
            assertTrue(successVal.contains(true), rolesBefore.isEmpty)
          case _ => assertTrue(false)
        }
      },
      test("revoke_role fails when roleId is missing") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Leo"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.revoke_role", mkArgs(intArg("userId", created.id.value))).either
        } yield assertTrue(result.isLeft)
      },
      // ─── user_mgmt.link_identity ──────────────────────────────────────────────
      test("link_identity creates a channel identity for the current user when userId omitted") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.link_identity",
            strArgs("channelType" -> "Telegram", "channelUserId" -> "tg_12345"),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val ctVal = fields.collectFirst { case ("channelType", Json.Str(s)) => s }
            val cuidVal = fields.collectFirst { case ("channelUserId", Json.Str(s)) => s }
            assertTrue(ctVal.contains("Telegram"), cuidVal.contains("tg_12345"))
          case _ => assertTrue(false)
        }
      },
      test("link_identity creates a channel identity for an explicit userId") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Mia"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.link_identity",
            mkArgs(
              ("userId", Json.Str(created.id.value.toString)),
              ("channelType", Json.Str("Slack")),
              ("channelUserId", Json.Str("slack_abc")),
            ),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val ctVal = fields.collectFirst { case ("channelType", Json.Str(s)) => s }
            assertTrue(ctVal.contains("Slack"))
          case _ => assertTrue(false)
        }
      },
      test("link_identity fails when channelType is missing") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.link_identity", strArgs("channelUserId" -> "tg_999")).either
        } yield assertTrue(result.isLeft)
      },
      test("link_identity fails when channelUserId is missing") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.link_identity", strArgs("channelType" -> "Telegram")).either
        } yield assertTrue(result.isLeft)
      },
      test("link_identity fails for unknown channelType") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill
            .invoke(
              ctx,
              "user_mgmt.link_identity",
              strArgs("channelType" -> "Pigeon", "channelUserId" -> "pigeon_1"),
            )
            .either
        } yield assertTrue(result.isLeft)
      },
      // ─── user_mgmt.list_identities ────────────────────────────────────────────
      test("list_identities returns identities for the current user when userId omitted") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          now2 = java.time.Instant.now()
          ci = ChannelIdentity(
            id = ChannelIdentityId.empty,
            userId = ctx.actorId,
            channelType = jorlan.ChannelType.Telegram,
            channelUserId = "tg_for_ctx_user",
            verified = false,
            providerData = None,
            createdAt = now2,
          )
          _ <- repos.user.upsertChannelIdentity(ci)
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.list_identities", Json.Obj())
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.length == 1)
          case _               => assertTrue(false)
        }
      },
      test("list_identities returns identities for an explicit userId") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Nina"))
          now2 = java.time.Instant.now()
          ci = ChannelIdentity(
            id = ChannelIdentityId.empty,
            userId = created.id,
            channelType = jorlan.ChannelType.Slack,
            channelUserId = "slack_nina",
            verified = false,
            providerData = None,
            createdAt = now2,
          )
          _ <- repos.user.upsertChannelIdentity(ci)
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.list_identities",
            mkArgs(("userId", Json.Str(created.id.value.toString))),
          )
        } yield result match {
          case Json.Arr(elems) =>
            assertTrue(elems.length == 1)
          case _ => assertTrue(false)
        }
      },
      test("list_identities returns empty array when user has no identities") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Oscar"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.list_identities",
            mkArgs(("userId", Json.Str(created.id.value.toString))),
          )
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.isEmpty)
          case _               => assertTrue(false)
        }
      },
      // ─── user_mgmt.revoke_grant ───────────────────────────────────────────────
      test("revoke_grant removes an existing capability grant") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Paula"))
          grant   <- repos.permission.upsertCapabilityGrant(
            CapabilityGrant(
              id = CapabilityGrantId.empty,
              capability = CapabilityName("shell.execute"),
              scopeJson = None,
              granteeId = created.id.value,
              granteeType = GranteeType.User,
              grantorId = None,
              approvalMode = ApprovalMode.Persistent,
              expiresAt = None,
              resourceConstraints = None,
              createdAt = now,
            ),
          )
          skill = new UserManagementSkill(repos)
          result    <- skill.invoke(ctx, "user_mgmt.revoke_grant", mkArgs(intArg("grantId", grant.id.value)))
          remaining <- repos.permission.searchGrants(GrantSearch(userId = Some(created.id)))
        } yield result match {
          case Json.Obj(fields) =>
            val successVal = fields.collectFirst { case ("success", Json.Bool(b)) => b }
            assertTrue(successVal.contains(true), remaining.isEmpty)
          case _ => assertTrue(false)
        }
      },
      test("revoke_grant fails when grantId is missing") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.revoke_grant", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      // ─── user_mgmt.resolve_identity ───────────────────────────────────────────
      test("resolve_identity finds user by channel type and ID") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Quinn"))
          now2 = java.time.Instant.now()
          ci = ChannelIdentity(
            id = ChannelIdentityId.empty,
            userId = created.id,
            channelType = jorlan.ChannelType.Telegram,
            channelUserId = "tg_quinn",
            verified = false,
            providerData = None,
            createdAt = now2,
          )
          _ <- repos.user.upsertChannelIdentity(ci)
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.resolve_identity",
            strArgs("channelType" -> "Telegram", "channelUserId" -> "tg_quinn"),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val foundVal = fields.collectFirst { case ("found", Json.Bool(b)) => b }
            val nameVal = fields.collectFirst { case ("displayName", Json.Str(n)) => n }
            assertTrue(foundVal.contains(true), nameVal.contains("Quinn"))
          case _ => assertTrue(false)
        }
      },
      test("resolve_identity returns not-found for unknown channel identity") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(
            ctx,
            "user_mgmt.resolve_identity",
            strArgs("channelType" -> "Telegram", "channelUserId" -> "nobody"),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val foundVal = fields.collectFirst { case ("found", Json.Bool(b)) => b }
            assertTrue(foundVal.contains(false))
          case _ => assertTrue(false)
        }
      },
      test("resolve_identity fails when channelType is missing") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill
            .invoke(ctx, "user_mgmt.resolve_identity", strArgs("channelUserId" -> "tg_99"))
            .either
        } yield assertTrue(result.isLeft)
      },
      test("resolve_identity fails when channelUserId is missing") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill
            .invoke(ctx, "user_mgmt.resolve_identity", strArgs("channelType" -> "Telegram"))
            .either
        } yield assertTrue(result.isLeft)
      },
      test("resolve_identity fails for unknown channelType") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill
            .invoke(
              ctx,
              "user_mgmt.resolve_identity",
              strArgs("channelType" -> "Carrier Pigeon", "channelUserId" -> "cp_1"),
            )
            .either
        } yield assertTrue(result.isLeft)
      },
      // ─── user_mgmt.find ───────────────────────────────────────────────────────
      test("find returns all active users when name omitted") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          _     <- repos.user.upsert(makeUser(0L, "Rose"))
          _     <- repos.user.upsert(makeUser(0L, "Sam"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.find", Json.Obj())
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.length == 2)
          case _               => assertTrue(false)
        }
      },
      test("find returns fuzzy-matched users by name") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          _     <- repos.user.upsert(makeUser(0L, "Roberto Leibman"))
          _     <- repos.user.upsert(makeUser(0L, "Tina Turner"))
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.find", strArgs("name" -> "Roberto"))
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.nonEmpty)
          case _               => assertTrue(false)
        }
      },
      test("find includes channel identities in results") {
        for {
          repos   <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          created <- repos.user.upsert(makeUser(0L, "Uma"))
          now2 = java.time.Instant.now()
          ci = ChannelIdentity(
            id = ChannelIdentityId.empty,
            userId = created.id,
            channelType = jorlan.ChannelType.Telegram,
            channelUserId = "tg_uma",
            verified = false,
            providerData = None,
            createdAt = now2,
          )
          _ <- repos.user.upsertChannelIdentity(ci)
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.find", strArgs("name" -> "Uma"))
        } yield result match {
          case Json.Arr(elems) =>
            val hasIdentity = elems.exists {
              case Json.Obj(fields) =>
                fields.collectFirst { case ("identities", Json.Arr(ids)) => ids }.exists(_.nonEmpty)
              case _ => false
            }
            assertTrue(hasIdentity)
          case _ => assertTrue(false)
        }
      },
      // ─── list_roles with pagination validation ────────────────────────────────
      test("list_roles fails when pageSize is 0") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.list_roles", mkArgs(intArg("pageSize", 0L))).either
        } yield assertTrue(result.isLeft)
      },
      test("list_roles fails when page is negative") {
        for {
          repos <- InMemoryRepositories.live().build.map(_.get[ZIORepositories])
          skill = new UserManagementSkill(repos)
          result <- skill.invoke(ctx, "user_mgmt.list_roles", mkArgs(intArg("page", -1L))).either
        } yield assertTrue(result.isLeft)
      },
    )

}
