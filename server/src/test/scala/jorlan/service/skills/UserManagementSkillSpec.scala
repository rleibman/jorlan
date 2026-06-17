/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
              granteeId = created.id,
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
    )

}
