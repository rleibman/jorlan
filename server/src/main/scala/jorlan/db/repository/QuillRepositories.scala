/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.db.repository

import io.getquill.*
import io.getquill.extras.InstantOps
import io.getquill.jdbczio.Quill
import jorlan.db.{*, given}
import jorlan.service.{EventLogFilter, EventLogOrder}
import jorlan.{*, given}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import javax.sql.DataSource

object JorlanSchema {

  inline def qServerSettings = quote(querySchema[ServerSettingRow]("server_settings", _.settingKey -> "setting_key"))

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

  inline def qSkills = quote(querySchema[SkillRecord]("skill"))

  inline def qSkillVersions = quote(querySchema[SkillVersion]("skillVersion"))

  inline def qConnectorInstances = quote(querySchema[ConnectorInstance]("connectorInstance"))

  case class SkillSearchResult(
    id:   Long,
    name: String,
  )

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

  case class ExternalCredentialRow(
    id:             ExternalCredentialId,
    userId:         UserId,
    provider:       String,
    credentialData: Json,
    expiresAt:      Option[Instant],
    scopes:         Option[String],
    createdAt:      Instant,
    updatedAt:      Instant,
  )
  inline def qExternalCredentials =
    quote(
      querySchema[ExternalCredentialRow](
        "external_credentials",
        _.credentialData -> "credential_data",
        _.expiresAt      -> "expires_at",
        _.createdAt      -> "created_at",
        _.updatedAt      -> "updated_at",
        _.userId         -> "user_id",
      ),
    )

}

// Shared Quill context carrier — one context per datasource, shared across all repos.
private[repository] class QuillCtx(hds: DataSource) {

  object ctx extends MysqlZioJdbcContext(MysqlEscape)
  val dataSourceLayer: ULayer[DataSource] = ZLayer.succeed(hds)

}

// Common exec helper shared across all repository classes.
private[repository] abstract class QuillRepoBase(qc: QuillCtx) {

  protected val ds: ULayer[DataSource] = qc.dataSourceLayer

  protected def exec[A](q: ZIO[DataSource, Throwable, A]): RepositoryTask[A] =
    q.provideLayer(ds).mapError(RepositoryError(_))

}

object QuillRepositories {

  val live: ZLayer[ConfigurationService, JorlanError, QuillRepositories] =
    ZLayer
      .scoped {
        for {
          config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.db)
          hds    <- managedDataSource(config)
        } yield QuillCtx(hds)
      }.flatMap { env =>
        val qc = env.get

        ZLayer.succeed(QuillRepositories(qc))
      }

}

class QuillRepositories(qc: QuillCtx) extends ZIORepositories {

  override def user: ZIOUserRepository = QuillUserRepository(qc)

  override def agent: ZIOAgentRepository = QuillAgentRepository(qc)

  override def conversation: ZIOConversationRepository = QuillConversationRepository(qc)

  override def skill: ZIOSkillRepository = QuillSkillRepository(qc)

  override def memory: ZIOMemoryRepository = QuillMemoryRepository(qc)

  override def eventLog: ZIOEventLogRepository = QuillEventLogRepository(qc)

  override def scheduler: ZIOSchedulerRepository = QuillSchedulerRepository(qc)

  override def artifact: ZIOArtifactRepository = QuillArtifactRepository(qc)

  override def permission: ZIOPermissionRepository = QuillPermissionRepository(qc)

  override def setting: ZIOServerSettingsRepository = QuillServerSettingsRepository(qc)

  override def extCredential: ZIOExternalCredentialRepository = QuillExternalCredentialRepository(qc)

  override def serverInfo: ZIOServerInfoRepository =
    new ZIOServerInfoRepository {
      override def statusCheck(): RepositoryTask[Json] = ZIO.succeed(Json.Obj())
    }

  override def skillIndex: ZIOSkillIndexRepository = QuillSkillIndexRepository(qc)

  val dataSourceLayer: ULayer[DataSource] = qc.dataSourceLayer

}

// ─── User ─────────────────────────────────────────────────────────────────────

