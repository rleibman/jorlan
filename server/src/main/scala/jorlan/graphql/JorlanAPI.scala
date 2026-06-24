/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.graphql

import caliban.*
import caliban.introspection.adt.__EnumValue
import caliban.schema.*
import caliban.schema.Schema.auto.*
import caliban.wrappers.Wrapper.OverallWrapper
import caliban.wrappers.Wrappers.*
import jorlan.*
import jorlan.db.repository.*
import jorlan.connector.{HasDashboardData, InvocationContext, Skill}
import jorlan.service.*
import jorlan.service.mcp.{McpManagerImpl, McpServerConfig, McpTransport}
import jorlan.service.skills.SkillRegistry
import zio.*
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonEncoder}
import zio.stream.ZStream

import java.time.Instant

/** Caliban GraphQL schema for the Jorlan control-plane API. */
@scala.annotation.nowarn("msg=IsUnionOf")
object JorlanAPI {

  private val logErrors: OverallWrapper[Any] =
    new OverallWrapper[Any] {
      def wrap[R1 <: Any](
        process: GraphQLRequest => ZIO[R1, Nothing, GraphQLResponse[CalibanError]],
      ): GraphQLRequest => ZIO[R1, Nothing, GraphQLResponse[CalibanError]] =
        request =>
          process(request)
            .map { response =>
              if (response.errors.isEmpty) response
              else {
                val fixed = response.errors.map {
                  case CalibanError.ExecutionError("Effect failure", path, locs, Some(t), ext) =>
                    CalibanError.ExecutionError(
                      Option(t.getMessage).getOrElse(t.getClass.getName),
                      path,
                      locs,
                      Some(t),
                      ext,
                    )
                  case other => other
                }
                response.copy(errors = fixed)
              }
            }
            .tap { response =>
              ZIO.foreachDiscard(response.errors)(err => ZIO.logErrorCause(Cause.fail(err)))
            }
    }

  private val logRequests: OverallWrapper[Any] =
    new OverallWrapper[Any] {
      def wrap[R1 <: Any](
        process: GraphQLRequest => ZIO[R1, Nothing, GraphQLResponse[CalibanError]],
      ): GraphQLRequest => ZIO[R1, Nothing, GraphQLResponse[CalibanError]] =
        request =>
          ZIO.logDebug {
            val preview = request.query.getOrElse("").take(120).replaceAll("\\s+", " ")
            val ellipsis = if (request.query.exists(_.length > 120)) "…" else ""
            s"GraphQL ${request.operationName.getOrElse("request")}: $preview$ellipsis"
          } *>
            process(request)
    }

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

  private given Schema[Any, ModelInfo] = Schema.gen[Any, ModelInfo]

  private given Schema[Any, SkillToolInfo] = Schema.gen[Any, SkillToolInfo]

  private given Schema[Any, SkillInfo] = Schema.gen[Any, SkillInfo]

  private given Schema[Any, ContactIdentityResult] = Schema.gen[Any, ContactIdentityResult]

  private given Schema[Any, ContactResult] = Schema.gen[Any, ContactResult]

  private given Schema[Any, User] = Schema.gen[Any, User]

  private given Schema[Any, Role] = Schema.gen[Any, Role]

  private given Schema[Any, Permission] = Schema.gen[Any, Permission]

  private given Schema[Any, ApprovalRequest] = Schema.gen[Any, ApprovalRequest]

  private given Schema[Any, EventLog[Json]] = Schema.gen[Any, EventLog[Json]]

  private given Schema[Any, AgentSession] = Schema.gen[Any, AgentSession]

  private given Schema[Any, ResponseChunk] = Schema.gen[Any, ResponseChunk]

  private given Schema[Any, Personality] = Schema.gen[Any, Personality]

  private given Schema[Any, MemoryRecordId] =
    Schema.scalarSchema("MemoryRecordId", None, None, None, id => Value.IntValue(id.value))

  private given Schema[Any, MemoryRecord] = Schema.gen[Any, MemoryRecord]

  private given Schema[Any, CheckpointPolicyConfig] = Schema.gen[Any, CheckpointPolicyConfig]

  private given Schema[Any, CapabilityGrantId] =
    Schema.scalarSchema("CapabilityGrantId", None, None, None, id => Value.IntValue(id.value))

  private given Schema[Any, CapabilityGrant] = Schema.gen[Any, CapabilityGrant]

  private given Schema[Any, SchedulerJobId] =
    Schema.scalarSchema("SchedulerJobId", None, None, None, id => Value.IntValue(id.value))

  private given Schema[Any, SchedulerTriggerId] =
    Schema.scalarSchema("SchedulerTriggerId", None, None, None, id => Value.IntValue(id.value))

  private given Schema[Any, ChannelIdentityId] =
    Schema.scalarSchema("ChannelIdentityId", None, None, None, id => Value.StringValue(id.value.toString))

  private given Schema[Any, ChannelIdentity] = Schema.gen[Any, ChannelIdentity]
  private given ArgBuilder[ChannelIdentityId] =
    ArgBuilder.string
      .flatMap(s =>
        s.toLongOption.fold(Left(CalibanError.ExecutionError(s"Invalid ChannelIdentityId: '$s'")))(l =>
          Right(ChannelIdentityId(l)),
        ),
      )
      .orElse(ArgBuilder.long.map(ChannelIdentityId(_)))

  private given ArgBuilder[CapabilityGrantId] = ArgBuilder.long.map(CapabilityGrantId(_))

  private given Schema[Any, ChannelType] =
    Schema.enumSchema[ChannelType](
      name = "ChannelType",
      values = ChannelType.values
        .map(v =>
          __EnumValue(
            name = v.toString,
            description = None,
            deprecationReason = None,
            isDeprecated = false,
            directives = None,
          ),
        ).toList,
      repr = _.toString,
    )

  private given Schema[Any, RetryBackoffPolicy] =
    Schema.enumSchema[RetryBackoffPolicy](
      name = "RetryBackoffPolicy",
      values = RetryBackoffPolicy.values
        .map(v =>
          __EnumValue(
            name = v.toString,
            description = None,
            deprecationReason = None,
            isDeprecated = false,
            directives = None,
          ),
        ).toList,
      repr = _.toString,
    )

  private given Schema[Any, ApprovalStatus] =
    Schema.enumSchema[ApprovalStatus](
      name = "ApprovalStatus",
      values = ApprovalStatus.values
        .map(v =>
          __EnumValue(
            name = v.toString,
            description = None,
            deprecationReason = None,
            isDeprecated = false,
            directives = None,
          ),
        ).toList,
      repr = _.toString,
    )

  private given Schema[Any, ApprovalMode] =
    Schema.enumSchema[ApprovalMode](
      name = "ApprovalMode",
      values = ApprovalMode.values
        .map(v =>
          __EnumValue(
            name = v.toString,
            description = None,
            deprecationReason = None,
            isDeprecated = false,
            directives = None,
          ),
        ).toList,
      repr = _.toString,
    )

  private given Schema[Any, EventType] =
    Schema.enumSchema[EventType](
      name = "EventType",
      values = EventType.values
        .map(v =>
          __EnumValue(
            name = v.toString,
            description = None,
            deprecationReason = None,
            isDeprecated = false,
            directives = None,
          ),
        ).toList,
      repr = _.toString,
    )

