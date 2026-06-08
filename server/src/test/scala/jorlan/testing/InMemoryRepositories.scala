/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.testing

import jorlan.*
import jorlan.db.repository.*
import jorlan.domain.*
import jorlan.service.EventLogFilter
import zio.*
import zio.json.ast.Json

import java.time.Instant

/** Shared in-memory repository implementations for unit tests.
  *
  * All implementations are purely functional, using `Ref` for state. They support the full interface so tests can mix
  * service layers without needing a real database.
  */
object InMemoryRepositories {

  // ─── User ─────────────────────────────────────────────────────────────────────

  class InMemoryUserRepo(
    idGen: Ref[Long],
    store: Ref[Map[Long, User]],
  ) extends UserZIORepository {

    override def getById(id: UserId): RepositoryTask[Option[User]] =
      store.get.map(_.get(id.value))

    override def search(s: UserSearch): RepositoryTask[List[User]] =
      store.get.map(_.values.toList.filter(u => s.active.forall(_ == u.active)))

    override def upsert(user: User): RepositoryTask[User] =
      for {
        id <- if (user.id == UserId.empty) idGen.updateAndGet(_ + 1) else ZIO.succeed(user.id.value)
        saved = user.copy(id = UserId(id))
        _ <- store.update(_.updated(id, saved))
      } yield saved

    override def deactivate(id: UserId): RepositoryTask[Long] =
      store.modify { m =>
        m.get(id.value).fold((0L, m))(u => (1L, m.updated(id.value, u.copy(active = false))))
      }

    override def getChannelIdentities(userId: UserId):       RepositoryTask[List[ChannelIdentity]] = ZIO.succeed(Nil)
    override def upsertChannelIdentity(ci: ChannelIdentity): RepositoryTask[ChannelIdentity] =
      ZIO.die(RuntimeException("not implemented"))
    override def deleteChannelIdentity(id: ChannelIdentityId): RepositoryTask[Long] =
      ZIO.die(RuntimeException("not implemented"))
    override def login(
      email:    String,
      password: String,
    ):                                       RepositoryTask[Option[User]] = ZIO.none
    override def userByEmail(email: String): RepositoryTask[Option[User]] =
      store.get.map(_.values.find(_.email.contains(email)))
    override def changePassword(
      id:          UserId,
      newPassword: String,
    ): RepositoryTask[Unit] = ZIO.unit
    override def userByChannelIdentity(
      channelType:   ChannelType,
      channelUserId: String,
    ): RepositoryTask[Option[User]] = ZIO.none

  }

  object InMemoryUserRepo {

    def make: UIO[InMemoryUserRepo] =
      (Ref.make(0L) <*> Ref.make(Map.empty[Long, User])).map { case (idGen, store) =>
        InMemoryUserRepo(idGen, store)
      }

    val layer: ULayer[UserZIORepository] = ZLayer(make.map(r => r: UserZIORepository))

  }

  // ─── Permission ───────────────────────────────────────────────────────────────

