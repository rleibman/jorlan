/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.graphql.client

import caliban.client.CalibanClientError.DecodingError
import caliban.client.FieldBuilder._
import caliban.client._
import caliban.client.__Value._

import jorlan.domain._
import jorlan.shell.client.JorlanClientDecoders._

object JorlanClient {

  sealed trait ApprovalStatus extends scala.Product with scala.Serializable { def value: String }
  object ApprovalStatus {

    case object Approved extends ApprovalStatus { val value: String = "Approved" }
    case object Cancelled extends ApprovalStatus { val value: String = "Cancelled" }
    case object Expired extends ApprovalStatus { val value: String = "Expired" }
    case object Pending extends ApprovalStatus { val value: String = "Pending" }
    case object Rejected extends ApprovalStatus { val value: String = "Rejected" }

    implicit val decoder: ScalarDecoder[ApprovalStatus] = {
      case __StringValue("Approved")  => Right(ApprovalStatus.Approved)
      case __StringValue("Cancelled") => Right(ApprovalStatus.Cancelled)
      case __StringValue("Expired")   => Right(ApprovalStatus.Expired)
      case __StringValue("Pending")   => Right(ApprovalStatus.Pending)
      case __StringValue("Rejected")  => Right(ApprovalStatus.Rejected)
      case other                      => Left(DecodingError(s"Can't build ApprovalStatus from input $other"))
    }
    implicit val encoder: ArgEncoder[ApprovalStatus] = {
      case ApprovalStatus.Approved  => __EnumValue("Approved")
      case ApprovalStatus.Cancelled => __EnumValue("Cancelled")
      case ApprovalStatus.Expired   => __EnumValue("Expired")
      case ApprovalStatus.Pending   => __EnumValue("Pending")
      case ApprovalStatus.Rejected  => __EnumValue("Rejected")
    }

    val values: scala.collection.immutable.Vector[ApprovalStatus] =
      scala.collection.immutable.Vector(Approved, Cancelled, Expired, Pending, Rejected)

  }

  sealed trait EventType extends scala.Product with scala.Serializable { def value: String }
  object EventType {

    case object AgentCompleted extends EventType { val value: String = "AgentCompleted" }
    case object AgentFailed extends EventType { val value: String = "AgentFailed" }
    case object AgentResponseCompleted extends EventType { val value: String = "AgentResponseCompleted" }
    case object AgentStarted extends EventType { val value: String = "AgentStarted" }
    case object ApprovalDenied extends EventType { val value: String = "ApprovalDenied" }
    case object ApprovalGranted extends EventType { val value: String = "ApprovalGranted" }
    case object ApprovalRequested extends EventType { val value: String = "ApprovalRequested" }
    case object CapabilityAllowed extends EventType { val value: String = "CapabilityAllowed" }
    case object CapabilityDenied extends EventType { val value: String = "CapabilityDenied" }
    case object CapabilityGranted extends EventType { val value: String = "CapabilityGranted" }
    case object CapabilityRevoked extends EventType { val value: String = "CapabilityRevoked" }
    case object MemoryExpired extends EventType { val value: String = "MemoryExpired" }
    case object MemoryWritten extends EventType { val value: String = "MemoryWritten" }
    case object ModelCallCompleted extends EventType { val value: String = "ModelCallCompleted" }
    case object ModelCallFailed extends EventType { val value: String = "ModelCallFailed" }
    case object ModelCallStarted extends EventType { val value: String = "ModelCallStarted" }
    case object PermissionGranted extends EventType { val value: String = "PermissionGranted" }
    case object PermissionRevoked extends EventType { val value: String = "PermissionRevoked" }
    case object RoleAssigned extends EventType { val value: String = "RoleAssigned" }
    case object RoleRevoked extends EventType { val value: String = "RoleRevoked" }
    case object SessionCreated extends EventType { val value: String = "SessionCreated" }
    case object SessionSuspended extends EventType { val value: String = "SessionSuspended" }
    case object SessionTerminated extends EventType { val value: String = "SessionTerminated" }
    case object SkillFailed extends EventType { val value: String = "SkillFailed" }
    case object SkillInvoked extends EventType { val value: String = "SkillInvoked" }
    case object SkillSucceeded extends EventType { val value: String = "SkillSucceeded" }
    case object SystemAlert extends EventType { val value: String = "SystemAlert" }
    case object UserConnected extends EventType { val value: String = "UserConnected" }
    case object UserCreated extends EventType { val value: String = "UserCreated" }
    case object UserDisconnected extends EventType { val value: String = "UserDisconnected" }
    case object UserMessageReceived extends EventType { val value: String = "UserMessageReceived" }
    case object UserUpdated extends EventType { val value: String = "UserUpdated" }

