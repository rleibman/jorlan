/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db.repository

import io.getquill.*
import io.getquill.jdbczio.Quill
import jorlan.db.{*, given}
import jorlan.domain.*
import jorlan.{AppConfig, ConfigurationService}
import zio.*

import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

object JorlanSchema {

  inline def qUsers = quote(querySchema[User]("user"))

  inline def qChannelIdentities = quote(querySchema[ChannelIdentity]("channelIdentity"))

  inline def qRoles = quote(querySchema[Role]("role"))

  inline def qPermissions = quote(querySchema[Permission]("permission"))

  inline def qCapabilityGrants = quote(querySchema[CapabilityGrant]("capabilityGrant"))

  inline def qApprovalRequests = quote(querySchema[ApprovalRequest]("approvalRequest"))

  inline def qApprovalDecisions = quote(querySchema[ApprovalDecision]("approvalDecision"))

  inline def qAgents = quote(querySchema[Agent]("agent"))

  inline def qAgentSessions = quote(querySchema[AgentSession]("agentSession"))

  inline def qWorkspaces = quote(querySchema[Workspace]("workspace"))

  inline def qConversations = quote(querySchema[Conversation]("conversation"))

  inline def qMessages = quote(querySchema[Message]("message"))

  inline def qSkills = quote(querySchema[Skill]("skill"))

  inline def qSkillVersions = quote(querySchema[SkillVersion]("skillVersion"))

  inline def qConnectorInstances = quote(querySchema[ConnectorInstance]("connectorInstance"))

  inline def qMemoryRecords = quote(querySchema[MemoryRecord]("memoryRecord"))

  inline def qMemoryEmbeddings = quote(querySchema[MemoryEmbedding]("memoryEmbedding"))

  inline def qEventLogs = quote(querySchema[EventLog]("eventLog"))

  inline def qSchedulerJobs = quote(querySchema[SchedulerJob]("schedulerJob"))

  inline def qSchedulerTriggers = quote(querySchema[SchedulerTrigger]("schedulerTrigger"))

  inline def qArtifacts = quote(querySchema[Artifact]("artifact"))

  inline def qOrchestratorIdentities = quote(querySchema[OrchestratorIdentity]("orchestratorIdentity"))

  case class UserRoleRow(
    userId: UserId,
    roleId: RoleId,
  )
  inline def qUserRoles = quote(querySchema[UserRoleRow]("userRole"))

}

// Shared Quill context carrier — one context per config, shared across all repos.
private class QuillCtx(config: AppConfig) {

  object ctx extends MysqlZioJdbcContext(MysqlEscape)
  val dataSourceLayer: TaskLayer[DataSource] = Quill.DataSource.fromDataSource(config.dataSource)

}

object QuillRepositories {

  val live: ZLayer[
    ConfigurationService,
    Nothing,
    UserRepository & AgentRepository & ConversationRepository & SkillRepository & MemoryRepository &
      EventLogRepository & SchedulerRepository & ArtifactRepository & PermissionRepository,
  ] =
    ZLayer
      .fromZIO {
        ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie.map { config =>
          val qc = new QuillCtx(config)
          (
            new QuillUserRepository(qc),
            new QuillAgentRepository(qc),
            new QuillConversationRepository(qc),
            new QuillSkillRepository(qc),
            new QuillMemoryRepository(qc),
            new QuillEventLogRepository(qc),
            new QuillSchedulerRepository(qc),
            new QuillArtifactRepository(qc),
            new QuillPermissionRepository(qc),
          )
        }
      }.flatMap { env =>
        val (ur, ar, cr, sr, mr, elr, schr, artR, pr) = env.get
        ZLayer.succeed(ur: UserRepository) ++
          ZLayer.succeed(ar: AgentRepository) ++
          ZLayer.succeed(cr: ConversationRepository) ++
          ZLayer.succeed(sr: SkillRepository) ++
          ZLayer.succeed(mr: MemoryRepository) ++
          ZLayer.succeed(elr: EventLogRepository) ++
          ZLayer.succeed(schr: SchedulerRepository) ++
          ZLayer.succeed(artR: ArtifactRepository) ++
          ZLayer.succeed(pr: PermissionRepository)
      }

}

