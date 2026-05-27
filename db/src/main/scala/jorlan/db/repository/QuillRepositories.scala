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

// Shared Quill context carrier — one context per datasource, shared across all repos.
private class QuillCtx(hds: DataSource) {

  object ctx extends MysqlZioJdbcContext(MysqlEscape)
  val dataSourceLayer: ULayer[DataSource] = ZLayer.succeed(hds)

}

// Common exec helper shared across all repository classes.
private abstract class QuillRepoBase(qc: QuillCtx) {

  protected val ds: ULayer[DataSource] = qc.dataSourceLayer

  protected def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

}

object QuillRepositories {

  val live: ZLayer[
    ConfigurationService,
    Nothing,
    UserZIORepository & AgentZIORepository & ConversationZIORepository & SkillZIORepository & MemoryZIORepository &
      EventLogZIORepository & SchedulerZIORepository & ArtifactZIORepository & PermissionZIORepository,
  ] =
    ZLayer
      .scoped {
        for {
          config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).orDie
          hds    <- managedDataSource(config)
        } yield {
          val qc = new QuillCtx(hds)
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

private class QuillUserRepository(qc: QuillCtx) extends QuillRepoBase(qc) with UserZIORepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: UserId): RepositoryTask[Option[User]] =
    exec(qc.ctx.run(qUsers.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: UserSearch): RepositoryTask[List[User]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    inline def base = qUsers.filter(u => lift(s.active).forall(a => u.active == a))
    inline def run(q: Query[User]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(UserOrder.Id, OrderDirection.Desc))          => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(UserOrder.DisplayName, OrderDirection.Asc))  => run(base.sortBy(_.displayName)(Ord.asc))
      case Some(Sort(UserOrder.DisplayName, OrderDirection.Desc)) => run(base.sortBy(_.displayName)(Ord.desc))
      case Some(Sort(UserOrder.CreatedAt, OrderDirection.Asc))    => run(base.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(UserOrder.CreatedAt, OrderDirection.Desc))   => run(base.sortBy(_.createdAt)(Ord.desc))
      case _                                                      => run(base.sortBy(_.id)(Ord.asc))
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
              ) => t.email -> e.email,
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
              (
                t,
                e,
              ) => t.providerData -> e.providerData,
            )
            .returningGenerated(_.id),
        ).map(id => ci.copy(id = id)),
    )

  override def login(
    email:    String,
    password: String,
  ): RepositoryTask[Option[User]] = {
    inline def sql =
      quote(
        infix"SELECT id, displayName, email, createdAt, updatedAt, active FROM `user` WHERE email = ${lift(email)} AND hashedPassword = SHA2(${lift(password)}, 512) AND active = 1 LIMIT 1"
          .as[Query[User]],
      )
    exec(qc.ctx.run(sql)).map(_.headOption)
  }

  override def userByEmail(email: String): RepositoryTask[Option[User]] =
    exec(qc.ctx.run(qUsers.filter(_.email == lift(Some(email): Option[String])).take(1))).map(_.headOption)

  override def changePassword(
    id:          UserId,
    newPassword: String,
  ): RepositoryTask[Unit] =
    exec(
      qc.ctx.run(
        quote(
          infix"UPDATE `user` SET hashedPassword = SHA2(${lift(newPassword)}, 512) WHERE id = ${lift(id)}"
            .as[Update[Long]],
        ),
      ),
    ).unit

  override def userByChannelIdentity(
    channelType:   ChannelType,
    channelUserId: String,
  ): RepositoryTask[Option[User]] =
    exec(
      qc.ctx.run(
        (for {
          ci <- qChannelIdentities.filter(ci =>
            ci.channelType == lift(channelType) && ci.channelUserId == lift(channelUserId),
          )
          user <- qUsers.join(_.id == ci.userId)
        } yield user).take(1),
      ),
    ).map(_.headOption)

  override def deleteChannelIdentity(id: ChannelIdentityId): RepositoryTask[Long] =
    exec(qc.ctx.run(qChannelIdentities.filter(_.id == lift(id)).delete))

}