    implicit val decoder: ScalarDecoder[EventType] = {
      case __StringValue("AgentCompleted")         => Right(EventType.AgentCompleted)
      case __StringValue("AgentFailed")            => Right(EventType.AgentFailed)
      case __StringValue("AgentResponseCompleted") => Right(EventType.AgentResponseCompleted)
      case __StringValue("AgentStarted")           => Right(EventType.AgentStarted)
      case __StringValue("ApprovalDenied")         => Right(EventType.ApprovalDenied)
      case __StringValue("ApprovalGranted")        => Right(EventType.ApprovalGranted)
      case __StringValue("ApprovalRequested")      => Right(EventType.ApprovalRequested)
      case __StringValue("CapabilityAllowed")      => Right(EventType.CapabilityAllowed)
      case __StringValue("CapabilityDenied")       => Right(EventType.CapabilityDenied)
      case __StringValue("CapabilityGranted")      => Right(EventType.CapabilityGranted)
      case __StringValue("CapabilityRevoked")      => Right(EventType.CapabilityRevoked)
      case __StringValue("MemoryExpired")          => Right(EventType.MemoryExpired)
      case __StringValue("MemoryWritten")          => Right(EventType.MemoryWritten)
      case __StringValue("ModelCallCompleted")     => Right(EventType.ModelCallCompleted)
      case __StringValue("ModelCallFailed")        => Right(EventType.ModelCallFailed)
      case __StringValue("ModelCallStarted")       => Right(EventType.ModelCallStarted)
      case __StringValue("PermissionGranted")      => Right(EventType.PermissionGranted)
      case __StringValue("PermissionRevoked")      => Right(EventType.PermissionRevoked)
      case __StringValue("RoleAssigned")           => Right(EventType.RoleAssigned)
      case __StringValue("RoleRevoked")            => Right(EventType.RoleRevoked)
      case __StringValue("SessionCreated")         => Right(EventType.SessionCreated)
      case __StringValue("SessionSuspended")       => Right(EventType.SessionSuspended)
      case __StringValue("SessionTerminated")      => Right(EventType.SessionTerminated)
      case __StringValue("SkillFailed")            => Right(EventType.SkillFailed)
      case __StringValue("SkillInvoked")           => Right(EventType.SkillInvoked)
      case __StringValue("SkillSucceeded")         => Right(EventType.SkillSucceeded)
      case __StringValue("SystemAlert")            => Right(EventType.SystemAlert)
      case __StringValue("UserConnected")          => Right(EventType.UserConnected)
      case __StringValue("UserCreated")            => Right(EventType.UserCreated)
      case __StringValue("UserDisconnected")       => Right(EventType.UserDisconnected)
      case __StringValue("UserMessageReceived")    => Right(EventType.UserMessageReceived)
      case __StringValue("UserUpdated")            => Right(EventType.UserUpdated)
      case other                                   => Left(DecodingError(s"Can't build EventType from input $other"))
    }
    implicit val encoder: ArgEncoder[EventType] = {
      case EventType.AgentCompleted         => __EnumValue("AgentCompleted")
      case EventType.AgentFailed            => __EnumValue("AgentFailed")
      case EventType.AgentResponseCompleted => __EnumValue("AgentResponseCompleted")
      case EventType.AgentStarted           => __EnumValue("AgentStarted")
      case EventType.ApprovalDenied         => __EnumValue("ApprovalDenied")
      case EventType.ApprovalGranted        => __EnumValue("ApprovalGranted")
      case EventType.ApprovalRequested      => __EnumValue("ApprovalRequested")
      case EventType.CapabilityAllowed      => __EnumValue("CapabilityAllowed")
      case EventType.CapabilityDenied       => __EnumValue("CapabilityDenied")
      case EventType.CapabilityGranted      => __EnumValue("CapabilityGranted")
      case EventType.CapabilityRevoked      => __EnumValue("CapabilityRevoked")
      case EventType.MemoryExpired          => __EnumValue("MemoryExpired")
      case EventType.MemoryWritten          => __EnumValue("MemoryWritten")
      case EventType.ModelCallCompleted     => __EnumValue("ModelCallCompleted")
      case EventType.ModelCallFailed        => __EnumValue("ModelCallFailed")
      case EventType.ModelCallStarted       => __EnumValue("ModelCallStarted")
      case EventType.PermissionGranted      => __EnumValue("PermissionGranted")
      case EventType.PermissionRevoked      => __EnumValue("PermissionRevoked")
      case EventType.RoleAssigned           => __EnumValue("RoleAssigned")
      case EventType.RoleRevoked            => __EnumValue("RoleRevoked")
      case EventType.SessionCreated         => __EnumValue("SessionCreated")
      case EventType.SessionSuspended       => __EnumValue("SessionSuspended")
      case EventType.SessionTerminated      => __EnumValue("SessionTerminated")
      case EventType.SkillFailed            => __EnumValue("SkillFailed")
      case EventType.SkillInvoked           => __EnumValue("SkillInvoked")
      case EventType.SkillSucceeded         => __EnumValue("SkillSucceeded")
      case EventType.SystemAlert            => __EnumValue("SystemAlert")
      case EventType.UserConnected          => __EnumValue("UserConnected")
      case EventType.UserCreated            => __EnumValue("UserCreated")
      case EventType.UserDisconnected       => __EnumValue("UserDisconnected")
      case EventType.UserMessageReceived    => __EnumValue("UserMessageReceived")
      case EventType.UserUpdated            => __EnumValue("UserUpdated")
    }

