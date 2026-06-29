/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.db.repository.ZIORepositories
import just.semver.SemVer
import zio.*
import zio.json.ast.Json
import zio.json.literal.*

/** Built-in skill for user discovery, channel-identity management, role, and capability-grant management.
  *
  * Tools:
  *   - `user_mgmt.find` — fuzzy search users by display name (requires `users.read`)
  *   - `user_mgmt.resolve_identity` — find user by channel type + ID (requires `users.read`)
  *   - `user_mgmt.link_identity` — create/update a channel identity (requires `identity.manage`)
  *   - `user_mgmt.list_identities` — list channel identities for a user (requires `users.read`)
  *   - `user_mgmt.list_users` — list/search users (requires `admin.user.list`)
  *   - `user_mgmt.get_user` — fetch a single user by ID (requires `admin.user.list`)
  *   - `user_mgmt.create_user` — create a new user (requires `user.create`)
  *   - `user_mgmt.update_user` — update display name or email (requires `user.update`)
  *   - `user_mgmt.deactivate_user` — soft-delete a user (requires `user.update`)
  *   - `user_mgmt.list_roles` — list roles (requires `admin.user.list`)
  *   - `user_mgmt.create_role` — create a role (requires `role.create`)
  *   - `user_mgmt.assign_role` — assign a role to a user (requires `role.assign`)
  *   - `user_mgmt.revoke_role` — remove a role from a user (requires `role.revoke`)
  *   - `user_mgmt.list_grants` — list capability grants for a user (requires `admin.user.list`)
  *   - `user_mgmt.grant_capability` — grant a capability to a user (requires `permission.grant`)
  *   - `user_mgmt.revoke_grant` — revoke a capability grant (requires `permission.revoke`)
  */