// ─── Agent ────────────────────────────────────────────────────────────────────

private class QuillAgentRepository(qc: QuillCtx) extends QuillRepoBase(qc) with AgentZIORepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: AgentId): RepositoryTask[Option[Agent]] =
    exec(qc.ctx.run(qAgents.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: AgentSearch): RepositoryTask[List[Agent]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    inline def base = qAgents
    inline def run(q: Query[Agent]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(AgentOrder.Id, OrderDirection.Desc))        => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(AgentOrder.Name, OrderDirection.Asc))       => run(base.sortBy(_.name)(Ord.asc))
      case Some(Sort(AgentOrder.Name, OrderDirection.Desc))      => run(base.sortBy(_.name)(Ord.desc))
      case Some(Sort(AgentOrder.CreatedAt, OrderDirection.Asc))  => run(base.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(AgentOrder.CreatedAt, OrderDirection.Desc)) => run(base.sortBy(_.createdAt)(Ord.desc))
      case _                                                     => run(base.sortBy(_.id)(Ord.asc))
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
    inline def base = qAgentSessions.filter(sess => lift(s.agentId).forall(aid => sess.agentId == aid))
    inline def run(q: Query[AgentSession]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(AgentSessionOrder.Id, OrderDirection.Desc))        => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(AgentSessionOrder.CreatedAt, OrderDirection.Asc))  => run(base.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(AgentSessionOrder.CreatedAt, OrderDirection.Desc)) => run(base.sortBy(_.createdAt)(Ord.desc))
      case _                                                            => run(base.sortBy(_.id)(Ord.asc))
    }
  }

  override def upsertSession(session: AgentSession): RepositoryTask[AgentSession] =
    if (session.id.value == 0L) {
      exec(
        qc.ctx.run(qAgentSessions.insertValue(lift(session)).returningGenerated(_.id)).map(id => session.copy(id = id)),
      )
    } else {
      exec(
        qc.ctx
          .run(
            qAgentSessions
              .filter(_.id == lift(session.id))
              .update(
                _.status    -> lift(session.status),
                _.updatedAt -> lift(session.updatedAt),
              ),
          ).as(session),
      )
    }

}

// ─── Conversation ─────────────────────────────────────────────────────────────

private class QuillConversationRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ConversationZIORepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: ConversationId): RepositoryTask[Option[Conversation]] =
    exec(qc.ctx.run(qConversations.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: ConversationSearch): RepositoryTask[List[Conversation]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    inline def base = qConversations.filter(_.sessionId == lift(s.sessionId))
    inline def run(q: Query[Conversation]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(ConversationOrder.Id, OrderDirection.Desc))        => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(ConversationOrder.StartedAt, OrderDirection.Asc))  => run(base.sortBy(_.startedAt)(Ord.asc))
      case Some(Sort(ConversationOrder.StartedAt, OrderDirection.Desc)) => run(base.sortBy(_.startedAt)(Ord.desc))
      case _                                                            => run(base.sortBy(_.id)(Ord.asc))
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
    inline def base = qMessages.filter(_.conversationId == lift(s.conversationId))
    inline def run(q: Query[Message]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(MessageOrder.Id, OrderDirection.Desc))        => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(MessageOrder.CreatedAt, OrderDirection.Asc))  => run(base.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(MessageOrder.CreatedAt, OrderDirection.Desc)) => run(base.sortBy(_.createdAt)(Ord.desc))
      case _                                                       => run(base.sortBy(_.createdAt)(Ord.asc))
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