    val values: scala.collection.immutable.Vector[EventType] = scala.collection.immutable.Vector(
      AgentCompleted,
      AgentFailed,
      AgentResponseCompleted,
      AgentStarted,
      ApprovalDenied,
      ApprovalGranted,
      ApprovalRequested,
      CapabilityAllowed,
      CapabilityDenied,
      CapabilityGranted,
      CapabilityRevoked,
      MemoryExpired,
      MemoryWritten,
      ModelCallCompleted,
      ModelCallFailed,
      ModelCallStarted,
      PermissionGranted,
      PermissionRevoked,
      RoleAssigned,
      RoleRevoked,
      SessionCreated,
      SessionSuspended,
      SessionTerminated,
      SkillFailed,
      SkillInvoked,
      SkillSucceeded,
      SystemAlert,
      UserConnected,
      UserCreated,
      UserDisconnected,
      UserMessageReceived,
      UserUpdated,
    )

  }

  sealed trait SessionStatus extends scala.Product with scala.Serializable { def value: String }
  object SessionStatus {

    case object Active extends SessionStatus { val value: String = "Active" }
    case object Blocked extends SessionStatus { val value: String = "Blocked" }
    case object Cancelled extends SessionStatus { val value: String = "Cancelled" }
    case object Completed extends SessionStatus { val value: String = "Completed" }
    case object Created extends SessionStatus { val value: String = "Created" }
    case object Failed extends SessionStatus { val value: String = "Failed" }
    case object Paused extends SessionStatus { val value: String = "Paused" }

