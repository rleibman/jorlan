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
import zio.stream.ZStream

import java.time.Instant

/** Caliban GraphQL schema for the Jorlan control-plane API. */
object JorlanAPI {

  type JorlanApiEnv =
    UserService & PermissionService & CapabilityEvaluator & AgentSessionManager & AgentRunner & PersonalityService

  // ─── ArgBuilder instances for opaque ID types, if you remove them you won't get nice Ids in the gql schema ────────────────────────────────

  private given ArgBuilder[UserId] = ArgBuilder.long.map(UserId(_))
  private given ArgBuilder[RoleId] = ArgBuilder.long.map(RoleId(_))
  private given ArgBuilder[PermissionId] = ArgBuilder.long.map(PermissionId(_))
  private given ArgBuilder[AgentId] = ArgBuilder.long.map(AgentId(_))
  private given ArgBuilder[AgentSessionId] = ArgBuilder.long.map(AgentSessionId(_))
  private given ArgBuilder[EventLogId] = ArgBuilder.long.map(EventLogId(_))
  private given ArgBuilder[WorkspaceId] = ArgBuilder.long.map(WorkspaceId(_))
  private given ArgBuilder[ModelId] = ArgBuilder.string.map(ModelId(_))
  private given ArgBuilder[CapabilityName] = ArgBuilder.string.map(CapabilityName(_))

  // ─── Query input types ────────────────────────────────────────────────────────

  /** Input for `user(id)` */
  case class UserByIdInput(id: UserId) derives Schema.SemiAuto, ArgBuilder

  /** Input for `role(id)` */
  case class RoleByIdInput(id: RoleId) derives Schema.SemiAuto, ArgBuilder

  /** Input for `revokePermission(id)` */
  case class PermissionByIdInput(id: PermissionId) derives Schema.SemiAuto, ArgBuilder