// ─── User ─────────────────────────────────────────────────────────────────────

private class QuillUserRepository(qc: QuillCtx) extends UserRepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  override def getById(id: UserId): RepositoryTask[Option[User]] =
    qc.ctx
      .run(qUsers.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getAll: RepositoryTask[List[User]] =
    qc.ctx
      .run(qUsers.filter(_.active == lift(true)))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsert(user: User): RepositoryTask[User] =
    qc.ctx
      .run(
        qUsers
          .insertValue(lift(user))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.displayName -> e.displayName,
            (
              t,
              e,
            ) => t.active -> e.active,
            (
              t,
              e,
            ) => t.updatedAt -> e.updatedAt,
          )
          .returningGenerated(_.id),
      ).map(id => user.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def deactivate(id: UserId): RepositoryTask[Unit] =
    qc.ctx
      .run(qUsers.filter(_.id == lift(id)).update(_.active -> lift(false))).unit
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getChannelIdentities(userId: UserId): RepositoryTask[List[ChannelIdentity]] =
    qc.ctx
      .run(qChannelIdentities.filter(_.userId == lift(userId)))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsertChannelIdentity(ci: ChannelIdentity): RepositoryTask[ChannelIdentity] =
    qc.ctx
      .run(
        qChannelIdentities
          .insertValue(lift(ci))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.verified -> e.verified,
          )
          .returningGenerated(_.id),
      ).map(id => ci.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def deleteChannelIdentity(id: UserId): RepositoryTask[Unit] =
    qc.ctx
      .run(qChannelIdentities.filter(_.id == lift(id)).delete).unit
      .provideLayer(ds).refineToOrDie[SQLException]

}

// ─── Agent ────────────────────────────────────────────────────────────────────