  private given Schema[Any, SessionStatus] =
    Schema.enumSchema[SessionStatus](
      name = "SessionStatus",
      values = SessionStatus.values
        .map(v =>
          __EnumValue(
            name = v.toString,
            description = None,
            deprecationReason = None,
            isDeprecated = false,
            directives = None,
          ),
        ).toList,
      repr = _.toString,
    )

  private given Schema[Any, Formality] =
    Schema.enumSchema[Formality](
      name = "Formality",
      values = Formality.values
        .map(v =>
          __EnumValue(
            name = v.toString,
            description = None,
            deprecationReason = None,
            isDeprecated = false,
            directives = None,
          ),
        ).toList,
      repr = _.toString,
    )

  private given Schema[Any, JobStatus] =
    Schema.enumSchema[JobStatus](
      name = "JobStatus",
      values = JobStatus.values
        .map(v =>
          __EnumValue(
            name = v.toString,
            description = None,
            deprecationReason = None,
            isDeprecated = false,
            directives = None,
          ),
        ).toList,
      repr = _.toString,
    )

  private given Schema[Any, MissedRunPolicy] =
    Schema.enumSchema[MissedRunPolicy](
      name = "MissedRunPolicy",
      values = MissedRunPolicy.values
        .map(v =>
          __EnumValue(
            name = v.toString,
            description = None,
            deprecationReason = None,
            isDeprecated = false,
            directives = None,
          ),
        ).toList,
      repr = _.toString,
    )
  private given Schema[Any, MemoryScope] =
    Schema.enumSchema[MemoryScope](
      name = "MemoryScope",
      values = MemoryScope.values
        .map(v =>
          __EnumValue(
            name = v.toString,
            description = None,
            deprecationReason = None,
            isDeprecated = false,
            directives = None,
          ),
        ).toList,
      repr = _.toString,
    )

  private given Schema[Any, TriggerType] =
    Schema.scalarSchema("TriggerType", None, None, None, e => Value.StringValue(e.toString))

  private given Schema[Any, SkillId] =
    Schema.scalarSchema("SkillId", None, None, None, id => Value.IntValue(id.value))

  private given Schema[Any, SchedulerJob] = Schema.gen[Any, SchedulerJob]

  private given Schema[Any, SchedulerTrigger] = Schema.gen[Any, SchedulerTrigger]

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
  private given ArgBuilder[Personality] = ArgBuilder.gen[Personality]
  private given ArgBuilder[CheckpointPolicyConfig] = ArgBuilder.gen[CheckpointPolicyConfig]
  private given ArgBuilder[MemoryRecordId] = ArgBuilder.long.map(MemoryRecordId(_))
  private given ArgBuilder[SchedulerJobId] = ArgBuilder.long.map(SchedulerJobId(_))
  private given ArgBuilder[SchedulerTriggerId] = ArgBuilder.long.map(SchedulerTriggerId(_))
  private given ArgBuilder[ApprovalRequestId] = ArgBuilder.long.map(ApprovalRequestId(_))

  private given ArgBuilder[RetryBackoffPolicy] =
    ArgBuilder.enumString[RetryBackoffPolicy] { s =>
      RetryBackoffPolicy.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(
          CalibanError.ExecutionError(s"Invalid RetryBackoffPolicy '$s'"),
        )
    }
  private given ArgBuilder[MissedRunPolicy] =
    ArgBuilder.enumString[MissedRunPolicy] { s =>
      MissedRunPolicy.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid MissedRunPolicy '$s'"))
    }
  private given ArgBuilder[ChannelType] =
    ArgBuilder.enumString[ChannelType] { s =>
      ChannelType.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid ChannelType '$s'"))
    }
  private given ArgBuilder[ApprovalStatus] =
    ArgBuilder.enumString[ApprovalStatus] { s =>
      ApprovalStatus.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid ApprovalStatus '$s'"))
    }
  private given ArgBuilder[ApprovalMode] =
    ArgBuilder.enumString[ApprovalMode] { s =>
      ApprovalMode.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid ApprovalMode '$s'"))
    }
  private given ArgBuilder[EventType] =
    ArgBuilder.enumString[EventType] { s =>
      EventType.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid EventType '$s'"))
    }
  private given ArgBuilder[SessionStatus] =
    ArgBuilder.enumString[SessionStatus] { s =>
      SessionStatus.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid SessionStatus '$s'"))
    }
  private given ArgBuilder[RiskClass] =
    ArgBuilder.enumString[RiskClass] { s =>
      RiskClass.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid RiskClass '$s'"))
    }
  private given ArgBuilder[Formality] =
    ArgBuilder.enumString[Formality] { s =>
      Formality.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid JobStatus '$s'"))
    }

  private given ArgBuilder[JobStatus] =
    ArgBuilder.enumString[JobStatus] { s =>
      JobStatus.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid JobStatus '$s'"))
    }
  private given ArgBuilder[MemoryScope] =
    ArgBuilder.enumString[MemoryScope] { s =>
      MemoryScope.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid MemoryScope '$s'"))
    }

  private given ArgBuilder[TriggerType] =
    ArgBuilder.string.flatMap { s =>
      TriggerType.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid TriggerType '$s'"))
    }

  // ─── Query input types ────────────────────────────────────────────────────────

  /** GQL-safe view of a single tool exposed by a registered skill. */
  case class SkillToolInfo(
    name:                 String,
    description:          String,
    requiredCapabilities: List[String],
    examplePrompts:       List[String],
  ) derives Schema.SemiAuto, ArgBuilder

  /** GQL-safe view of a registered skill and its tools. */
  case class SkillInfo(
    name:              String,
    tier:              String,
    tools:             List[SkillToolInfo],
    enabled:           Boolean,
    keywords:          List[String],
    configKey:         Option[String],
    configJsModule:    Option[String],
    dashboardJsModule: Option[String],
    hasDashboardData:  Boolean,
  ) derives Schema.SemiAuto, ArgBuilder

  /** A timestamped count bucket for time-series charts. */
  case class TimeSeriesPoint(
    timestampMs: Long,
    count:       Int,
  ) derives Schema.SemiAuto

  /** A named count for distribution charts. */
  case class NamedCount(
    name:  String,
    count: Int,
  ) derives Schema.SemiAuto

  /** Aggregated system metrics returned by the `dashboardStats` query. */
  case class DashboardStats(
    activeSessionCount:     Int,
    eventCountToday:        Int,
    skillInvocationCount:   Int,
    schedulerSuccessRate:   Double,
    eventVolumeSeries:      List[TimeSeriesPoint],
    skillInvocationsByName: List[NamedCount],
    sessionStatusCounts:    List[NamedCount],
    jobOutcomeCounts:       List[NamedCount],
  ) derives Schema.SemiAuto

