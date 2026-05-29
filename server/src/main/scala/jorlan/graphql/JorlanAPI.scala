/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.graphql

import caliban.*
import caliban.interop.zio.json.*
import caliban.schema.*
import caliban.schema.Schema.auto.*
import caliban.schema.ArgBuilder.auto.*
import caliban.wrappers.Wrappers.*
import jorlan.*
import jorlan.domain.*
import jorlan.service.*
import zio.*
import zio.json.JsonEncoder
import zio.json.ast.Json

import java.time.Instant

/** Caliban GraphQL schema for the Jorlan control-plane API.
  *
  * Queries cover users / roles / permissions. Mutations cover CRUD for those entities plus role assignment and
  * permission grants. Subscription infrastructure is wired but stubs empty streams until Phase 8 adds live sources.
  */
object JorlanAPI {

  type JorlanApiEnv = UserService & PermissionService & CapabilityEvaluator

  // ─── Query input types ────────────────────────────────────────────────────────

  case class EntityIdInput(id: Long) derives Schema.SemiAuto, ArgBuilder

  case class PaginationInput(
    page:     Option[Int] = None,
    pageSize: Option[Int] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  case class RolesForUserInput(
    userId:   Long,
    page:     Option[Int] = None,
    pageSize: Option[Int] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  case class PermissionsForUserInput(
    userId:   Long,
    page:     Option[Int] = None,
    pageSize: Option[Int] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  // ─── Mutation input types ─────────────────────────────────────────────────────

  case class CreateUserInput(
    displayName: String,
    email:       Option[String],
  ) derives Schema.SemiAuto, ArgBuilder

  case class UpdateUserInput(
    id:          Long,
    displayName: String,
    email:       Option[String],
    active:      Boolean,
  ) derives Schema.SemiAuto, ArgBuilder

  case class CreateRoleInput(
    name:        String,
    description: Option[String],
  ) derives Schema.SemiAuto, ArgBuilder

  case class AssignRoleInput(
    userId: Long,
    roleId: Long,
  ) derives Schema.SemiAuto, ArgBuilder

  case class GrantPermissionInput(
    resource: String,
    action:   String,
    userId:   Option[Long],
    roleId:   Option[Long],
  ) derives Schema.SemiAuto, ArgBuilder

  // ─── Query / Mutation / Subscription containers ───────────────────────────────

  case class Queries(
    user:        EntityIdInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Option[User]],
    users:       PaginationInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[User]],
    role:        EntityIdInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Option[Role]],
    roles:       RolesForUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[Role]],
    permissions: PermissionsForUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[Permission]],
  )

  case class Mutations(
    createUser:       CreateUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, User],
    updateUser:       UpdateUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, User],
    createRole:       CreateRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Role],
    assignRole:       AssignRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Unit],
    revokeRole:       AssignRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Unit],
    grantPermission:  GrantPermissionInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Permission],
    revokePermission: EntityIdInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Long],
  )

  case class Subscriptions(
    approvalNotifications: zio.stream.ZStream[JorlanApiEnv & JorlanSession, JorlanError, ApprovalRequest],
    eventLogTail:          zio.stream.ZStream[JorlanApiEnv & JorlanSession, JorlanError, EventLog[Json]],
  )

  // ─── Schema instances ─────────────────────────────────────────────────────────
  // Named scalars so the SDL (and generated client) carry typed IDs rather than
  // anonymous Long values. Each scalarSchema call registers a distinct GraphQL
  // scalar name while serialising to/from the underlying Long or String value.

  private given Schema[Any, UserId] =
    Schema.scalarSchema("UserId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, RoleId] =
    Schema.scalarSchema("RoleId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, PermissionId] =
    Schema.scalarSchema("PermissionId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, AgentId] =
    Schema.scalarSchema("AgentId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, AgentSessionId] =
    Schema.scalarSchema("AgentSessionId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, ApprovalRequestId] =
    Schema.scalarSchema("ApprovalRequestId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, EventLogId] =
    Schema.scalarSchema("EventLogId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, CapabilityName] =
    Schema.scalarSchema("CapabilityName", None, None, None, cn => Value.StringValue(cn.value))
  private given Schema[Any, RiskClass] = Schema.stringSchema.contramap(_.toString)
  private given Schema[Any, Json] = Schema.stringSchema.contramap(j => JsonEncoder[Json].encodeJson(j, None).toString)

  private given Schema[Any, ChannelType] = Schema.gen[Any, ChannelType]
  private given Schema[Any, ApprovalStatus] = Schema.gen[Any, ApprovalStatus]
  private given Schema[Any, ApprovalMode] = Schema.gen[Any, ApprovalMode]
  private given Schema[Any, EventType] = Schema.gen[Any, EventType]

  private given Schema[Any, User] = Schema.gen[Any, User]
  private given Schema[Any, Role] = Schema.gen[Any, Role]
  private given Schema[Any, Permission] = Schema.gen[Any, Permission]
  private given Schema[Any, ApprovalRequest] = Schema.gen[Any, ApprovalRequest]
  private given Schema[Any, EventLog[Json]] = Schema.gen[Any, EventLog[Json]]

  // ─── Authorization helpers ────────────────────────────────────────────────────

  private val actorIdFromSession: ZIO[JorlanSession, JorlanError, UserId] =
    ZIO
      .serviceWith[JorlanSession](_.user.map(_.id))
      .someOrFail(JorlanError("Not authenticated"))

  private def requireCapability(
    cap:    String,
    userId: UserId,
  ): ZIO[CapabilityEvaluator, JorlanError, Unit] =
    CapabilityEvaluator
      .evaluate(CapabilityRequest(CapabilityName(cap), userId, None, None, None))
      .flatMap {
        case EvaluationResult.ExplicitDeny => ZIO.fail(JorlanError(s"Access denied: explicit deny on '$cap'"))
        case EvaluationResult.DefaultDeny  => ZIO.fail(JorlanError(s"Access denied: no permission for '$cap'"))
        case _                             => ZIO.unit
      }

  // ─── API ─────────────────────────────────────────────────────────────────────

  val api: GraphQL[JorlanApiEnv & JorlanSession] =
    graphQL[
      JorlanApiEnv & JorlanSession,
      Queries,
      Mutations,
      Subscriptions,
    ](
      RootResolver(
        Queries(
          user = input => ZIO.serviceWithZIO[UserService](_.getById(UserId(input.id))),
          users = input =>
            ZIO.serviceWithZIO[UserService](
              _.search(UserSearch(page = input.page.getOrElse(0), pageSize = input.pageSize.getOrElse(20))),
            ),
          role = input => ZIO.serviceWithZIO[PermissionService](_.getRole(RoleId(input.id))),
          roles = input =>
            ZIO
              .serviceWithZIO[PermissionService](
                _.searchRoles(
                  RoleSearch(
                    userId = UserId(input.userId),
                    page = input.page.getOrElse(0),
                    pageSize = input.pageSize.getOrElse(20),
                  ),
                ),
              ),
          permissions = input =>
            ZIO
              .serviceWithZIO[PermissionService](
                _.searchPermissions(
                  PermissionSearch(
                    userId = Some(UserId(input.userId)),
                    page = input.page.getOrElse(0),
                    pageSize = input.pageSize.getOrElse(20),
                  ),
                ),
              ),
        ),
        Mutations(
          createUser = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("user.create", actorId)
              user    <- ZIO.serviceWithZIO[UserService](_.createUser(input.displayName, input.email, Some(actorId)))
            } yield user,
          updateUser = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("user.update", actorId)
              user    <- ZIO
                .serviceWithZIO[UserService](
                  _.updateUser(UserId(input.id), input.displayName, input.email, input.active, Some(actorId)),
                )
            } yield user,
          createRole = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("role.create", actorId)
              role    <- ZIO
                .serviceWithZIO[PermissionService](
                  _.upsertRole(Role(RoleId.empty, input.name, input.description)),
                )
            } yield role,
          assignRole = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("role.assign", actorId)
              _       <- ZIO
                .serviceWithZIO[PermissionService](
                  _.assignRole(UserId(input.userId), RoleId(input.roleId), Some(actorId)),
                )
            } yield (),
          revokeRole = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("role.revoke", actorId)
              _       <- ZIO
                .serviceWithZIO[PermissionService](
                  _.removeRole(UserId(input.userId), RoleId(input.roleId), Some(actorId)),
                )
            } yield (),
          grantPermission = input =>
            for {
              _ <- ZIO
                .unless(input.userId.isDefined != input.roleId.isDefined)(
                  ZIO.fail(JorlanError("A permission must target exactly one of userId or roleId")),
                )
              actorId <- actorIdFromSession
              _       <- requireCapability("permission.grant", actorId)
              perm    <- ZIO
                .serviceWithZIO[PermissionService](
                  _.upsertPermission(
                    Permission(
                      PermissionId.empty,
                      input.roleId.map(RoleId(_)),
                      input.userId.map(UserId(_)),
                      input.resource,
                      input.action,
                      None,
                    ),
                  ),
                )
            } yield perm,
          revokePermission = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("permission.revoke", actorId)
              count   <- ZIO.serviceWithZIO[PermissionService](_.deletePermission(PermissionId(input.id)))
            } yield count,
        ),
        Subscriptions(
          approvalNotifications = zio.stream.ZStream.empty,
          eventLogTail = zio.stream.ZStream.empty,
        ),
      ),
    ) @@ maxFields(200) @@ maxDepth(20)

}
