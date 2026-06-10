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
import caliban.schema.*
import caliban.schema.Schema.auto.*
import caliban.wrappers.Wrapper.OverallWrapper
import caliban.wrappers.Wrappers.*
import jorlan.*
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.service.*
import zio.*
import zio.json.JsonEncoder
import zio.json.ast.Json
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

  type JorlanApiEnv = ZIORepositories & CapabilityEvaluator & AgentSessionManager & AgentRunner & MemoryService &
    MemorySkill & JobManager & ApprovalService & ModelGateway & SkillRegistry

  /** GQL-safe view of a single tool exposed by a registered skill. */
  case class SkillToolInfo(
    name:        String,
    description: String,
  )

  /** GQL-safe view of a registered skill and its tools. */
  case class SkillInfo(
    name:  String,
    tier:  String,
    tools: List[SkillToolInfo],
  )

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
  private given ArgBuilder[MemoryScope] =
    ArgBuilder.string.flatMap { s =>
      MemoryScope.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid MemoryScope '$s'"))
    }
  private given ArgBuilder[MemoryRecordId] = ArgBuilder.long.map(MemoryRecordId(_))
  private given ArgBuilder[SchedulerJobId] = ArgBuilder.long.map(SchedulerJobId(_))
  private given ArgBuilder[SchedulerTriggerId] = ArgBuilder.long.map(SchedulerTriggerId(_))
  private given ArgBuilder[ApprovalRequestId] = ArgBuilder.long.map(ApprovalRequestId(_))
  private given ArgBuilder[TriggerType] =
    ArgBuilder.string.flatMap { s =>
      TriggerType.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid TriggerType '$s'"))
    }
  private given ArgBuilder[RetryBackoffPolicy] =
    ArgBuilder.string.flatMap { s =>
      RetryBackoffPolicy.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(
          CalibanError.ExecutionError(s"Invalid RetryBackoffPolicy '$s'"),
        )
    }
  private given ArgBuilder[MissedRunPolicy] =
    ArgBuilder.string.flatMap { s =>
      MissedRunPolicy.values
        .find(v => s.equalsIgnoreCase(v.toString)).toRight(CalibanError.ExecutionError(s"Invalid MissedRunPolicy '$s'"))
    }

  // ─── Query input types ────────────────────────────────────────────────────────

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

  /** Input for `createSession` — optional model override; if absent, the agent's default model is used. */
  case class CreateSessionInput(
    modelId: Option[ModelId] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  /** Input for `submitMessage` — sends a user message to the active agent session. */
  case class SubmitMessageInput(
    sessionId: AgentSessionId,
    content:   String,
  ) derives Schema.SemiAuto, ArgBuilder

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

  /** Input for `decideApproval` — approve or reject a pending approval request. */
  case class DecideApprovalInput(
    requestId: ApprovalRequestId,
    approved:  Boolean,
    note:      Option[String] = None,
  ) derives Schema.SemiAuto, ArgBuilder

  // ─── Query / Mutation / Subscription containers ───────────────────────────────

  case class Queries(
    user:         UserId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Option[User]],
    users:        PaginationInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[User]],
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
    jobs: Option[AgentId] => ZIO[JorlanApiEnv & JorlanSession, JorlanError, List[SchedulerJob]],
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
  )

  case class Mutations(
    createUser:        CreateUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, User],
    updateUser:        UpdateUserInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, User],
    createRole:        CreateRoleInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Role],
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
    decideApproval:    DecideApprovalInput => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
    terminateSession:  AgentSessionId => ZIO[JorlanApiEnv & JorlanSession, JorlanError, Boolean],
  )

  case class Subscriptions(
    approvalNotifications: ZStream[JorlanApiEnv & JorlanSession, JorlanError, ApprovalRequest],
    eventLogTail:          ZStream[JorlanApiEnv & JorlanSession, JorlanError, EventLog[Json]],
    agentResponseStream:   AgentSessionId => ZStream[JorlanApiEnv & JorlanSession, JorlanError, ResponseChunk],
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
  private given Schema[Any, ModelInfo] = Schema.gen[Any, ModelInfo]
  private given Schema[Any, SkillToolInfo] = Schema.gen[Any, SkillToolInfo]
  private given Schema[Any, SkillInfo] = Schema.gen[Any, SkillInfo]

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
  private given Schema[Any, MemoryScope] =
    Schema.scalarSchema("MemoryScope", None, None, None, s => Value.StringValue(s.toString))
  private given Schema[Any, MemoryRecordId] =
    Schema.scalarSchema("MemoryRecordId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, MemoryRecord] = Schema.gen[Any, MemoryRecord]
  private given Schema[Any, CapabilityGrantId] =
    Schema.scalarSchema("CapabilityGrantId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, CapabilityGrant] = Schema.gen[Any, CapabilityGrant]
  private given Schema[Any, SchedulerJobId] =
    Schema.scalarSchema("SchedulerJobId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, SchedulerTriggerId] =
    Schema.scalarSchema("SchedulerTriggerId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, JobStatus] =
    Schema.scalarSchema("JobStatus", None, None, None, e => Value.StringValue(e.toString))
  private given Schema[Any, TriggerType] =
    Schema.scalarSchema("TriggerType", None, None, None, e => Value.StringValue(e.toString))
  private given Schema[Any, MissedRunPolicy] =
    Schema.scalarSchema("MissedRunPolicy", None, None, None, e => Value.StringValue(e.toString))
  private given Schema[Any, RetryBackoffPolicy] =
    Schema.scalarSchema("RetryBackoffPolicy", None, None, None, e => Value.StringValue(e.toString))
  private given Schema[Any, SkillId] =
    Schema.scalarSchema("SkillId", None, None, None, id => Value.IntValue(id.value))
  private given Schema[Any, SchedulerJob] = Schema.gen[Any, SchedulerJob]
  private given Schema[Any, SchedulerTrigger] = Schema.gen[Any, SchedulerTrigger]

  // ─── Authorization helpers ────────────────────────────────────────────────────

  private val actorIdFromSession: ZIO[JorlanSession, JorlanError, UserId] =
    ZIO
      .serviceWith[JorlanSession](_.user.map(_.id))
      .someOrFail(JorlanError("Not authenticated"))

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
  ): ZIO[ZIORepositories, JorlanError, Unit] =
    ZIO
      .serviceWithZIO[ZIORepositories](
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
      .unit

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
            ZIO.serviceWithZIO[ZIORepositories](
              _.user.search(UserSearch(page = input.page.getOrElse(0), pageSize = input.pageSize.getOrElse(20))),
            ),
          role = input => ZIO.serviceWithZIO[ZIORepositories](_.permission.getRole(input)),
          roles = input =>
            ZIO.serviceWithZIO[ZIORepositories](
              _.permission.searchRoles(
                RoleSearch(
                  userId = input.userId,
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
            p       <- ZIOServerSettingsRepository.getPersonality
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
          jobs = agentIdOpt =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("scheduler.manage", actorId)
              jobs    <- ZIO.serviceWithZIO[JobManager](_.listJobs(agentIdOpt))
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
          skills = ZIO.serviceWithZIO[SkillRegistry](_.allTools).map { tools =>
            tools
              .groupBy(t => t.name.takeWhile(_ != '.'))
              .toList
              .sortBy(_._1)
              .map { case (ns, tds) =>
                SkillInfo(
                  name = ns,
                  tier = tds.headOption.map(_ => "BuiltIn").getOrElse("Unknown"),
                  tools = tds.map(td => SkillToolInfo(td.name, td.description)),
                )
              }
          },
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
          createRole = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("role.create", actorId)
              role    <- ZIO.serviceWithZIO[ZIORepositories](
                _.permission.upsertRole(Role(RoleId.empty, input.name, input.description)),
              )
            } yield role,
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
              _       <- ZIOServerSettingsRepository.setPersonality(input)
            } yield input,
          storeMemory = input =>
            for {
              actorId <- actorIdFromSession
              _       <- requireCapability("memory.write", actorId)
              agentId <- resolveAgentId(actorId)
              record  <- ZIO.serviceWithZIO[MemorySkill](
                _.remember(input.key, input.text, input.scope, actorId, agentId),
              )
              now <- Clock.instant
              _   <- logEvent(EventType.MemoryWritten, Some(actorId), None, now)
            } yield record,
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
        ),
        Subscriptions(
          approvalNotifications = ZStream.empty,
          eventLogTail = ZStream.empty,
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
        ),
      ),
    ) @@ maxFields(200) @@ maxDepth(20) @@ logRequests @@ logErrors

}