  /** GQL-safe view of one channel identity for a contact result. */
  case class ContactIdentityResult(
    channelType:   String,
    channelUserId: String,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `grantCapability` — grants a named capability directly to a user. */
  case class GrantCapabilityInput(
    userId:       UserId,
    capability:   CapabilityName,
    approvalMode: ApprovalMode,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `linkChannelIdentity` — associates an external channel identity with a user. */
  case class LinkChannelIdentityInput(
    userId:        UserId,
    channelType:   String,
    channelUserId: String,
  ) derives Schema.SemiAuto, ArgBuilder

  /** GQL-safe contact result from the `contacts` query. */
  case class ContactResult(
    userId:      Long,
    displayName: String,
    identities:  List[ContactIdentityResult],
  ) derives Schema.SemiAuto, ArgBuilder

  /** GQL view of a tool invocation event (emitted by the ReAct loop). */
  private case class ToolEventResult(
    sessionId: Long,
    eventType: String,
    toolName:  String,
    payload:   String,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Standard pagination input used by list queries. Defaults: `page = 0`, `pageSize = 20`. */
  case class PaginationInput(
    page:     Option[Int] = None,
    pageSize: Option[Int] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `users` admin list query. */
  case class ListUsersInput(
    active:       Option[Boolean] = None,
    nameContains: Option[String] = None,
    page:         Option[Int] = None,
    pageSize:     Option[Int] = None,
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

  /** Input for `updateRole`. */
  case class UpdateRoleInput(
    id:          RoleId,
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

  /** Input for `createSession` — optional model override; if absent, the agent's default model is used. */
  case class CreateSessionInput(
    modelId: Option[ModelId] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `submitMessage` — sends a user message to the active agent session. */
  case class SubmitMessageInput(
    sessionId: AgentSessionId,
    content:   String,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Necessary because you can't have simple options
    */
  case class JobsListInput(optAgentId: Option[AgentId]) derives Schema.SemiAuto, ArgBuilder

  /** Input for `listMemory` — filters memory records by scope and optional text search. */
  case class ListMemoryInput(
    scope:      MemoryScope = MemoryScope.User,
    textSearch: Option[String] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `storeMemory` — explicitly stores a fact into long-term memory. */
  case class StoreMemoryInput(
    key:   String,
    text:  String,
    scope: MemoryScope = MemoryScope.User,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `createJob` — creates a new scheduler job. */
  case class CreateJobInput(
    name:            String,
    prompt:          String,
    inputJson:       Option[String] = None,
    maxRetries:      Int = 0,
    backoffSeconds:  Int = 60,
    backoffPolicy:   RetryBackoffPolicy = RetryBackoffPolicy.Fixed,
    missedRunPolicy: MissedRunPolicy = MissedRunPolicy.Skip,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `addTrigger` — attaches a trigger to an existing job. */
  case class AddTriggerInput(
    jobId:       SchedulerJobId,
    triggerType: TriggerType,
    expression:  String,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `updateJob` — updates mutable configuration of an existing job. */
  case class UpdateJobInput(
    id:              SchedulerJobId,
    name:            String,
    prompt:          String,
    maxRetries:      Int = 0,
    backoffSeconds:  Int = 60,
    backoffPolicy:   RetryBackoffPolicy = RetryBackoffPolicy.Fixed,
    missedRunPolicy: MissedRunPolicy = MissedRunPolicy.Skip,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `decideApproval` — approve or reject a pending approval request. */
  case class DecideApprovalInput(
    requestId: ApprovalRequestId,
    approved:  Boolean,
    note:      Option[String] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `notifyUser` — send a notification to a user's preferred channel. */
  case class NotifyUserInput(
    userId:  UserId,
    message: String,
  ) derives Schema.SemiAuto, ArgBuilder

  case class InvokeToolInput(
    toolName: String,
    argsJson: String,
  ) derives Schema.SemiAuto, ArgBuilder

  case class UpdateSkillConfigInput(
    name:       String,
    configJson: String,
  ) derives Schema.SemiAuto, ArgBuilder

  /** A single key/value pair in an MCP server's environment map. */
  case class McpEnvVar(
    key:   String,
    value: String,
  ) derives Schema.SemiAuto, ArgBuilder

  /** GQL-safe view of a configured MCP server. */
  case class McpServerView(
    name:      String,
    transport: String,
    command:   Option[String],
    args:      List[String],
    env:       List[McpEnvVar],
    url:       Option[String],
    enabled:   Boolean,
  ) derives Schema.SemiAuto

  /** Input for `upsertMcpServer` — add or replace a server config entry. */
  case class UpsertMcpServerInput(
    name:      String,
    transport: String,
    command:   Option[String] = None,
    args:      List[String] = List.empty,
    env:       List[McpEnvVar] = List.empty,
    url:       Option[String] = None,
    enabled:   Boolean = true,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Result type for `oauthStatus` — whether the user has connected credentials for a provider. */
  case class OAuthStatus(
    connected: Boolean,
    expiresAt: Option[Instant],
  ) derives Schema.SemiAuto

  /** Result type for `startOAuth` — the URL the user should open in a browser. */
  case class OAuthStartResult(
    authUrl: String,
  ) derives Schema.SemiAuto

  // ─── Query / Mutation / Subscription containers ───────────────────────────────

  case class Queries(
    user:         UserId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Option[User]],
    users:        ListUsersInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[User]],
    role:         RoleId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Option[Role]],
    roles:        RolesForUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[Role]],
    permissions:  PermissionsForUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[Permission]],
    listSessions: PaginationInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[AgentSession]],
    /** Returns the current server personality. Requires `admin.personality.read` capability. */
    serverPersonality: ZIO[JorlanApiEnv & JorlanSession, JorlanError, Personality],
    /** Returns memory records visible to the authenticated user. Requires `memory.read` capability. */
    listMemory: ListMemoryInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[MemoryRecord]],
    /** Returns active capability grants for the authenticated user. */
    listCapabilities: ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[CapabilityGrant]],
    /** Returns scheduler jobs, optionally filtered by agentId. */
    jobs: JobsListInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[SchedulerJob]],
    /** Returns a single scheduler job by ID. */
    job: SchedulerJobId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Option[SchedulerJob]],
    /** Returns triggers for a given job. */
    triggers: SchedulerJobId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[SchedulerTrigger]],
    /** Returns pending approval requests for the authenticated user. */
    listApprovals: ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[ApprovalRequest]],
    /** Returns the list of AI models available on this server. */
    availableModels: ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[ModelInfo]],
    /** Returns all registered skills and their tools. */
    skills: ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[SkillInfo]],
    /** Returns the current config JSON for a configurable skill. Requires `admin.settings`. */
    skillConfig: String => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Option[String]],
    /** Case-insensitive substring search on user displayName, with channel identities. Requires `contacts.read`. */
    contacts: String => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[ContactResult]],
    /** Returns OAuth connection status for a given provider. */
    oauthStatus: String => ZIO[JorlanApiEnv & JorlanSession, JorlanError, OAuthStatus],
    /** Returns the list of OAuth providers the calling user has connected credentials for. */
    listOAuthProviders: ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[String]],
    /** Returns capability grants for a specific user. Requires `admin.user.manage`. */
    userCapabilityGrants: UserId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[CapabilityGrant]],
    /** Returns channel identities for a specific user. Requires `admin.user.manage`. */
    userChannelIdentities: UserId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[ChannelIdentity]],
    /** Returns all roles in the system. Requires `admin.user.manage`. */
    allRoles: PaginationInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[Role]],
    /** Returns the current checkpoint policy configuration. */
    checkpointPolicy: ZIO[JorlanApiEnv & JorlanSession, JorlanError, CheckpointPolicyConfig],
    /** Returns aggregated system metrics for the dashboard. */
    dashboardStats: ZIO[JorlanApiEnv & JorlanSession, JorlanError, DashboardStats],
    /** Returns per-skill dashboard JSON data for a skill that implements HasDashboardData. */
    skillDashboardData: String => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Option[String]],
    /** Returns the configured MCP servers. Requires `admin.settings`. */
    mcpServers: ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[McpServerView]],
    /** Returns all known capability names from registered skills. Requires `admin.user.manage`. */
    allKnownCapabilities: ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[CapabilityName]],
  )

  case class Mutations(
    createUser:        CreateUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, User],
    updateUser:        UpdateUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, User],
    deactivateUser:    UserId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    createRole:        CreateRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Role],
    updateRole:        UpdateRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Role],
    deleteRole:        RoleId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    assignRole:        AssignRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Unit],
    revokeRole:        AssignRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Unit],
    grantPermission:   GrantPermissionInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Permission],
    revokePermission:  PermissionId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Long],
    createSession:     CreateSessionInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, AgentSession],
    submitMessage:     SubmitMessageInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Unit],
    updatePersonality: Personality => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Personality],
    storeMemory:       StoreMemoryInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, MemoryRecord],
    forgetMemory:      MemoryRecordId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    markMemoryShared:  MemoryRecordId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, MemoryRecord],
    markMemoryPrivate: MemoryRecordId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, MemoryRecord],
    createJob:         CreateJobInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, SchedulerJob],
    addTrigger:        AddTriggerInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, SchedulerTrigger],
    pauseJob:          SchedulerJobId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    resumeJob:         SchedulerJobId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    cancelJob:         SchedulerJobId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    triggerNow:        SchedulerJobId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    deleteJob:         SchedulerJobId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    updateJob:         UpdateJobInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, SchedulerJob],
    deleteTrigger:     SchedulerTriggerId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    decideApproval:    DecideApprovalInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    terminateSession:  AgentSessionId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    /** Send a notification to a user's preferred channel. Requires `notify.send` capability. */
    notifyUser: NotifyUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    /** Returns the Google OAuth authorization URL for the calling user to open in a browser. */
    startOAuth: String => ZIO[JorlanApiEnv & JorlanSession, JorlanError, OAuthStartResult],
    /** Revokes stored OAuth credentials for the calling user and the named provider. */
    revokeOAuth: String => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    /** Directly invokes a skill tool by name with JSON args; returns JSON result as a string. */
    invokeTool: InvokeToolInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, String],
    /** Grants a named capability directly to a user. Requires `permission.grant`. */
    grantCapability: GrantCapabilityInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, CapabilityGrant],
    /** Revokes a capability grant by ID. Requires `permission.revoke`. */
    revokeCapabilityGrant: CapabilityGrantId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    /** Associates a ch`annel identity with a user. Requires `admin.user.manage`. */
    linkChannelIdentity: LinkChannelIdentityInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, ChannelIdentity],
    /** Removes a channel identity from a user. Requires `admin.user.manage`. */
    unlinkChannelIdentity: ChannelIdentityId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    /** Triggers an immediate checkpoint for the given session. Requires `memory.read`. */
    requestCheckpoint: AgentSessionId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    /** Updates the checkpoint policy configuration. Requires `admin.settings`. */
    updateCheckpointPolicy: CheckpointPolicyConfig => ZIO[
      JorlanApiEnv & JorlanSession,
      JorlanError,
      CheckpointPolicyConfig,
    ],
    /** Reloads MCP servers from server_settings and re-registers adapters. Requires `admin.mcp.reload`. */
    reloadMcpServers: Unit => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    /** Adds or replaces an MCP server configuration entry. Requires `admin.settings`. */
    upsertMcpServer: UpsertMcpServerInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, McpServerView],
    /** Removes an MCP server configuration entry by name. Requires `admin.settings`. Returns true if deleted. */
    deleteMcpServer: String => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    /** Enables a previously disabled skill by name. Requires `admin.settings`. */
    enableSkill: String => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    /** Disables a skill by name (including built-in skills). Requires `admin.settings`. */
    disableSkill: String => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    /** Stores new config JSON for a configurable skill. Requires `admin.settings`. Returns `true`; the new config takes
      * effect on the next server restart.
      */
    updateSkillConfig: UpdateSkillConfigInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
  )

  private case class Subscriptions(
    approvalNotifications: ZStream[JorlanApiEnv & JorlanSession, JorlanError, ApprovalRequest],
    eventLogTail:          ZStream[JorlanApiEnv & JorlanSession, JorlanError, EventLog[Json]],
    agentResponseStream:   AgentSessionId => ZStream[JorlanApiEnv & JorlanSession, JorlanError, ResponseChunk],
    /** Streams tool invocation events for the given session (SkillInvoked / SkillSucceeded). */
    toolEvents: AgentSessionId => ZStream[JorlanApiEnv & JorlanSession, JorlanError, ToolEventResult],
  )

  // ─── Authorization helpers ────────────────────────────────────────────────────

  private val actorIdFromSession: ZIO[JorlanSession, JorlanError, UserId] =
    ZIO
      .serviceWith[JorlanSession](_.user.map(_.id))
      .someOrFail(JorlanError("Not authenticated"))

  private def disabledArrayWith(
    name:   String,
    remove: Boolean,
  ): ZIO[ZIORepositories, Nothing, Json] =
    ZIO.serviceWithZIO[ZIORepositories](_.setting.get("skill.disabled")).orElseSucceed(None).map { existing =>
      val current: List[String] = existing
        .flatMap {
          case Json.Arr(elems) => Some(elems.collect { case Json.Str(s) => s }.toList)
          case _               => None
        }.getOrElse(List.empty)
      val updated =
        if (remove) current.filterNot(_ == name)
        else if (current.contains(name)) current
        else current :+ name
      Json.Arr(updated.map(Json.Str(_))*)
    }

  private def requireCapability(
    cap:    String,
    userId: UserId,
  ): ZIO[CapabilityEvaluator, JorlanError, Unit] =
    ZIO
      .serviceWithZIO[CapabilityEvaluator](_.evaluate(CapabilityRequest(CapabilityName(cap), userId, None, None, None)))
      .flatMap {
        case EvaluationResult.ExplicitDeny => ZIO.fail(JorlanError(s"Access denied: explicit deny on '$cap'"))
        case EvaluationResult.DefaultDeny  => ZIO.fail(JorlanError(s"Access denied: no permission for '$cap'"))
        case _                             => ZIO.unit
      }

  private def resolveAgentId(actorId: UserId): ZIO[AgentSessionManager, Nothing, AgentId] =
    ZIO
      .serviceWithZIO[AgentSessionManager](_.listSessions(actorId, 0, 1))
      .map(_.headOption.map(_.agentId).getOrElse(AgentId.empty))
      .orElseSucceed(AgentId.empty)

  // Strict variant used for createJob: a job with no agent is a dangling reference.
  private def resolveAgentIdStrict(actorId: UserId): ZIO[AgentSessionManager, JorlanError, AgentId] =
    ZIO
      .serviceWithZIO[AgentSessionManager](_.listSessions(actorId, 0, 1))
      .mapError(e => JorlanError(e.toString))
      .flatMap { sessions =>
        sessions.headOption.map(_.agentId) match {
          case Some(id) => ZIO.succeed(id)
          case None     => ZIO.fail(JorlanError("No active agent session resolved for this user"))
        }
      }

  private def assertJobOwnership(
    id:      SchedulerJobId,
    actorId: UserId,
  ): ZIO[JobManager, JorlanError, SchedulerJob] =
    ZIO.serviceWithZIO[JobManager](_.getJob(id)).flatMap { job =>
      if (job.userId == actorId) ZIO.succeed(job)
      else ZIO.fail(JorlanError(s"Access denied: job ${id.value} is not owned by the current user"))
    }

  private def logEvent(
    eventType: EventType,
    actorId:   Option[UserId],
    resource:  Option[Any],
    now:       Instant,
  ): ZIO[ZIORepositories & EventLogHub, JorlanError, Unit] =
    for {
      logEntry <- ZIO.serviceWithZIO[ZIORepositories](
        _.eventLog.append(
          EventLog[Nothing](
            id = EventLogId.empty,
            eventType = eventType,
            actorId = actorId,
            agentId = None,
            sessionId = None,
            resource = None,
            payloadJson = None,
            occurredAt = now,
          ),
        ),
      )
      _ <- ZIO.serviceWithZIO[EventLogHub](_.publishTyped(logEntry))
    } yield ()

  private def mcpServerToView(cfg: McpServerConfig): McpServerView =
    McpServerView(
      name = cfg.name,
      transport = cfg.transport.toString,
      command = cfg.command,
      args = cfg.args,
      env = cfg.env.map { case (k, v) => McpEnvVar(k, v) }.toList,
      url = cfg.url,
      enabled = cfg.enabled,
    )

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
          user = input => ZIO.serviceWithZIO[ZIORepositories](_.user.getById(input)),
          users = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.user.list", actorId)
              result  <- ZIO.serviceWithZIO[ZIORepositories](
                _.user.search(
                  UserSearch(
                    active = input.active,
                    nameContains = input.nameContains,
                    page = input.page.getOrElse(0),
                    pageSize = input.pageSize.getOrElse(20),
                  ),
                ),
              )
            } yield result,
          role = input => ZIO.serviceWithZIO[ZIORepositories](_.permission.getRole(input)),
          roles = input =>
            ZIO.serviceWithZIO[ZIORepositories](
              _.permission.searchRoles(
                RoleSearch(
                  userId = Some(input.userId),
                  page = input.page.getOrElse(0),
                  pageSize = input.pageSize.getOrElse(20),
                ),
              ),
            ),
          permissions = input =>
            ZIO.serviceWithZIO[ZIORepositories](
              _.permission.searchPermissions(
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
            p       <- ZIO.serviceWithZIO[ZIORepositories](_.setting.getPersonality)
          } yield p,
          listMemory = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("memory.read", actorId)
              agentId <- resolveAgentId(actorId)
              records <- ZIO.serviceWithZIO[MemoryService](
                _.query(input.scope, actorId, agentId, input.textSearch),
              )
            } yield records,
          listCapabilities = for {
            actorId <- actorIdFromSession
            grants  <- ZIO.serviceWithZIO[ZIORepositories](
              _.permission.searchGrants(GrantSearch(userId = actorId, pageSize = 100)),
            )
          } yield grants,
          jobs = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              jobs    <- ZIO.serviceWithZIO[JobManager](_.listJobs(input.optAgentId))
            } yield jobs,
          job = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              result  <- ZIO.serviceWithZIO[JobManager](_.getJob(id).map(Some(_)).orElseSucceed(None))
            } yield result,
          triggers = jobId =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              result  <- ZIO.serviceWithZIO[JobManager](_.listTriggers(jobId))
            } yield result,
          listApprovals = for {
            actorId <- actorIdFromSession
            result  <- ZIO
              .serviceWithZIO[ZIORepositories](_.permission.listPendingApprovals(actorId)).mapError(JorlanError(_))
          } yield result,
          availableModels = ZIO.serviceWithZIO[ModelGateway](_.availableModels),
          skills = for {
            allSkills <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
            dis       <- ZIO.serviceWithZIO[SkillRegistry](_.disabledSet)
          } yield allSkills.sortBy(_.descriptor.name).map { skill =>
            SkillInfo(
              name = skill.descriptor.name,
              tier = skill.descriptor.tier.toString,
              tools = skill.descriptor.tools.map(td =>
                SkillToolInfo(
                  name = td.name,
                  description = td.description,
                  requiredCapabilities = td.requiredCapabilities.map(_.value),
                  examplePrompts = td.examplePrompts,
                ),
              ),
              enabled = !dis.contains(skill.descriptor.name),
              keywords = skill.descriptor.keywords,
              configKey = skill.descriptor.configKey,
              configJsModule = skill.descriptor.configJsModule,
              dashboardJsModule = skill.descriptor.dashboardJsModule,
              hasDashboardData = skill.isInstanceOf[HasDashboardData],
            )
          },
          contacts = name =>
            for {
              actorId  <- actorIdFromSession
              _        <- requireCapability("contacts.read", actorId)
              rawUsers <- ZIO
                .serviceWithZIO[ZIORepositories](
                  _.user.search(UserSearch(fuzzyName = Some(name), active = Some(true))),
                ).mapError(JorlanError(_))
              users = jorlan.service.FuzzyNameMatch.rank(rawUsers, name)(_.displayName)
              results <- ZIO.foreach(users) { user =>
                ZIO
                  .serviceWithZIO[ZIORepositories](_.user.getChannelIdentities(user.id)).mapError(JorlanError(_))
                  .map { ids =>
                    ContactResult(
                      userId = user.id.value,
                      displayName = user.displayName,
                      identities = ids.map(ci =>
                        ContactIdentityResult(
                          channelType = ci.channelType.toString,
                          channelUserId = ci.channelUserId,
                        ),
                      ),
                    )
                  }
              }
            } yield results,
          oauthStatus = provider =>
            for {
              actorId <- actorIdFromSession
              credOpt <- ZIO
                .serviceWithZIO[OAuthCredentialService](_.load(actorId, provider))
                .mapError(e => JorlanError(s"Failed to load OAuth status: ${e.getMessage}"))
              expiresAt <- ZIO
                .serviceWithZIO[OAuthCredentialService](_.getExpiresAt(actorId, provider))
                .mapError(e => JorlanError(s"Failed to load OAuth expiry: ${e.getMessage}"))
                .when(credOpt.isDefined)
                .map(_.flatten)
            } yield OAuthStatus(
              connected = credOpt.isDefined,
              expiresAt = expiresAt,
            ),
          listOAuthProviders =
            for {
              actorId   <- actorIdFromSession
              providers <- ZIO
                .serviceWithZIO[OAuthCredentialService](_.listProviders(actorId))
                .mapError(e => JorlanError(s"Failed to list OAuth providers: ${e.getMessage}"))
            } yield providers,
          userCapabilityGrants = userId =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.user.manage", actorId)
              grants  <- ZIO.serviceWithZIO[ZIORepositories](
                _.permission.searchGrants(GrantSearch(userId = userId, pageSize = 200)),
              )
            } yield grants,
          userChannelIdentities = userId =>
            for {
              actorId    <- actorIdFromSession
              _          <- requireCapability("admin.user.manage", actorId)
              identities <- ZIO
                .serviceWithZIO[ZIORepositories](_.user.getChannelIdentities(userId))
                .mapError(JorlanError(_))
            } yield identities,
          allRoles = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.user.manage", actorId)
              roles   <- ZIO.serviceWithZIO[ZIORepositories](
                _.permission.searchRoles(
                  RoleSearch(userId = None, page = input.page.getOrElse(0), pageSize = input.pageSize.getOrElse(50)),
                ),
              )
            } yield roles,
          checkpointPolicy =
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("memory.read", actorId)
              config  <- ZIO.serviceWithZIO[MemoryService](_.getCheckpointPolicy)
            } yield config,
          dashboardStats =
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("agent.session.list", actorId)
              stats   <- ZIO.serviceWithZIO[DashboardService](_.globalStats)
            } yield DashboardStats(
              activeSessionCount = stats.activeSessionCount,
              eventCountToday = stats.eventCountToday,
              skillInvocationCount = stats.skillInvocationCount,
              schedulerSuccessRate = stats.schedulerSuccessRate,
              eventVolumeSeries = stats.eventVolumeSeries.map(p => TimeSeriesPoint(p.timestampMs, p.count)),
              skillInvocationsByName = stats.skillInvocationsByName.map(c => NamedCount(c.name, c.count)),
              sessionStatusCounts = stats.sessionStatusCounts.map(c => NamedCount(c.name, c.count)),
              jobOutcomeCounts = stats.jobOutcomeCounts.map(c => NamedCount(c.name, c.count)),
            ),
          skillDashboardData = skillName =>
            for {
              actorId  <- actorIdFromSession
              _        <- requireCapability("agent.session.list", actorId)
              skillOpt <- ZIO.serviceWithZIO[SkillRegistry](_.getSkill[jorlan.connector.Skill](skillName))
              result   <- skillOpt match {
                case Some(s: jorlan.connector.HasDashboardData) =>
                  val ctx = jorlan.connector.InvocationContext(actorId = actorId, agentId = None, sessionId = None)
                  s.dashboardData(ctx).map(json => Some(json.toJson))
                case _ =>
                  ZIO.succeed(None)
              }
            } yield result,
          skillConfig = name =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.settings", actorId)
              skills  <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
              skill   <- ZIO
                .fromOption(skills.find(_.descriptor.name == name))
                .orElseFail(JorlanError(s"Skill '$name' not found"))
              key <- ZIO
                .fromOption(skill.descriptor.configKey)
                .orElseFail(JorlanError(s"Skill '$name' has no configurable settings"))
              json <- ZIO.serviceWithZIO[ZIORepositories](_.setting.get(key)).mapError(JorlanError(_))
            } yield json.map(_.toJson),
          mcpServers = for {
            actorId <- actorIdFromSession
            _       <- requireCapability("admin.settings", actorId)
            json    <- ZIO.serviceWithZIO[ZIORepositories](_.setting.get("mcp.servers")).mapError(JorlanError(_))
            configs <- json match {
              case None    => ZIO.succeed(List.empty)
              case Some(j) => ZIO.fromEither(j.as[List[McpServerConfig]]).mapError(e => JorlanError(e))
            }
          } yield configs.map(mcpServerToView),
          allKnownCapabilities = for {
            actorId <- actorIdFromSession
            _       <- requireCapability("admin.user.manage", actorId)
            caps    <- ZIO.serviceWithZIO[SkillRegistry](_.allAdminCapabilities)
          } yield caps,
        ),
        Mutations(
          createUser = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("user.create", actorId)
              email   <- ZIO.fromOption(input.email).orElseFail(JorlanError("email is required"))
              now     <- Clock.instant
              user    <- ZIO.serviceWithZIO[ZIORepositories](
                _.user.upsert(User(UserId.empty, input.displayName, email, now, now)),
              )
              _ <- logEvent(EventType.UserCreated, Some(actorId), None, now)
            } yield user,
          updateUser = input =>
            for {
              actorId  <- actorIdFromSession
              _        <- requireCapability("user.update", actorId)
              email    <- ZIO.fromOption(input.email).orElseFail(JorlanError("email is required"))
              now      <- Clock.instant
              existing <- ZIO
                .serviceWithZIO[ZIORepositories](_.user.getById(input.id))
                .flatMap(ZIO.fromOption(_).orElseFail(JorlanError(s"User ${input.id.value} not found")))
              user <- ZIO.serviceWithZIO[ZIORepositories](
                _.user.upsert(User(input.id, input.displayName, email, existing.createdAt, now, input.active)),
              )
              _ <- logEvent(EventType.UserUpdated, Some(actorId), None, now)
            } yield user,
          deactivateUser = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("user.update", actorId)
              now     <- Clock.instant
              count   <- ZIO.serviceWithZIO[ZIORepositories](_.user.deactivate(id))
              _       <- logEvent(EventType.UserUpdated, Some(actorId), None, now)
            } yield count > 0,
          createRole = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("role.create", actorId)
              role    <- ZIO.serviceWithZIO[ZIORepositories](
                _.permission.upsertRole(Role(RoleId.empty, input.name, input.description)),
              )
            } yield role,
          updateRole = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("role.create", actorId)
              role    <- ZIO.serviceWithZIO[ZIORepositories](
                _.permission.upsertRole(Role(input.id, input.name, input.description)),
              )
            } yield role,
          deleteRole = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("role.create", actorId)
              count   <- ZIO.serviceWithZIO[ZIORepositories](_.permission.deleteRole(id))
            } yield count > 0,
          assignRole = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("role.assign", actorId)
              now     <- Clock.instant
              _       <- ZIO.serviceWithZIO[ZIORepositories](_.permission.assignRole(input.userId, input.roleId))
              _       <- logEvent(EventType.RoleAssigned, Some(actorId), None, now)
            } yield (),
          revokeRole = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("role.revoke", actorId)
              now     <- Clock.instant
              _       <- ZIO.serviceWithZIO[ZIORepositories](_.permission.removeRole(input.userId, input.roleId))
              _       <- logEvent(EventType.RoleRevoked, Some(actorId), None, now)
            } yield (),
          grantPermission = input =>
            for {
              _ <- ZIO
                .when(input.userId.isDefined == input.roleId.isDefined)(
                  ZIO.fail(JorlanError("A permission must target exactly one of userId or roleId")),
                )
              actorId <- actorIdFromSession
              _       <- requireCapability("permission.grant", actorId)
              now     <- Clock.instant
              perm    <- ZIO.serviceWithZIO[ZIORepositories](
                _.permission.upsertPermission(
                  Permission(PermissionId.empty, input.roleId, input.userId, input.resource, input.action, None),
                ),
              )
              _ <- logEvent(EventType.PermissionGranted, Some(actorId), None, now)
            } yield perm,
          revokePermission = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("permission.revoke", actorId)
              now     <- Clock.instant
              count   <- ZIO.serviceWithZIO[ZIORepositories](_.permission.deletePermission(input))
              _       <- logEvent(EventType.PermissionRevoked, Some(actorId), None, now)
            } yield count,
          createSession = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("agent.session.create", actorId)
              session <- ZIO.serviceWithZIO[AgentSessionManager](_.createSession(actorId, input.modelId))
            } yield session,
          submitMessage = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("agent.message", actorId)
              _       <- ZIO
                .serviceWithZIO[AgentRunner](_.processMessage(input.sessionId, input.content, Some(actorId)))
                .forkDaemon
            } yield (),
          updatePersonality = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.personality.update", actorId)
              _       <- ZIO.serviceWithZIO[ZIORepositories](_.setting.setPersonality(input))
            } yield input,
          storeMemory = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("memory.write", actorId)
              agentId <- resolveAgentId(actorId)
              now     <- Clock.instant
              record = MemoryRecord(
                id = MemoryRecordId.empty,
                scope = input.scope,
                userId = Some(actorId),
                workspaceId = None,
                agentId = Option.when(agentId != AgentId.empty)(agentId),
                recordKey = input.key,
                value = Json.Obj("text" -> Json.Str(input.text)),
                ttl = None,
                createdAt = now,
                updatedAt = now,
              )
              saved <- ZIO.serviceWithZIO[MemoryService](_.store(record))
              _     <- logEvent(EventType.MemoryWritten, Some(actorId), None, now)
            } yield saved,
          forgetMemory = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("memory.write", actorId)
              deleted <- ZIO.serviceWithZIO[MemoryService](_.forget(id, actorId))
              now     <- Clock.instant
              _       <- ZIO.when(deleted)(logEvent(EventType.MemoryDeleted, Some(actorId), None, now))
            } yield deleted,
          markMemoryShared = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("memory.write", actorId)
              record  <- ZIO.serviceWithZIO[MemoryService](_.markShared(id, actorId))
              now     <- Clock.instant
              _       <- logEvent(EventType.MemoryRescoped, Some(actorId), None, now)
            } yield record,
          markMemoryPrivate = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("memory.write", actorId)
              record  <- ZIO.serviceWithZIO[MemoryService](_.markPrivate(id, actorId))
              now     <- Clock.instant
              _       <- logEvent(EventType.MemoryRescoped, Some(actorId), None, now)
            } yield record,
          createJob = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              agentId <- resolveAgentIdStrict(actorId)
              now     <- Clock.instant
              job     <- ZIO.serviceWithZIO[JobManager](
                _.createJob(
                  agentId,
                  actorId,
                  input.name,
                  input.prompt,
                  input.inputJson,
                  input.maxRetries,
                  input.backoffSeconds,
                  input.backoffPolicy,
                  input.missedRunPolicy,
                ),
              )
              _ <- logEvent(EventType.SchedulerJobQueued, Some(actorId), None, now)
            } yield job,
          addTrigger = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              _       <- assertJobOwnership(input.jobId, actorId)
              now     <- Clock.instant
              trigger <- ZIO.serviceWithZIO[JobManager](
                _.addTrigger(
                  input.jobId,
                  SchedulerTrigger(
                    id = SchedulerTriggerId.empty,
                    jobId = input.jobId,
                    triggerType = input.triggerType,
                    expression = input.expression,
                    enabled = true,
                    createdAt = now,
                  ),
                ),
              )
              _ <- logEvent(EventType.SchedulerTriggerAdded, Some(actorId), None, now)
            } yield trigger,
          pauseJob = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              _       <- assertJobOwnership(id, actorId)
              _       <- ZIO.serviceWithZIO[JobManager](_.pauseJob(id))
              now     <- Clock.instant
              _       <- logEvent(EventType.SchedulerJobPaused, Some(actorId), None, now)
            } yield true,
          resumeJob = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              _       <- assertJobOwnership(id, actorId)
              _       <- ZIO.serviceWithZIO[JobManager](_.resumeJob(id))
              now     <- Clock.instant
              _       <- logEvent(EventType.SchedulerJobResumed, Some(actorId), None, now)
            } yield true,
          cancelJob = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              _       <- assertJobOwnership(id, actorId)
              _       <- ZIO.serviceWithZIO[JobManager](_.cancelJob(id))
              now     <- Clock.instant
              _       <- logEvent(EventType.SchedulerJobCancelled, Some(actorId), None, now)
            } yield true,
          triggerNow = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              _       <- assertJobOwnership(id, actorId)
              _       <- ZIO.serviceWithZIO[JobManager](_.triggerNow(id))
              now     <- Clock.instant
              _       <- logEvent(EventType.SchedulerJobTriggered, Some(actorId), None, now)
            } yield true,
          deleteJob = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              _       <- assertJobOwnership(id, actorId)
              _       <- ZIO.serviceWithZIO[JobManager](_.deleteJob(id))
              now     <- Clock.instant
              _       <- logEvent(EventType.SchedulerJobDeleted, Some(actorId), None, now)
            } yield true,
          updateJob = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              _       <- assertJobOwnership(input.id, actorId)
              job     <- ZIO.serviceWithZIO[JobManager](
                _.updateJob(
                  input.id,
                  input.name,
                  input.prompt,
                  input.maxRetries,
                  input.backoffSeconds,
                  input.backoffPolicy,
                  input.missedRunPolicy,
                ),
              )
              now <- Clock.instant
              _   <- logEvent(EventType.SchedulerJobUpdated, Some(actorId), None, now)
            } yield job,
          deleteTrigger = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              _       <- ZIO.serviceWithZIO[JobManager](_.deleteTrigger(id))
              now     <- Clock.instant
              _       <- logEvent(EventType.SchedulerTriggerDeleted, Some(actorId), None, now)
            } yield true,
          decideApproval = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("approval.decide", actorId)
              now     <- Clock.instant
              decision = ApprovalDecision(
                id = ApprovalDecisionId.empty,
                approvalRequestId = input.requestId,
                decidedBy = actorId,
                decision = if (input.approved) ApprovalStatus.Approved else ApprovalStatus.Rejected,
                scopeOverride = input.note,
                decidedAt = now,
              )
              _ <- ZIO.serviceWithZIO[ApprovalService](_.recordDecision(decision))
            } yield true,
          terminateSession = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("agent.session.terminate", actorId)
              _       <- ZIO.serviceWithZIO[AgentSessionManager](_.terminateSession(id))
            } yield true,
          notifyUser = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("notify.send", actorId)
              ctx = jorlan.connector.InvocationContext(actorId = actorId, agentId = None, sessionId = None)
              result <- ZIO.serviceWithZIO[NotificationRouter](_.notifyUser(input.userId, input.message, ctx))
              succeeded = result match {
                case zio.json.ast.Json.Str(s) if s.startsWith("Error:") => false
                case _                                                  => true
              }
            } yield succeeded,
          startOAuth = provider =>
            for {
              // Resolve caller; fail early if unauthenticated
              _ <- actorIdFromSession
            } yield OAuthStartResult(authUrl = s"/api/oauth/start/$provider"),
          revokeOAuth = provider =>
            for {
              actorId <- actorIdFromSession
              _       <- ZIO
                .serviceWithZIO[OAuthCredentialService](_.revoke(actorId, provider))
                .mapError(e => JorlanError(s"Failed to revoke OAuth: ${e.getMessage}"))
            } yield true,
          invokeTool = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("agent.skill.invoke", actorId)
              ctx = jorlan.connector.InvocationContext(actorId = actorId, agentId = None, sessionId = None)
              result <- ZIO.serviceWithZIO[SkillRegistry](_.invoke(input.toolName, input.argsJson, ctx))
            } yield result.toJson,
          grantCapability = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("permission.grant", actorId)
              now     <- Clock.instant
              grant   <- ZIO.serviceWithZIO[ZIORepositories](
                _.permission.upsertCapabilityGrant(
                  CapabilityGrant(
                    id = CapabilityGrantId.empty,
                    capability = input.capability,
                    scopeJson = None,
                    granteeId = input.userId,
                    grantorId = Some(actorId),
                    approvalMode = input.approvalMode,
                    expiresAt = None,
                    resourceConstraints = None,
                    createdAt = now,
                  ),
                ),
              )
              _ <- logEvent(EventType.PermissionGranted, Some(actorId), None, now)
            } yield grant,
          revokeCapabilityGrant = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("permission.revoke", actorId)
              now     <- Clock.instant
              count   <- ZIO.serviceWithZIO[ZIORepositories](_.permission.revokeGrant(id))
              _       <- logEvent(EventType.PermissionRevoked, Some(actorId), None, now)
            } yield count > 0,
          linkChannelIdentity = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.user.manage", actorId)
              now     <- Clock.instant
              chType  <- ZIO
                .fromTry(scala.util.Try(ChannelType.valueOf(input.channelType)))
                .orElseFail(JorlanError(s"Unknown channel type: ${input.channelType}"))
              ci <- ZIO
                .serviceWithZIO[ZIORepositories](
                  _.user.upsertChannelIdentity(
                    ChannelIdentity(
                      id = ChannelIdentityId.empty,
                      userId = input.userId,
                      channelType = chType,
                      channelUserId = input.channelUserId,
                      verified = false,
                      providerData = None,
                      createdAt = now,
                    ),
                  ),
                )
                .mapError(JorlanError(_))
            } yield ci,
          unlinkChannelIdentity = id =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.user.manage", actorId)
              count   <- ZIO
                .serviceWithZIO[ZIORepositories](_.user.deleteChannelIdentity(id))
                .mapError(JorlanError(_))
            } yield count > 0,
          requestCheckpoint = sessionId =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("memory.read", actorId)
              agentId <- ZIO
                .serviceWithZIO[ZIORepositories](_.agent.getSession(sessionId))
                .mapError(JorlanError(_))
                .map(_.map(_.agentId).getOrElse(AgentId.empty))
              _ <- ZIO.serviceWithZIO[MemoryService](_.requestCheckpoint(sessionId, actorId, agentId))
            } yield true,
          updateCheckpointPolicy = config =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.settings", actorId)
              _       <- ZIO.serviceWithZIO[MemoryService](_.updateCheckpointPolicy(config))
            } yield config,
          reloadMcpServers = _ =>
            for {
              actorId  <- actorIdFromSession
              _        <- requireCapability("admin.mcp.reload", actorId)
              registry <- ZIO.service[SkillRegistry]
              repos    <- ZIO.service[ZIORepositories]
              client   <- ZIO.service[zio.http.Client]
              _        <- ZIO.scoped {
                McpManagerImpl(registry, client, repos.setting).loadAndRegister
              }
            } yield true,
          upsertMcpServer = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.settings", actorId)
              transport <- ZIO
                .fromTry(scala.util.Try(McpTransport.valueOf(input.transport)))
                .orElseFail(JorlanError(s"Unknown MCP transport: ${input.transport}"))
              newCfg = McpServerConfig(
                name = input.name,
                transport = transport,
                command = input.command,
                args = input.args,
                env = input.env.map(e => e.key -> e.value).toMap,
                url = input.url,
                enabled = input.enabled,
              )
              json <- ZIO.serviceWithZIO[ZIORepositories](_.setting.get("mcp.servers")).mapError(JorlanError(_))
              existing <- json match {
                case None    => ZIO.succeed(List.empty)
                case Some(j) => ZIO.fromEither(j.as[List[McpServerConfig]]).mapError(e => JorlanError(e))
              }
              updated = existing.filterNot(_.name == newCfg.name) :+ newCfg
              updatedJson <- ZIO.fromEither(updated.toJsonAST).mapError(e => JorlanError(s"Encoding error: $e"))
              _ <- ZIO
                .serviceWithZIO[ZIORepositories](_.setting.set("mcp.servers", updatedJson))
                .mapError(JorlanError(_))
            } yield mcpServerToView(newCfg),
          deleteMcpServer = name =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.settings", actorId)
              json    <- ZIO.serviceWithZIO[ZIORepositories](_.setting.get("mcp.servers")).mapError(JorlanError(_))
              existing <- json match {
                case None    => ZIO.succeed(List.empty)
                case Some(j) => ZIO.fromEither(j.as[List[McpServerConfig]]).mapError(e => JorlanError(e))
              }
              updated = existing.filterNot(_.name == name)
              updatedJson <- ZIO.fromEither(updated.toJsonAST).mapError(e => JorlanError(s"Encoding error: $e"))
              _ <- ZIO
                .serviceWithZIO[ZIORepositories](_.setting.set("mcp.servers", updatedJson))
                .mapError(JorlanError(_))
            } yield existing.exists(_.name == name),
          enableSkill = name =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.settings", actorId)
              _       <- ZIO.serviceWithZIO[SkillRegistry](_.enableSkill(name))
              updated <- disabledArrayWith(name, remove = true)
              _       <- ZIO.serviceWithZIO[ZIORepositories](_.setting.set("skill.disabled", updated))
            } yield true,
          disableSkill = name =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.settings", actorId)
              _       <- ZIO.serviceWithZIO[SkillRegistry](_.disableSkill(name))
              updated <- disabledArrayWith(name, remove = false)
              _       <- ZIO.serviceWithZIO[ZIORepositories](_.setting.set("skill.disabled", updated))
            } yield true,
          updateSkillConfig = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("admin.settings", actorId)
              skills  <- ZIO.serviceWithZIO[SkillRegistry](_.allSkills)
              skill   <- ZIO
                .fromOption(skills.find(_.descriptor.name == input.name))
                .orElseFail(JorlanError(s"Skill '${input.name}' not found"))
              key <- ZIO
                .fromOption(skill.descriptor.configKey)
                .orElseFail(JorlanError(s"Skill '${input.name}' has no configurable settings"))
              json <- ZIO
                .fromEither(input.configJson.fromJson[zio.json.ast.Json])
                .mapError(e => JorlanError(s"Invalid config JSON: $e"))
              _ <- ZIO.serviceWithZIO[ZIORepositories](_.setting.set(key, json)).mapError(JorlanError(_))
              _ <- ZIO.serviceWithZIO[SkillRegistry](_.reloadSkillConfig(key, input.configJson))
              _ <- ZIO.logInfo(s"Skill config reloaded live for '${input.name}' (key=$key)")
            } yield true,
        ),
        Subscriptions(
          approvalNotifications = ZStream.empty,
          eventLogTail = ZStream.unwrap(
            for {
              hub    <- ZIO.service[EventLogHub]
              repos  <- ZIO.service[ZIORepositories]
              recent <- repos.eventLog
                .search(
                  EventLogFilter(
                    pageSize = 50,
                    sorts = Some(Sort(EventLogOrder.Id, OrderDirection.Desc)),
                  ),
                )
                .mapError(JorlanError(_))
              live <- hub.subscribe
            } yield ZStream.fromIterable(recent.reverse) ++ live,
          ),
          agentResponseStream = sessionId =>
            ZStream.unwrap(
              for {
                connId <- ConnectionId.randomZIO
                _ <- ZIO.logInfo(s"[API] agentResponseStream subscription starting: session=$sessionId conn=$connId")
                runner <- ZIO.service[AgentRunner]
                stream <- runner.subscribeToSession(sessionId, connId)
              } yield stream.ensuring(
                ZIO.logInfo(s"[API] agentResponseStream subscription ended: session=$sessionId conn=$connId"),
              ),
            ),
          toolEvents = sessionId =>
            ZStream.unwrap(
              ZIO.serviceWithZIO[ToolEventHub](_.subscribe(sessionId)).map { stream =>
                stream
                  .map {
                    case ToolEvent.ToolInvokedEvent(sid, toolName, argsJson) =>
                      ToolEventResult(sid.value, "SkillInvoked", toolName, argsJson)
                    case ToolEvent.ToolResultEvent(sid, toolName, resultJson, succeeded) =>
                      ToolEventResult(
                        sid.value,
                        if (succeeded) "SkillSucceeded" else "SkillFailed",
                        toolName,
                        resultJson,
                      )
                  }
              },
            ),
        ),
      ),
    ) @@ maxFields(200) @@ maxDepth(20) @@ logRequests @@ logErrors

}
