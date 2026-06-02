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

import caliban.client.FieldBuilder._
import caliban.client._

import jorlan.domain._
import jorlan.shell.client.JorlanClientDecoders._

object JorlanClient {

  type Formality = String

  type AgentSession
  object AgentSession {

    final case class AgentSessionView(
      id:          jorlan.domain.AgentSessionId,
      agentId:     jorlan.domain.AgentId,
      userId:      jorlan.domain.UserId,
      workspaceId: scala.Option[jorlan.domain.WorkspaceId],
      status:      jorlan.domain.SessionStatus,
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
    def status: SelectionBuilder[AgentSession, jorlan.domain.SessionStatus] =
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
      riskClass:       jorlan.domain.RiskClass,
      status:          jorlan.domain.ApprovalStatus,
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
    def riskClass: SelectionBuilder[ApprovalRequest, jorlan.domain.RiskClass] =
      _root_.caliban.client.SelectionBuilder.Field("riskClass", Scalar())
    def status: SelectionBuilder[ApprovalRequest, jorlan.domain.ApprovalStatus] =
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
      eventType:   jorlan.domain.EventType,
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
    def eventType: SelectionBuilder[EventLogJson, jorlan.domain.EventType] =
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

  type Personality
  object Personality {

    final case class PersonalityView(
      name:      String,
      formality: Formality,
      languages: List[String],
      expertise: List[String],
      prompt:    String,
    )

    type ViewSelection = SelectionBuilder[Personality, PersonalityView]

    def view: ViewSelection =
      (name ~ formality ~ languages ~ expertise ~ prompt).map { case (name, formality, languages, expertise, prompt) =>
        PersonalityView(name, formality, languages, expertise, prompt)
      }

    def name: SelectionBuilder[Personality, String] = _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
    def formality: SelectionBuilder[Personality, Formality] =
      _root_.caliban.client.SelectionBuilder.Field("formality", Scalar())
    def languages: SelectionBuilder[Personality, List[String]] =
      _root_.caliban.client.SelectionBuilder.Field("languages", ListOf(Scalar()))
    def expertise: SelectionBuilder[Personality, List[String]] =
      _root_.caliban.client.SelectionBuilder.Field("expertise", ListOf(Scalar()))
    def prompt: SelectionBuilder[Personality, String] = _root_.caliban.client.SelectionBuilder.Field("prompt", Scalar())

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

    def user[A](
      value: jorlan.domain.UserId,
    )(
      innerSelection:    SelectionBuilder[User, A],
    )(implicit encoder0: ArgEncoder[jorlan.domain.UserId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder
        .Field("user", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "UserId!")))
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
    def role[A](
      value: jorlan.domain.RoleId,
    )(
      innerSelection:    SelectionBuilder[Role, A],
    )(implicit encoder0: ArgEncoder[jorlan.domain.RoleId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder
        .Field("role", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "RoleId!")))
    def roles[A](
      userId:   jorlan.domain.UserId,
      page:     scala.Option[Int] = None,
      pageSize: scala.Option[Int] = None,
    )(
      innerSelection: SelectionBuilder[Role, A],
    )(implicit
      encoder0: ArgEncoder[jorlan.domain.UserId],
      encoder1: ArgEncoder[scala.Option[Int]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "roles",
        OptionOf(ListOf(Obj(innerSelection))),
        arguments = List(
          Argument("userId", userId, "UserId!"),
          Argument("page", page, "Int"),
          Argument("pageSize", pageSize, "Int"),
        ),
      )
    def permissions[A](
      userId:   jorlan.domain.UserId,
      page:     scala.Option[Int] = None,
      pageSize: scala.Option[Int] = None,
    )(
      innerSelection: SelectionBuilder[Permission, A],
    )(implicit
      encoder0: ArgEncoder[jorlan.domain.UserId],
      encoder1: ArgEncoder[scala.Option[Int]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "permissions",
        OptionOf(ListOf(Obj(innerSelection))),
        arguments = List(
          Argument("userId", userId, "UserId!"),
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
    def serverPersonality[A](innerSelection: SelectionBuilder[Personality, A])
      : SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field("serverPersonality", OptionOf(Obj(innerSelection)))

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
      id:          jorlan.domain.UserId,
      displayName: String,
      email:       scala.Option[String] = None,
      active:      Boolean,
    )(
      innerSelection: SelectionBuilder[User, A],
    )(implicit
      encoder0: ArgEncoder[jorlan.domain.UserId],
      encoder1: ArgEncoder[String],
      encoder2: ArgEncoder[scala.Option[String]],
      encoder3: ArgEncoder[Boolean],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "updateUser",
        OptionOf(Obj(innerSelection)),
        arguments = List(
          Argument("id", id, "UserId!"),
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
      userId: jorlan.domain.UserId,
      roleId: jorlan.domain.RoleId,
    )(implicit
      encoder0: ArgEncoder[jorlan.domain.UserId],
      encoder1: ArgEncoder[jorlan.domain.RoleId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "assignRole",
        OptionOf(Scalar()),
        arguments = List(Argument("userId", userId, "UserId!"), Argument("roleId", roleId, "RoleId!")),
      )
    def revokeRole(
      userId: jorlan.domain.UserId,
      roleId: jorlan.domain.RoleId,
    )(implicit
      encoder0: ArgEncoder[jorlan.domain.UserId],
      encoder1: ArgEncoder[jorlan.domain.RoleId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "revokeRole",
        OptionOf(Scalar()),
        arguments = List(Argument("userId", userId, "UserId!"), Argument("roleId", roleId, "RoleId!")),
      )
    def grantPermission[A](
      resource: String,
      action:   String,
      userId:   scala.Option[jorlan.domain.UserId] = None,
      roleId:   scala.Option[jorlan.domain.RoleId] = None,
    )(
      innerSelection: SelectionBuilder[Permission, A],
    )(implicit
      encoder0: ArgEncoder[String],
      encoder1: ArgEncoder[scala.Option[jorlan.domain.UserId]],
      encoder2: ArgEncoder[scala.Option[jorlan.domain.RoleId]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "grantPermission",
        OptionOf(Obj(innerSelection)),
        arguments = List(
          Argument("resource", resource, "String!"),
          Argument("action", action, "String!"),
          Argument("userId", userId, "UserId"),
          Argument("roleId", roleId, "RoleId"),
        ),
      )
    def revokePermission(value: jorlan.domain.PermissionId)(implicit encoder0: ArgEncoder[jorlan.domain.PermissionId])
      : SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Long]] =
      _root_.caliban.client.SelectionBuilder
        .Field("revokePermission", OptionOf(Scalar()), arguments = List(Argument("value", value, "PermissionId!")))
    def createSession[A](
      value: scala.Option[jorlan.domain.ModelId] = None,
    )(
      innerSelection:    SelectionBuilder[AgentSession, A],
    )(implicit encoder0: ArgEncoder[scala.Option[jorlan.domain.ModelId]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder
        .Field("createSession", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "ModelId")))
    def submitMessage(
      sessionId: jorlan.domain.AgentSessionId,
      content:   String,
    )(implicit
      encoder0: ArgEncoder[jorlan.domain.AgentSessionId],
      encoder1: ArgEncoder[String],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "submitMessage",
        OptionOf(Scalar()),
        arguments = List(Argument("sessionId", sessionId, "AgentSessionId!"), Argument("content", content, "String!")),
      )
    def updatePersonality[A](
      name:      String,
      formality: Formality,
      languages: List[String] = Nil,
      expertise: List[String] = Nil,
      prompt:    String,
    )(
      innerSelection: SelectionBuilder[Personality, A],
    )(implicit
      encoder0: ArgEncoder[String],
      encoder1: ArgEncoder[List[String]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "updatePersonality",
        OptionOf(Obj(innerSelection)),
        arguments = List(
          Argument("name", name, "String!"),
          Argument("formality", formality, "Formality!"),
          Argument("languages", languages, "[String!]!"),
          Argument("expertise", expertise, "[String!]!"),
          Argument("prompt", prompt, "String!"),
        ),
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
      value: jorlan.domain.AgentSessionId,
    )(
      innerSelection:    SelectionBuilder[ResponseChunk, A],
    )(implicit encoder0: ArgEncoder[jorlan.domain.AgentSessionId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootSubscription, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "agentResponseStream",
        OptionOf(Obj(innerSelection)),
        arguments = List(Argument("value", value, "AgentSessionId!")),
      )

  }

}