private class QuillUserRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOUserRepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: UserId): RepositoryTask[Option[User]] =
    exec(qc.ctx.run(qUsers.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: UserSearch): RepositoryTask[List[User]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    val base = (s.nameContains, s.fuzzyName) match {
      case (_, Some(name)) =>
        val likePattern = s"%$name%"
        quote(
          qUsers.filter(u =>
            lift(s.active).forall(a => u.active == a) &&
              (infix"${u.displayName} LIKE ${lift(likePattern)}".as[Boolean] ||
                infix"SOUNDEX(${u.displayName}) = SOUNDEX(${lift(name)})".as[Boolean] ||
                infix"SOUNDEX(SUBSTRING_INDEX(${u.displayName}, ' ', -1)) = SOUNDEX(${lift(name)})".as[Boolean]),
          ),
        )
      case (None, None) =>
        quote(qUsers.filter(u => lift(s.active).forall(a => u.active == a)))
      case (Some(name), None) =>
        val likePattern = s"%$name%"
        quote(
          qUsers.filter(u =>
            lift(s.active).forall(a => u.active == a) &&
              infix"${u.displayName} LIKE ${lift(likePattern)}".as[Boolean],
          ),
        )
    }
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[User]] = s.sorts match {
      case Some(Sort(UserOrder.Id, OrderDirection.Desc))          => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(UserOrder.DisplayName, OrderDirection.Asc))  => quote(limited.sortBy(_.displayName)(Ord.asc))
      case Some(Sort(UserOrder.DisplayName, OrderDirection.Desc)) => quote(limited.sortBy(_.displayName)(Ord.desc))
      case Some(Sort(UserOrder.CreatedAt, OrderDirection.Asc))    => quote(limited.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(UserOrder.CreatedAt, OrderDirection.Desc))   => quote(limited.sortBy(_.createdAt)(Ord.desc))
      case _                                                      => quote(limited.sortBy(_.id)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
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
    exec(qc.ctx.run(qUsers.filter(_.email == lift(email)).take(1))).map(_.headOption)

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

  override def findContacts(nameOpt: Option[String]): RepositoryTask[Json] = ZIO.succeed(Json.Arr())

}

// ─── Agent ────────────────────────────────────────────────────────────────────

private class QuillAgentRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOAgentRepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: AgentId): RepositoryTask[Option[Agent]] =
    exec(qc.ctx.run(qAgents.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: AgentSearch): RepositoryTask[List[Agent]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    val base = quote(qAgents)
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[Agent]] = s.sorts match {
      case Some(Sort(AgentOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(AgentOrder.Name, OrderDirection.Asc))       => quote(limited.sortBy(_.name)(Ord.asc))
      case Some(Sort(AgentOrder.Name, OrderDirection.Desc))      => quote(limited.sortBy(_.name)(Ord.desc))
      case Some(Sort(AgentOrder.CreatedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(AgentOrder.CreatedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.createdAt)(Ord.desc))
      case _                                                     => quote(limited.sortBy(_.id)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
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
              (
                t,
                e,
              ) => t.prioritizedSkills -> e.prioritizedSkills,
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
    val base = quote(
      qAgentSessions
        .filter(sess => lift(s.agentId).forall(aid => sess.agentId == aid))
        .filter(sess => lift(s.userId).forall(uid => sess.userId == uid))
        .filter(sess => lift(s.chatRef).forall(cr => sess.chatRef.contains(cr))),
    )
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[AgentSession]] = s.sorts match {
      case Some(Sort(AgentSessionOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(AgentSessionOrder.CreatedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(AgentSessionOrder.CreatedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.createdAt)(Ord.desc))
      case _                                                            => quote(limited.sortBy(_.id)(Ord.asc))
    }
    val terminalStatuses = Set(SessionStatus.Completed, SessionStatus.Failed, SessionStatus.Cancelled)
    exec(qc.ctx.run(sorted)).map { sessions =>
      s.hideOldTerminatedBefore.fold(sessions) { cutoff =>
        sessions.filterNot(sess => terminalStatuses.contains(sess.status) && sess.updatedAt.isBefore(cutoff))
      }
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
                _.chatRef   -> lift(session.chatRef),
                _.updatedAt -> lift(session.updatedAt),
              ),
          ).as(session),
      )
    }

  override def createSession(modelId: Option[ModelId]): RepositoryTask[Option[AgentSession]] =
    ZIO.fail(RepositoryError("createSession not implemented in QuillAgentRepository"))
  override def terminateSession(sessionId: AgentSessionId): RepositoryTask[Unit] =
    ZIO.fail(RepositoryError("terminateSession not implemented in QuillAgentRepository"))
  override def availableModels(): RepositoryTask[List[ModelInfo]] =
    ZIO.fail(RepositoryError("availableModels not implemented in QuillAgentRepository"))
  override def submitMessage(
    sessionId: AgentSessionId,
    content:   String,
  ): RepositoryTask[Unit] =
    ZIO.fail(RepositoryError("submitMessage not implemented in QuillAgentRepository"))

}

// ─── Conversation ─────────────────────────────────────────────────────────────

private class QuillConversationRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOConversationRepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: ConversationId): RepositoryTask[Option[Conversation]] =
    exec(qc.ctx.run(qConversations.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: ConversationSearch): RepositoryTask[List[Conversation]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    val base = quote(qConversations.filter(_.sessionId == lift(s.sessionId)))
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[Conversation]] = s.sorts match {
      case Some(Sort(ConversationOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(ConversationOrder.StartedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.startedAt)(Ord.asc))
      case Some(Sort(ConversationOrder.StartedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.startedAt)(Ord.desc))
      case _                                                            => quote(limited.sortBy(_.id)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
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
    val base = quote(qMessages.filter(_.conversationId == lift(s.conversationId)))
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[Message]] = s.sorts match {
      case Some(Sort(MessageOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(MessageOrder.CreatedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(MessageOrder.CreatedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.createdAt)(Ord.desc))
      case _                                                       => quote(limited.sortBy(_.createdAt)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
  }

  override def addMessage(message: Message): RepositoryTask[Message] =
    exec(
      qc.ctx
        .run(qMessages.insertValue(lift(message)).returningGenerated(_.id))
        .map(id => message.copy(id = id)),
    )

}

// ─── Skill ────────────────────────────────────────────────────────────────────

private class QuillSkillRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOSkillRepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: SkillId): RepositoryTask[Option[SkillRecord]] =
    exec(qc.ctx.run(qSkills.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: SkillSearch): RepositoryTask[List[SkillRecord]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    val base = quote(qSkills.filter(r => lift(s.name).forall(n => r.name == n)))
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[SkillRecord]] = s.sorts match {
      case Some(Sort(SkillOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(SkillOrder.Name, OrderDirection.Asc))       => quote(limited.sortBy(_.name)(Ord.asc))
      case Some(Sort(SkillOrder.Name, OrderDirection.Desc))      => quote(limited.sortBy(_.name)(Ord.desc))
      case Some(Sort(SkillOrder.Tier, OrderDirection.Asc))       => quote(limited.sortBy(_.tier)(Ord.asc))
      case Some(Sort(SkillOrder.Tier, OrderDirection.Desc))      => quote(limited.sortBy(_.tier)(Ord.desc))
      case Some(Sort(SkillOrder.CreatedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(SkillOrder.CreatedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.createdAt)(Ord.desc))
      case _                                                     => quote(limited.sortBy(_.id)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
  }

  override def upsert(skill: SkillRecord): RepositoryTask[SkillRecord] =
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
    val base = quote(
      qSkillVersions
        .filter(v => lift(s.skillId).forall(sid => v.skillId == sid))
        .filter(v => lift(s.status).forall(st => v.status == st)),
    )
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[SkillVersion]] = s.sorts match {
      case Some(Sort(SkillVersionOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(SkillVersionOrder.Version, OrderDirection.Asc))    => quote(limited.sortBy(_.version)(Ord.asc))
      case Some(Sort(SkillVersionOrder.Version, OrderDirection.Desc))   => quote(limited.sortBy(_.version)(Ord.desc))
      case Some(Sort(SkillVersionOrder.CreatedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(SkillVersionOrder.CreatedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.createdAt)(Ord.desc))
      case _                                                            => quote(limited.sortBy(_.version)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
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
              (
                t,
                e,
              ) => t.reviewNote -> e.reviewNote,
            )
            .returningGenerated(_.id),
        ).map(id => v.copy(id = id)),
    )

  override def upsertVersionStatus(
    id:         SkillVersionId,
    status:     SkillStatus,
    reviewNote: Option[String],
  ): RepositoryTask[Unit] =
    exec(
      qc.ctx
        .run(
          qSkillVersions
            .filter(_.id == lift(id))
            .update(_.status -> lift(status), _.reviewNote -> lift(reviewNote)),
        ).unit,
    )

  override def getVersionWithSkillName(id: SkillVersionId): RepositoryTask[Option[(SkillVersion, String)]] =
    exec(
      qc.ctx
        .run(
          qSkillVersions
            .filter(_.id == lift(id))
            .join(qSkills)
            .on(_.skillId == _.id)
            .map { case (v, s) => (v, s.name) },
        ).map(_.headOption),
    )

override def searchByTier(tiers: List[SkillTier]): RepositoryTask[List[SkillRecord]] =
  if (tiers.isEmpty) exec(qc.ctx.run(qSkills))
  else exec(qc.ctx.run(qSkills.filter(s => liftQuery(tiers).contains(s.tier))))

  override def getConnector(id: ConnectorInstanceId): RepositoryTask[Option[ConnectorInstance]] =
    exec(qc.ctx.run(qConnectorInstances.filter(_.id == lift(id))).map(_.headOption))

  override def searchConnectors(s: ConnectorSearch): RepositoryTask[List[ConnectorInstance]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    val base = quote(qConnectorInstances)
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[ConnectorInstance]] = s.sorts match {
      case Some(Sort(ConnectorOrder.Id, OrderDirection.Desc))           => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(ConnectorOrder.Name, OrderDirection.Asc))          => quote(limited.sortBy(_.name)(Ord.asc))
      case Some(Sort(ConnectorOrder.Name, OrderDirection.Desc))         => quote(limited.sortBy(_.name)(Ord.desc))
      case Some(Sort(ConnectorOrder.ConnectorType, OrderDirection.Asc)) =>
        quote(limited.sortBy(_.connectorType)(Ord.asc))
      case Some(Sort(ConnectorOrder.ConnectorType, OrderDirection.Desc)) =>
        quote(limited.sortBy(_.connectorType)(Ord.desc))
      case _ => quote(limited.sortBy(_.id)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
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

  override def listSkills(): RepositoryTask[List[SkillInfo]] =
    ZIO.fail(RepositoryError("listSkills not implemented in QuillSkillRepository"))
  override def enableSkill(name: String): RepositoryTask[Unit] =
    ZIO.fail(RepositoryError("enableSkill not implemented in QuillSkillRepository"))
  override def disableSkill(name: String): RepositoryTask[Unit] =
    ZIO.fail(RepositoryError("disableSkill not implemented in QuillSkillRepository"))
  override def invokeTool(
    toolName: String,
    argsJson: String,
  ): RepositoryTask[Option[String]] =
    ZIO.fail(RepositoryError("invokeTool not implemented in QuillSkillRepository"))
  override def getSkillConfig(name: String): RepositoryTask[Option[String]] =
    ZIO.fail(RepositoryError("getSkillConfig not implemented in QuillSkillRepository"))
  override def updateSkillConfig(
    name:       String,
    configJson: String,
  ): RepositoryTask[Boolean] =
    ZIO.fail(RepositoryError("updateSkillConfig not implemented in QuillSkillRepository"))

}

// ─── Memory ───────────────────────────────────────────────────────────────────

private class QuillMemoryRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOMemoryRepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: MemoryRecordId): RepositoryTask[Option[MemoryRecord]] =
    exec(qc.ctx.run(qMemoryRecords.filter(_.id == lift(id))).map(_.headOption))

  override def getByKey(
    key:     String,
    userId:  Option[UserId],
    agentId: Option[AgentId],
  ): RepositoryTask[Option[MemoryRecord]] =
    exec(
      qc.ctx
        .run(
          qMemoryRecords
            .filter(r => r.recordKey == lift(key))
            .filter(r => lift(userId).forall(uid => r.userId.contains(uid)))
            .filter(r => lift(agentId).forall(aid => r.agentId.contains(aid))),
        ).map(_.headOption),
    )

  override def search(s: MemorySearch): RepositoryTask[List[MemoryRecord]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    val base = quote(
      qMemoryRecords
        .filter(r => r.scope == lift(s.scope))
        .filter(r => lift(s.userId).forall(uid => r.userId.contains(uid)))
        .filter(r => lift(s.workspaceId).forall(wid => r.workspaceId.contains(wid)))
        .filter(r => lift(s.agentId).forall(aid => r.agentId.contains(aid)))
        .filter(r => lift(s.key).forall(k => r.recordKey == k))
        .filter(r => lift(s.minImportance).forall(mi => r.importance >= mi)),
    )
    // NOTE: textSearch is currently applied in-memory below; keep SQL paging to avoid unbounded fetches.
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[MemoryRecord]] = s.sorts match {
      case Some(Sort(MemoryOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(MemoryOrder.RecordKey, OrderDirection.Asc))  => quote(limited.sortBy(_.recordKey)(Ord.asc))
      case Some(Sort(MemoryOrder.RecordKey, OrderDirection.Desc)) => quote(limited.sortBy(_.recordKey)(Ord.desc))
      case Some(Sort(MemoryOrder.CreatedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(MemoryOrder.CreatedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.createdAt)(Ord.desc))
      case Some(Sort(MemoryOrder.UpdatedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.updatedAt)(Ord.asc))
      case Some(Sort(MemoryOrder.UpdatedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.updatedAt)(Ord.desc))
      case _                                                      => quote(limited.sortBy(_.importance)(Ord.desc))
    }
    exec(qc.ctx.run(sorted)).map { records =>
      s.textSearch.fold(records) { text =>
        val lower = text.toLowerCase
        records.filter(r => r.value.toString.toLowerCase.contains(lower) || r.recordKey.toLowerCase.contains(lower))
      }
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
              ) => t.scope -> e.scope,
              (
                t,
                e,
              ) => t.importance -> e.importance,
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

  override def updateScope(
    id:    MemoryRecordId,
    scope: MemoryScope,
  ): RepositoryTask[Long] =
    Clock.instant.flatMap { now =>
      exec(
        qc.ctx.run(
          qMemoryRecords
            .filter(_.id == lift(id))
            .update(
              _.scope     -> lift(scope),
              _.updatedAt -> lift(now),
            ),
        ),
      )
    }

  override def delete(id: MemoryRecordId): RepositoryTask[Long] =
    exec(qc.ctx.run(qMemoryRecords.filter(_.id == lift(id)).delete))

  override def purgeExpired: RepositoryTask[Long] =
    exec(qc.ctx.run(sql"DELETE FROM memoryRecord WHERE ttl IS NOT NULL AND ttl < NOW()".as[Action[Long]]))

}

// ─── EventLog ─────────────────────────────────────────────────────────────────

private class QuillEventLogRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOEventLogRepository {

  import JorlanSchema.*
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
    val base = quote(
      qEventLogs
        .filter(e => lift(filter.eventType).forall(t => e.eventType == t))
        .filter(e => lift(filter.agentId).forall(id => e.agentId.contains(id)))
        .filter(e => lift(filter.sessionId).forall(sid => e.sessionId.contains(sid)))
        .filter(e => lift(filter.from).forall(f => e.occurredAt >= f))
        .filter(e => lift(filter.to).forall(t => e.occurredAt <= t)),
    )
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[EventLogRow]] = filter.sorts match {
      case Some(Sort(EventLogOrder.OccurredAt, OrderDirection.Asc)) => quote(limited.sortBy(_.occurredAt)(Ord.asc))
      case Some(Sort(EventLogOrder.Id, OrderDirection.Asc))         => quote(limited.sortBy(_.id)(Ord.asc))
      case Some(Sort(EventLogOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
      case _                                                        => quote(limited.sortBy(_.occurredAt)(Ord.desc))
    }
    exec(qc.ctx.run(sorted).map(_.map(fromRow)))
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

private class QuillSchedulerRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOSchedulerRepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getJob(id: SchedulerJobId): RepositoryTask[Option[SchedulerJob]] =
    exec(qc.ctx.run(qSchedulerJobs.filter(_.id == lift(id))).map(_.headOption))

  override def listJobs(
    agentId: Option[AgentId],
    limit:   Int = 200,
  ): RepositoryTask[List[SchedulerJob]] = {
    agentId match {
      case Some(aid) =>
        exec(
          qc.ctx.run(qSchedulerJobs.filter(_.agentId == lift(aid)).sortBy(_.createdAt)(Ord.desc).take(lift(limit))),
        )
      case None =>
        exec(qc.ctx.run(qSchedulerJobs.sortBy(_.createdAt)(Ord.desc).take(lift(limit))))
    }
  }

  override def getPendingJobs: RepositoryTask[List[SchedulerJob]] =
    for {
      now    <- Clock.instant
      result <- exec(
        qc.ctx.run(
          qSchedulerJobs
            .filter(j => j.status == lift(JobStatus.Pending) && j.scheduledAt <= lift(now) && j.leasedAt.isEmpty)
            .sortBy(_.scheduledAt),
        ),
      )
    } yield result

  // Partial-update contract: UPDATE only touches runtime-state fields (status, timestamps, lease, result, retryCount,
  // scheduledAt). Configuration fields (name, inputJson, maxRetries, backoffSeconds, backoffPolicy, missedRunPolicy,
  // userId, agentId, skillId) are immutable after creation and are never overwritten by the UPDATE path.
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
                _.status      -> lift(job.status),
                _.startedAt   -> lift(job.startedAt),
                _.finishedAt  -> lift(job.finishedAt),
                _.resultJson  -> lift(job.resultJson),
                _.retryCount  -> lift(job.retryCount),
                _.scheduledAt -> lift(job.scheduledAt),
                _.leasedAt    -> lift(job.leasedAt),
                _.leasedBy    -> lift(job.leasedBy),
              ),
          ).as(job),
      )
    }

  override def updateJobConfig(
    id:              SchedulerJobId,
    name:            String,
    prompt:          String,
    maxRetries:      Int,
    backoffSeconds:  Int,
    backoffPolicy:   RetryBackoffPolicy,
    missedRunPolicy: MissedRunPolicy,
  ): RepositoryTask[Boolean] =
    exec(
      qc.ctx.run(
        qSchedulerJobs
          .filter(_.id == lift(id))
          .update(
            _.name            -> lift(name),
            _.prompt          -> lift(prompt),
            _.inputJson       -> lift(Option.empty[String]),
            _.maxRetries      -> lift(maxRetries),
            _.backoffSeconds  -> lift(backoffSeconds),
            _.backoffPolicy   -> lift(backoffPolicy),
            _.missedRunPolicy -> lift(missedRunPolicy),
          ),
      ),
    ).map(_ > 0L)

  override def deleteJob(id: SchedulerJobId): RepositoryTask[Boolean] =
    exec(qc.ctx.run(qSchedulerJobs.filter(_.id == lift(id)).delete)).map(_ > 0L)

  override def pauseJob(id: SchedulerJobId): RepositoryTask[Boolean] =
    exec(qc.ctx.run(qSchedulerJobs.filter(_.id == lift(id)).update(_.status -> lift(JobStatus.Paused)))).map(_ > 0L)

  override def resumeJob(id: SchedulerJobId): RepositoryTask[Boolean] =
    exec(qc.ctx.run(qSchedulerJobs.filter(_.id == lift(id)).update(_.status -> lift(JobStatus.Pending)))).map(_ > 0L)

  override def cancelJob(id: SchedulerJobId): RepositoryTask[Boolean] =
    exec(qc.ctx.run(qSchedulerJobs.filter(_.id == lift(id)).update(_.status -> lift(JobStatus.Cancelled)))).map(_ > 0L)

  override def triggerNow(id: SchedulerJobId): RepositoryTask[Boolean] =
    ZIO.clockWith(_.instant).flatMap { now =>
      exec(
        qc.ctx.run(
          qSchedulerJobs
            .filter(_.id == lift(id))
            .update(_.status -> lift(JobStatus.Pending), _.scheduledAt -> lift(now)),
        ),
      ).map(_ > 0L)
    }

  override def claimJob(
    id:              SchedulerJobId,
    workerId:        String,
    now:             Instant,
    leaseTtlSeconds: Int,
  ): RepositoryTask[Boolean] = {
    val staleBefore = now.minusSeconds(leaseTtlSeconds.toLong)
    exec(
      qc.ctx
        .run(
          infix"""UPDATE schedulerJob
               SET leasedAt = ${lift(now)}, leasedBy = ${lift(workerId)}, status = ${lift(JobStatus.Running.toString)}
               WHERE id = ${lift(id.value)}
                 AND status = 'Pending'
                 AND (leasedAt IS NULL OR leasedAt < ${lift(staleBefore)})"""
            .as[Action[Long]],
        ).map(_ > 0L),
    )
  }

  override def releaseJob(
    id:         SchedulerJobId,
    status:     JobStatus,
    resultJson: Option[String],
    finishedAt: Instant,
  ): RepositoryTask[Unit] =
    exec(
      qc.ctx
        .run(
          qSchedulerJobs
            .filter(_.id == lift(id))
            .update(
              _.status     -> lift(status),
              _.resultJson -> lift(resultJson),
              _.finishedAt -> lift(Some(finishedAt): Option[Instant]),
              _.leasedAt   -> lift(Option.empty[Instant]),
              _.leasedBy   -> lift(Option.empty[String]),
            ),
        ).unit,
    )

  override def expireLeases(olderThan: Instant): RepositoryTask[Long] =
    exec(
      qc.ctx.run(
        qSchedulerJobs
          .filter(j => j.leasedAt.exists(_ < lift(olderThan)) && j.status == lift(JobStatus.Running))
          .update(
            _.status   -> lift(JobStatus.Pending),
            _.leasedAt -> lift(Option.empty[Instant]),
            _.leasedBy -> lift(Option.empty[String]),
          ),
      ),
    )

  override def searchTriggers(s: TriggerSearch): RepositoryTask[List[SchedulerTrigger]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    val base = quote(qSchedulerTriggers.filter(_.jobId == lift(s.jobId)))
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[SchedulerTrigger]] = s.sorts match {
      case Some(Sort(TriggerOrder.Id, OrderDirection.Desc)) => quote(limited.sortBy(_.id)(Ord.desc))
      case _                                                => quote(limited.sortBy(_.id)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
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

private class QuillArtifactRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOArtifactRepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getById(id: ArtifactId): RepositoryTask[Option[Artifact]] =
    exec(qc.ctx.run(qArtifacts.filter(_.id == lift(id))).map(_.headOption))

  override def search(s: ArtifactSearch): RepositoryTask[List[Artifact]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    val base = quote(qArtifacts.filter(_.workspaceId.contains(lift(s.workspaceId))))
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[Artifact]] = s.sorts match {
      case Some(Sort(ArtifactOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(ArtifactOrder.Name, OrderDirection.Asc))       => quote(limited.sortBy(_.name)(Ord.asc))
      case Some(Sort(ArtifactOrder.Name, OrderDirection.Desc))      => quote(limited.sortBy(_.name)(Ord.desc))
      case Some(Sort(ArtifactOrder.CreatedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(ArtifactOrder.CreatedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.createdAt)(Ord.desc))
      case _                                                        => quote(limited.sortBy(_.id)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
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
    val base = quote(qWorkspaces.filter(_.ownerId == lift(s.ownerId)))
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[Workspace]] = s.sorts match {
      case Some(Sort(WorkspaceOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(WorkspaceOrder.Name, OrderDirection.Asc))       => quote(limited.sortBy(_.name)(Ord.asc))
      case Some(Sort(WorkspaceOrder.Name, OrderDirection.Desc))      => quote(limited.sortBy(_.name)(Ord.desc))
      case Some(Sort(WorkspaceOrder.CreatedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.createdAt)(Ord.asc))
      case Some(Sort(WorkspaceOrder.CreatedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.createdAt)(Ord.desc))
      case _                                                         => quote(limited.sortBy(_.id)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
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

private class QuillPermissionRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOPermissionRepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def getRole(id: RoleId): RepositoryTask[Option[Role]] =
    exec(qc.ctx.run(qRoles.filter(_.id == lift(id))).map(_.headOption))

  override def searchRoles(s: RoleSearch): RepositoryTask[List[Role]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    val base: Quoted[Query[Role]] = s.userId match {
      case Some(uid) =>
        quote(for {
          ur   <- qUserRoles.filter(_.userId == lift(uid))
          role <- qRoles.join(_.id == ur.roleId)
        } yield role)
      case None =>
        quote(qRoles)
    }
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[Role]] = s.sorts match {
      case Some(Sort(RoleOrder.Name, OrderDirection.Asc))  => quote(limited.sortBy(_.name)(Ord.asc))
      case Some(Sort(RoleOrder.Name, OrderDirection.Desc)) => quote(limited.sortBy(_.name)(Ord.desc))
      case Some(Sort(RoleOrder.Id, OrderDirection.Desc))   => quote(limited.sortBy(_.id)(Ord.desc))
      case _                                               => quote(limited.sortBy(_.id)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
  }

  override def upsertRole(role: Role): RepositoryTask[Role] =
    if (role.id.value == 0L) {
      exec(
        qc.ctx
          .run(qRoles.insertValue(lift(role)).returningGenerated(_.id))
          .map(id => role.copy(id = id)),
      )
    } else {
      exec(
        qc.ctx
          .run(
            qRoles
              .filter(_.id == lift(role.id))
              .update(
                _.name        -> lift(role.name),
                _.description -> lift(role.description),
              ),
          ).as(role),
      )
    }

  override def assignRole(
    userId: UserId,
    roleId: RoleId,
  ): RepositoryTask[Unit] =
    exec(
      qc.ctx
        .run(
          qUserRoles
            .insertValue(lift(UserRoleRow(userId, roleId)))
            .onConflictIgnore,
        ).unit,
    )

  override def removeRole(
    userId: UserId,
    roleId: RoleId,
  ): RepositoryTask[Unit] =
    exec(
      qc.ctx
        .run(
          qUserRoles
            .filter(r => r.userId == lift(userId) && r.roleId == lift(roleId))
            .delete,
        ).unit,
    )

  override def upsertPermission(permission: Permission): RepositoryTask[Permission] =
    if (permission.id.value == 0L) {
      exec(
        qc.ctx
          .run(qPermissions.insertValue(lift(permission)).returningGenerated(_.id))
          .map(id => permission.copy(id = id)),
      )
    } else {
      exec(
        qc.ctx
          .run(
            qPermissions
              .filter(_.id == lift(permission.id))
              .update(
                _.roleId   -> lift(permission.roleId),
                _.userId   -> lift(permission.userId),
                _.resource -> lift(permission.resource),
                _.action   -> lift(permission.action),
                _.scope    -> lift(permission.scope),
              ),
          ).as(permission),
      )
    }

  override def searchPermissions(s: PermissionSearch): RepositoryTask[List[Permission]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    val base = quote(
      qPermissions
        .filter(p => lift(s.roleId).forall(rid => p.roleId.contains(rid)))
        .filter(p => lift(s.userId).forall(uid => p.userId.contains(uid))),
    )
    val limited = quote(base.drop(lift(offset)).take(lift(ps)))
    val sorted: Quoted[Query[Permission]] = s.sorts match {
      case Some(Sort(PermissionOrder.Id, OrderDirection.Desc))       => quote(limited.sortBy(_.id)(Ord.desc))
      case Some(Sort(PermissionOrder.Resource, OrderDirection.Asc))  => quote(limited.sortBy(_.resource)(Ord.asc))
      case Some(Sort(PermissionOrder.Resource, OrderDirection.Desc)) => quote(limited.sortBy(_.resource)(Ord.desc))
      case Some(Sort(PermissionOrder.Action, OrderDirection.Asc))    => quote(limited.sortBy(_.action)(Ord.asc))
      case Some(Sort(PermissionOrder.Action, OrderDirection.Desc))   => quote(limited.sortBy(_.action)(Ord.desc))
      case _                                                         => quote(limited.sortBy(_.id)(Ord.asc))
    }
    exec(qc.ctx.run(sorted))
  }

  override def upsertCapabilityGrant(grant: CapabilityGrant): RepositoryTask[CapabilityGrant] =
    if (grant.id.value == 0L) {
      exec(
        qc.ctx
          .run(
            qCapabilityGrants
              .insertValue(lift(grant))
              .onConflictUpdate(
                (t, e) => t.approvalMode -> e.approvalMode,
                (t, e) => t.expiresAt -> e.expiresAt,
                (t, e) => t.resourceConstraints -> e.resourceConstraints,
                (t, e) => t.scopeJson -> e.scopeJson,
                (t, e) => t.grantorId -> e.grantorId,
              ),
          )
          .flatMap(_ =>
            qc.ctx.run(
              qCapabilityGrants
                .filter(g =>
                  g.capability == lift(grant.capability) &&
                    g.granteeId == lift(grant.granteeId) &&
                    g.granteeType == lift(grant.granteeType),
                )
                .take(1),
            ).map(_.headOption.getOrElse(grant)),
          ),
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

  override def getUserRoleIds(userId: UserId): RepositoryTask[List[RoleId]] =
    exec(qc.ctx.run(qUserRoles.filter(_.userId == lift(userId)).map(_.roleId)))

  override def searchGrants(s: GrantSearch): RepositoryTask[List[CapabilityGrant]] = {
    val offset = s.page * s.pageSize
    val ps = s.pageSize
    (s.userId, s.roleId) match {
      case (Some(uid), _) =>
        val uidVal = uid.value
        val base = quote(
          qCapabilityGrants.filter(g =>
            g.granteeId == lift(uidVal) && g.granteeType == lift(GranteeType.User: GranteeType),
          ),
        )
        val limited = quote(base.drop(lift(offset)).take(lift(ps)))
        val sorted: Quoted[Query[CapabilityGrant]] = s.sorts match {
          case Some(Sort(GrantOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
          case Some(Sort(GrantOrder.GrantedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.createdAt)(Ord.asc))
          case Some(Sort(GrantOrder.GrantedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.createdAt)(Ord.desc))
          case _                                                     => quote(limited.sortBy(_.id)(Ord.asc))
        }
        exec(qc.ctx.run(sorted))
      case (_, Some(rid)) =>
        val ridVal = rid.value
        val base = quote(
          qCapabilityGrants.filter(g =>
            g.granteeId == lift(ridVal) && g.granteeType == lift(GranteeType.Role: GranteeType),
          ),
        )
        val limited = quote(base.drop(lift(offset)).take(lift(ps)))
        val sorted: Quoted[Query[CapabilityGrant]] = s.sorts match {
          case Some(Sort(GrantOrder.Id, OrderDirection.Desc))        => quote(limited.sortBy(_.id)(Ord.desc))
          case Some(Sort(GrantOrder.GrantedAt, OrderDirection.Asc))  => quote(limited.sortBy(_.createdAt)(Ord.asc))
          case Some(Sort(GrantOrder.GrantedAt, OrderDirection.Desc)) => quote(limited.sortBy(_.createdAt)(Ord.desc))
          case _                                                     => quote(limited.sortBy(_.id)(Ord.asc))
        }
        exec(qc.ctx.run(sorted))
      case _ =>
        exec(qc.ctx.run(quote(qCapabilityGrants.drop(lift(offset)).take(lift(ps)))))
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

  override def expireApprovalRequest(id: ApprovalRequestId): RepositoryTask[Long] =
    exec(
      qc.ctx.run(
        qApprovalRequests
          .filter(_.id == lift(id))
          .update(_.status -> lift(ApprovalStatus.Expired)),
      ),
    )

  override def getGrantsForCapability(
    userId:     UserId,
    capability: CapabilityName,
  ): RepositoryTask[List[CapabilityGrant]] = {
    val uidVal = userId.value
    for {
      now        <- Clock.instant
      userGrants <- exec(
        qc.ctx.run(
          qCapabilityGrants.filter(g =>
            g.granteeId == lift(uidVal) &&
              g.granteeType == lift(GranteeType.User: GranteeType) &&
              g.capability == lift(capability) &&
              (g.approvalMode == lift(ApprovalMode.Denied) ||
                g.expiresAt.isEmpty ||
                g.expiresAt.exists(_ > lift(now))),
          ),
        ),
      )
      roleIds    <- exec(qc.ctx.run(qUserRoles.filter(_.userId == lift(userId)).map(_.roleId)))
      roleGrants <-
        if (roleIds.isEmpty) ZIO.succeed(List.empty[CapabilityGrant])
        else {
          val ridVals = roleIds.map(_.value)
          exec(
            qc.ctx.run(
              qCapabilityGrants.filter(g =>
                liftQuery(ridVals).contains(g.granteeId) &&
                  g.granteeType == lift(GranteeType.Role: GranteeType) &&
                  g.capability == lift(capability) &&
                  (g.approvalMode == lift(ApprovalMode.Denied) ||
                    g.expiresAt.isEmpty ||
                    g.expiresAt.exists(_ > lift(now))),
              ),
            ),
          )
        }
    } yield userGrants ++ roleGrants
  }

  override def hasDirectPermission(
    userId:   UserId,
    resource: String,
    action:   String,
  ): RepositoryTask[Boolean] =
    exec(
      qc.ctx.run(
        qPermissions
          .filter(p =>
            p.resource == lift(resource) &&
              p.action == lift(action) &&
              p.userId.contains(lift(userId)),
          )
          .nonEmpty,
      ),
    )

  override def hasRolePermission(
    userId:   UserId,
    resource: String,
    action:   String,
  ): RepositoryTask[Boolean] =
    exec(
      qc.ctx.run(
        (for {
          ur <- qUserRoles.filter(_.userId == lift(userId))
          p  <- qPermissions.join(p =>
            p.roleId.contains(ur.roleId) &&
              p.resource == lift(resource) &&
              p.action == lift(action),
          )
        } yield p).nonEmpty,
      ),
    )

  override def findApprovedRequest(
    capability: CapabilityName,
    userId:     UserId,
    sessionId:  Option[AgentSessionId],
  ): RepositoryTask[Option[ApprovalRequest]] =
    exec(
      qc.ctx
        .run(
          qApprovalRequests
            .filter(r =>
              r.capability == lift(capability) &&
                r.requestorUserId == lift(userId) &&
                r.status == lift(ApprovalStatus.Approved) &&
                lift(sessionId).forall(sid => r.sessionId.contains(sid)),
            ).take(1),
        ).map(_.headOption),
    )

  override def recordApprovalDecision(decision: ApprovalDecision): RepositoryTask[ApprovalDecision] =
    exec(
      qc.ctx
        .run(qApprovalDecisions.insertValue(lift(decision)).returningGenerated(_.id))
        .map(id => decision.copy(id = id)),
    )

  override def getApprovalRequest(id: ApprovalRequestId): RepositoryTask[Option[ApprovalRequest]] =
    exec(qc.ctx.run(qApprovalRequests.filter(_.id == lift(id))).map(_.headOption))

  override def listPendingApprovals(userId: UserId): RepositoryTask[List[ApprovalRequest]] =
    exec(
      qc.ctx.run(
        qApprovalRequests
          .filter(r => r.requestorUserId == lift(userId) && r.status == lift(ApprovalStatus.Pending))
          .sortBy(_.createdAt)(Ord.desc),
      ),
    )

  override def deleteRole(id: RoleId): RepositoryTask[Long] = exec(qc.ctx.run(qRoles.filter(_.id == lift(id)).delete))

  override def deletePermission(id: PermissionId): RepositoryTask[Long] =
    exec(qc.ctx.run(qPermissions.filter(_.id == lift(id)).delete))

  override def expireAllStaleApprovalRequests(): RepositoryTask[Long] =
    for {
      now   <- Clock.instant
      count <- exec(
        qc.ctx.run(
          qApprovalRequests
            .filter(r => r.status == lift(ApprovalStatus.Pending) && r.expiresAt.exists(_ <= lift(now)))
            .update(_.status -> lift(ApprovalStatus.Expired)),
        ),
      )
    } yield count

  override def getExpiredApprovalRequests: RepositoryTask[List[ApprovalRequest]] =
    for {
      now    <- Clock.instant
      result <- exec(
        qc.ctx.run(
          qApprovalRequests
            .filter(r => r.status == lift(ApprovalStatus.Pending))
            .filter(r => r.expiresAt.exists(_ <= lift(now))),
        ),
      )
    } yield result

  override def listCapabilities(): RepositoryTask[List[CapabilityGrant]] =
    ZIO.fail(RepositoryError("listCapabilities not implemented in QuillPermissionRepository"))
  override def listApprovals(): RepositoryTask[List[ApprovalRequest]] =
    ZIO.fail(RepositoryError("listApprovals not implemented in QuillPermissionRepository"))
  override def decideApproval(
    id:       ApprovalRequestId,
    approved: Boolean,
    note:     Option[String] = None,
  ): RepositoryTask[Boolean] =
    ZIO.fail(RepositoryError("decideApproval not implemented in QuillPermissionRepository"))

}

// ─── ServerSettings ───────────────────────────────────────────────────────────

/** Quill row type for the `server_settings` table.
  *
  * Named `setting_key` (not `key`) because `key` is a reserved word in MariaDB. `value` stores valid JSON as a raw
  * string; `QuillServerSettingsRepository` handles serialization.
  */
private[repository] case class ServerSettingRow(
  settingKey: String,
  value:      String,
)

private class QuillServerSettingsRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOServerSettingsRepository {

  import JorlanSchema.*
  import qc.ctx.{*, given}

  override def get(key: String): IO[RepositoryError, Option[Json]] =
    exec(
      qc.ctx
        .run(qServerSettings.filter(_.settingKey == lift(key)))
        .map(_.headOption.flatMap(row => row.value.fromJson[Json].toOption)),
    ).mapError(RepositoryError.apply)

  override def set(
    key:   String,
    value: Json,
  ): IO[RepositoryError, Unit] = {
    val jsonStr = value.toJson
    exec(
      qc.ctx.run(
        qServerSettings
          .insertValue(lift(ServerSettingRow(key, jsonStr)))
          .onConflictUpdate(
            (
              t,
              e,
            ) => t.value -> e.value,
          ),
      ),
    ).unit.mapError(RepositoryError.apply)
  }

  override def serverPersonality(): IO[RepositoryError, Option[Personality]] =
    get(ZIOServerSettingsRepository.PersonalityKey).map(_.flatMap(_.as[Personality].toOption))

  override def updatePersonality(
    name:      String,
    formality: Formality,
    languages: List[String],
    expertise: List[String],
    prompt:    String,
  ): IO[RepositoryError, Option[Personality]] = {
    val p = Personality(name, formality, languages, expertise, prompt)
    ZIO
      .fromEither(p.toJsonAST.left.map(RuntimeException(_)))
      .flatMap(json => set(ZIOServerSettingsRepository.PersonalityKey, json).as(Some(p)))
      .mapError(RepositoryError.apply)
  }

}

// ─── ExternalCredential ───────────────────────────────────────────────────────

private class QuillExternalCredentialRepository(qc: QuillCtx)
    extends QuillRepoBase(qc) with ZIOExternalCredentialRepository {

  import JorlanSchema.*
  import qc.ctx.{*, given}

  private def rowToCredential(row: ExternalCredentialRow): ExternalCredential =
    ExternalCredential(
      id = row.id,
      userId = row.userId,
      provider = row.provider,
      credentialData = row.credentialData,
      expiresAt = row.expiresAt,
      scopes = row.scopes,
      createdAt = row.createdAt,
      updatedAt = row.updatedAt,
    )

  override def upsert(
    userId:        UserId,
    provider:      String,
    encryptedData: Json,
    expiresAt:     Option[Instant],
    scopes:        Option[String],
  ): RepositoryTask[Unit] = {
    Clock.instant.flatMap { now =>
      val row = ExternalCredentialRow(
        id = ExternalCredentialId.empty,
        userId = userId,
        provider = provider,
        credentialData = encryptedData,
        expiresAt = expiresAt,
        scopes = scopes,
        createdAt = now,
        updatedAt = now,
      )
      exec(
        qc.ctx.run(
          qExternalCredentials
            .insertValue(lift(row))
            .onConflictUpdate(
              (
                t,
                e,
              ) => t.credentialData -> e.credentialData,
              (
                t,
                e,
              ) => t.expiresAt -> e.expiresAt,
              (
                t,
                e,
              ) => t.scopes -> e.scopes,
            ),
        ),
      ).unit
    }
  }

  override def find(
    userId:   UserId,
    provider: String,
  ): RepositoryTask[Option[ExternalCredential]] =
    exec(
      qc.ctx
        .run(
          qExternalCredentials
            .filter(r => r.userId == lift(userId) && r.provider == lift(provider)),
        )
        .map(_.headOption.map(rowToCredential)),
    )

  override def delete(
    userId:   UserId,
    provider: String,
  ): RepositoryTask[Unit] =
    exec(
      qc.ctx
        .run(
          qExternalCredentials
            .filter(r => r.userId == lift(userId) && r.provider == lift(provider))
            .delete,
        ),
    ).unit

  override def listByUser(userId: UserId): RepositoryTask[List[ExternalCredential]] =
    exec(
      qc.ctx
        .run(qExternalCredentials.filter(_.userId == lift(userId)))
        .map(_.map(rowToCredential)),
    )

  override def listOAuthProviders(): RepositoryTask[List[String]] =
    ZIO.fail(RepositoryError("listOAuthProviders not implemented in QuillExternalCredentialRepository"))
  override def startOAuth(provider: String): RepositoryTask[Option[String]] =
    ZIO.fail(RepositoryError("startOAuth not implemented in QuillExternalCredentialRepository"))
  override def revokeOAuth(provider: String): RepositoryTask[Unit] =
    ZIO.fail(RepositoryError("revokeOAuth not implemented in QuillExternalCredentialRepository"))
  override def oauthStatus(provider: String): RepositoryTask[Option[OAuthStatus]] =
    ZIO.fail(RepositoryError("oauthStatus not implemented in QuillExternalCredentialRepository"))

}

// ─── SkillIndex ────────────────────────────────────────────────────────────────

private class QuillSkillIndexRepository(qc: QuillCtx) extends QuillRepoBase(qc) with ZIOSkillIndexRepository {

  import JorlanSchema.*
  import qc.ctx.*

  override def upsert(
    skillId:    SkillId,
    keywords:   String,
    searchText: String,
  ): RepositoryTask[Unit] = {
    val id = skillId.value
    exec(
      qc.ctx
        .run(
          quote {
            infix"""INSERT INTO skillIndex (skillId, keywords, searchText)
                  VALUES (${lift(id)}, ${lift(keywords)}, ${lift(searchText)})
                  ON DUPLICATE KEY UPDATE
                    keywords   = VALUES(keywords),
                    searchText = VALUES(searchText)""".as[Action[Long]]
          },
        ).unit,
    )
  }

  override def search(
    query: String,
    limit: Int,
  ): RepositoryTask[List[(SkillId, String)]] = {
    val lim = limit
    // Convert to BOOLEAN MODE to avoid the NL-mode 50% threshold (words appearing in >50% of rows
    // are suppressed in NL mode, which silences common skill verbs like "fetch", "get", "send").
    // Each token gets a + prefix (required) and * suffix (prefix match); if the whole query yields
    // no hits we fall back to a plain OR search so partial queries still return results.
    val boolQuery = query
      .split("\\s+")
      .filter(_.length >= 3)
      .map(w => s"+${w.replaceAll("[+\\-><()~*\"@]", "")}*")
      .mkString(" ")
    val fallbackQuery = query
      .split("\\s+")
      .filter(_.length >= 3)
      .map(w => s"${w.replaceAll("[+\\-><()~*\"@]", "")}*")
      .mkString(" ")
    val bq = if (boolQuery.nonEmpty) boolQuery else fallbackQuery
    val fq = if (fallbackQuery.nonEmpty) fallbackQuery else query
    exec(
      qc.ctx
        .run(
          quote {
            infix"""SELECT s.id AS id, s.name AS name
                  FROM skillIndex si
                  JOIN skill s ON s.id = si.skillId
                  WHERE MATCH(si.keywords)   AGAINST(${lift(bq)} IN BOOLEAN MODE) > 0
                     OR MATCH(si.searchText) AGAINST(${lift(fq)} IN BOOLEAN MODE) > 0
                  ORDER BY
                    (MATCH(si.keywords)   AGAINST(${lift(bq)} IN BOOLEAN MODE) * 3.0
                    + MATCH(si.searchText) AGAINST(${lift(fq)} IN BOOLEAN MODE)) DESC
                  LIMIT ${lift(lim)}""".as[Query[SkillSearchResult]]
          },
        ).map(_.map { case SkillSearchResult(id, name) => (SkillId(id), name) }),
    )
  }

  override def removeBySkillId(skillId: SkillId): RepositoryTask[Unit] = {
    val id = skillId.value
    exec(
      qc.ctx
        .run(
          quote {
            infix"DELETE FROM skillIndex WHERE skillId = ${lift(id)}".as[Action[Long]]
          },
        ).unit,
    )
  }

  override def removeBySkillName(skillName: String): RepositoryTask[Unit] =
    exec(
      qc.ctx
        .run(
          quote {
            infix"""DELETE si FROM skillIndex si
                  JOIN skill s ON s.id = si.skillId
                  WHERE s.name = ${lift(skillName)}""".as[Action[Long]]
          },
        ).unit,
    )

  override def keepOnly(skillNames: Set[String]): RepositoryTask[Unit] =
    if (skillNames.isEmpty) ZIO.unit
    else {
      val placeholders = skillNames.toList.map(_ => "?").mkString(", ")
      val sql = s"DELETE si FROM skillIndex si JOIN skill s ON s.id = si.skillId WHERE s.name NOT IN ($placeholders)"
      exec(
        ZIO
          .service[DataSource].flatMap { ds =>
            ZIO.attempt {
              import scala.language.unsafeNulls
              val conn = ds.getConnection().nn
              try {
                val stmt = conn.prepareStatement(sql).nn
                try {
                  skillNames.toList.zipWithIndex.foreach { case (name, i) => stmt.setString(i + 1, name) }
                  stmt.executeUpdate()
                } finally stmt.close()
              } finally conn.close()
            }
          }.unit,
      )
    }

}