private class QuillSkillRepository(qc: QuillCtx) extends QuillRepoBase(qc) with SkillZIORepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: SkillId): RepositoryTask[Option[Skill]] =
    exec(qc.ctx.run(qSkills.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: SkillSearch): RepositoryTask[List[Skill]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    inline def base = qSkills
    inline def run(q: Query[Skill]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(SkillOrder.Id, OrderDirection.Desc))        => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(SkillOrder.Name, OrderDirection.Asc))       => run(base.sortBy(_.name)(Ord.asc))
      case Some(Sort(SkillOrder.Name, OrderDirection.Desc))      => run(base.sortBy(_.name)(Ord.desc))
      case Some(Sort(SkillOrder.Tier, OrderDirection.Asc))       => run(base.sortBy(_.tier)(Ord.asc))
      case Some(Sort(SkillOrder.Tier, OrderDirection.Desc))      => run(base.sortBy(_.tier)(Ord.desc))
      case Some(Sort(SkillOrder.CreatedAt, OrderDirection.Asc))  => run(base.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(SkillOrder.CreatedAt, OrderDirection.Desc)) => run(base.sortBy(_.createdAt)(Ord.desc))
      case _                                                     => run(base.sortBy(_.id)(Ord.asc))
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
    inline def base = qSkillVersions.filter(_.skillId == lift(s.skillId))
    inline def run(q: Query[SkillVersion]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(SkillVersionOrder.Id, OrderDirection.Desc))        => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(SkillVersionOrder.Version, OrderDirection.Asc))    => run(base.sortBy(_.version)(Ord.asc))
      case Some(Sort(SkillVersionOrder.Version, OrderDirection.Desc))   => run(base.sortBy(_.version)(Ord.desc))
      case Some(Sort(SkillVersionOrder.CreatedAt, OrderDirection.Asc))  => run(base.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(SkillVersionOrder.CreatedAt, OrderDirection.Desc)) => run(base.sortBy(_.createdAt)(Ord.desc))
      case _                                                            => run(base.sortBy(_.version)(Ord.asc))
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
    inline def base = qConnectorInstances
    inline def run(q: Query[ConnectorInstance]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(ConnectorOrder.Id, OrderDirection.Desc))            => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(ConnectorOrder.Name, OrderDirection.Asc))           => run(base.sortBy(_.name)(Ord.asc))
      case Some(Sort(ConnectorOrder.Name, OrderDirection.Desc))          => run(base.sortBy(_.name)(Ord.desc))
      case Some(Sort(ConnectorOrder.ConnectorType, OrderDirection.Asc))  => run(base.sortBy(_.connectorType)(Ord.asc))
      case Some(Sort(ConnectorOrder.ConnectorType, OrderDirection.Desc)) => run(base.sortBy(_.connectorType)(Ord.desc))
      case _                                                             => run(base.sortBy(_.id)(Ord.asc))
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

