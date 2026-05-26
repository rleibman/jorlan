/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import jorlan.domain.*

import java.time.Instant

/** Abstract repository interfaces parameterised by effect type `F[_]`.
  *
  * Keeping these traits in `model` rather than `db` means any module — including a GraphQL client or an in-memory test
  * double — can implement the same interface without depending on Quill or ZIO. The concrete ZIO implementations in
  * `db` fix `F = IO[RepositoryError, *]`.
  */

/** Repository for [[jorlan.domain.User]] and [[jorlan.domain.ChannelIdentity]] records. */
trait UserRepository[F[_]] {

  def getById(id:                  UserId):            F[Option[User]]
  def getAll:                                          F[List[User]]
  def upsert(user:                 User):              F[User]
  def deactivate(id:               UserId):            F[Long]
  def getChannelIdentities(userId: UserId):            F[List[ChannelIdentity]]
  def upsertChannelIdentity(ci:    ChannelIdentity):   F[ChannelIdentity]
  def deleteChannelIdentity(id:    ChannelIdentityId): F[Long]

}

/** Repository for [[jorlan.domain.Agent]] definitions and [[jorlan.domain.AgentSession]] runtime records.
  */
trait AgentRepository[F[_]] {

  def getById(id:                  AgentId):        F[Option[Agent]]
  def getAll:                                       F[List[Agent]]
  def upsert(agent:                Agent):          F[Agent]
  def delete(id:                   AgentId):        F[Long]
  def getSession(id:               AgentSessionId): F[Option[AgentSession]]
  def getSessionsForAgent(agentId: AgentId):        F[List[AgentSession]]
  def upsertSession(session:       AgentSession):   F[AgentSession]

}

/** Repository for [[jorlan.domain.Conversation]] threads and [[jorlan.domain.Message]] records. */
trait ConversationRepository[F[_]] {

  def getById(id:                 ConversationId): F[Option[Conversation]]
  def getBySession(sessionId:     AgentSessionId): F[List[Conversation]]
  def create(conversation:        Conversation):   F[Conversation]
  def getMessages(conversationId: ConversationId): F[List[Message]]
  def addMessage(message:         Message):        F[Message]

}

/** Repository for [[jorlan.domain.Skill]] entries, [[jorlan.domain.SkillVersion]] snapshots, and
  * [[jorlan.domain.ConnectorInstance]] configurations.
  */
trait SkillRepository[F[_]] {

  def getById(id:          SkillId):             F[Option[Skill]]
  def getAll:                                    F[List[Skill]]
  def upsert(skill:        Skill):               F[Skill]
  def getVersion(id:       SkillVersionId):      F[Option[SkillVersion]]
  def getVersions(skillId: SkillId):             F[List[SkillVersion]]
  def upsertVersion(v:     SkillVersion):        F[SkillVersion]
  def getConnector(id:     ConnectorInstanceId): F[Option[ConnectorInstance]]
  def getAllConnectors:                          F[List[ConnectorInstance]]
  def upsertConnector(ci:  ConnectorInstance):   F[ConnectorInstance]

}

/** Repository for [[jorlan.domain.MemoryRecord]] key-value entries.
  *
  * `purgeExpired` is intended to be called periodically (e.g. by the scheduler) to remove records whose `ttl` has
  * passed.
  */
trait MemoryRepository[F[_]] {

  def getById(id: MemoryRecordId): F[Option[MemoryRecord]]
  def search(
    scope:       MemoryScope,
    userId:      Option[UserId],
    workspaceId: Option[WorkspaceId],
    agentId:     Option[AgentId],
    key:         Option[String],
  ):                                  F[List[MemoryRecord]]
  def upsert(record: MemoryRecord):   F[MemoryRecord]
  def delete(id:     MemoryRecordId): F[Long]
  def purgeExpired:                   F[Long]

}

/** Append-only repository for [[jorlan.domain.EventLog]] records. No update or delete operations are defined — the
  * event log must remain immutable for audit purposes.
  */
trait EventLogRepository[F[_]] {

  def append(event: EventLog): F[EventLog]
  def search(
    eventType: Option[EventType],
    agentId:   Option[AgentId],
    from:      Option[Instant],
    to:        Option[Instant],
    limit:     Int,
  ): F[List[EventLog]]

}

/** Repository for [[jorlan.domain.SchedulerJob]] and [[jorlan.domain.SchedulerTrigger]] records. The `getPendingJobs`
  * query is used by the trigger engine to claim jobs for execution.
  */
trait SchedulerRepository[F[_]] {

  def getJob(id:             SchedulerJobId):     F[Option[SchedulerJob]]
  def getPendingJobs:                             F[List[SchedulerJob]]
  def upsertJob(job:         SchedulerJob):       F[SchedulerJob]
  def deleteJob(id:          SchedulerJobId):     F[Long]
  def getTriggers(jobId:     SchedulerJobId):     F[List[SchedulerTrigger]]
  def upsertTrigger(trigger: SchedulerTrigger):   F[SchedulerTrigger]
  def deleteTrigger(id:      SchedulerTriggerId): F[Long]

}

/** Repository for [[jorlan.domain.Artifact]] files and [[jorlan.domain.Workspace]] records. */
trait ArtifactRepository[F[_]] {

  def getById(id:                 ArtifactId):  F[Option[Artifact]]
  def getByWorkspace(workspaceId: WorkspaceId): F[List[Artifact]]
  def upsert(artifact:            Artifact):    F[Artifact]
  def delete(id:                  ArtifactId):  F[Long]
  def getWorkspace(id:            WorkspaceId): F[Option[Workspace]]
  def getAllWorkspaces(ownerId:   UserId):      F[List[Workspace]]
  def upsertWorkspace(ws:         Workspace):   F[Workspace]

}

/** Repository for roles, permissions, capability grants, and the approval request/decision lifecycle. This is the
  * persistence backing for the capability evaluator.
  */
trait PermissionRepository[F[_]] {

  def getRolesForUser(userId:          UserId):            F[List[Role]]
  def getPermissionsForRole(roleId:    RoleId):            F[List[Permission]]
  def getPermissionsForUser(userId:    UserId):            F[List[Permission]]
  def upsertCapabilityGrant(grant:     CapabilityGrant):   F[CapabilityGrant]
  def revokeGrant(id:                  CapabilityGrantId): F[Long]
  def getGrantsForUser(userId:         UserId):            F[List[CapabilityGrant]]
  def createApprovalRequest(req:       ApprovalRequest):   F[ApprovalRequest]
  def cancelApprovalRequest(id:        ApprovalRequestId): F[Long]
  def recordApprovalDecision(decision: ApprovalDecision):  F[ApprovalDecision]
  def getApprovalRequest(id:           ApprovalRequestId): F[Option[ApprovalRequest]]

}