    implicit val decoder: ScalarDecoder[SessionStatus] = {
      case __StringValue("Active")    => Right(SessionStatus.Active)
      case __StringValue("Blocked")   => Right(SessionStatus.Blocked)
      case __StringValue("Cancelled") => Right(SessionStatus.Cancelled)
      case __StringValue("Completed") => Right(SessionStatus.Completed)
      case __StringValue("Created")   => Right(SessionStatus.Created)
      case __StringValue("Failed")    => Right(SessionStatus.Failed)
      case __StringValue("Paused")    => Right(SessionStatus.Paused)
      case other                      => Left(DecodingError(s"Can't build SessionStatus from input $other"))
    }
    implicit val encoder: ArgEncoder[SessionStatus] = {
      case SessionStatus.Active    => __EnumValue("Active")
      case SessionStatus.Blocked   => __EnumValue("Blocked")
      case SessionStatus.Cancelled => __EnumValue("Cancelled")
      case SessionStatus.Completed => __EnumValue("Completed")
      case SessionStatus.Created   => __EnumValue("Created")
      case SessionStatus.Failed    => __EnumValue("Failed")
      case SessionStatus.Paused    => __EnumValue("Paused")
    }

    val values: scala.collection.immutable.Vector[SessionStatus] =
      scala.collection.immutable.Vector(Active, Blocked, Cancelled, Completed, Created, Failed, Paused)

  }

  type AgentSession
  object AgentSession {

    final case class AgentSessionView(
      id:          jorlan.domain.AgentSessionId,
      agentId:     jorlan.domain.AgentId,
      userId:      jorlan.domain.UserId,
      workspaceId: scala.Option[jorlan.domain.WorkspaceId],
      status:      SessionStatus,
      modelId:     scala.Option[jorlan.domain.ModelId],
      createdAt:   java.time.Instant,
      updatedAt:   java.time.Instant,
    )

    type ViewSelection = SelectionBuilder[AgentSession, AgentSessionView]

    def view: ViewSelection =
      (id ~ agentId ~ userId ~ workspaceId ~ status ~ modelId ~ createdAt ~ updatedAt).map {
        case (id, agentId, userId, workspaceId, status, modelId, createdAt, updatedAt) =>
          AgentSessionView(id, agentId, userId, workspaceId, status, modelId, createdAt, updatedAt)
      }