private class QuillMemoryRepository(qc: QuillCtx) extends QuillRepoBase(qc) with MemoryZIORepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: MemoryRecordId): RepositoryTask[Option[MemoryRecord]] =
    exec(qc.ctx.run(qMemoryRecords.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: MemorySearch): RepositoryTask[List[MemoryRecord]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    inline def base =
      qMemoryRecords
        .filter(r => r.scope == lift(s.scope))
        .filter(r => lift(s.userId).forall(uid => r.userId.contains(uid)))
        .filter(r => lift(s.workspaceId).forall(wid => r.workspaceId.contains(wid)))
        .filter(r => lift(s.agentId).forall(aid => r.agentId.contains(aid)))
        .filter(r => lift(s.key).forall(k => r.recordKey == k))
    inline def run(q: Query[MemoryRecord]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(MemoryOrder.Id, OrderDirection.Desc))        => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(MemoryOrder.RecordKey, OrderDirection.Asc))  => run(base.sortBy(_.recordKey)(Ord.asc))
      case Some(Sort(MemoryOrder.RecordKey, OrderDirection.Desc)) => run(base.sortBy(_.recordKey)(Ord.desc))
      case Some(Sort(MemoryOrder.CreatedAt, OrderDirection.Asc))  => run(base.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(MemoryOrder.CreatedAt, OrderDirection.Desc)) => run(base.sortBy(_.createdAt)(Ord.desc))
      case Some(Sort(MemoryOrder.UpdatedAt, OrderDirection.Asc))  => run(base.sortBy(_.updatedAt)(Ord.asc))
      case Some(Sort(MemoryOrder.UpdatedAt, OrderDirection.Desc)) => run(base.sortBy(_.updatedAt)(Ord.desc))
      case _                                                      => run(base.sortBy(_.id)(Ord.asc))
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

private class QuillEventLogRepository(qc: QuillCtx) extends QuillRepoBase(qc) with EventLogZIORepository {

  import JorlanSchema.*
  import JorlanSchema.EventLogRow
  import qc.ctx.*

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
    inline def base =
      qEventLogs
        .filter(e => lift(filter.eventType).forall(t => e.eventType == t))
        .filter(e => lift(filter.agentId).forall(id => e.agentId.contains(id)))
        .filter(e => lift(filter.sessionId).forall(sid => e.sessionId.contains(sid)))
        .filter(e => lift(filter.from).forall(f => e.occurredAt >= f))
        .filter(e => lift(filter.to).forall(t => e.occurredAt <= t))
    inline def run(q: Query[EventLogRow]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))).map(_.map(fromRow)))
    filter.sorts match {
      case Some(sort) if sort.orderType == EventLogOrder.OccurredAt && sort.direction == OrderDirection.Asc =>
        run(base.sortBy(_.occurredAt)(Ord.asc))
      case Some(sort) if sort.orderType == EventLogOrder.Id && sort.direction == OrderDirection.Asc =>
        run(base.sortBy(_.id)(Ord.asc))
      case Some(sort) if sort.orderType == EventLogOrder.Id && sort.direction == OrderDirection.Desc =>
        run(base.sortBy(_.id)(Ord.desc))
      case _ => run(base.sortBy(_.occurredAt)(Ord.desc))
    }
  }

  override def replaySession(
    sessionId: AgentSessionId,
    limit:     Int = 1000,
  ): RepositoryTask[List[EventLog[Json]]] =
    exec(
      qc.ctx
        .run(
          qEventLogs
            .filter(e => e.sessionId.contains(lift(sessionId)))
            .sortBy(_.occurredAt)(Ord.asc)
            .take(lift(limit)),
        ).map(_.map(fromRow)),
    )

}

// ─── Scheduler ────────────────────────────────────────────────────────────────

private class QuillSchedulerRepository(qc: QuillCtx) extends QuillRepoBase(qc) with SchedulerZIORepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getJob(id: SchedulerJobId): RepositoryTask[Option[SchedulerJob]] =
    exec(qc.ctx.run(qSchedulerJobs.filter(_.id == lift(id))).map(_.headOption))

  override def getPendingJobs: RepositoryTask[List[SchedulerJob]] =
    for {
      now <- Clock.instant
      result <- exec(
        qc.ctx.run(
          qSchedulerJobs
            .filter(j => j.status == lift(JobStatus.Pending) && j.scheduledAt <= lift(now))
            .sortBy(_.scheduledAt),
        ),
      )
    } yield result

  override def upsertJob(job: SchedulerJob): RepositoryTask[SchedulerJob] =
    if (job.id.value == 0L) {
      exec(
        qc.ctx.run(qSchedulerJobs.insertValue(lift(job)).returningGenerated(_.id)).map(id => job.copy(id = id)),
      )
    } else {
      exec(
        qc.ctx
          .run(
            qSchedulerJobs
              .filter(_.id == lift(job.id))
              .update(
                _.status     -> lift(job.status),
                _.startedAt  -> lift(job.startedAt),
                _.finishedAt -> lift(job.finishedAt),
                _.resultJson -> lift(job.resultJson),
              ),
          ).as(job),
      )
    }

  override def deleteJob(id: SchedulerJobId): RepositoryTask[Long] =
    exec(qc.ctx.run(qSchedulerJobs.filter(_.id == lift(id)).delete))

  override def searchTriggers(s: TriggerSearch): RepositoryTask[List[SchedulerTrigger]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    inline def base = qSchedulerTriggers.filter(_.jobId == lift(s.jobId))
    inline def run(q: Query[SchedulerTrigger]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(TriggerOrder.Id, OrderDirection.Desc)) => run(base.sortBy(_.id)(Ord.desc))
      case _                                                => run(base.sortBy(_.id)(Ord.asc))
    }
  }

  override def upsertTrigger(trigger: SchedulerTrigger): RepositoryTask[SchedulerTrigger] =
    if (trigger.id.value == 0L) {
      exec(
        qc.ctx
          .run(qSchedulerTriggers.insertValue(lift(trigger)).returningGenerated(_.id)).map(id => trigger.copy(id = id)),
      )
    } else {
      exec(
        qc.ctx
          .run(
            qSchedulerTriggers
              .filter(_.id == lift(trigger.id))
              .update(
                _.expression -> lift(trigger.expression),
                _.enabled    -> lift(trigger.enabled),
              ),
          ).as(trigger),
      )
    }

  override def deleteTrigger(id: SchedulerTriggerId): RepositoryTask[Long] =
    exec(qc.ctx.run(qSchedulerTriggers.filter(_.id == lift(id)).delete))

}

