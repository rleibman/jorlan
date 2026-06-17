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
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.db.repository.ZIORepositories
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Built-in skill for user, role, and capability-grant management.
  *
  * All tools require elevated capabilities seeded by [[jorlan.init.InitService]] for admin users. No new capabilities
  * are introduced — the skill reuses the existing `admin.user.list`, `user.create`, `user.update`, `role.create`,
  * `role.assign`, `role.revoke`, `permission.grant`, and `permission.revoke` grants.
  *
  * Tools:
  *   - `user_mgmt.list_users` — list/search users
  *   - `user_mgmt.get_user` — fetch a single user by ID
  *   - `user_mgmt.create_user` — create a new user
  *   - `user_mgmt.update_user` — update display name or email
  *   - `user_mgmt.deactivate_user` — soft-delete a user
  *   - `user_mgmt.list_roles` — list roles
  *   - `user_mgmt.create_role` — create a role
  *   - `user_mgmt.assign_role` — assign a role to a user
  *   - `user_mgmt.revoke_role` — remove a role from a user
  *   - `user_mgmt.list_grants` — list capability grants for a user
  *   - `user_mgmt.grant_capability` — grant a capability to a user
  *   - `user_mgmt.revoke_grant` — revoke a capability grant
  */
class UserManagementSkill(repos: ZIORepositories) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "user_mgmt",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "user_mgmt.list_users",
        description = "List or search users in the system. Supports optional filters for name substring, active status, and pagination.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"page":{"type":"integer","description":"Zero-based page index (default 0)"},"pageSize":{"type":"integer","description":"Results per page (default 20)"},"nameContains":{"type":"string","description":"Filter by display name substring (case-insensitive)"},"active":{"type":"boolean","description":"Filter by active status"}},"required":[]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("admin.user.list")),
        examplePrompts = List(
          "List all users",
          "Show me active users whose name contains Alice",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.get_user",
        description = "Fetch a single user by their numeric ID. Returns null if not found.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"}},"required":["userId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("admin.user.list")),
        examplePrompts = List(
          "Get user 42",
          "Show me the details for user ID 7",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.create_user",
        description = "Create a new user account with a display name, email, and password.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"displayName":{"type":"string","description":"Human-readable name"},"email":{"type":"string","description":"Login email address"},"password":{"type":"string","description":"Initial password (min 12 chars)"}},"required":["displayName","email","password"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("user.create")),
        examplePrompts = List(
          "Create a user named Bob with email bob@example.com",
          "Add a new account for alice@corp.com",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.update_user",
        description = "Update a user's display name or email address.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"},"displayName":{"type":"string","description":"New display name"},"email":{"type":"string","description":"New email address"}},"required":["userId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("user.update")),
        examplePrompts = List(
          "Change user 5's display name to Roberto",
          "Update the email for user 12",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.deactivate_user",
        description = "Soft-deactivate a user account so they can no longer log in.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID to deactivate"}},"required":["userId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("user.update")),
        examplePrompts = List(
          "Deactivate user 9",
          "Disable the account for user ID 3",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.list_roles",
        description = "List all roles in the system, with optional pagination.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"page":{"type":"integer","description":"Zero-based page index (default 0)"},"pageSize":{"type":"integer","description":"Results per page (default 20)"}},"required":[]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("admin.user.list")),
        examplePrompts = List(
          "List all roles",
          "Show me the available roles",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.create_role",
        description = "Create a new role with a name and optional description.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"name":{"type":"string","description":"Role name"},"description":{"type":"string","description":"Optional description of what this role grants"}},"required":["name"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("role.create")),
        examplePrompts = List(
          "Create a role called moderator",
          "Add a new role named data-analyst with description 'Can query analytics'",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.assign_role",
        description = "Assign an existing role to a user.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"},"roleId":{"type":"integer","description":"Numeric role ID"}},"required":["userId","roleId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("role.assign")),
        examplePrompts = List(
          "Assign role 2 to user 5",
          "Give user 10 the moderator role",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.revoke_role",
        description = "Remove a role from a user.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"},"roleId":{"type":"integer","description":"Numeric role ID"}},"required":["userId","roleId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("role.revoke")),
        examplePrompts = List(
          "Remove role 2 from user 5",
          "Revoke the moderator role from user 10",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.list_grants",
        description = "List all capability grants for a specific user.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"}},"required":["userId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("admin.user.list")),
        examplePrompts = List(
          "List capability grants for user 3",
          "What capabilities does user 7 have?",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.grant_capability",
        description = "Grant a named capability to a user. The approvalMode controls how strictly the system requires approval before each use.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"},"capability":{"type":"string","description":"Dot-separated capability name, e.g. shell.execute"},"approvalMode":{"type":"string","enum":["Persistent","Once","Session","Timed","PerInvocation","Denied"],"description":"Approval mode (default Persistent)"}},"required":["userId","capability"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("permission.grant")),
        examplePrompts = List(
          "Grant shell.execute to user 5",
          "Give user 3 the memory.write capability with Persistent approval",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.revoke_grant",
        description = "Revoke a specific capability grant by its grant ID.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"grantId":{"type":"integer","description":"Numeric capability grant ID"}},"required":["grantId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("permission.revoke")),
        examplePrompts = List(
          "Revoke grant 42",
          "Remove capability grant ID 7",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "user_mgmt.list_users"       => listUsers(args)
      case "user_mgmt.get_user"         => getUser(args)
      case "user_mgmt.create_user"      => createUser(args)
      case "user_mgmt.update_user"      => updateUser(args)
      case "user_mgmt.deactivate_user"  => deactivateUser(args)
      case "user_mgmt.list_roles"       => listRoles(args)
      case "user_mgmt.create_role"      => createRole(args)
      case "user_mgmt.assign_role"      => assignRole(args)
      case "user_mgmt.revoke_role"      => revokeRole(args)
      case "user_mgmt.list_grants"      => listGrants(args)
      case "user_mgmt.grant_capability" => grantCapability(args)
      case "user_mgmt.revoke_grant"     => revokeGrant(args)
      case other                        => ZIO.fail(JorlanError(s"UserManagementSkill: unknown tool '$other'"))
    }

  // ─── User operations ─────────────────────────────────────────────────────────

  private def listUsers(args: Json): IO[JorlanError, Json] = {
    val page = SkillArgs.int(args, "page").getOrElse(0)
    val pageSize = SkillArgs.int(args, "pageSize").getOrElse(20)
    val nameContains = SkillArgs.str(args, "nameContains")
    val active = SkillArgs.bool(args, "active")
    repos.user
      .search(UserSearch(nameContains = nameContains, active = active, page = page, pageSize = pageSize))
      .mapError(JorlanError(_))
      .map(users => Json.Arr(users.map(userJson)*))
  }

  private def getUser(args: Json): IO[JorlanError, Json] = {
    requireLong(args, "get_user", "userId").flatMap { id =>
      repos.user
        .getById(UserId(id))
        .mapError(JorlanError(_))
        .map {
          case Some(u) => userJson(u)
          case None    => Json.Obj()
        }
    }
  }

  private def createUser(args: Json): IO[JorlanError, Json] = {
    for {
      displayName <- requireStr(args, "create_user", "displayName")
      email       <- requireStr(args, "create_user", "email")
      password    <- requireStr(args, "create_user", "password")
      now         <- Clock.instant
      created     <- repos.user
        .upsert(User(UserId.empty, displayName, email, now, now))
        .mapError(JorlanError(_))
      _ <- repos.user.changePassword(created.id, password).mapError(JorlanError(_))
    } yield userJson(created)
  }

  private def updateUser(args: Json): IO[JorlanError, Json] = {
    for {
      id       <- requireLong(args, "update_user", "userId")
      existing <- repos.user.getById(UserId(id)).mapError(JorlanError(_))
      user     <- ZIO
        .fromOption(existing)
        .orElseFail(JorlanError(s"user_mgmt.update_user: user $id not found"))
      displayName = SkillArgs.str(args, "displayName").getOrElse(user.displayName)
      email = SkillArgs.str(args, "email").getOrElse(user.email)
      now     <- Clock.instant
      updated <- repos.user
        .upsert(user.copy(displayName = displayName, email = email, updatedAt = now))
        .mapError(JorlanError(_))
    } yield userJson(updated)
  }

  private def deactivateUser(args: Json): IO[JorlanError, Json] = {
    requireLong(args, "deactivate_user", "userId").flatMap { id =>
      repos.user
        .deactivate(UserId(id))
        .mapError(JorlanError(_))
        .as(Json.Obj("success" -> Json.Bool(true)))
    }
  }

  // ─── Role operations ─────────────────────────────────────────────────────────

  private def listRoles(args: Json): IO[JorlanError, Json] = {
    val page = SkillArgs.int(args, "page").getOrElse(0)
    val pageSize = SkillArgs.int(args, "pageSize").getOrElse(20)
    repos.permission
      .searchRoles(RoleSearch(page = page, pageSize = pageSize))
      .mapError(JorlanError(_))
      .map(roles => Json.Arr(roles.map(roleJson)*))
  }

  private def createRole(args: Json): IO[JorlanError, Json] = {
    requireStr(args, "create_role", "name").flatMap { name =>
      val description = SkillArgs.str(args, "description")
      repos.permission
        .upsertRole(Role(RoleId.empty, name, description))
        .mapError(JorlanError(_))
        .map(roleJson)
    }
  }

  private def assignRole(args: Json): IO[JorlanError, Json] = {
    for {
      userId <- requireLong(args, "assign_role", "userId")
      roleId <- requireLong(args, "assign_role", "roleId")
      _      <- repos.permission.assignRole(UserId(userId), RoleId(roleId)).mapError(JorlanError(_))
    } yield Json.Obj("success" -> Json.Bool(true))
  }

  private def revokeRole(args: Json): IO[JorlanError, Json] = {
    for {
      userId <- requireLong(args, "revoke_role", "userId")
      roleId <- requireLong(args, "revoke_role", "roleId")
      _      <- repos.permission.removeRole(UserId(userId), RoleId(roleId)).mapError(JorlanError(_))
    } yield Json.Obj("success" -> Json.Bool(true))
  }

  // ─── Capability grant operations ─────────────────────────────────────────────

  private def listGrants(args: Json): IO[JorlanError, Json] = {
    requireLong(args, "list_grants", "userId").flatMap { id =>
      repos.permission
        .searchGrants(GrantSearch(userId = UserId(id)))
        .mapError(JorlanError(_))
        .map(grants => Json.Arr(grants.map(grantJson)*))
    }
  }

  private def grantCapability(args: Json): IO[JorlanError, Json] = {
    for {
      userId     <- requireLong(args, "grant_capability", "userId")
      capability <- requireStr(args, "grant_capability", "capability")
      approvalMode = SkillArgs.str(args, "approvalMode").flatMap(parseApprovalMode).getOrElse(ApprovalMode.Persistent)
      now     <- Clock.instant
      created <- repos.permission
        .upsertCapabilityGrant(
          CapabilityGrant(
            id = CapabilityGrantId.empty,
            capability = CapabilityName(capability),
            scopeJson = None,
            granteeId = UserId(userId),
            grantorId = None,
            approvalMode = approvalMode,
            expiresAt = None,
            resourceConstraints = None,
            createdAt = now,
          ),
        )
        .mapError(JorlanError(_))
    } yield grantJson(created)
  }

  private def revokeGrant(args: Json): IO[JorlanError, Json] = {
    requireLong(args, "revoke_grant", "grantId").flatMap { id =>
      repos.permission
        .revokeGrant(CapabilityGrantId(id))
        .mapError(JorlanError(_))
        .as(Json.Obj("success" -> Json.Bool(true)))
    }
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────────

  private def requireStr(
    args:  Json,
    tool:  String,
    field: String,
  ): IO[JorlanError, String] =
    ZIO
      .fromOption(SkillArgs.str(args, field))
      .orElseFail(JorlanError(s"user_mgmt.$tool: $field is required"))

  private def requireLong(
    args:  Json,
    tool:  String,
    field: String,
  ): IO[JorlanError, Long] =
    SkillArgs.int(args, field) match {
      case Some(n) => ZIO.succeed(n.toLong)
      case None    =>
        SkillArgs.str(args, field) match {
          case Some(s) =>
            s.toLongOption match {
              case Some(n) => ZIO.succeed(n)
              case None    => ZIO.fail(JorlanError(s"user_mgmt.$tool: $field must be numeric, got '$s'"))
            }
          case None => ZIO.fail(JorlanError(s"user_mgmt.$tool: $field is required"))
        }
    }

  private def parseApprovalMode(s: String): Option[ApprovalMode] =
    ApprovalMode.values.find(_.toString.equalsIgnoreCase(s))

  private def userJson(u: User): Json =
    Json.Obj(
      "id"          -> Json.Str(u.id.value.toString),
      "displayName" -> Json.Str(u.displayName),
      "email"       -> Json.Str(u.email),
      "active"      -> Json.Bool(u.active),
      "createdAt"   -> Json.Str(u.createdAt.toString),
    )

  private def roleJson(r: Role): Json =
    Json.Obj(
      "id"          -> Json.Str(r.id.value.toString),
      "name"        -> Json.Str(r.name),
      "description" -> r.description.fold(Json.Null: Json)(Json.Str(_)),
    )

  private def grantJson(g: CapabilityGrant): Json =
    Json.Obj(
      "id"           -> Json.Str(g.id.value.toString),
      "capability"   -> Json.Str(g.capability.value),
      "granteeId"    -> Json.Str(g.granteeId.value.toString),
      "approvalMode" -> Json.Str(g.approvalMode.toString),
      "createdAt"    -> Json.Str(g.createdAt.toString),
    )

}

object UserManagementSkill
