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
import io.getquill.extras.InstantOps
import io.getquill.jdbczio.Quill
import jorlan.db.{*, given}
import jorlan.domain.*
import jorlan.service.{EventLogFilter, EventLogOrder}
import jorlan.{*, given}
import jorlan.{AppConfig, ConfigurationService}
import zio.*
import zio.json.JsonEncoder
import zio.json.ast.Json

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

  case class EventLogRow(
    id:          EventLogId,
    eventType:   EventType,
    actorId:     Option[UserId],
    agentId:     Option[AgentId],
    sessionId:   Option[AgentSessionId],
    resource:    Option[Json],
    payloadJson: Option[Json],
    occurredAt:  Instant,
  )
  inline def qEventLogs = quote(querySchema[EventLogRow]("eventLog"))

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
  val dataSourceLayer: TaskLayer[DataSource] = Quill.DataSource.fromDataSource(makeDataSource(config))

}

object QuillRepositories {

  val live: ZLayer[
    ConfigurationService,
    Nothing,
    UserZIORepository & AgentZIORepository & ConversationZIORepository & SkillZIORepository & MemoryZIORepository &
      EventLogZIORepository & SchedulerZIORepository & ArtifactZIORepository & PermissionZIORepository,
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
        ZLayer.succeed(ur: UserZIORepository) ++
          ZLayer.succeed(ar: AgentZIORepository) ++
          ZLayer.succeed(cr: ConversationZIORepository) ++
          ZLayer.succeed(sr: SkillZIORepository) ++
          ZLayer.succeed(mr: MemoryZIORepository) ++
          ZLayer.succeed(elr: EventLogZIORepository) ++
          ZLayer.succeed(schr: SchedulerZIORepository) ++
          ZLayer.succeed(artR: ArtifactZIORepository) ++
          ZLayer.succeed(pr: PermissionZIORepository)
      }

}

// ─── User ─────────────────────────────────────────────────────────────────────