// ─── Artifact ─────────────────────────────────────────────────────────────────

private class QuillArtifactRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ArtifactZIORepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: ArtifactId): RepositoryTask[Option[Artifact]] =
    exec(qc.ctx.run(qArtifacts.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: ArtifactSearch): RepositoryTask[List[Artifact]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    inline def base = qArtifacts.filter(_.workspaceId.contains(lift(s.workspaceId)))
    inline def run(q: Query[Artifact]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(ArtifactOrder.Id, OrderDirection.Desc))        => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(ArtifactOrder.Name, OrderDirection.Asc))       => run(base.sortBy(_.name)(Ord.asc))
      case Some(Sort(ArtifactOrder.Name, OrderDirection.Desc))      => run(base.sortBy(_.name)(Ord.desc))
      case Some(Sort(ArtifactOrder.CreatedAt, OrderDirection.Asc))  => run(base.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(ArtifactOrder.CreatedAt, OrderDirection.Desc)) => run(base.sortBy(_.createdAt)(Ord.desc))
      case _                                                        => run(base.sortBy(_.id)(Ord.asc))
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
    inline def base = qWorkspaces.filter(_.ownerId == lift(s.ownerId))
    inline def run(q: Query[Workspace]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(WorkspaceOrder.Id, OrderDirection.Desc))        => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(WorkspaceOrder.Name, OrderDirection.Asc))       => run(base.sortBy(_.name)(Ord.asc))
      case Some(Sort(WorkspaceOrder.Name, OrderDirection.Desc))      => run(base.sortBy(_.name)(Ord.desc))
      case Some(Sort(WorkspaceOrder.CreatedAt, OrderDirection.Asc))  => run(base.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(WorkspaceOrder.CreatedAt, OrderDirection.Desc)) => run(base.sortBy(_.createdAt)(Ord.desc))
      case _                                                         => run(base.sortBy(_.id)(Ord.asc))
    }
  }

  override def upsertWorkspace(ws: Workspace): RepositoryTask[Workspace] =
    if (ws.id.value == 0L) {
      exec(
        qc.ctx.run(qWorkspaces.insertValue(lift(ws)).returningGenerated(_.id)).map(id => ws.copy(id = id)),
      )
    } else {
      exec(
        qc.ctx
          .run(
            qWorkspaces
              .filter(_.id == lift(ws.id))
              .update(
                _.name        -> lift(ws.name),
                _.description -> lift(ws.description),
                _.updatedAt   -> lift(ws.updatedAt),
              ),
          ).as(ws),
      )
    }

}

// ─── Permission ───────────────────────────────────────────────────────────────

