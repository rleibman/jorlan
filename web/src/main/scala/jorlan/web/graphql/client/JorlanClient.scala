/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web.graphql.client

import caliban.client.FieldBuilder._
import caliban.client._

import jorlan.domain._
import jorlan.web.graphql.client.JorlanClientDecoders._

object JorlanClient {

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
      formality: jorlan.domain.Formality,
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
    def formality: SelectionBuilder[Personality, jorlan.domain.Formality] =
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

  type MemoryRecord
  object MemoryRecord {

    final case class MemoryRecordView(
      id:        jorlan.domain.MemoryRecordId,
      scope:     String,
      recordKey: String,
      value:     String,
      createdAt: java.time.Instant,
      updatedAt: java.time.Instant,
    )

    type ViewSelection = SelectionBuilder[MemoryRecord, MemoryRecordView]

    def view: ViewSelection =
      (id ~ scope ~ recordKey ~ value ~ createdAt ~ updatedAt).map {
        case (id, scope, recordKey, value, createdAt, updatedAt) =>
          MemoryRecordView(id, scope, recordKey, value, createdAt, updatedAt)
      }

    def id: SelectionBuilder[MemoryRecord, jorlan.domain.MemoryRecordId] =
      _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def scope: SelectionBuilder[MemoryRecord, String] =
      _root_.caliban.client.SelectionBuilder.Field("scope", Scalar())
    def recordKey: SelectionBuilder[MemoryRecord, String] =
      _root_.caliban.client.SelectionBuilder.Field("recordKey", Scalar())
    def value: SelectionBuilder[MemoryRecord, String] =
      _root_.caliban.client.SelectionBuilder.Field("value", Scalar())
    def createdAt: SelectionBuilder[MemoryRecord, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
    def updatedAt: SelectionBuilder[MemoryRecord, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("updatedAt", Scalar())

  }

  type CapabilityGrant
  object CapabilityGrant {

    final case class CapabilityGrantView(
      id:           jorlan.domain.CapabilityGrantId,
      capability:   jorlan.domain.CapabilityName,
      scopeJson:    scala.Option[String],
      granteeId:    jorlan.domain.UserId,
      approvalMode: jorlan.domain.ApprovalMode,
      expiresAt:    scala.Option[java.time.Instant],
      createdAt:    java.time.Instant,
    )

    type ViewSelection = SelectionBuilder[CapabilityGrant, CapabilityGrantView]

    def view: ViewSelection =
      (id ~ capability ~ scopeJson ~ granteeId ~ approvalMode ~ expiresAt ~ createdAt).map {
        case (id, capability, scopeJson, granteeId, approvalMode, expiresAt, createdAt) =>
          CapabilityGrantView(id, capability, scopeJson, granteeId, approvalMode, expiresAt, createdAt)
      }

    def id: SelectionBuilder[CapabilityGrant, jorlan.domain.CapabilityGrantId] =
      _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def capability: SelectionBuilder[CapabilityGrant, jorlan.domain.CapabilityName] =
      _root_.caliban.client.SelectionBuilder.Field("capability", Scalar())
    def scopeJson: SelectionBuilder[CapabilityGrant, scala.Option[String]] =
      _root_.caliban.client.SelectionBuilder.Field("scopeJson", OptionOf(Scalar()))
    def granteeId: SelectionBuilder[CapabilityGrant, jorlan.domain.UserId] =
      _root_.caliban.client.SelectionBuilder.Field("granteeId", Scalar())
    def approvalMode: SelectionBuilder[CapabilityGrant, jorlan.domain.ApprovalMode] =
      _root_.caliban.client.SelectionBuilder.Field("approvalMode", Scalar())
    def expiresAt: SelectionBuilder[CapabilityGrant, scala.Option[java.time.Instant]] =
      _root_.caliban.client.SelectionBuilder.Field("expiresAt", OptionOf(Scalar()))
    def createdAt: SelectionBuilder[CapabilityGrant, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())

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

    def listMemory[A](
      scope:      scala.Option[String] = None,
      textSearch: scala.Option[String] = None,
    )(
      innerSelection: SelectionBuilder[MemoryRecord, A],
    )(implicit
      encoder0: ArgEncoder[scala.Option[String]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "listMemory",
        OptionOf(ListOf(Obj(innerSelection))),
        arguments = List(Argument("scope", scope, "MemoryScope"), Argument("textSearch", textSearch, "String")),
      )

    def listCapabilities[A](
      innerSelection: SelectionBuilder[CapabilityGrant, A],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field("listCapabilities", OptionOf(ListOf(Obj(innerSelection))))

    def listApprovals[A](
      innerSelection: SelectionBuilder[ApprovalRequest, A],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field("listApprovals", OptionOf(ListOf(Obj(innerSelection))))

    def availableModels[A](
      innerSelection: SelectionBuilder[ModelInfoGql, A],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field("availableModels", OptionOf(ListOf(Obj(innerSelection))))

    def jobs[A](
      agentId: scala.Option[jorlan.domain.AgentId] = None,
    )(
      innerSelection: SelectionBuilder[SchedulerJob, A],
    )(implicit
      encoder0: ArgEncoder[scala.Option[jorlan.domain.AgentId]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "jobs",
        OptionOf(ListOf(Obj(innerSelection))),
        arguments = List(Argument("agentId", agentId, "AgentId")),
      )

    def triggers[A](
      jobId: jorlan.domain.SchedulerJobId,
    )(
      innerSelection: SelectionBuilder[SchedulerTrigger, A],
    )(implicit
      encoder0: ArgEncoder[jorlan.domain.SchedulerJobId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "triggers",
        OptionOf(ListOf(Obj(innerSelection))),
        arguments = List(Argument("jobId", jobId, "SchedulerJobId!")),
      )

  }

  type ModelInfoGql
  object ModelInfoGql {

    final case class ModelInfoView(
      id:                jorlan.domain.ModelId,
      provider:          String,
      contextWindow:     Int,
      supportsStreaming: Boolean,
    )

    type ViewSelection = SelectionBuilder[ModelInfoGql, ModelInfoView]

    val view: ViewSelection =
      (id ~ provider ~ contextWindow ~ supportsStreaming).map { case (id, provider, ctx, streaming) =>
        ModelInfoView(id, provider, ctx, streaming)
      }

    def id: SelectionBuilder[ModelInfoGql, jorlan.domain.ModelId] =
      _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def provider: SelectionBuilder[ModelInfoGql, String] =
      _root_.caliban.client.SelectionBuilder.Field("provider", Scalar())
    def contextWindow: SelectionBuilder[ModelInfoGql, Int] =
      _root_.caliban.client.SelectionBuilder.Field("contextWindow", Scalar())
    def supportsStreaming: SelectionBuilder[ModelInfoGql, Boolean] =
      _root_.caliban.client.SelectionBuilder.Field("supportsStreaming", Scalar())

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
      modelId: scala.Option[jorlan.domain.ModelId] = None,
    )(
      innerSelection:    SelectionBuilder[AgentSession, A],
    )(implicit encoder0: ArgEncoder[scala.Option[jorlan.domain.ModelId]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder
        .Field(
          "createSession",
          OptionOf(Obj(innerSelection)),
          arguments = List(Argument("modelId", modelId, "ModelId")),
        )
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
    def storeMemory[A](
      key:   String,
      text:  String,
      scope: scala.Option[String] = None,
    )(
      innerSelection: SelectionBuilder[MemoryRecord, A],
    )(implicit
      encoder0: ArgEncoder[String],
      encoder1: ArgEncoder[scala.Option[String]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "storeMemory",
        OptionOf(Obj(innerSelection)),
        arguments = List(
          Argument("key", key, "String!"),
          Argument("text", text, "String!"),
          Argument("scope", scope, "MemoryScope"),
        ),
      )

    def forgetMemory(
      value:             jorlan.domain.MemoryRecordId,
    )(implicit encoder0: ArgEncoder[jorlan.domain.MemoryRecordId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] =
      _root_.caliban.client.SelectionBuilder
        .Field("forgetMemory", OptionOf(Scalar()), arguments = List(Argument("value", value, "MemoryRecordId!")))

    def markMemoryShared[A](
      value: jorlan.domain.MemoryRecordId,
    )(
      innerSelection:    SelectionBuilder[MemoryRecord, A],
    )(implicit encoder0: ArgEncoder[jorlan.domain.MemoryRecordId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder
        .Field(
          "markMemoryShared",
          OptionOf(Obj(innerSelection)),
          arguments = List(Argument("value", value, "MemoryRecordId!")),
        )

    def markMemoryPrivate[A](
      value: jorlan.domain.MemoryRecordId,
    )(
      innerSelection:    SelectionBuilder[MemoryRecord, A],
    )(implicit encoder0: ArgEncoder[jorlan.domain.MemoryRecordId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder
        .Field(
          "markMemoryPrivate",
          OptionOf(Obj(innerSelection)),
          arguments = List(Argument("value", value, "MemoryRecordId!")),
        )

    def updatePersonality[A](
      name:      String,
      formality: jorlan.domain.Formality,
      languages: List[String] = Nil,
      expertise: List[String] = Nil,
      prompt:    String,
    )(
      innerSelection: SelectionBuilder[Personality, A],
    )(implicit
      encoder0: ArgEncoder[String],
      encoder1: ArgEncoder[List[String]],
      encoder2: ArgEncoder[jorlan.domain.Formality],
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

    def decideApproval(
      requestId: jorlan.domain.ApprovalRequestId,
      approved:  Boolean,
      note:      scala.Option[String] = None,
    )(implicit
      encoder0: ArgEncoder[jorlan.domain.ApprovalRequestId],
      encoder1: ArgEncoder[Boolean],
      encoder2: ArgEncoder[scala.Option[String]],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "decideApproval",
        OptionOf(Scalar()),
        arguments = List(
          Argument("requestId", requestId, "ApprovalRequestId!"),
          Argument("approved", approved, "Boolean!"),
          Argument("note", note, "String"),
        ),
      )

    def terminateSession(
      value:             jorlan.domain.AgentSessionId,
    )(implicit encoder0: ArgEncoder[jorlan.domain.AgentSessionId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "terminateSession",
        OptionOf(Scalar()),
        arguments = List(Argument("value", value, "AgentSessionId!")),
      )

    def createJob[A](
      name:            String,
      inputJson:       scala.Option[String] = None,
      maxRetries:      Int = 0,
      backoffSeconds:  Int = 60,
      backoffPolicy:   jorlan.domain.RetryBackoffPolicy = jorlan.domain.RetryBackoffPolicy.Fixed,
      missedRunPolicy: jorlan.domain.MissedRunPolicy = jorlan.domain.MissedRunPolicy.Skip,
    )(
      innerSelection: SelectionBuilder[SchedulerJob, A],
    )(implicit
      encoder0: ArgEncoder[String],
      encoder1: ArgEncoder[scala.Option[String]],
      encoder2: ArgEncoder[Int],
      encoder3: ArgEncoder[jorlan.domain.RetryBackoffPolicy],
      encoder4: ArgEncoder[jorlan.domain.MissedRunPolicy],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "createJob",
        OptionOf(Obj(innerSelection)),
        arguments = List(
          Argument("name", name, "String!"),
          Argument("inputJson", inputJson, "String"),
          Argument("maxRetries", maxRetries, "Int!"),
          Argument("backoffSeconds", backoffSeconds, "Int!"),
          Argument("backoffPolicy", backoffPolicy, "RetryBackoffPolicy!"),
          Argument("missedRunPolicy", missedRunPolicy, "MissedRunPolicy!"),
        ),
      )

    def addTrigger[A](
      jobId:       jorlan.domain.SchedulerJobId,
      triggerType: jorlan.domain.TriggerType,
      expression:  String,
    )(
      innerSelection: SelectionBuilder[SchedulerTrigger, A],
    )(implicit
      encoder0: ArgEncoder[jorlan.domain.SchedulerJobId],
      encoder1: ArgEncoder[jorlan.domain.TriggerType],
      encoder2: ArgEncoder[String],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "addTrigger",
        OptionOf(Obj(innerSelection)),
        arguments = List(
          Argument("jobId", jobId, "SchedulerJobId!"),
          Argument("triggerType", triggerType, "TriggerType!"),
          Argument("expression", expression, "String!"),
        ),
      )

    def pauseJob(
      value:             jorlan.domain.SchedulerJobId,
    )(implicit encoder0: ArgEncoder[jorlan.domain.SchedulerJobId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "pauseJob",
        OptionOf(Scalar()),
        arguments = List(Argument("value", value, "SchedulerJobId!")),
      )

    def resumeJob(
      value:             jorlan.domain.SchedulerJobId,
    )(implicit encoder0: ArgEncoder[jorlan.domain.SchedulerJobId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "resumeJob",
        OptionOf(Scalar()),
        arguments = List(Argument("value", value, "SchedulerJobId!")),
      )

    def cancelJob(
      value:             jorlan.domain.SchedulerJobId,
    )(implicit encoder0: ArgEncoder[jorlan.domain.SchedulerJobId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "cancelJob",
        OptionOf(Scalar()),
        arguments = List(Argument("value", value, "SchedulerJobId!")),
      )

    def triggerNow(
      value:             jorlan.domain.SchedulerJobId,
    )(implicit encoder0: ArgEncoder[jorlan.domain.SchedulerJobId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "triggerNow",
        OptionOf(Scalar()),
        arguments = List(Argument("value", value, "SchedulerJobId!")),
      )

    def deleteJob(
      value:             jorlan.domain.SchedulerJobId,
    )(implicit encoder0: ArgEncoder[jorlan.domain.SchedulerJobId],
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "deleteJob",
        OptionOf(Scalar()),
        arguments = List(Argument("value", value, "SchedulerJobId!")),
      )

  }

  // ─── Scheduler types ──────────────────────────────────────────────────────────

  type SchedulerJob
  object SchedulerJob {

    final case class SchedulerJobView(
      id:              jorlan.domain.SchedulerJobId,
      agentId:         jorlan.domain.AgentId,
      userId:          jorlan.domain.UserId,
      name:            String,
      status:          jorlan.domain.JobStatus,
      scheduledAt:     java.time.Instant,
      startedAt:       scala.Option[java.time.Instant],
      finishedAt:      scala.Option[java.time.Instant],
      maxRetries:      Int,
      retryCount:      Int,
      backoffSeconds:  Int,
      backoffPolicy:   jorlan.domain.RetryBackoffPolicy,
      missedRunPolicy: jorlan.domain.MissedRunPolicy,
      createdAt:       java.time.Instant,
    )

    type ViewSelection = SelectionBuilder[SchedulerJob, SchedulerJobView]

    def view: ViewSelection =
      (id ~ agentId ~ userId ~ name ~ status ~ scheduledAt ~ startedAt ~ finishedAt ~
        maxRetries ~ retryCount ~ backoffSeconds ~ backoffPolicy ~ missedRunPolicy ~ createdAt).map {
        case (
              id,
              agentId,
              userId,
              name,
              status,
              scheduledAt,
              startedAt,
              finishedAt,
              maxRetries,
              retryCount,
              backoffSeconds,
              backoffPolicy,
              missedRunPolicy,
              createdAt,
            ) =>
          SchedulerJobView(
            id,
            agentId,
            userId,
            name,
            status,
            scheduledAt,
            startedAt,
            finishedAt,
            maxRetries,
            retryCount,
            backoffSeconds,
            backoffPolicy,
            missedRunPolicy,
            createdAt,
          )
      }

    def id: SelectionBuilder[SchedulerJob, jorlan.domain.SchedulerJobId] =
      _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def agentId: SelectionBuilder[SchedulerJob, jorlan.domain.AgentId] =
      _root_.caliban.client.SelectionBuilder.Field("agentId", Scalar())
    def userId: SelectionBuilder[SchedulerJob, jorlan.domain.UserId] =
      _root_.caliban.client.SelectionBuilder.Field("userId", Scalar())
    def name: SelectionBuilder[SchedulerJob, String] =
      _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
    def status: SelectionBuilder[SchedulerJob, jorlan.domain.JobStatus] =
      _root_.caliban.client.SelectionBuilder.Field("status", Scalar())
    def scheduledAt: SelectionBuilder[SchedulerJob, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("scheduledAt", Scalar())
    def startedAt: SelectionBuilder[SchedulerJob, scala.Option[java.time.Instant]] =
      _root_.caliban.client.SelectionBuilder.Field("startedAt", OptionOf(Scalar()))
    def finishedAt: SelectionBuilder[SchedulerJob, scala.Option[java.time.Instant]] =
      _root_.caliban.client.SelectionBuilder.Field("finishedAt", OptionOf(Scalar()))
    def maxRetries: SelectionBuilder[SchedulerJob, Int] =
      _root_.caliban.client.SelectionBuilder.Field("maxRetries", Scalar())
    def retryCount: SelectionBuilder[SchedulerJob, Int] =
      _root_.caliban.client.SelectionBuilder.Field("retryCount", Scalar())
    def backoffSeconds: SelectionBuilder[SchedulerJob, Int] =
      _root_.caliban.client.SelectionBuilder.Field("backoffSeconds", Scalar())
    def backoffPolicy: SelectionBuilder[SchedulerJob, jorlan.domain.RetryBackoffPolicy] =
      _root_.caliban.client.SelectionBuilder.Field("backoffPolicy", Scalar())
    def missedRunPolicy: SelectionBuilder[SchedulerJob, jorlan.domain.MissedRunPolicy] =
      _root_.caliban.client.SelectionBuilder.Field("missedRunPolicy", Scalar())
    def createdAt: SelectionBuilder[SchedulerJob, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())

  }

  type SchedulerTrigger
  object SchedulerTrigger {

    final case class SchedulerTriggerView(
      id:          jorlan.domain.SchedulerTriggerId,
      jobId:       jorlan.domain.SchedulerJobId,
      triggerType: jorlan.domain.TriggerType,
      expression:  String,
      enabled:     Boolean,
      createdAt:   java.time.Instant,
    )

    type ViewSelection = SelectionBuilder[SchedulerTrigger, SchedulerTriggerView]

    def view: ViewSelection =
      (id ~ jobId ~ triggerType ~ expression ~ enabled ~ createdAt).map {
        case (id, jobId, triggerType, expression, enabled, createdAt) =>
          SchedulerTriggerView(id, jobId, triggerType, expression, enabled, createdAt)
      }

    def id: SelectionBuilder[SchedulerTrigger, jorlan.domain.SchedulerTriggerId] =
      _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
    def jobId: SelectionBuilder[SchedulerTrigger, jorlan.domain.SchedulerJobId] =
      _root_.caliban.client.SelectionBuilder.Field("jobId", Scalar())
    def triggerType: SelectionBuilder[SchedulerTrigger, jorlan.domain.TriggerType] =
      _root_.caliban.client.SelectionBuilder.Field("triggerType", Scalar())
    def expression: SelectionBuilder[SchedulerTrigger, String] =
      _root_.caliban.client.SelectionBuilder.Field("expression", Scalar())
    def enabled: SelectionBuilder[SchedulerTrigger, Boolean] =
      _root_.caliban.client.SelectionBuilder.Field("enabled", Scalar())
    def createdAt: SelectionBuilder[SchedulerTrigger, java.time.Instant] =
      _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())

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