private class QuillUserRepository(qc: QuillCtx) extends UserZIORepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  private def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

  override def getById(id: UserId): RepositoryTask[Option[User]] =
    exec(qc.ctx.run(qUsers.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: UserSearch): RepositoryTask[List[User]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(UserOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qUsers
              .filter(u => lift(s.active).forall(a => u.active == a)).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
      case Some(Sort(UserOrder.DisplayName, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qUsers
              .filter(u => lift(s.active).forall(a => u.active == a)).sortBy(_.displayName)(Ord.asc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case Some(Sort(UserOrder.DisplayName, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qUsers
              .filter(u => lift(s.active).forall(a => u.active == a)).sortBy(_.displayName)(Ord.desc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case Some(Sort(UserOrder.CreatedAt, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qUsers
              .filter(u => lift(s.active).forall(a => u.active == a)).sortBy(_.createdAt)(Ord.asc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case Some(Sort(UserOrder.CreatedAt, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qUsers
              .filter(u => lift(s.active).forall(a => u.active == a)).sortBy(_.createdAt)(Ord.desc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qUsers
              .filter(u => lift(s.active).forall(a => u.active == a)).sortBy(_.id)(Ord.asc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
    }
  }

  override def upsert(user: User): RepositoryTask[User] =
    exec(
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
        ).map(id => user.copy(id = id)),
    )

  override def deactivate(id: UserId): RepositoryTask[Long] =
    exec(qc.ctx.run(qUsers.filter(_.id == lift(id)).update(_.active -> lift(false))))

  override def getChannelIdentities(userId: UserId): RepositoryTask[List[ChannelIdentity]] =
    exec(qc.ctx.run(qChannelIdentities.filter(_.userId == lift(userId))))

  override def upsertChannelIdentity(ci: ChannelIdentity): RepositoryTask[ChannelIdentity] =
    exec(
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
        ).map(id => ci.copy(id = id)),
    )

  override def deleteChannelIdentity(id: ChannelIdentityId): RepositoryTask[Long] =
    exec(qc.ctx.run(qChannelIdentities.filter(_.id == lift(id)).delete))

}

// ─── Agent ────────────────────────────────────────────────────────────────────

private class QuillAgentRepository(qc: QuillCtx) extends AgentZIORepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  private def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

  override def getById(id: AgentId): RepositoryTask[Option[Agent]] =
    exec(qc.ctx.run(qAgents.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: AgentSearch): RepositoryTask[List[Agent]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(AgentOrder.Id, OrderDirection.Desc)) =>
        exec(qc.ctx.run(qAgents.sortBy(_.id)(Ord.desc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(AgentOrder.Name, OrderDirection.Asc)) =>
        exec(qc.ctx.run(qAgents.sortBy(_.name)(Ord.asc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(AgentOrder.Name, OrderDirection.Desc)) =>
        exec(qc.ctx.run(qAgents.sortBy(_.name)(Ord.desc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(AgentOrder.CreatedAt, OrderDirection.Asc)) =>
        exec(qc.ctx.run(qAgents.sortBy(_.createdAt)(Ord.asc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(AgentOrder.CreatedAt, OrderDirection.Desc)) =>
        exec(qc.ctx.run(qAgents.sortBy(_.createdAt)(Ord.desc).drop(lift(offset)).take(lift(ps))))
      case _ =>
        exec(qc.ctx.run(qAgents.sortBy(_.id)(Ord.asc).drop(lift(offset)).take(lift(ps))))
    }
  }

  override def upsert(agent: Agent): RepositoryTask[Agent] =
    exec(
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
        ).map(id => agent.copy(id = id)),
    )

  override def delete(id: AgentId): RepositoryTask[Long] = exec(qc.ctx.run(qAgents.filter(_.id == lift(id)).delete))

  override def getSession(id: AgentSessionId): RepositoryTask[Option[AgentSession]] =
    exec(qc.ctx.run(qAgentSessions.filter(_.id == lift(id))).map(_.headOption))

  override def searchSessions(s: AgentSessionSearch): RepositoryTask[List[AgentSession]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(AgentSessionOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qAgentSessions
              .filter(sess => lift(s.agentId).forall(aid => sess.agentId == aid)).sortBy(_.id)(Ord.desc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case Some(Sort(AgentSessionOrder.CreatedAt, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qAgentSessions
              .filter(sess => lift(s.agentId).forall(aid => sess.agentId == aid)).sortBy(_.createdAt)(Ord.asc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case Some(Sort(AgentSessionOrder.CreatedAt, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qAgentSessions
              .filter(sess => lift(s.agentId).forall(aid => sess.agentId == aid)).sortBy(_.createdAt)(Ord.desc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qAgentSessions
              .filter(sess => lift(s.agentId).forall(aid => sess.agentId == aid)).sortBy(_.id)(Ord.asc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
    }
  }

  override def upsertSession(session: AgentSession): RepositoryTask[AgentSession] =
    exec(
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
        ).map(id => session.copy(id = id)),
    )

}

// ─── Conversation ─────────────────────────────────────────────────────────────

private class QuillConversationRepository(qc: QuillCtx) extends ConversationZIORepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  private def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

  override def getById(id: ConversationId): RepositoryTask[Option[Conversation]] =
    exec(qc.ctx.run(qConversations.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: ConversationSearch): RepositoryTask[List[Conversation]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(ConversationOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qConversations
              .filter(_.sessionId == lift(s.sessionId)).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(ConversationOrder.StartedAt, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qConversations
              .filter(_.sessionId == lift(s.sessionId)).sortBy(_.startedAt)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(ConversationOrder.StartedAt, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qConversations
              .filter(_.sessionId == lift(s.sessionId)).sortBy(_.startedAt)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qConversations
              .filter(_.sessionId == lift(s.sessionId)).sortBy(_.id)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
    }
  }

  override def create(conversation: Conversation): RepositoryTask[Conversation] =
    exec(
      qc.ctx
        .run(qConversations.insertValue(lift(conversation)).returningGenerated(_.id))
        .map(id => conversation.copy(id = id)),
    )

  override def searchMessages(s: MessageSearch): RepositoryTask[List[Message]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(MessageOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qMessages
              .filter(_.conversationId == lift(s.conversationId)).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
      case Some(Sort(MessageOrder.CreatedAt, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qMessages
              .filter(_.conversationId == lift(s.conversationId)).sortBy(_.createdAt)(Ord.asc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
      case Some(Sort(MessageOrder.CreatedAt, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qMessages
              .filter(_.conversationId == lift(s.conversationId)).sortBy(_.createdAt)(Ord.desc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qMessages
              .filter(_.conversationId == lift(s.conversationId)).sortBy(_.createdAt)(Ord.asc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
    }
  }

  override def addMessage(message: Message): RepositoryTask[Message] =
    exec(
      qc.ctx
        .run(qMessages.insertValue(lift(message)).returningGenerated(_.id))
        .map(id => message.copy(id = id)),
    )

}

// ─── Skill ────────────────────────────────────────────────────────────────────

private class QuillSkillRepository(qc: QuillCtx) extends SkillZIORepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  private def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

  override def getById(id: SkillId): RepositoryTask[Option[Skill]] =
    exec(qc.ctx.run(qSkills.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: SkillSearch): RepositoryTask[List[Skill]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(SkillOrder.Id, OrderDirection.Desc)) =>
        exec(qc.ctx.run(qSkills.sortBy(_.id)(Ord.desc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(SkillOrder.Name, OrderDirection.Asc)) =>
        exec(qc.ctx.run(qSkills.sortBy(_.name)(Ord.asc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(SkillOrder.Name, OrderDirection.Desc)) =>
        exec(qc.ctx.run(qSkills.sortBy(_.name)(Ord.desc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(SkillOrder.Tier, OrderDirection.Asc)) =>
        exec(qc.ctx.run(qSkills.sortBy(_.tier)(Ord.asc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(SkillOrder.Tier, OrderDirection.Desc)) =>
        exec(qc.ctx.run(qSkills.sortBy(_.tier)(Ord.desc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(SkillOrder.CreatedAt, OrderDirection.Asc)) =>
        exec(qc.ctx.run(qSkills.sortBy(_.createdAt)(Ord.asc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(SkillOrder.CreatedAt, OrderDirection.Desc)) =>
        exec(qc.ctx.run(qSkills.sortBy(_.createdAt)(Ord.desc).drop(lift(offset)).take(lift(ps))))
      case _ =>
        exec(qc.ctx.run(qSkills.sortBy(_.id)(Ord.asc).drop(lift(offset)).take(lift(ps))))
    }
  }

  override def upsert(skill: Skill): RepositoryTask[Skill] =
    exec(
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
        ).map(id => skill.copy(id = id)),
    )

  override def getVersion(id: SkillVersionId): RepositoryTask[Option[SkillVersion]] =
    exec(qc.ctx.run(qSkillVersions.filter(_.id == lift(id))).map(_.headOption))

  override def searchVersions(s: SkillVersionSearch): RepositoryTask[List[SkillVersion]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(SkillVersionOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qSkillVersions.filter(_.skillId == lift(s.skillId)).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(SkillVersionOrder.Version, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qSkillVersions
              .filter(_.skillId == lift(s.skillId)).sortBy(_.version)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(SkillVersionOrder.Version, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qSkillVersions
              .filter(_.skillId == lift(s.skillId)).sortBy(_.version)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(SkillVersionOrder.CreatedAt, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qSkillVersions
              .filter(_.skillId == lift(s.skillId)).sortBy(_.createdAt)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(SkillVersionOrder.CreatedAt, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qSkillVersions
              .filter(_.skillId == lift(s.skillId)).sortBy(_.createdAt)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qSkillVersions
              .filter(_.skillId == lift(s.skillId)).sortBy(_.version)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
    }
  }

  override def upsertVersion(v: SkillVersion): RepositoryTask[SkillVersion] =
    exec(
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
        ).map(id => v.copy(id = id)),
    )

  override def getConnector(id: ConnectorInstanceId): RepositoryTask[Option[ConnectorInstance]] =
    exec(qc.ctx.run(qConnectorInstances.filter(_.id == lift(id))).map(_.headOption))

  override def searchConnectors(s: ConnectorSearch): RepositoryTask[List[ConnectorInstance]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(ConnectorOrder.Id, OrderDirection.Desc)) =>
        exec(qc.ctx.run(qConnectorInstances.sortBy(_.id)(Ord.desc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(ConnectorOrder.Name, OrderDirection.Asc)) =>
        exec(qc.ctx.run(qConnectorInstances.sortBy(_.name)(Ord.asc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(ConnectorOrder.Name, OrderDirection.Desc)) =>
        exec(qc.ctx.run(qConnectorInstances.sortBy(_.name)(Ord.desc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(ConnectorOrder.ConnectorType, OrderDirection.Asc)) =>
        exec(qc.ctx.run(qConnectorInstances.sortBy(_.connectorType)(Ord.asc).drop(lift(offset)).take(lift(ps))))
      case Some(Sort(ConnectorOrder.ConnectorType, OrderDirection.Desc)) =>
        exec(qc.ctx.run(qConnectorInstances.sortBy(_.connectorType)(Ord.desc).drop(lift(offset)).take(lift(ps))))
      case _ =>
        exec(qc.ctx.run(qConnectorInstances.sortBy(_.id)(Ord.asc).drop(lift(offset)).take(lift(ps))))
    }
  }

  override def upsertConnector(ci: ConnectorInstance): RepositoryTask[ConnectorInstance] =
    exec(
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
        ).map(id => ci.copy(id = id)),
    )

}

// ─── Memory ───────────────────────────────────────────────────────────────────

private class QuillMemoryRepository(qc: QuillCtx) extends MemoryZIORepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  private def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

  override def getById(id: MemoryRecordId): RepositoryTask[Option[MemoryRecord]] =
    exec(qc.ctx.run(qMemoryRecords.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: MemorySearch): RepositoryTask[List[MemoryRecord]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(MemoryOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qMemoryRecords
              .filter(r => r.scope == lift(s.scope)).filter(r =>
                lift(s.userId).forall(uid => r.userId.contains(uid)),
              ).filter(r => lift(s.workspaceId).forall(wid => r.workspaceId.contains(wid))).filter(r =>
                lift(s.agentId).forall(aid => r.agentId.contains(aid)),
              ).filter(r => lift(s.key).forall(k => r.recordKey == k)).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
      case Some(Sort(MemoryOrder.RecordKey, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qMemoryRecords
              .filter(r => r.scope == lift(s.scope)).filter(r =>
                lift(s.userId).forall(uid => r.userId.contains(uid)),
              ).filter(r => lift(s.workspaceId).forall(wid => r.workspaceId.contains(wid))).filter(r =>
                lift(s.agentId).forall(aid => r.agentId.contains(aid)),
              ).filter(r => lift(s.key).forall(k => r.recordKey == k)).sortBy(_.recordKey)(Ord.asc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case Some(Sort(MemoryOrder.RecordKey, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qMemoryRecords
              .filter(r => r.scope == lift(s.scope)).filter(r =>
                lift(s.userId).forall(uid => r.userId.contains(uid)),
              ).filter(r => lift(s.workspaceId).forall(wid => r.workspaceId.contains(wid))).filter(r =>
                lift(s.agentId).forall(aid => r.agentId.contains(aid)),
              ).filter(r => lift(s.key).forall(k => r.recordKey == k)).sortBy(_.recordKey)(Ord.desc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case Some(Sort(MemoryOrder.CreatedAt, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qMemoryRecords
              .filter(r => r.scope == lift(s.scope)).filter(r =>
                lift(s.userId).forall(uid => r.userId.contains(uid)),
              ).filter(r => lift(s.workspaceId).forall(wid => r.workspaceId.contains(wid))).filter(r =>
                lift(s.agentId).forall(aid => r.agentId.contains(aid)),
              ).filter(r => lift(s.key).forall(k => r.recordKey == k)).sortBy(_.createdAt)(Ord.asc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case Some(Sort(MemoryOrder.CreatedAt, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qMemoryRecords
              .filter(r => r.scope == lift(s.scope)).filter(r =>
                lift(s.userId).forall(uid => r.userId.contains(uid)),
              ).filter(r => lift(s.workspaceId).forall(wid => r.workspaceId.contains(wid))).filter(r =>
                lift(s.agentId).forall(aid => r.agentId.contains(aid)),
              ).filter(r => lift(s.key).forall(k => r.recordKey == k)).sortBy(_.createdAt)(Ord.desc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case Some(Sort(MemoryOrder.UpdatedAt, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qMemoryRecords
              .filter(r => r.scope == lift(s.scope)).filter(r =>
                lift(s.userId).forall(uid => r.userId.contains(uid)),
              ).filter(r => lift(s.workspaceId).forall(wid => r.workspaceId.contains(wid))).filter(r =>
                lift(s.agentId).forall(aid => r.agentId.contains(aid)),
              ).filter(r => lift(s.key).forall(k => r.recordKey == k)).sortBy(_.updatedAt)(Ord.asc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case Some(Sort(MemoryOrder.UpdatedAt, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qMemoryRecords
              .filter(r => r.scope == lift(s.scope)).filter(r =>
                lift(s.userId).forall(uid => r.userId.contains(uid)),
              ).filter(r => lift(s.workspaceId).forall(wid => r.workspaceId.contains(wid))).filter(r =>
                lift(s.agentId).forall(aid => r.agentId.contains(aid)),
              ).filter(r => lift(s.key).forall(k => r.recordKey == k)).sortBy(_.updatedAt)(Ord.desc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qMemoryRecords
              .filter(r => r.scope == lift(s.scope)).filter(r =>
                lift(s.userId).forall(uid => r.userId.contains(uid)),
              ).filter(r => lift(s.workspaceId).forall(wid => r.workspaceId.contains(wid))).filter(r =>
                lift(s.agentId).forall(aid => r.agentId.contains(aid)),
              ).filter(r => lift(s.key).forall(k => r.recordKey == k)).sortBy(_.id)(Ord.asc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
    }
  }

  override def upsert(record: MemoryRecord): RepositoryTask[MemoryRecord] =
    exec(
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
        ).map(id => record.copy(id = id)),
    )

  override def delete(id: MemoryRecordId): RepositoryTask[Long] =
    exec(qc.ctx.run(qMemoryRecords.filter(_.id == lift(id)).delete))

  override def purgeExpired: RepositoryTask[Long] =
    exec(qc.ctx.run(sql"DELETE FROM memoryRecord WHERE ttl IS NOT NULL AND ttl < NOW()".as[Action[Long]]))

}

// ─── EventLog ─────────────────────────────────────────────────────────────────

private class QuillEventLogRepository(qc: QuillCtx) extends EventLogZIORepository {

  import JorlanSchema.*
  import JorlanSchema.EventLogRow
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  private def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

  private def encodeResource[R: JsonEncoder](resource: Option[R]): Either[String, Option[Json]] =
    resource.fold[Either[String, Option[Json]]](Right(None))(r => JsonEncoder[R].toJsonAST(r).map(Some(_)))

  private def fromRow(row: EventLogRow): EventLog[Json] =
    EventLog(
      row.id,
      row.eventType,
      row.actorId,
      row.agentId,
      row.sessionId,
      row.resource,
      row.payloadJson,
      row.occurredAt,
    )

  override def append[R: JsonEncoder](event: EventLog[R]): RepositoryTask[EventLog[R]] =
    for {
      resourceJson <- ZIO
        .fromEither(encodeResource(event.resource))
        .mapError(msg => RepositoryError(s"Resource encoding failed: $msg"))
      row = EventLogRow(
        event.id,
        event.eventType,
        event.actorId,
        event.agentId,
        event.sessionId,
        resourceJson,
        event.payloadJson,
        event.occurredAt,
      )
      saved <- exec(
        qc.ctx.run(qEventLogs.insertValue(lift(row)).returningGenerated(_.id)).map(id => event.copy(id = id)),
      )
    } yield saved

  override def search(filter: EventLogFilter): RepositoryTask[List[EventLog[Json]]] = {
    val offset = filter.page * filter.pageSize
    val ps = filter.pageSize
    filter.sorts.headOption match {
      case Some(sort) if sort.orderType == EventLogOrder.OccurredAt && sort.direction == OrderDirection.Asc =>
        exec(
          qc.ctx
            .run(
              qEventLogs
                .filter(e => lift(filter.eventType).forall(t => e.eventType == t))
                .filter(e => lift(filter.agentId).forall(id => e.agentId.contains(id)))
                .filter(e => lift(filter.sessionId).forall(sid => e.sessionId.contains(sid)))
                .filter(e => lift(filter.from).forall(f => e.occurredAt >= f))
                .filter(e => lift(filter.to).forall(t => e.occurredAt <= t))
                .sortBy(_.occurredAt)(Ord.asc)
                .drop(lift(offset))
                .take(lift(ps)),
            ).map(_.map(fromRow)),
        )

      case Some(sort) if sort.orderType == EventLogOrder.Id && sort.direction == OrderDirection.Asc =>
        exec(
          qc.ctx
            .run(
              qEventLogs
                .filter(e => lift(filter.eventType).forall(t => e.eventType == t))
                .filter(e => lift(filter.agentId).forall(id => e.agentId.contains(id)))
                .filter(e => lift(filter.sessionId).forall(sid => e.sessionId.contains(sid)))
                .filter(e => lift(filter.from).forall(f => e.occurredAt >= f))
                .filter(e => lift(filter.to).forall(t => e.occurredAt <= t))
                .sortBy(_.id)(Ord.asc)
                .drop(lift(offset))
                .take(lift(ps)),
            ).map(_.map(fromRow)),
        )

      case Some(sort) if sort.orderType == EventLogOrder.Id && sort.direction == OrderDirection.Desc =>
        exec(
          qc.ctx
            .run(
              qEventLogs
                .filter(e => lift(filter.eventType).forall(t => e.eventType == t))
                .filter(e => lift(filter.agentId).forall(id => e.agentId.contains(id)))
                .filter(e => lift(filter.sessionId).forall(sid => e.sessionId.contains(sid)))
                .filter(e => lift(filter.from).forall(f => e.occurredAt >= f))
                .filter(e => lift(filter.to).forall(t => e.occurredAt <= t))
                .sortBy(_.id)(Ord.desc)
                .drop(lift(offset))
                .take(lift(ps)),
            ).map(_.map(fromRow)),
        )

      case _ =>
        exec(
          qc.ctx
            .run(
              qEventLogs
                .filter(e => lift(filter.eventType).forall(t => e.eventType == t))
                .filter(e => lift(filter.agentId).forall(id => e.agentId.contains(id)))
                .filter(e => lift(filter.sessionId).forall(sid => e.sessionId.contains(sid)))
                .filter(e => lift(filter.from).forall(f => e.occurredAt >= f))
                .filter(e => lift(filter.to).forall(t => e.occurredAt <= t))
                .sortBy(_.occurredAt)(Ord.desc)
                .drop(lift(offset))
                .take(lift(ps)),
            ).map(_.map(fromRow)),
        )
    }
  }

  override def replaySession(sessionId: AgentSessionId): RepositoryTask[List[EventLog[Json]]] =
    exec(
      qc.ctx
        .run(
          qEventLogs
            .filter(e => e.sessionId.contains(lift(sessionId)))
            .sortBy(_.occurredAt)(Ord.asc),
        ).map(_.map(fromRow)),
    )

}

// ─── Scheduler ────────────────────────────────────────────────────────────────

private class QuillSchedulerRepository(qc: QuillCtx) extends SchedulerZIORepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  private def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

  override def getJob(id: SchedulerJobId): RepositoryTask[Option[SchedulerJob]] =
    exec(qc.ctx.run(qSchedulerJobs.filter(_.id == lift(id))).map(_.headOption))

  override def getPendingJobs: RepositoryTask[List[SchedulerJob]] =
    for {
      now <- Clock.instant
      result <- exec(
        qc.ctx
          .run(
            qSchedulerJobs
              .filter(_.status == lift(JobStatus.Pending))
              .sortBy(_.scheduledAt),
          ).map(_.filter(j => !j.scheduledAt.isAfter(now))),
      )
    } yield result

  override def upsertJob(job: SchedulerJob): RepositoryTask[SchedulerJob] =
    exec(
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
        ).map(id => job.copy(id = id)),
    )

  override def deleteJob(id: SchedulerJobId): RepositoryTask[Long] =
    exec(qc.ctx.run(qSchedulerJobs.filter(_.id == lift(id)).delete))

  override def searchTriggers(s: TriggerSearch): RepositoryTask[List[SchedulerTrigger]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(TriggerOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qSchedulerTriggers.filter(_.jobId == lift(s.jobId)).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qSchedulerTriggers.filter(_.jobId == lift(s.jobId)).sortBy(_.id)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
    }
  }

  override def upsertTrigger(trigger: SchedulerTrigger): RepositoryTask[SchedulerTrigger] =
    exec(
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
        ).map(id => trigger.copy(id = id)),
    )

  override def deleteTrigger(id: SchedulerTriggerId): RepositoryTask[Long] =
    exec(qc.ctx.run(qSchedulerTriggers.filter(_.id == lift(id)).delete))

}

// ─── Artifact ─────────────────────────────────────────────────────────────────

private class QuillArtifactRepository(qc: QuillCtx) extends ArtifactZIORepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  private def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

  override def getById(id: ArtifactId): RepositoryTask[Option[Artifact]] =
    exec(qc.ctx.run(qArtifacts.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: ArtifactSearch): RepositoryTask[List[Artifact]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(ArtifactOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qArtifacts
              .filter(_.workspaceId.contains(lift(s.workspaceId))).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
      case Some(Sort(ArtifactOrder.Name, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qArtifacts
              .filter(_.workspaceId.contains(lift(s.workspaceId))).sortBy(_.name)(Ord.asc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
      case Some(Sort(ArtifactOrder.Name, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qArtifacts
              .filter(_.workspaceId.contains(lift(s.workspaceId))).sortBy(_.name)(Ord.desc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
      case Some(Sort(ArtifactOrder.CreatedAt, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qArtifacts
              .filter(_.workspaceId.contains(lift(s.workspaceId))).sortBy(_.createdAt)(Ord.asc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
      case Some(Sort(ArtifactOrder.CreatedAt, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qArtifacts
              .filter(_.workspaceId.contains(lift(s.workspaceId))).sortBy(_.createdAt)(Ord.desc).drop(
                lift(offset),
              ).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qArtifacts
              .filter(_.workspaceId.contains(lift(s.workspaceId))).sortBy(_.id)(Ord.asc).drop(lift(offset)).take(
                lift(ps),
              ),
          ),
        )
    }
  }

  override def upsert(artifact: Artifact): RepositoryTask[Artifact] =
    exec(
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
        ).map(id => artifact.copy(id = id)),
    )

  override def delete(id: ArtifactId): RepositoryTask[Long] =
    exec(qc.ctx.run(qArtifacts.filter(_.id == lift(id)).delete))

  override def getWorkspace(id: WorkspaceId): RepositoryTask[Option[Workspace]] =
    exec(qc.ctx.run(qWorkspaces.filter(_.id == lift(id))).map(_.headOption))

  override def searchWorkspaces(s: WorkspaceSearch): RepositoryTask[List[Workspace]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(WorkspaceOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qWorkspaces.filter(_.ownerId == lift(s.ownerId)).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(WorkspaceOrder.Name, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qWorkspaces.filter(_.ownerId == lift(s.ownerId)).sortBy(_.name)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(WorkspaceOrder.Name, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qWorkspaces.filter(_.ownerId == lift(s.ownerId)).sortBy(_.name)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(WorkspaceOrder.CreatedAt, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qWorkspaces
              .filter(_.ownerId == lift(s.ownerId)).sortBy(_.createdAt)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(WorkspaceOrder.CreatedAt, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qWorkspaces
              .filter(_.ownerId == lift(s.ownerId)).sortBy(_.createdAt)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qWorkspaces.filter(_.ownerId == lift(s.ownerId)).sortBy(_.id)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
    }
  }

  override def upsertWorkspace(ws: Workspace): RepositoryTask[Workspace] =
    exec(
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
        ).map(id => ws.copy(id = id)),
    )

}

// ─── Permission ───────────────────────────────────────────────────────────────

private class QuillPermissionRepository(qc: QuillCtx) extends PermissionZIORepository {

  import JorlanSchema.*
  import qc.ctx.*
  private val ds = qc.dataSourceLayer

  private def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

  override def searchRoles(s: RoleSearch): RepositoryTask[List[Role]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(RoleOrder.Name, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            (for {
              ur   <- qUserRoles.filter(_.userId == lift(s.userId))
              role <- qRoles.join(_.id == ur.roleId)
            } yield role).sortBy(_.name)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(RoleOrder.Name, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            (for {
              ur   <- qUserRoles.filter(_.userId == lift(s.userId))
              role <- qRoles.join(_.id == ur.roleId)
            } yield role).sortBy(_.name)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(RoleOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            (for {
              ur   <- qUserRoles.filter(_.userId == lift(s.userId))
              role <- qRoles.join(_.id == ur.roleId)
            } yield role).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            (for {
              ur   <- qUserRoles.filter(_.userId == lift(s.userId))
              role <- qRoles.join(_.id == ur.roleId)
            } yield role).sortBy(_.id)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
    }
  }

  override def searchPermissions(s: PermissionSearch): RepositoryTask[List[Permission]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(PermissionOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qPermissions
              .filter(p => lift(s.roleId).forall(rid => p.roleId.contains(rid))).filter(p =>
                lift(s.userId).forall(uid => p.userId.contains(uid)),
              ).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(PermissionOrder.Resource, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qPermissions
              .filter(p => lift(s.roleId).forall(rid => p.roleId.contains(rid))).filter(p =>
                lift(s.userId).forall(uid => p.userId.contains(uid)),
              ).sortBy(_.resource)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(PermissionOrder.Resource, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qPermissions
              .filter(p => lift(s.roleId).forall(rid => p.roleId.contains(rid))).filter(p =>
                lift(s.userId).forall(uid => p.userId.contains(uid)),
              ).sortBy(_.resource)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(PermissionOrder.Action, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qPermissions
              .filter(p => lift(s.roleId).forall(rid => p.roleId.contains(rid))).filter(p =>
                lift(s.userId).forall(uid => p.userId.contains(uid)),
              ).sortBy(_.action)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(PermissionOrder.Action, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qPermissions
              .filter(p => lift(s.roleId).forall(rid => p.roleId.contains(rid))).filter(p =>
                lift(s.userId).forall(uid => p.userId.contains(uid)),
              ).sortBy(_.action)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qPermissions
              .filter(p => lift(s.roleId).forall(rid => p.roleId.contains(rid))).filter(p =>
                lift(s.userId).forall(uid => p.userId.contains(uid)),
              ).sortBy(_.id)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
    }
  }

  override def upsertCapabilityGrant(grant: CapabilityGrant): RepositoryTask[CapabilityGrant] =
    exec(
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
        ).map(id => grant.copy(id = id)),
    )

  override def revokeGrant(id: CapabilityGrantId): RepositoryTask[Long] =
    exec(qc.ctx.run(qCapabilityGrants.filter(_.id == lift(id)).delete))

  override def searchGrants(s: GrantSearch): RepositoryTask[List[CapabilityGrant]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    s.sorts.headOption match {
      case Some(Sort(GrantOrder.Id, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qCapabilityGrants
              .filter(_.granteeId == lift(s.userId)).sortBy(_.id)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(GrantOrder.GrantedAt, OrderDirection.Asc)) =>
        exec(
          qc.ctx.run(
            qCapabilityGrants
              .filter(_.granteeId == lift(s.userId)).sortBy(_.createdAt)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case Some(Sort(GrantOrder.GrantedAt, OrderDirection.Desc)) =>
        exec(
          qc.ctx.run(
            qCapabilityGrants
              .filter(_.granteeId == lift(s.userId)).sortBy(_.createdAt)(Ord.desc).drop(lift(offset)).take(lift(ps)),
          ),
        )
      case _ =>
        exec(
          qc.ctx.run(
            qCapabilityGrants
              .filter(_.granteeId == lift(s.userId)).sortBy(_.id)(Ord.asc).drop(lift(offset)).take(lift(ps)),
          ),
        )
    }
  }

  override def createApprovalRequest(req: ApprovalRequest): RepositoryTask[ApprovalRequest] =
    exec(
      qc.ctx
        .run(qApprovalRequests.insertValue(lift(req)).returningGenerated(_.id))
        .map(id => req.copy(id = id)),
    )

  override def cancelApprovalRequest(id: ApprovalRequestId): RepositoryTask[Long] =
    exec(
      qc.ctx.run(
        qApprovalRequests
          .filter(_.id == lift(id))
          .update(_.status -> lift(ApprovalStatus.Cancelled)),
      ),
    )

  override def recordApprovalDecision(decision: ApprovalDecision): RepositoryTask[ApprovalDecision] =
    exec(
      qc.ctx
        .run(qApprovalDecisions.insertValue(lift(decision)).returningGenerated(_.id))
        .map(id => decision.copy(id = id)),
    )

  override def getApprovalRequest(id: ApprovalRequestId): RepositoryTask[Option[ApprovalRequest]] =
    exec(qc.ctx.run(qApprovalRequests.filter(_.id == lift(id))).map(_.headOption))

}