    def id: SelectionBuilder[AgentSession, jorlan.domain.AgentSessionId] =
      _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def agentId: SelectionBuilder[AgentSession, jorlan.domain.AgentId] =
      _root_.caliban.client.SelectionBuilder.Field("agentId", Scalar())
    def userId: SelectionBuilder[AgentSession, jorlan.domain.UserId] =
      _root_.caliban.client.SelectionBuilder.Field("userId", Scalar())
    def workspaceId: SelectionBuilder[AgentSession, scala.Option[jorlan.domain.WorkspaceId]] =
      _root_.caliban.client.SelectionBuilder.Field("workspaceId", OptionOf(Scalar()))
    def status: SelectionBuilder[AgentSession, SessionStatus] =
      _root_.caliban.client.SelectionBuilder.Field("status", Scalar())
    def modelId: SelectionBuilder[AgentSession, scala.Option[jorlan.domain.ModelId]] =
      _root_.caliban.client.SelectionBuilder.Field("modelId", OptionOf(Scalar()))
    def createdAt: SelectionBuilder[AgentSession, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
    def updatedAt: SelectionBuilder[AgentSession, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("updatedAt", Scalar())

  }

  type ApprovalRequest
  object ApprovalRequest {

    final case class ApprovalRequestView(
      id:              jorlan.domain.ApprovalRequestId,
      capability:      jorlan.domain.CapabilityName,
      scopeJson:       scala.Option[String],
      agentId:         scala.Option[jorlan.domain.AgentId],
      requestorUserId: jorlan.domain.UserId,
      sessionId:       scala.Option[jorlan.domain.AgentSessionId],
      riskClass:       String,
      status:          ApprovalStatus,
      createdAt:       java.time.Instant,
      expiresAt:       scala.Option[java.time.Instant],
    )

    type ViewSelection = SelectionBuilder[ApprovalRequest, ApprovalRequestView]

    def view: ViewSelection =
      (id ~ capability ~ scopeJson ~ agentId ~ requestorUserId ~ sessionId ~ riskClass ~ status ~ createdAt ~ expiresAt)
        .map {
          case (
                id,
                capability,
                scopeJson,
                agentId,
                requestorUserId,
                sessionId,
                riskClass,
                status,
                createdAt,
                expiresAt,
              ) =>
            ApprovalRequestView(
              id,
              capability,
              scopeJson,
              agentId,
              requestorUserId,
              sessionId,
              riskClass,
              status,
              createdAt,
              expiresAt,
            )
        }

    def id: SelectionBuilder[ApprovalRequest, jorlan.domain.ApprovalRequestId] =
      _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def capability: SelectionBuilder[ApprovalRequest, jorlan.domain.CapabilityName] =
      _root_.caliban.client.SelectionBuilder.Field("capability", Scalar())
    def scopeJson: SelectionBuilder[ApprovalRequest, scala.Option[String]] =
      _root_.caliban.client.SelectionBuilder.Field("scopeJson", OptionOf(Scalar()))
    def agentId: SelectionBuilder[ApprovalRequest, scala.Option[jorlan.domain.AgentId]] =
      _root_.caliban.client.SelectionBuilder.Field("agentId", OptionOf(Scalar()))
    def requestorUserId: SelectionBuilder[ApprovalRequest, jorlan.domain.UserId] =
      _root_.caliban.client.SelectionBuilder.Field("requestorUserId", Scalar())
    def sessionId: SelectionBuilder[ApprovalRequest, scala.Option[jorlan.domain.AgentSessionId]] =
      _root_.caliban.client.SelectionBuilder.Field("sessionId", OptionOf(Scalar()))
    def riskClass: SelectionBuilder[ApprovalRequest, String] =
      _root_.caliban.client.SelectionBuilder.Field("riskClass", Scalar())
    def status: SelectionBuilder[ApprovalRequest, ApprovalStatus] =
      _root_.caliban.client.SelectionBuilder.Field("status", Scalar())
    def createdAt: SelectionBuilder[ApprovalRequest, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
    def expiresAt: SelectionBuilder[ApprovalRequest, scala.Option[java.time.Instant]] =
      _root_.caliban.client.SelectionBuilder.Field("expiresAt", OptionOf(Scalar()))

  }

  type EventLogJson
  object EventLogJson {

    final case class EventLogJsonView(
      id:          jorlan.domain.EventLogId,
      eventType:   EventType,
      actorId:     scala.Option[jorlan.domain.UserId],
      agentId:     scala.Option[jorlan.domain.AgentId],
      sessionId:   scala.Option[jorlan.domain.AgentSessionId],
      resource:    scala.Option[String],
      payloadJson: scala.Option[String],
      occurredAt:  java.time.Instant,
    )

    type ViewSelection = SelectionBuilder[EventLogJson, EventLogJsonView]

    def view: ViewSelection =
      (id ~ eventType ~ actorId ~ agentId ~ sessionId ~ resource ~ payloadJson ~ occurredAt).map {
        case (id, eventType, actorId, agentId, sessionId, resource, payloadJson, occurredAt) =>
          EventLogJsonView(id, eventType, actorId, agentId, sessionId, resource, payloadJson, occurredAt)
      }

    def id: SelectionBuilder[EventLogJson, jorlan.domain.EventLogId] =
      _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def eventType: SelectionBuilder[EventLogJson, EventType] =
      _root_.caliban.client.SelectionBuilder.Field("eventType", Scalar())
    def actorId: SelectionBuilder[EventLogJson, scala.Option[jorlan.domain.UserId]] =
      _root_.caliban.client.SelectionBuilder.Field("actorId", OptionOf(Scalar()))
    def agentId: SelectionBuilder[EventLogJson, scala.Option[jorlan.domain.AgentId]] =
      _root_.caliban.client.SelectionBuilder.Field("agentId", OptionOf(Scalar()))
    def sessionId: SelectionBuilder[EventLogJson, scala.Option[jorlan.domain.AgentSessionId]] =
      _root_.caliban.client.SelectionBuilder.Field("sessionId", OptionOf(Scalar()))
    def resource: SelectionBuilder[EventLogJson, scala.Option[String]] =
      _root_.caliban.client.SelectionBuilder.Field("resource", OptionOf(Scalar()))
    def payloadJson: SelectionBuilder[EventLogJson, scala.Option[String]] =
      _root_.caliban.client.SelectionBuilder.Field("payloadJson", OptionOf(Scalar()))
    def occurredAt: SelectionBuilder[EventLogJson, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("occurredAt", Scalar())

  }

  type Permission
  object Permission {

    final case class PermissionView(
      id:       jorlan.domain.PermissionId,
      roleId:   scala.Option[jorlan.domain.RoleId],
      userId:   scala.Option[jorlan.domain.UserId],
      resource: String,
      action:   String,
      scope:    scala.Option[String],
    )

    type ViewSelection = SelectionBuilder[Permission, PermissionView]

    def view: ViewSelection =
      (id ~ roleId ~ userId ~ resource ~ action ~ scope).map { case (id, roleId, userId, resource, action, scope) =>
        PermissionView(id, roleId, userId, resource, action, scope)
      }

    def id: SelectionBuilder[Permission, jorlan.domain.PermissionId] =
      _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def roleId: SelectionBuilder[Permission, scala.Option[jorlan.domain.RoleId]] =
      _root_.caliban.client.SelectionBuilder.Field("roleId", OptionOf(Scalar()))
    def userId: SelectionBuilder[Permission, scala.Option[jorlan.domain.UserId]] =
      _root_.caliban.client.SelectionBuilder.Field("userId", OptionOf(Scalar()))
    def resource: SelectionBuilder[Permission, String] =
      _root_.caliban.client.SelectionBuilder.Field("resource", Scalar())
    def action: SelectionBuilder[Permission, String] = _root_.caliban.client.SelectionBuilder.Field("action", Scalar())
    def scope:  SelectionBuilder[Permission, scala.Option[String]] =
      _root_.caliban.client.SelectionBuilder.Field("scope", OptionOf(Scalar()))

  }

  type ResponseChunk
  object ResponseChunk {

    final case class ResponseChunkView(
      sessionId: jorlan.domain.AgentSessionId,
      content:   String,
      finished:  Boolean,
      isError:   Boolean,
    )

    type ViewSelection = SelectionBuilder[ResponseChunk, ResponseChunkView]

    def view: ViewSelection =
      (sessionId ~ content ~ finished ~ isError).map { case (sessionId, content, finished, isError) =>
        ResponseChunkView(sessionId, content, finished, isError)
      }

    def sessionId: SelectionBuilder[ResponseChunk, jorlan.domain.AgentSessionId] =
      _root_.caliban.client.SelectionBuilder.Field("sessionId", Scalar())
    def content: SelectionBuilder[ResponseChunk, String] =
      _root_.caliban.client.SelectionBuilder.Field("content", Scalar())
    def finished: SelectionBuilder[ResponseChunk, Boolean] =
      _root_.caliban.client.SelectionBuilder.Field("finished", Scalar())
    def isError: SelectionBuilder[ResponseChunk, Boolean] =
      _root_.caliban.client.SelectionBuilder.Field("isError", Scalar())

  }

  type Role
  object Role {

    final case class RoleView(
      id:          jorlan.domain.RoleId,
      name:        String,
      description: scala.Option[String],
    )

    type ViewSelection = SelectionBuilder[Role, RoleView]

    def view: ViewSelection =
      (id ~ name ~ description).map { case (id, name, description) => RoleView(id, name, description) }

    def id: SelectionBuilder[Role, jorlan.domain.RoleId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def name:        SelectionBuilder[Role, String] = _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
    def description: SelectionBuilder[Role, scala.Option[String]] =
      _root_.caliban.client.SelectionBuilder.Field("description", OptionOf(Scalar()))

  }

  type User
  object User {

    final case class UserView(
      id:          jorlan.domain.UserId,
      displayName: String,
      email:       scala.Option[String],
      createdAt:   java.time.Instant,
      updatedAt:   java.time.Instant,
      active:      Boolean,
    )

    type ViewSelection = SelectionBuilder[User, UserView]

    def view: ViewSelection =
      (id ~ displayName ~ email ~ createdAt ~ updatedAt ~ active).map {
        case (id, displayName, email, createdAt, updatedAt, active) =>
          UserView(id, displayName, email, createdAt, updatedAt, active)
      }

    def id: SelectionBuilder[User, jorlan.domain.UserId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def displayName: SelectionBuilder[User, String] =
      _root_.caliban.client.SelectionBuilder.Field("displayName", Scalar())
    def email: SelectionBuilder[User, scala.Option[String]] =
      _root_.caliban.client.SelectionBuilder.Field("email", OptionOf(Scalar()))
    def createdAt: SelectionBuilder[User, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
    def updatedAt: SelectionBuilder[User, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("updatedAt", Scalar())
    def active: SelectionBuilder[User, Boolean] = _root_.caliban.client.SelectionBuilder.Field("active", Scalar())

  }

  type Queries = _root_.caliban.client.Operations.RootQuery
  object Queries {

    def user[A](id: Long)(innerSelection: SelectionBuilder[User, A])(implicit encoder0: ArgEncoder[Long])
      : SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder
        .Field("user", OptionOf(Obj(innerSelection)), arguments = List(Argument("id", id, "Long!")))
    def users[A](
      page:     scala.Option[Int] = None,
      pageSize: scala.Option[Int] = None,
    )(
      innerSelection:    SelectionBuilder[User, A],
    )(implicit encoder0: ArgEncoder[scala.Option[Int]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "users",
        OptionOf(ListOf(Obj(innerSelection))),
        arguments = List(Argument("page", page, "Int"), Argument("pageSize", pageSize, "Int")),
      )
    def role[A](id: Long)(innerSelection: SelectionBuilder[Role, A])(implicit encoder0: ArgEncoder[Long])
      : SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder
        .Field("role", OptionOf(Obj(innerSelection)), arguments = List(Argument("id", id, "Long!")))
    def roles[A](
      userId:   Long,
      page:     scala.Option[Int] = None,
      pageSize: scala.Option[Int] = None,
    )(
      innerSelection: SelectionBuilder[Role, A],
    )(implicit
      encoder0: ArgEncoder[Long],
      encoder1: ArgEncoder[scala.Option[Int]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "roles",
        OptionOf(ListOf(Obj(innerSelection))),
        arguments = List(
          Argument("userId", userId, "Long!"),
          Argument("page", page, "Int"),
          Argument("pageSize", pageSize, "Int"),
        ),
      )
    def permissions[A](
      userId:   Long,
      page:     scala.Option[Int] = None,
      pageSize: scala.Option[Int] = None,
    )(
      innerSelection: SelectionBuilder[Permission, A],
    )(implicit
      encoder0: ArgEncoder[Long],
      encoder1: ArgEncoder[scala.Option[Int]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "permissions",
        OptionOf(ListOf(Obj(innerSelection))),
        arguments = List(
          Argument("userId", userId, "Long!"),
          Argument("page", page, "Int"),
          Argument("pageSize", pageSize, "Int"),
        ),
      )
    def listSessions[A](
      page:     scala.Option[Int] = None,
      pageSize: scala.Option[Int] = None,
    )(
      innerSelection:    SelectionBuilder[AgentSession, A],
    )(implicit encoder0: ArgEncoder[scala.Option[Int]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "listSessions",
        OptionOf(ListOf(Obj(innerSelection))),
        arguments = List(Argument("page", page, "Int"), Argument("pageSize", pageSize, "Int")),
      )

  }

  type Mutations = _root_.caliban.client.Operations.RootMutation
  object Mutations {

    def createUser[A](
      displayName: String,
      email:       scala.Option[String] = None,
    )(
      innerSelection: SelectionBuilder[User, A],
    )(implicit
      encoder0: ArgEncoder[String],
      encoder1: ArgEncoder[scala.Option[String]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "createUser",
        OptionOf(Obj(innerSelection)),
        arguments = List(Argument("displayName", displayName, "String!"), Argument("email", email, "String")),
      )
    def updateUser[A](
      id:          Long,
      displayName: String,
      email:       scala.Option[String] = None,
      active:      Boolean,
    )(
      innerSelection: SelectionBuilder[User, A],
    )(implicit
      encoder0: ArgEncoder[Long],
      encoder1: ArgEncoder[String],
      encoder2: ArgEncoder[scala.Option[String]],
      encoder3: ArgEncoder[Boolean],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "updateUser",
        OptionOf(Obj(innerSelection)),
        arguments = List(
          Argument("id", id, "Long!"),
          Argument("displayName", displayName, "String!"),
          Argument("email", email, "String"),
          Argument("active", active, "Boolean!"),
        ),
      )
    def createRole[A](
      name:        String,
      description: scala.Option[String] = None,
    )(
      innerSelection: SelectionBuilder[Role, A],
    )(implicit
      encoder0: ArgEncoder[String],
      encoder1: ArgEncoder[scala.Option[String]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "createRole",
        OptionOf(Obj(innerSelection)),
        arguments = List(Argument("name", name, "String!"), Argument("description", description, "String")),
      )
    def assignRole(
      userId:            Long,
      roleId:            Long,
    )(implicit encoder0: ArgEncoder[Long],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "assignRole",
        OptionOf(Scalar()),
        arguments = List(Argument("userId", userId, "Long!"), Argument("roleId", roleId, "Long!")),
      )
    def revokeRole(
      userId:            Long,
      roleId:            Long,
    )(implicit encoder0: ArgEncoder[Long],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "revokeRole",
        OptionOf(Scalar()),
        arguments = List(Argument("userId", userId, "Long!"), Argument("roleId", roleId, "Long!")),
      )
    def grantPermission[A](
      resource: String,
      action:   String,
      userId:   scala.Option[Long] = None,
      roleId:   scala.Option[Long] = None,
    )(
      innerSelection: SelectionBuilder[Permission, A],
    )(implicit
      encoder0: ArgEncoder[String],
      encoder1: ArgEncoder[scala.Option[Long]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "grantPermission",
        OptionOf(Obj(innerSelection)),
        arguments = List(
          Argument("resource", resource, "String!"),
          Argument("action", action, "String!"),
          Argument("userId", userId, "Long"),
          Argument("roleId", roleId, "Long"),
        ),
      )
    def revokePermission(id: Long)(implicit encoder0: ArgEncoder[Long])
      : SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Long]] =
      _root_.caliban.client.SelectionBuilder
        .Field("revokePermission", OptionOf(Scalar()), arguments = List(Argument("id", id, "Long!")))
    def createSession[A](
      modelId: scala.Option[String] = None,
    )(
      innerSelection:    SelectionBuilder[AgentSession, A],
    )(implicit encoder0: ArgEncoder[scala.Option[String]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "createSession",
        OptionOf(Obj(innerSelection)),
        arguments = List(Argument("modelId", modelId, "String")),
      )
    def submitMessage(
      sessionId: Long,
      content:   String,
    )(implicit
      encoder0: ArgEncoder[Long],
      encoder1: ArgEncoder[String],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "submitMessage",
        OptionOf(Scalar()),
        arguments = List(Argument("sessionId", sessionId, "Long!"), Argument("content", content, "String!")),
      )

  }

  type Subscriptions = _root_.caliban.client.Operations.RootSubscription
  object Subscriptions {

    def approvalNotifications[A](innerSelection: SelectionBuilder[ApprovalRequest, A])
      : SelectionBuilder[_root_.caliban.client.Operations.RootSubscription, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field("approvalNotifications", OptionOf(Obj(innerSelection)))
    def eventLogTail[A](innerSelection: SelectionBuilder[EventLogJson, A])
      : SelectionBuilder[_root_.caliban.client.Operations.RootSubscription, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field("eventLogTail", OptionOf(Obj(innerSelection)))
    def agentResponseStream[A](
      sessionId: Long,
    )(
      innerSelection:    SelectionBuilder[ResponseChunk, A],
    )(implicit encoder0: ArgEncoder[Long],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootSubscription, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "agentResponseStream",
        OptionOf(Obj(innerSelection)),
        arguments = List(Argument("sessionId", sessionId, "Long!")),
      )

  }

}