  /** Standard pagination input used by list queries. Defaults: `page = 0`, `pageSize = 20`. */
  case class PaginationInput(
    page:     Option[Int] = None,
    pageSize: Option[Int] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `roles(userId)` — returns roles assigned to the given user. */
  case class RolesForUserInput(
    userId:   UserId,
    page:     Option[Int] = None,
    pageSize: Option[Int] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `permissions(userId)` — returns permissions granted to the given user. */
  case class PermissionsForUserInput(
    userId:   UserId,
    page:     Option[Int] = None,
    pageSize: Option[Int] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  // ─── Mutation input types ─────────────────────────────────────────────────────

  /** Input for `createUser`. */
  case class CreateUserInput(
    displayName: String,
    email:       Option[String],
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `updateUser`. `active = false` soft-deletes the account. */
  case class UpdateUserInput(
    id:          UserId,
    displayName: String,
    email:       Option[String],
    active:      Boolean,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `createRole`. */
  case class CreateRoleInput(
    name:        String,
    description: Option[String],
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `assignRole` / `revokeRole`. */
  case class AssignRoleInput(
    userId: UserId,
    roleId: RoleId,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `grantPermission`. Exactly one of `userId` or `roleId` must be provided. */
  case class GrantPermissionInput(
    resource: String,
    action:   String,
    userId:   Option[UserId],
    roleId:   Option[RoleId],
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `createSession`. `modelId = null` uses the agent's configured default model. */
  case class CreateSessionInput(
    modelId: Option[ModelId],
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `submitMessage` — sends a user message to the active agent session. */
  case class SubmitMessageInput(
    sessionId: AgentSessionId,
    content:   String,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for the `agentResponseStream` subscription. */
  case class AgentResponseStreamInput(
    sessionId: AgentSessionId,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `updatePersonality`. All fields are required — send the complete personality object. */
  case class UpdatePersonalityInput(
    name:      String,
    formality: Formality,
    languages: List[String],
    expertise: List[String],
    prompt:    String,
  ) derives Schema.SemiAuto, ArgBuilder

  extension (i: UpdatePersonalityInput) {

    def toPersonality: Personality = Personality(i.name, i.formality, i.languages, i.expertise, i.prompt)

  }

  // ─── Query / Mutation / Subscription containers ───────────────────────────────

  case class Queries(
    user:         UserByIdInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Option[User]],
    users:        PaginationInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[User]],
    role:         RoleByIdInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Option[Role]],
    roles:        RolesForUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[Role]],
    permissions:  PermissionsForUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[Permission]],
    listSessions: PaginationInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[AgentSession]],
    /** Returns the current server personality. Requires `admin.personality.read` capability. */
    serverPersonality: ZIO[JorlanApiEnv & JorlanSession, JorlanError, Personality],
  )

  case class Mutations(
    createUser:       CreateUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, User],
    updateUser:       UpdateUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, User],
    createRole:       CreateRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Role],
    assignRole:       AssignRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Unit],
    revokeRole:       AssignRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Unit],
    grantPermission:  GrantPermissionInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Permission],
    revokePermission: PermissionByIdInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Long],
    createSession:    CreateSessionInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, AgentSession],
    submitMessage:    SubmitMessageInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Unit],
    /** Replaces the server personality. Requires `admin.personality.update` capability. */
    updatePersonality: UpdatePersonalityInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Personality],
  )

  case class Subscriptions(
    approvalNotifications: ZStream[JorlanApiEnv & JorlanSession, JorlanError, ApprovalRequest],
    eventLogTail:          ZStream[JorlanApiEnv & JorlanSession, JorlanError, EventLog[Json]],
    agentResponseStream:   AgentResponseStreamInput => ZStream[JorlanApiEnv & JorlanSession, JorlanError, ResponseChunk],
  )

  // ─── Schema instances ─────────────────────────────────────────────────────────

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
  private given Schema[Any, WorkspaceId] =
    Schema.scalarSchema("WorkspaceId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, CapabilityName] =
    Schema.scalarSchema("CapabilityName", None, None, None, cn => Value.StringValue(cn.value))
  private given Schema[Any, ModelId] =
    Schema.scalarSchema("ModelId", None, None, None, id => Value.StringValue(id.value))
  private given Schema[Any, RiskClass] =
    Schema.scalarSchema("RiskClass", None, None, None, r => Value.StringValue(r.toString))
  private given Schema[Any, Json] = Schema.stringSchema.contramap(j => JsonEncoder[Json].encodeJson(j, None).toString)
  private given Schema[Any, ChannelType] =
    Schema.scalarSchema("ChannelType", None, None, None, e => Value.StringValue(e.toString))
  private given Schema[Any, ApprovalStatus] =
    Schema.scalarSchema("ApprovalStatus", None, None, None, e => Value.StringValue(e.toString))
  private given Schema[Any, ApprovalMode] =
    Schema.scalarSchema("ApprovalMode", None, None, None, e => Value.StringValue(e.toString))
  private given Schema[Any, EventType] =
    Schema.scalarSchema("EventType", None, None, None, e => Value.StringValue(e.toString))
  private given Schema[Any, SessionStatus] =
    Schema.scalarSchema("SessionStatus", None, None, None, e => Value.StringValue(e.toString))
  private given Schema[Any, Formality] =
    Schema.scalarSchema("Formality", None, None, None, e => Value.StringValue(e.toString))

  private given ArgBuilder[ChannelType] = ArgBuilder.string.map(ChannelType.valueOf)
  private given ArgBuilder[ApprovalStatus] = ArgBuilder.string.map(ApprovalStatus.valueOf)
  private given ArgBuilder[ApprovalMode] = ArgBuilder.string.map(ApprovalMode.valueOf)
  private given ArgBuilder[EventType] = ArgBuilder.string.map(EventType.valueOf)
  private given ArgBuilder[SessionStatus] = ArgBuilder.string.map(SessionStatus.valueOf)
  private given ArgBuilder[RiskClass] = ArgBuilder.string.map(RiskClass.valueOf)
  private given ArgBuilder[Formality] = ArgBuilder.string.map(Formality.valueOf)

  private given Schema[Any, User] = Schema.gen[Any, User]
  private given Schema[Any, Role] = Schema.gen[Any, Role]
  private given Schema[Any, Permission] = Schema.gen[Any, Permission]
  private given Schema[Any, ApprovalRequest] = Schema.gen[Any, ApprovalRequest]
  private given Schema[Any, EventLog[Json]] = Schema.gen[Any, EventLog[Json]]
  private given Schema[Any, AgentSession] = Schema.gen[Any, AgentSession]
  private given Schema[Any, ResponseChunk] = Schema.gen[Any, ResponseChunk]
  private given Schema[Any, Personality] = Schema.gen[Any, Personality]

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
          user = input => ZIO.serviceWithZIO[UserService](_.getById(input.id)),
          users = input =>
            ZIO.serviceWithZIO[UserService](
              _.search(UserSearch(page = input.page.getOrElse(0), pageSize = input.pageSize.getOrElse(20))),
            ),
          role = input => ZIO.serviceWithZIO[PermissionService](_.getRole(input.id)),
          roles = input =>
            ZIO
              .serviceWithZIO[PermissionService](
                _.searchRoles(
                  RoleSearch(
                    userId = input.userId,
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
                    userId = Some(input.userId),
                    page = input.page.getOrElse(0),
                    pageSize = input.pageSize.getOrElse(20),
                  ),
                ),
              ),
          listSessions = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("agent.session.list", actorId)
              results <- ZIO.serviceWithZIO[AgentSessionManager](
                _.listSessions(actorId, input.page.getOrElse(0), input.pageSize.getOrElse(20)),
              )
            } yield results,
          serverPersonality = for {
            actorId <- actorIdFromSession
            _       <- requireCapability("admin.personality.read", actorId)
            p       <- ZIO.serviceWithZIO[PersonalityService](_.get())
          } yield p,
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
                  _.updateUser(input.id, input.displayName, input.email, input.active, Some(actorId)),
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
                  _.assignRole(input.userId, input.roleId, Some(actorId)),
                )
            } yield (),
          revokeRole = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("role.revoke", actorId)
              _       <- ZIO
                .serviceWithZIO[PermissionService](
                  _.removeRole(input.userId, input.roleId, Some(actorId)),
                )
            } yield (),
          grantPermission = input =>
            for {
              _ <- ZIO
                .when(input.userId.isDefined == input.roleId.isDefined)(
                  ZIO.fail(JorlanError("A permission must target exactly one of userId or roleId")),
                )
              actorId <- actorIdFromSession
              _       <- requireCapability("permission.grant", actorId)
              perm    <- ZIO
                .serviceWithZIO[PermissionService](
                  _.upsertPermission(
                    Permission(
                      PermissionId.empty,
                      input.roleId,
                      input.userId,
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
              count   <- ZIO.serviceWithZIO[PermissionService](_.deletePermission(input.id))
            } yield count,
          createSession = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("agent.session.create", actorId)
              session <- ZIO.serviceWithZIO[AgentSessionManager](
                _.createSession(actorId, input.modelId),
              )
            } yield session,
          submitMessage = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("agent.message", actorId)
              _       <- ZIO
                .serviceWithZIO[AgentRunner](_.processMessage(input.sessionId, input.content, Some(actorId)))
                .tapError(e => ZIO.logError(s"AgentRunner failed for session ${input.sessionId}: ${e.getMessage}"))
                .forkDaemon
            } yield (),
          updatePersonality = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.personality.update", actorId)
              updated <- ZIO.serviceWithZIO[PersonalityService](_.update(input.toPersonality))
            } yield updated,
        ),
        Subscriptions(
          approvalNotifications = ZStream.empty,
          eventLogTail = ZStream.empty,
          agentResponseStream = input => ZStream.serviceWithStream[AgentRunner](_.subscribeToSession(input.sessionId)),
        ),
      ),
    ) @@ maxFields(200) @@ maxDepth(20)

}