  class InMemoryPermissionRepo(
    roleIdGen:     Ref[Long],
    roles:         Ref[Map[Long, Role]],
    userRoles:     Ref[Map[(Long, Long), Unit]],
    permIdGen:     Ref[Long],
    permissions:   Ref[Map[Long, Permission]],
    grantIdGen:    Ref[Long],
    grants:        Ref[Map[Long, CapabilityGrant]],
    approvalIdGen: Ref[Long],
    approvals:     Ref[Map[Long, ApprovalRequest]],
  ) extends PermissionZIORepository {

    override def getRole(id: RoleId): RepositoryTask[Option[Role]] = roles.get.map(_.get(id.value))

    override def searchRoles(s: RoleSearch): RepositoryTask[List[Role]] =
      for {
        rm  <- userRoles.get
        rm2 <- roles.get
        myIds = rm.keys.filter(_._1 == s.userId.value).map(_._2).toSet
      } yield rm2.values.toList.filter(r => myIds.contains(r.id.value))

    override def upsertRole(role: Role): RepositoryTask[Role] =
      for {
        id <- if (role.id == RoleId.empty) roleIdGen.updateAndGet(_ + 1) else ZIO.succeed(role.id.value)
        saved = role.copy(id = RoleId(id))
        _ <- roles.update(_.updated(id, saved))
      } yield saved

    override def deleteRole(id: RoleId): RepositoryTask[Long] =
      roles.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value)) else (0L, m)
      }

    override def assignRole(
      userId: UserId,
      roleId: RoleId,
    ): RepositoryTask[Unit] = userRoles.update(_.updated((userId.value, roleId.value), ()))

    override def removeRole(
      userId: UserId,
      roleId: RoleId,
    ): RepositoryTask[Unit] = userRoles.update(_.removed((userId.value, roleId.value)))

    override def searchPermissions(s: PermissionSearch): RepositoryTask[List[Permission]] =
      permissions.get.map { ps =>
        ps.values.toList
          .filter(p => s.roleId.forall(id => p.roleId.contains(id)))
          .filter(p => s.userId.forall(id => p.userId.contains(id)))
      }

    override def upsertPermission(permission: Permission): RepositoryTask[Permission] =
      for {
        id <-
          if (permission.id == PermissionId.empty) permIdGen.updateAndGet(_ + 1)
          else ZIO.succeed(permission.id.value)
        saved = permission.copy(id = PermissionId(id))
        _ <- permissions.update(_.updated(id, saved))
      } yield saved

    override def deletePermission(id: PermissionId): RepositoryTask[Long] =
      permissions.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value)) else (0L, m)
      }

    override def upsertCapabilityGrant(grant: CapabilityGrant): RepositoryTask[CapabilityGrant] =
      for {
        id <-
          if (grant.id == CapabilityGrantId.empty) grantIdGen.updateAndGet(_ + 1)
          else ZIO.succeed(grant.id.value)
        saved = grant.copy(id = CapabilityGrantId(id))
        _ <- grants.update(_.updated(id, saved))
      } yield saved

    override def revokeGrant(id: CapabilityGrantId): RepositoryTask[Long] =
      grants.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value)) else (0L, m)
      }

    override def searchGrants(s: GrantSearch): RepositoryTask[List[CapabilityGrant]] =
      grants.get.map(_.values.toList.filter(_.granteeId == s.userId))

    override def createApprovalRequest(req: ApprovalRequest): RepositoryTask[ApprovalRequest] =
      for {
        id <- approvalIdGen.updateAndGet(_ + 1)
        saved = req.copy(id = ApprovalRequestId(id))
        _ <- approvals.update(_.updated(id, saved))
      } yield saved

    override def cancelApprovalRequest(id: ApprovalRequestId): RepositoryTask[Long] =
      approvals.modify { m =>
        m.get(id.value).fold((0L, m))(r => (1L, m.updated(id.value, r.copy(status = ApprovalStatus.Cancelled))))
      }

    override def expireApprovalRequest(id: ApprovalRequestId): RepositoryTask[Long] =
      approvals.modify { m =>
        m.get(id.value).fold((0L, m))(r => (1L, m.updated(id.value, r.copy(status = ApprovalStatus.Expired))))
      }

    override def expireAllStaleApprovalRequests(): RepositoryTask[Long] =
      approvals.modify { m =>
        val now = Instant.now()
        val expired = m.filter { case (_, r) =>
          r.status == ApprovalStatus.Pending && r.expiresAt.exists(_.isBefore(now))
        }
        (
          expired.size.toLong,
          expired.foldLeft(m) { case (acc, (k, r)) =>
            acc.updated(k, r.copy(status = ApprovalStatus.Expired))
          },
        )
      }

    override def recordApprovalDecision(decision: ApprovalDecision): RepositoryTask[ApprovalDecision] =
      for {
        id <- approvalIdGen.updateAndGet(_ + 1)
        saved = decision.copy(id = ApprovalDecisionId(id))
        _ <- approvals.update { m =>
          m.get(decision.approvalRequestId.value).fold(m)(r =>
              m.updated(decision.approvalRequestId.value, r.copy(status = decision.decision)),
            )
        }
      } yield saved

    override def getApprovalRequest(id: ApprovalRequestId): RepositoryTask[Option[ApprovalRequest]] =
      approvals.get.map(_.get(id.value))

    override def getExpiredApprovalRequests: RepositoryTask[List[ApprovalRequest]] =
      approvals.get.map(_.values.toList.filter(_.status == ApprovalStatus.Expired))

    override def listPendingApprovals(userId: UserId): RepositoryTask[List[ApprovalRequest]] =
      approvals.get.map(_.values.toList.filter(r => r.requestorUserId == userId && r.status == ApprovalStatus.Pending))

    override def findApprovedRequest(
      capability: CapabilityName,
      userId:     UserId,
      sessionId:  Option[AgentSessionId],
    ): RepositoryTask[Option[ApprovalRequest]] =
      approvals.get.map {
        _.values.find { r =>
          r.capability == capability && r.requestorUserId == userId &&
          r.status == ApprovalStatus.Approved &&
          sessionId.forall(sid => r.sessionId.contains(sid))
        }
      }

    override def getGrantsForCapability(
      userId:     UserId,
      capability: CapabilityName,
    ): RepositoryTask[List[CapabilityGrant]] =
      grants.get.map(_.values.toList.filter(g => g.granteeId == userId && g.capability == capability))

    override def hasDirectPermission(
      userId:   UserId,
      resource: String,
      action:   String,
    ): RepositoryTask[Boolean] =
      permissions.get.map(
        _.values.exists(p => p.userId.contains(userId) && p.resource == resource && p.action == action),
      )

    override def hasRolePermission(
      userId:   UserId,
      resource: String,
      action:   String,
    ): RepositoryTask[Boolean] =
      for {
        rm <- userRoles.get
        pm <- permissions.get
        rids = rm.keys.filter(_._1 == userId.value).map(_._2).toSet
      } yield pm.values.exists { p =>
        p.roleId.exists(rid => rids.contains(rid.value)) && p.resource == resource && p.action == action
      }

  }

  object InMemoryPermissionRepo {

    def make: UIO[InMemoryPermissionRepo] =
      for {
        roleIdGen     <- Ref.make(0L)
        roles         <- Ref.make(Map.empty[Long, Role])
        userRoles     <- Ref.make(Map.empty[(Long, Long), Unit])
        permIdGen     <- Ref.make(0L)
        permissions   <- Ref.make(Map.empty[Long, Permission])
        grantIdGen    <- Ref.make(0L)
        grants        <- Ref.make(Map.empty[Long, CapabilityGrant])
        approvalIdGen <- Ref.make(0L)
        approvals     <- Ref.make(Map.empty[Long, ApprovalRequest])
      } yield InMemoryPermissionRepo(
        roleIdGen,
        roles,
        userRoles,
        permIdGen,
        permissions,
        grantIdGen,
        grants,
        approvalIdGen,
        approvals,
      )

    val layer: ULayer[PermissionZIORepository] = ZLayer(make.map(r => r: PermissionZIORepository))

  }

  // ─── Event log ────────────────────────────────────────────────────────────────

  class InMemoryEventLogRepo(
    idGen: Ref[Long],
    store: Ref[List[EventLog[Json]]],
  ) extends EventLogZIORepository {

    override def append[R: zio.json.JsonEncoder](event: EventLog[R]): RepositoryTask[EventLog[R]] =
      for {
        nextId <- idGen.updateAndGet(_ + 1)
        saved = event.copy(id = EventLogId(nextId))
        generic = EventLog[Json](
          id = saved.id,
          eventType = saved.eventType,
          actorId = saved.actorId,
          agentId = saved.agentId,
          sessionId = saved.sessionId,
          resource = None,
          payloadJson = saved.payloadJson,
          occurredAt = saved.occurredAt,
        )
        _ <- store.update(generic :: _)
      } yield saved

    override def search(filter: EventLogFilter): RepositoryTask[List[EventLog[Json]]] =
      store.get.map(_.filter(e => filter.eventType.forall(_ == e.eventType)))

    override def replaySession(
      sessionId: AgentSessionId,
      limit:     Int,
    ): RepositoryTask[List[EventLog[Json]]] =
      store.get.map(_.filter(_.sessionId.contains(sessionId)).reverse.take(limit))

  }

  object InMemoryEventLogRepo {

    def make: UIO[InMemoryEventLogRepo] =
      (Ref.make(0L) <*> Ref.make(List.empty[EventLog[Json]])).map { case (idGen, store) =>
        InMemoryEventLogRepo(idGen, store)
      }

    val layer: ULayer[EventLogZIORepository] = ZLayer(make.map(r => r: EventLogZIORepository))

  }

  // ─── Agent ────────────────────────────────────────────────────────────────────

  class InMemoryAgentRepo(
    agentIdGen:   Ref[Long],
    agentStore:   Ref[Map[Long, Agent]],
    sessionIdGen: Ref[Long],
    sessionStore: Ref[Map[Long, AgentSession]],
  ) extends AgentZIORepository {

    override def getById(id: AgentId): RepositoryTask[Option[Agent]] =
      agentStore.get.map(_.get(id.value))

    override def search(s: AgentSearch): RepositoryTask[List[Agent]] =
      agentStore.get.map(_.values.toList)

    override def upsert(agent: Agent): RepositoryTask[Agent] =
      for {
        id <- if (agent.id == AgentId.empty) agentIdGen.updateAndGet(_ + 1) else ZIO.succeed(agent.id.value)
        saved = agent.copy(id = AgentId(id))
        _ <- agentStore.update(_.updated(id, saved))
      } yield saved

    override def delete(id: AgentId): RepositoryTask[Long] =
      agentStore.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value)) else (0L, m)
      }

    override def getSession(id: AgentSessionId): RepositoryTask[Option[AgentSession]] =
      sessionStore.get.map(_.get(id.value))

    override def searchSessions(s: AgentSessionSearch): RepositoryTask[List[AgentSession]] =
      sessionStore.get.map {
        _.values.toList
          .filter(sess => s.agentId.forall(_ == sess.agentId))
          .filter(sess => s.userId.forall(_ == sess.userId))
          .filter(sess => s.chatRef.forall(cr => sess.chatRef.contains(cr)))
      }

    override def upsertSession(session: AgentSession): RepositoryTask[AgentSession] =
      for {
        id <-
          if (session.id == AgentSessionId.empty) sessionIdGen.updateAndGet(_ + 1)
          else ZIO.succeed(session.id.value)
        saved = session.copy(id = AgentSessionId(id))
        _ <- sessionStore.update(_.updated(id, saved))
      } yield saved

  }

  object InMemoryAgentRepo {

    def make: UIO[InMemoryAgentRepo] =
      for {
        agentIdGen   <- Ref.make(0L)
        agentStore   <- Ref.make(Map.empty[Long, Agent])
        sessionIdGen <- Ref.make(0L)
        sessionStore <- Ref.make(Map.empty[Long, AgentSession])
      } yield InMemoryAgentRepo(agentIdGen, agentStore, sessionIdGen, sessionStore)

    val layer: ULayer[AgentZIORepository] = ZLayer(make.map(r => r: AgentZIORepository))

  }

  // ─── Conversation ─────────────────────────────────────────────────────────────

  class InMemoryConversationRepo(
    convIdGen: Ref[Long],
    convStore: Ref[Map[Long, Conversation]],
    msgIdGen:  Ref[Long],
    msgStore:  Ref[Map[Long, Message]],
  ) extends ConversationZIORepository {

    override def getById(id: ConversationId): RepositoryTask[Option[Conversation]] =
      convStore.get.map(_.get(id.value))

    override def search(s: ConversationSearch): RepositoryTask[List[Conversation]] =
      convStore.get.map(_.values.toList.filter(_.sessionId == s.sessionId))

    override def create(conversation: Conversation): RepositoryTask[Conversation] =
      for {
        id <- convIdGen.updateAndGet(_ + 1)
        saved = conversation.copy(id = ConversationId(id))
        _ <- convStore.update(_.updated(id, saved))
      } yield saved

    override def searchMessages(s: MessageSearch): RepositoryTask[List[Message]] =
      msgStore.get.map(
        _.values.toList
          .filter(_.conversationId == s.conversationId)
          .sortBy(_.createdAt),
      )

    override def addMessage(message: Message): RepositoryTask[Message] =
      for {
        id <- msgIdGen.updateAndGet(_ + 1)
        saved = message.copy(id = MessageId(id))
        _ <- msgStore.update(_.updated(id, saved))
      } yield saved

  }

  object InMemoryConversationRepo {

    def make: UIO[InMemoryConversationRepo] =
      for {
        convIdGen <- Ref.make(0L)
        convStore <- Ref.make(Map.empty[Long, Conversation])
        msgIdGen  <- Ref.make(0L)
        msgStore  <- Ref.make(Map.empty[Long, Message])
      } yield InMemoryConversationRepo(convIdGen, convStore, msgIdGen, msgStore)

    val layer: ULayer[ConversationZIORepository] = ZLayer(make.map(r => r: ConversationZIORepository))

  }

  // ─── Memory ───────────────────────────────────────────────────────────────────

  class InMemoryMemoryRepo(
    idGen: Ref[Long],
    store: Ref[Map[Long, MemoryRecord]],
  ) extends MemoryZIORepository {

    override def getById(id: MemoryRecordId): RepositoryTask[Option[MemoryRecord]] =
      store.get.map(_.get(id.value))

    override def search(s: MemorySearch): RepositoryTask[List[MemoryRecord]] =
      store.get.map { m =>
        val filtered = m.values.toList
          .filter(_.scope == s.scope)
          .filter(r => s.userId.forall(uid => r.userId.contains(uid)))
          .filter(r => s.workspaceId.forall(wid => r.workspaceId.contains(wid)))
          .filter(r => s.agentId.forall(aid => r.agentId.contains(aid)))
          .filter(r => s.key.forall(_ == r.recordKey))
        s.textSearch.fold(filtered) { text =>
          val lower = text.toLowerCase
          filtered.filter(r => r.value.toString.toLowerCase.contains(lower) || r.recordKey.toLowerCase.contains(lower))
        }
      }

    override def upsert(record: MemoryRecord): RepositoryTask[MemoryRecord] =
      for {
        id <- if (record.id == MemoryRecordId.empty) idGen.updateAndGet(_ + 1) else ZIO.succeed(record.id.value)
        saved = record.copy(id = MemoryRecordId(id))
        _ <- store.update(_.updated(id, saved))
      } yield saved

    override def updateScope(
      id:    MemoryRecordId,
      scope: MemoryScope,
    ): RepositoryTask[Long] =
      store.modify { m =>
        m.get(id.value) match {
          case None    => (0L, m)
          case Some(r) => (1L, m.updated(id.value, r.copy(scope = scope)))
        }
      }

    override def delete(id: MemoryRecordId): RepositoryTask[Long] =
      store.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value)) else (0L, m)
      }

    override def purgeExpired: RepositoryTask[Long] =
      Clock.instant.flatMap { now =>
        store.modify { m =>
          val expired = m.filter { case (_, r) => r.ttl.exists(_.isBefore(now)) }
          (expired.size.toLong, m -- expired.keys)
        }
      }

  }

  object InMemoryMemoryRepo {

    def make: UIO[InMemoryMemoryRepo] =
      (Ref.make(0L) <*> Ref.make(Map.empty[Long, MemoryRecord])).map { case (idGen, store) =>
        InMemoryMemoryRepo(idGen, store)
      }

    val layer: ULayer[MemoryZIORepository] = ZLayer(make.map(r => r: MemoryZIORepository))

  }

  // ─── ServerSettings ────────────────────────────────────────────────────────────

  class InMemoryServerSettingsRepo(store: Ref[Map[String, Json]]) extends ServerSettingsRepository {

    override def get(key: String): UIO[Option[Json]] = store.get.map(_.get(key))

    override def set(
      key:   String,
      value: Json,
    ): UIO[Unit] = store.update(_.updated(key, value))

  }

  object InMemoryServerSettingsRepo {

    def make: UIO[InMemoryServerSettingsRepo] =
      Ref.make(Map.empty[String, Json]).map(InMemoryServerSettingsRepo(_))

    val layer: ULayer[ServerSettingsRepository] =
      ZLayer(make.map(r => r: ServerSettingsRepository))

  }

  // ─── Scheduler (no-op stub for unit tests) ────────────────────────────────────

  class NoOpSchedulerRepo extends SchedulerZIORepository {

    override def getJob(id:             SchedulerJobId):     RepositoryTask[Option[SchedulerJob]] = ZIO.succeed(None)
    override def listJobs(agentId:      Option[AgentId]):    RepositoryTask[List[SchedulerJob]] = ZIO.succeed(Nil)
    override def getPendingJobs:                             RepositoryTask[List[SchedulerJob]] = ZIO.succeed(Nil)
    override def upsertJob(job:         SchedulerJob):       RepositoryTask[SchedulerJob] = ZIO.succeed(job)
    override def deleteJob(id:          SchedulerJobId):     RepositoryTask[Long] = ZIO.succeed(0L)
    override def searchTriggers(s:      TriggerSearch):      RepositoryTask[List[SchedulerTrigger]] = ZIO.succeed(Nil)
    override def upsertTrigger(trigger: SchedulerTrigger):   RepositoryTask[SchedulerTrigger] = ZIO.succeed(trigger)
    override def deleteTrigger(id:      SchedulerTriggerId): RepositoryTask[Long] = ZIO.succeed(0L)
    override def claimJob(
      id:              SchedulerJobId,
      workerId:        String,
      now:             Instant,
      leaseTtlSeconds: Int,
    ): RepositoryTask[Boolean] =
      ZIO.succeed(false)
    override def releaseJob(
      id:         SchedulerJobId,
      status:     JobStatus,
      resultJson: Option[String],
      finishedAt: Instant,
    ): RepositoryTask[Unit] =
      ZIO.unit
    override def expireLeases(olderThan: Instant): RepositoryTask[Long] = ZIO.succeed(0L)

  }

  object NoOpSchedulerRepo {

    val layer: ULayer[SchedulerZIORepository] = ZLayer.succeed(NoOpSchedulerRepo(): SchedulerZIORepository)

  }

  // ─── Scheduler (stateful in-memory, for JobManager and TriggerEngine tests) ──

  class InMemorySchedulerRepo(
    jobIdGen:  Ref[Long],
    jobs:      Ref[Map[SchedulerJobId, SchedulerJob]],
    trigIdGen: Ref[Long],
    triggers:  Ref[Map[SchedulerTriggerId, SchedulerTrigger]],
  ) extends SchedulerZIORepository {

    override def getJob(id: SchedulerJobId): RepositoryTask[Option[SchedulerJob]] =
      jobs.get.map(_.get(id))

    override def listJobs(agentId: Option[AgentId]): RepositoryTask[List[SchedulerJob]] =
      jobs.get.map { m =>
        val all = m.values.toList
        agentId.fold(all)(aid => all.filter(_.agentId == aid))
      }

    override def getPendingJobs: RepositoryTask[List[SchedulerJob]] =
      for {
        now <- Clock.instant
        m   <- jobs.get
      } yield m.values.toList.filter(j =>
        j.status == JobStatus.Pending && !j.scheduledAt.isAfter(now) && j.leasedAt.isEmpty,
      )

    override def upsertJob(job: SchedulerJob): RepositoryTask[SchedulerJob] =
      if (job.id.value == 0L) {
        jobIdGen.updateAndGet(_ + 1).flatMap { id =>
          val saved = job.copy(id = SchedulerJobId(id))
          jobs.update(_.updated(saved.id, saved)).as(saved)
        }
      } else {
        jobs.update(_.updated(job.id, job)).as(job)
      }

    override def deleteJob(id: SchedulerJobId): RepositoryTask[Long] =
      jobs.modify(m => if (m.contains(id)) (1L, m - id) else (0L, m))

    override def searchTriggers(s: TriggerSearch): RepositoryTask[List[SchedulerTrigger]] =
      triggers.get.map(_.values.toList.filter(_.jobId == s.jobId))

    override def upsertTrigger(trigger: SchedulerTrigger): RepositoryTask[SchedulerTrigger] =
      if (trigger.id.value == 0L) {
        trigIdGen.updateAndGet(_ + 1).flatMap { id =>
          val saved = trigger.copy(id = SchedulerTriggerId(id))
          triggers.update(_.updated(saved.id, saved)).as(saved)
        }
      } else {
        triggers.update(_.updated(trigger.id, trigger)).as(trigger)
      }

    override def deleteTrigger(id: SchedulerTriggerId): RepositoryTask[Long] =
      triggers.modify(m => if (m.contains(id)) (1L, m - id) else (0L, m))

    override def claimJob(
      id:              SchedulerJobId,
      workerId:        String,
      now:             Instant,
      leaseTtlSeconds: Int,
    ): RepositoryTask[Boolean] =
      jobs.modify { m =>
        m.get(id) match {
          case Some(j)
              if j.status == JobStatus.Pending &&
                j.leasedAt.forall(la => la.isBefore(now.minusSeconds(leaseTtlSeconds.toLong))) =>
            val claimed = j.copy(status = JobStatus.Running, leasedAt = Some(now), leasedBy = Some(workerId))
            (true, m.updated(id, claimed))
          case _ => (false, m)
        }
      }

    override def releaseJob(
      id:         SchedulerJobId,
      status:     JobStatus,
      resultJson: Option[String],
      finishedAt: Instant,
    ): RepositoryTask[Unit] =
      jobs.update(m =>
        m.get(id).fold(m) { j =>
          m.updated(
            id,
            j.copy(
              status = status,
              resultJson = resultJson,
              finishedAt = Some(finishedAt),
              leasedAt = None,
              leasedBy = None,
            ),
          )
        },
      )

    override def expireLeases(olderThan: Instant): RepositoryTask[Long] =
      jobs.modify { m =>
        val stale = m.values.filter(j => j.status == JobStatus.Running && j.leasedAt.exists(_.isBefore(olderThan)))
        val reset = stale.foldLeft(m) {
          (
            acc,
            j,
          ) =>
            acc.updated(j.id, j.copy(status = JobStatus.Pending, leasedAt = None, leasedBy = None))
        }
        (stale.size.toLong, reset)
      }

  }

  object InMemorySchedulerRepo {

    def make: UIO[InMemorySchedulerRepo] =
      for {
        jobIdGen  <- Ref.make(0L)
        jobs      <- Ref.make(Map.empty[SchedulerJobId, SchedulerJob])
        trigIdGen <- Ref.make(0L)
        triggers  <- Ref.make(Map.empty[SchedulerTriggerId, SchedulerTrigger])
      } yield InMemorySchedulerRepo(jobIdGen, jobs, trigIdGen, triggers)

    val layer: ULayer[SchedulerZIORepository] = ZLayer(make.map(r => r: SchedulerZIORepository))

  }

}