private class QuillPermissionRepository(qc: QuillCtx) extends QuillRepoBase(qc) with PermissionZIORepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def searchRoles(s: RoleSearch): RepositoryTask[List[Role]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    inline def base =
      for {
        ur   <- qUserRoles.filter(_.userId == lift(s.userId))
        role <- qRoles.join(_.id == ur.roleId)
      } yield role
    inline def run(q: Query[Role]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(RoleOrder.Name, OrderDirection.Asc))  => run(base.sortBy(_.name)(Ord.asc))
      case Some(Sort(RoleOrder.Name, OrderDirection.Desc)) => run(base.sortBy(_.name)(Ord.desc))
      case Some(Sort(RoleOrder.Id, OrderDirection.Desc))   => run(base.sortBy(_.id)(Ord.desc))
      case _                                               => run(base.sortBy(_.id)(Ord.asc))
    }
  }

  override def searchPermissions(s: PermissionSearch): RepositoryTask[List[Permission]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    inline def base =
      qPermissions
        .filter(p => lift(s.roleId).forall(rid => p.roleId.contains(rid)))
        .filter(p => lift(s.userId).forall(uid => p.userId.contains(uid)))
    inline def run(q: Query[Permission]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(PermissionOrder.Id, OrderDirection.Desc))       => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(PermissionOrder.Resource, OrderDirection.Asc))  => run(base.sortBy(_.resource)(Ord.asc))
      case Some(Sort(PermissionOrder.Resource, OrderDirection.Desc)) => run(base.sortBy(_.resource)(Ord.desc))
      case Some(Sort(PermissionOrder.Action, OrderDirection.Asc))    => run(base.sortBy(_.action)(Ord.asc))
      case Some(Sort(PermissionOrder.Action, OrderDirection.Desc))   => run(base.sortBy(_.action)(Ord.desc))
      case _                                                         => run(base.sortBy(_.id)(Ord.asc))
    }
  }

  override def upsertCapabilityGrant(grant: CapabilityGrant): RepositoryTask[CapabilityGrant] =
    if (grant.id.value == 0L) {
      exec(
        qc.ctx.run(qCapabilityGrants.insertValue(lift(grant)).returningGenerated(_.id)).map(id => grant.copy(id = id)),
      )
    } else {
      exec(
        qc.ctx
          .run(
            qCapabilityGrants
              .filter(_.id == lift(grant.id))
              .update(
                _.approvalMode        -> lift(grant.approvalMode),
                _.expiresAt           -> lift(grant.expiresAt),
                _.resourceConstraints -> lift(grant.resourceConstraints),
              ),
          ).as(grant),
      )
    }

  override def revokeGrant(id: CapabilityGrantId): RepositoryTask[Long] =
    exec(qc.ctx.run(qCapabilityGrants.filter(_.id == lift(id)).delete))

  override def searchGrants(s: GrantSearch): RepositoryTask[List[CapabilityGrant]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    inline def base = qCapabilityGrants.filter(_.granteeId == lift(s.userId))
    inline def run(q: Query[CapabilityGrant]) = exec(qc.ctx.run(q.drop(lift(offset)).take(lift(ps))))
    s.sorts match {
      case Some(Sort(GrantOrder.Id, OrderDirection.Desc))        => run(base.sortBy(_.id)(Ord.desc))
      case Some(Sort(GrantOrder.GrantedAt, OrderDirection.Asc))  => run(base.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(GrantOrder.GrantedAt, OrderDirection.Desc)) => run(base.sortBy(_.createdAt)(Ord.desc))
      case _                                                     => run(base.sortBy(_.id)(Ord.asc))
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

  override def deleteRole(id: RoleId): RepositoryTask[Long] = exec(qc.ctx.run(qRoles.filter(_.id == lift(id)).delete))

  override def deletePermission(id: PermissionId): RepositoryTask[Long] =
    exec(qc.ctx.run(qPermissions.filter(_.id == lift(id)).delete))

  override def getExpiredApprovalRequests: RepositoryTask[List[ApprovalRequest]] =
    for {
      now <- Clock.instant
      result <- exec(
        qc.ctx.run(
          qApprovalRequests
            .filter(r => r.status == lift(ApprovalStatus.Pending))
            .filter(r => r.expiresAt.exists(_ <= lift(now))),
        ),
      )
    } yield result

}