private class QuillAgentRepository(qc: QuillCtx) extends AgentRepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  override def getById(id: AgentId): RepositoryTask[Option[Agent]] =
    qc.ctx
      .run(qAgents.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getAll: RepositoryTask[List[Agent]] =
    qc.ctx
      .run(qAgents)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsert(agent: Agent): RepositoryTask[Agent] =
    qc.ctx
      .run(
        qAgents
          .insertValue(lift(agent))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.name -> e.name,
            (
              t,
              e,
            ) => t.description -> e.description,
            (
              t,
              e,
            ) => t.defaultModel -> e.defaultModel,
            (
              t,
              e,
            ) => t.trustLevel -> e.trustLevel,
          )
          .returningGenerated(_.id),
      ).map(id => agent.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def delete(id: AgentId): RepositoryTask[Unit] =
    qc.ctx
      .run(qAgents.filter(_.id == lift(id)).delete).unit
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getSession(id: AgentSessionId): RepositoryTask[Option[AgentSession]] =
    qc.ctx
      .run(qAgentSessions.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getSessionsForAgent(agentId: AgentId): RepositoryTask[List[AgentSession]] =
    qc.ctx
      .run(qAgentSessions.filter(_.agentId == lift(agentId)))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsertSession(session: AgentSession): RepositoryTask[AgentSession] =
    qc.ctx
      .run(
        qAgentSessions
          .insertValue(lift(session))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.status -> e.status,
            (
              t,
              e,
            ) => t.updatedAt -> e.updatedAt,
          )
          .returningGenerated(_.id),
      ).map(id => session.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

}

// ─── Conversation ─────────────────────────────────────────────────────────────

private class QuillConversationRepository(qc: QuillCtx) extends ConversationRepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  override def getById(id: ConversationId): RepositoryTask[Option[Conversation]] =
    qc.ctx
      .run(qConversations.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getBySession(sessionId: AgentSessionId): RepositoryTask[List[Conversation]] =
    qc.ctx
      .run(qConversations.filter(_.sessionId == lift(sessionId)))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def create(conversation: Conversation): RepositoryTask[Conversation] =
    qc.ctx
      .run(qConversations.insertValue(lift(conversation)).returningGenerated(_.id))
      .map(id => conversation.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getMessages(conversationId: ConversationId): RepositoryTask[List[Message]] =
    qc.ctx
      .run(qMessages.filter(_.conversationId == lift(conversationId)).sortBy(_.createdAt))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def addMessage(message: Message): RepositoryTask[Message] =
    qc.ctx
      .run(qMessages.insertValue(lift(message)).returningGenerated(_.id))
      .map(id => message.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

}

// ─── Skill ────────────────────────────────────────────────────────────────────

private class QuillSkillRepository(qc: QuillCtx) extends SkillRepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  override def getById(id: SkillId): RepositoryTask[Option[Skill]] =
    qc.ctx
      .run(qSkills.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getAll: RepositoryTask[List[Skill]] =
    qc.ctx
      .run(qSkills)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsert(skill: Skill): RepositoryTask[Skill] =
    qc.ctx
      .run(
        qSkills
          .insertValue(lift(skill))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.name -> e.name,
            (
              t,
              e,
            ) => t.currentVersion -> e.currentVersion,
            (
              t,
              e,
            ) => t.tier -> e.tier,
          )
          .returningGenerated(_.id),
      ).map(id => skill.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getVersion(id: SkillVersionId): RepositoryTask[Option[SkillVersion]] =
    qc.ctx
      .run(qSkillVersions.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getVersions(skillId: SkillId): RepositoryTask[List[SkillVersion]] =
    qc.ctx
      .run(qSkillVersions.filter(_.skillId == lift(skillId)).sortBy(_.version))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsertVersion(v: SkillVersion): RepositoryTask[SkillVersion] =
    qc.ctx
      .run(
        qSkillVersions
          .insertValue(lift(v))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.manifestJson -> e.manifestJson,
            (
              t,
              e,
            ) => t.status -> e.status,
          )
          .returningGenerated(_.id),
      ).map(id => v.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getConnector(id: ConnectorInstanceId): RepositoryTask[Option[ConnectorInstance]] =
    qc.ctx
      .run(qConnectorInstances.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getAllConnectors: RepositoryTask[List[ConnectorInstance]] =
    qc.ctx
      .run(qConnectorInstances)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsertConnector(ci: ConnectorInstance): RepositoryTask[ConnectorInstance] =
    qc.ctx
      .run(
        qConnectorInstances
          .insertValue(lift(ci))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.name -> e.name,
            (
              t,
              e,
            ) => t.configJson -> e.configJson,
            (
              t,
              e,
            ) => t.status -> e.status,
          )
          .returningGenerated(_.id),
      ).map(id => ci.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

}

// ─── Memory ───────────────────────────────────────────────────────────────────

private class QuillMemoryRepository(qc: QuillCtx) extends MemoryRepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  override def getById(id: MemoryRecordId): RepositoryTask[Option[MemoryRecord]] =
    qc.ctx
      .run(qMemoryRecords.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def search(
    scope:       MemoryScope,
    userId:      Option[UserId],
    workspaceId: Option[WorkspaceId],
    agentId:     Option[AgentId],
    key:         Option[String],
  ): RepositoryTask[List[MemoryRecord]] =
    qc.ctx
      .run(
        qMemoryRecords
          .filter(r => r.scope == lift(scope))
          .filter(r => lift(userId).forall(uid => r.userId.contains(uid)))
          .filter(r => lift(workspaceId).forall(wid => r.workspaceId.contains(wid)))
          .filter(r => lift(agentId).forall(aid => r.agentId.contains(aid)))
          .filter(r => lift(key).forall(k => r.key == k)),
      ).provideLayer(ds).refineToOrDie[SQLException]

  override def upsert(record: MemoryRecord): RepositoryTask[MemoryRecord] =
    qc.ctx
      .run(
        qMemoryRecords
          .insertValue(lift(record))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.value -> e.value,
            (
              t,
              e,
            ) => t.ttl -> e.ttl,
            (
              t,
              e,
            ) => t.updatedAt -> e.updatedAt,
          )
          .returningGenerated(_.id),
      ).map(id => record.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def delete(id: MemoryRecordId): RepositoryTask[Unit] =
    qc.ctx
      .run(qMemoryRecords.filter(_.id == lift(id)).delete).unit
      .provideLayer(ds).refineToOrDie[SQLException]

  override def purgeExpired: RepositoryTask[Long] =
    qc.ctx
      .run(sql"DELETE FROM memoryRecord WHERE ttl IS NOT NULL AND ttl < NOW()".as[Action[Long]])
      .provideLayer(ds).refineToOrDie[SQLException]

}

// ─── EventLog ─────────────────────────────────────────────────────────────────

private class QuillEventLogRepository(qc: QuillCtx) extends EventLogRepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  override def append(event: EventLog): RepositoryTask[EventLog] =
    qc.ctx
      .run(qEventLogs.insertValue(lift(event)).returningGenerated(_.id))
      .map(id => event.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def search(
    eventType: Option[EventType],
    agentId:   Option[AgentId],
    from:      Option[Instant],
    to:        Option[Instant],
    limit:     Int,
  ): RepositoryTask[List[EventLog]] =
    qc.ctx
      .run(
        qEventLogs
          .filter(e => lift(eventType).forall(t => e.eventType == t))
          .filter(e => lift(agentId).forall(id => e.agentId.contains(id)))
          .sortBy(_.occurredAt)(Ord.desc)
          .take(lift(limit)),
      ).map { rows =>
        rows
          .filter(e => from.forall(f => !e.occurredAt.isBefore(f)))
          .filter(e => to.forall(t => !e.occurredAt.isAfter(t)))
      }.provideLayer(ds).refineToOrDie[SQLException]

}

// ─── Scheduler ────────────────────────────────────────────────────────────────

private class QuillSchedulerRepository(qc: QuillCtx) extends SchedulerRepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  override def getJob(id: SchedulerJobId): RepositoryTask[Option[SchedulerJob]] =
    qc.ctx
      .run(qSchedulerJobs.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getPendingJobs: RepositoryTask[List[SchedulerJob]] =
    qc.ctx
      .run(qSchedulerJobs.filter(_.status == lift(JobStatus.Pending)).sortBy(_.scheduledAt))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsertJob(job: SchedulerJob): RepositoryTask[SchedulerJob] =
    qc.ctx
      .run(
        qSchedulerJobs
          .insertValue(lift(job))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.status -> e.status,
            (
              t,
              e,
            ) => t.startedAt -> e.startedAt,
            (
              t,
              e,
            ) => t.finishedAt -> e.finishedAt,
            (
              t,
              e,
            ) => t.resultJson -> e.resultJson,
          )
          .returningGenerated(_.id),
      ).map(id => job.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getTriggers(jobId: SchedulerJobId): RepositoryTask[List[SchedulerTrigger]] =
    qc.ctx
      .run(qSchedulerTriggers.filter(_.jobId == lift(jobId)))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsertTrigger(trigger: SchedulerTrigger): RepositoryTask[SchedulerTrigger] =
    qc.ctx
      .run(
        qSchedulerTriggers
          .insertValue(lift(trigger))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.expression -> e.expression,
            (
              t,
              e,
            ) => t.enabled -> e.enabled,
          )
          .returningGenerated(_.id),
      ).map(id => trigger.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

}

// ─── Artifact ─────────────────────────────────────────────────────────────────

private class QuillArtifactRepository(qc: QuillCtx) extends ArtifactRepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  override def getById(id: ArtifactId): RepositoryTask[Option[Artifact]] =
    qc.ctx
      .run(qArtifacts.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getByWorkspace(workspaceId: WorkspaceId): RepositoryTask[List[Artifact]] =
    qc.ctx
      .run(qArtifacts.filter(_.workspaceId.contains(lift(workspaceId))))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsert(artifact: Artifact): RepositoryTask[Artifact] =
    qc.ctx
      .run(
        qArtifacts
          .insertValue(lift(artifact))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.name -> e.name,
            (
              t,
              e,
            ) => t.mimeType -> e.mimeType,
            (
              t,
              e,
            ) => t.sizeBytes -> e.sizeBytes,
            (
              t,
              e,
            ) => t.storageUri -> e.storageUri,
            (
              t,
              e,
            ) => t.metadataJson -> e.metadataJson,
          )
          .returningGenerated(_.id),
      ).map(id => artifact.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def delete(id: ArtifactId): RepositoryTask[Unit] =
    qc.ctx
      .run(qArtifacts.filter(_.id == lift(id)).delete).unit
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getWorkspace(id: WorkspaceId): RepositoryTask[Option[Workspace]] =
    qc.ctx
      .run(qWorkspaces.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getAllWorkspaces(ownerId: UserId): RepositoryTask[List[Workspace]] =
    qc.ctx
      .run(qWorkspaces.filter(_.ownerId == lift(ownerId)))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsertWorkspace(ws: Workspace): RepositoryTask[Workspace] =
    qc.ctx
      .run(
        qWorkspaces
          .insertValue(lift(ws))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.name -> e.name,
            (
              t,
              e,
            ) => t.description -> e.description,
            (
              t,
              e,
            ) => t.updatedAt -> e.updatedAt,
          )
          .returningGenerated(_.id),
      ).map(id => ws.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

}

// ─── Permission ───────────────────────────────────────────────────────────────

private class QuillPermissionRepository(qc: QuillCtx) extends PermissionRepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  override def getRolesForUser(userId: UserId): RepositoryTask[List[Role]] =
    qc.ctx
      .run(
        for {
          ur   <- qUserRoles.filter(_.userId == lift(userId))
          role <- qRoles.join(_.id == ur.roleId)
        } yield role,
      ).provideLayer(ds).refineToOrDie[SQLException]

  override def getPermissionsForRole(roleId: RoleId): RepositoryTask[List[Permission]] =
    qc.ctx
      .run(qPermissions.filter(_.roleId.contains(lift(roleId))))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getPermissionsForUser(userId: UserId): RepositoryTask[List[Permission]] =
    qc.ctx
      .run(qPermissions.filter(_.userId.contains(lift(userId))))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def upsertCapabilityGrant(grant: CapabilityGrant): RepositoryTask[CapabilityGrant] =
    qc.ctx
      .run(
        qCapabilityGrants
          .insertValue(lift(grant))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.approvalMode -> e.approvalMode,
            (
              t,
              e,
            ) => t.expiresAt -> e.expiresAt,
            (
              t,
              e,
            ) => t.resourceConstraints -> e.resourceConstraints,
          )
          .returningGenerated(_.id),
      ).map(id => grant.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getGrantsForUser(userId: UserId): RepositoryTask[List[CapabilityGrant]] =
    qc.ctx
      .run(qCapabilityGrants.filter(_.granteeId == lift(userId)))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def createApprovalRequest(req: ApprovalRequest): RepositoryTask[ApprovalRequest] =
    qc.ctx
      .run(qApprovalRequests.insertValue(lift(req)).returningGenerated(_.id))
      .map(id => req.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def recordApprovalDecision(decision: ApprovalDecision): RepositoryTask[ApprovalDecision] =
    qc.ctx
      .run(qApprovalDecisions.insertValue(lift(decision)).returningGenerated(_.id))
      .map(id => decision.copy(id = id))
      .provideLayer(ds).refineToOrDie[SQLException]

  override def getApprovalRequest(id: ApprovalRequestId): RepositoryTask[Option[ApprovalRequest]] =
    qc.ctx
      .run(qApprovalRequests.filter(_.id == lift(id))).map(_.headOption)
      .provideLayer(ds).refineToOrDie[SQLException]

}