class UserManagementSkill(repos: ZIORepositories) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "user_mgmt",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "user",
      "account",
      "admin",
      "permission",
      "role",
      "authentication",
      "profile",
      "settings",
      "create user",
      "delete user",
      "grant",
      "revoke",
      "access control",
      "login",
      "identity",
      "capability",
    ),
    doc = Some(
      """|## User Management Skill
         |
         |User discovery, channel-identity management, and admin management of users, roles, and capability grants.
         |
         |### Tools
         || Tool | Description | Capability |
         ||------|-------------|------------|
         || `user_mgmt.find` | Fuzzy search users by display name | `users.read` |
         || `user_mgmt.resolve_identity` | Find user by channel type + ID | `users.read` |
         || `user_mgmt.link_identity` | Create/update a channel identity | `identity.manage` |
         || `user_mgmt.list_identities` | List channel identities for a user | `users.read` |
         || `user_mgmt.list_users` | List/search users (admin) | `admin.user.list` |
         || `user_mgmt.get_user` | Fetch user by ID (admin) | `admin.user.list` |
         || `user_mgmt.create_user` | Create a new user | `user.create` |
         || `user_mgmt.update_user` | Update display name or email | `user.update` |
         || `user_mgmt.deactivate_user` | Soft-delete a user | `user.update` |
         || `user_mgmt.list_roles` | List roles | `admin.user.list` |
         || `user_mgmt.create_role` | Create a role | `role.create` |
         || `user_mgmt.assign_role` | Assign role to a user | `role.assign` |
         || `user_mgmt.revoke_role` | Remove role from a user | `role.revoke` |
         || `user_mgmt.list_grants` | List capability grants for a user | `admin.user.list` |
         || `user_mgmt.grant_capability` | Grant a capability to a user | `permission.grant` |
         || `user_mgmt.revoke_grant` | Revoke a capability grant | `permission.revoke` |
         |
         |### Setup
         |No external configuration required. The skill is always available.
         |Admin capabilities are automatically seeded for admin users by the system.""".stripMargin,
    ),
    tools = List(
      ToolDescriptor(
        name = "user_mgmt.find",
        description = "Search users by display name with fuzzy/phonetic matching. 'Roberto' matches 'Robert Leibman'; 'Sara' matches 'Sarah Smith'. Omit 'name' to list all users. Also returns each user's channel identities (e.g. Telegram ID).",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"name":{"type":"string","description":"Name to search — supports partial names, phonetic variants, and minor spelling differences"}},"required":[]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("users.read")),
        examplePrompts = List(
          "Who is Alice?",
          "Find a user named Roberto",
          "Show me all users in the system",
          "Look up Roberto's Telegram chat ID so I can send him a message",
          "What is Dominique's Telegram user ID?",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.resolve_identity",
        description = "Find the user linked to a specific channel identity (e.g. a Telegram chat ID).",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"channelType":{"type":"string","description":"Channel type: Telegram, Slack, Email, etc."},"channelUserId":{"type":"string","description":"Channel-native user identifier"}},"required":["channelType","channelUserId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("users.read")),
        examplePrompts = List(
          "Who has Telegram ID 123456?",
          "Which user is associated with this Telegram handle?",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.link_identity",
        description = "Create or update a channel identity for a user. If userId is omitted, links to the currently authenticated user.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"userId":{"type":"string","description":"Numeric user ID — omit to use the current user"},"channelType":{"type":"string","description":"Channel type: Telegram, Slack, Email, etc."},"channelUserId":{"type":"string","description":"Channel-native user identifier (e.g. Telegram numeric user ID)"}},"required":["channelType","channelUserId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("identity.manage")),
        examplePrompts = List(
          "Link my Telegram account to user 7",
          "Associate Telegram ID 987654 with my account",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.list_identities",
        description =
          "List all registered channel identities for a user. If userId is omitted, lists for the current user.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"userId":{"type":"string","description":"Numeric user ID — omit to use the current user"}},"required":[]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("users.read")),
        examplePrompts = List(
          "What channels am I connected to?",
          "List all identities for user 3",
          "Show me my linked accounts",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.list_users",
        description = "List or search users in the system. Supports optional filters for name substring, active status, and pagination.",
        inputSchema = json"""{"type":"object","properties":{"page":{"type":"integer","description":"Zero-based page index (default 0)"},"pageSize":{"type":"integer","description":"Results per page (default 20)"},"nameContains":{"type":"string","description":"Filter by display name substring (case-insensitive)"},"active":{"type":"boolean","description":"Filter by active status"}},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("admin.user.list")),
        examplePrompts = List(
          "List all users",
          "Show me active users whose name contains Alice",
        ),
      ),
      ToolDescriptor(
        name = "user_mgmt.get_user",
        description = "Fetch a single user by their numeric ID. Returns an empty object if not found.",
        inputSchema = json"""{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID to get"}},"required":["userId"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"displayName":{"type":"string","description":"Human-readable name"},"email":{"type":"string","description":"Login email address"},"password":{"type":"string","description":"Initial password (min 12 chars)"}},"required":["displayName","email","password"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"},"displayName":{"type":"string","description":"New display name"},"email":{"type":"string","description":"New email address"}},"required":["userId"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID to deactivate"}},"required":["userId"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"page":{"type":"integer","description":"Zero-based page index (default 0)"},"pageSize":{"type":"integer","description":"Results per page (default 20)"}},"required":[]}""",
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
        inputSchema = json"""{"type":"object","properties":{"name":{"type":"string","description":"Role name"},"description":{"type":"string","description":"Optional description of what this role grants"}},"required":["name"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"},"roleId":{"type":"integer","description":"Numeric role ID"}},"required":["userId","roleId"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"},"roleId":{"type":"integer","description":"Numeric role ID"}},"required":["userId","roleId"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"}},"required":["userId"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"userId":{"type":"integer","description":"Numeric user ID"},"capability":{"type":"string","description":"Dot-separated capability name, e.g. shell.execute"},"approvalMode":{"type":"string","enum":["Persistent","Once","Session","Timed","PerInvocation","Denied"],"description":"Approval mode (default Persistent)"}},"required":["userId","capability"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"grantId":{"type":"integer","description":"Numeric capability grant ID"}},"required":["grantId"]}""",
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
      case "user_mgmt.find"             => find(args)
      case "user_mgmt.resolve_identity" => resolveIdentity(args)
      case "user_mgmt.link_identity"    => linkIdentity(ctx, args)
      case "user_mgmt.list_identities"  => listIdentities(ctx, args)
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

  // ─── User discovery & channel-identity ──────────────────────────────────────

  private def find(args: Json): IO[JorlanError, Json] = {
    val nameOpt = str(args, "name")
    for {
      users <- repos.user.search(UserSearch(fuzzyName = nameOpt, active = Some(true))).mapError(JorlanError(_))
      ranked = nameOpt.fold(users)(q => jorlan.service.FuzzyNameMatch.rank(users, q)(_.displayName))
      results <- ZIO.foreachPar(ranked) { u =>
        repos.user
          .getChannelIdentities(u.id).mapBoth(
            JorlanError(_),
            { identities =>
              val idJson = identities.map { ci =>
                Json.Obj(
                  "channelType"   -> Json.Str(ci.channelType.toString),
                  "channelUserId" -> Json.Str(ci.channelUserId),
                )
              }
              Json.Obj(
                "userId"      -> Json.Str(u.id.value.toString),
                "displayName" -> Json.Str(u.displayName),
                "identities"  -> Json.Arr(idJson*),
              )
            },
          )
      }
    } yield Json.Arr(results*)
  }

  private def resolveIdentity(args: Json): IO[JorlanError, Json] = {
    val channelTypeStr = str(args, "channelType")
    val channelUserId = str(args, "channelUserId")
    (channelTypeStr, channelUserId) match {
      case (None, _)              => ZIO.fail(JorlanError("user_mgmt.resolve_identity: channelType is required"))
      case (_, None)              => ZIO.fail(JorlanError("user_mgmt.resolve_identity: channelUserId is required"))
      case (Some(ct), Some(cuid)) =>
        parseChannelType(ct) match {
          case None         => ZIO.fail(JorlanError(s"user_mgmt.resolve_identity: unknown channelType '$ct'"))
          case Some(chType) =>
            repos.user
              .userByChannelIdentity(chType, cuid).mapBoth(
                JorlanError(_),
                {
                  case None    => Json.Obj("found" -> Json.Bool(false))
                  case Some(u) =>
                    Json.Obj(
                      "found"       -> Json.Bool(true),
                      "userId"      -> Json.Str(u.id.value.toString),
                      "displayName" -> Json.Str(u.displayName),
                      "email"       -> Json.Str(u.email),
                    )
                },
              )
        }
    }
  }

  private def linkIdentity(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val userIdRaw = str(args, "userId")
    val channelTypeStr = str(args, "channelType")
    val channelUserId = str(args, "channelUserId")
    val resolvedUserId: Either[String, UserId] = userIdRaw match {
      case None      => Right(ctx.actorId)
      case Some(uid) =>
        uid.toLongOption.map(UserId(_)).toRight(s"user_mgmt.link_identity: userId must be numeric, got '$uid'")
    }
    (resolvedUserId, channelTypeStr, channelUserId) match {
      case (_, None, _)      => ZIO.fail(JorlanError("user_mgmt.link_identity: channelType is required"))
      case (_, _, None)      => ZIO.fail(JorlanError("user_mgmt.link_identity: channelUserId is required"))
      case (Left(err), _, _) => ZIO.fail(JorlanError(err))
      case (Right(uid), Some(ct), Some(cuid)) =>
        parseChannelType(ct) match {
          case None         => ZIO.fail(JorlanError(s"user_mgmt.link_identity: unknown channelType '$ct'"))
          case Some(chType) =>
            Clock.instant.flatMap { now =>
              val ci = ChannelIdentity(
                id = ChannelIdentityId.empty,
                userId = uid,
                channelType = chType,
                channelUserId = cuid,
                verified = false,
                providerData = None,
                createdAt = now,
              )
              repos.user
                .upsertChannelIdentity(ci).mapBoth(
                  JorlanError(_),
                  { saved =>
                    Json.Obj(
                      "channelIdentityId" -> Json.Str(saved.id.value.toString),
                      "channelType"       -> Json.Str(saved.channelType.toString),
                      "channelUserId"     -> Json.Str(saved.channelUserId),
                    )
                  },
                )
            }
        }
    }
  }

  private def listIdentities(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val resolvedId = str(args, "userId") match {
      case None      => Right(ctx.actorId)
      case Some(uid) =>
        uid.toLongOption.map(UserId(_)).toRight(s"user_mgmt.list_identities: userId must be numeric, got '$uid'")
    }
    resolvedId match {
      case Left(err) => ZIO.fail(JorlanError(err))
      case Right(id) =>
        repos.user
          .getChannelIdentities(id).mapBoth(
            JorlanError(_),
            { identities =>
              Json.Arr(
                identities.map { ci =>
                  Json.Obj(
                    "channelType"   -> Json.Str(ci.channelType.toString),
                    "channelUserId" -> Json.Str(ci.channelUserId),
                    "verified"      -> Json.Bool(ci.verified),
                  )
                }*,
              )
            },
          )
    }
  }

  // ─── User operations ─────────────────────────────────────────────────────────

  private def listUsers(args: Json): IO[JorlanError, Json] = {
    val page = int(args, "page").getOrElse(0)
    val pageSize = int(args, "pageSize").getOrElse(20)
    if (page < 0) ZIO.fail(JorlanError("user_mgmt.list_users: page must be >= 0"))
    else if (pageSize <= 0) ZIO.fail(JorlanError("user_mgmt.list_users: pageSize must be > 0"))
    else {
      val nameContains = str(args, "nameContains")
      val active = bool(args, "active").orElse(Some(true))
      repos.user
        .search(UserSearch(nameContains = nameContains, active = active, page = page, pageSize = pageSize)).mapBoth(
          JorlanError(_),
          users => Json.Arr(users.map(userJson)*),
        )
    }
  }

  private def getUser(args: Json): IO[JorlanError, Json] = {
    requireLong(args, "get_user", "userId").flatMap { id =>
      repos.user
        .getById(UserId(id)).mapBoth(
          JorlanError(_),
          {
            case Some(u) => userJson(u)
            case None    => Json.Obj()
          },
        )
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
      displayName = str(args, "displayName").getOrElse(user.displayName)
      email = str(args, "email").getOrElse(user.email)
      now     <- Clock.instant
      updated <- repos.user
        .upsert(user.copy(displayName = displayName, email = email, updatedAt = now))
        .mapError(JorlanError(_))
    } yield userJson(updated)
  }

  private def deactivateUser(args: Json): IO[JorlanError, Json] = {
    requireLong(args, "deactivate_user", "userId").flatMap { id =>
      repos.user
        .deactivate(UserId(id)).mapBoth(JorlanError(_), count => Json.Obj("success" -> Json.Bool(count > 0)))
    }
  }

  // ─── Role operations ─────────────────────────────────────────────────────────

  private def listRoles(args: Json): IO[JorlanError, Json] = {
    val page = int(args, "page").getOrElse(0)
    val pageSize = int(args, "pageSize").getOrElse(20)
    if (page < 0) ZIO.fail(JorlanError("user_mgmt.list_roles: page must be >= 0"))
    else if (pageSize <= 0) ZIO.fail(JorlanError("user_mgmt.list_roles: pageSize must be > 0"))
    else
      repos.permission
        .searchRoles(RoleSearch(page = page, pageSize = pageSize)).mapBoth(
          JorlanError(_),
          roles => Json.Arr(roles.map(roleJson)*),
        )
  }

  private def createRole(args: Json): IO[JorlanError, Json] = {
    requireStr(args, "create_role", "name").flatMap { name =>
      val description = str(args, "description")
      repos.permission
        .upsertRole(Role(RoleId.empty, name, description)).mapBoth(JorlanError(_), roleJson)
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
        .searchGrants(GrantSearch(userId = Some(UserId(id)))).mapBoth(
          JorlanError(_),
          grants => Json.Arr(grants.map(grantJson)*),
        )
    }
  }

  private def grantCapability(args: Json): IO[JorlanError, Json] = {
    for {
      userId     <- requireLong(args, "grant_capability", "userId")
      capability <- requireStr(args, "grant_capability", "capability")
      approvalMode = str(args, "approvalMode").flatMap(parseApprovalMode).getOrElse(ApprovalMode.Persistent)
      now     <- Clock.instant
      created <- repos.permission
        .upsertCapabilityGrant(
          CapabilityGrant(
            id = CapabilityGrantId.empty,
            capability = CapabilityName(capability),
            scopeJson = None,
            granteeId = userId,
            granteeType = GranteeType.User,
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
        .revokeGrant(CapabilityGrantId(id)).mapBoth(JorlanError(_), _ => Json.Obj("success" -> Json.Bool(true)))
    }
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────────

  private def requireStr(
    args:  Json,
    tool:  String,
    field: String,
  ): IO[JorlanError, String] =
    ZIO
      .fromOption(str(args, field))
      .orElseFail(JorlanError(s"user_mgmt.$tool: $field is required"))

  private def requireLong(
    args:  Json,
    tool:  String,
    field: String,
  ): IO[JorlanError, Long] =
    args match {
      case Json.Obj(fields) =>
        fields.collectFirst { case (`field`, Json.Num(n)) => n } match {
          case Some(n) =>
            ZIO
              .attempt(n.longValueExact).orElseFail(JorlanError(s"user_mgmt.$tool: $field must be a 64-bit integer"))
          case None =>
            str(args, field) match {
              case Some(s) =>
                s.toLongOption match {
                  case Some(n) => ZIO.succeed(n)
                  case None    => ZIO.fail(JorlanError(s"user_mgmt.$tool: $field must be numeric, got '$s'"))
                }
              case None => ZIO.fail(JorlanError(s"user_mgmt.$tool: $field is required"))
            }
        }
      case _ =>
        str(args, field) match {
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
      "granteeId"    -> Json.Str(g.granteeId.toString),
      "granteeType"  -> Json.Str(g.granteeType.toString),
      "approvalMode" -> Json.Str(g.approvalMode.toString),
      "createdAt"    -> Json.Str(g.createdAt.toString),
    )

}

object UserManagementSkill
