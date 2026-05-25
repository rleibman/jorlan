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

import jorlan.domain.*
import zio.*

import java.sql.SQLException

type RepositoryTask[A] = IO[SQLException, A]

trait UserRepository {

  def getById(id:                  UserId):          RepositoryTask[Option[User]]
  def getAll:                                        RepositoryTask[List[User]]
  def upsert(user:                 User):            RepositoryTask[User]
  def deactivate(id:               UserId):          RepositoryTask[Unit]
  def getChannelIdentities(userId: UserId):          RepositoryTask[List[ChannelIdentity]]
  def upsertChannelIdentity(ci:    ChannelIdentity): RepositoryTask[ChannelIdentity]
  def deleteChannelIdentity(id:    UserId):          RepositoryTask[Unit]

}

trait AgentRepository {

  def getById(id:                  AgentId):        RepositoryTask[Option[Agent]]
  def getAll:                                       RepositoryTask[List[Agent]]
  def upsert(agent:                Agent):          RepositoryTask[Agent]
  def delete(id:                   AgentId):        RepositoryTask[Unit]
  def getSession(id:               AgentSessionId): RepositoryTask[Option[AgentSession]]
  def getSessionsForAgent(agentId: AgentId):        RepositoryTask[List[AgentSession]]
  def upsertSession(session:       AgentSession):   RepositoryTask[AgentSession]

}

trait ConversationRepository {

  def getById(id:                 ConversationId): RepositoryTask[Option[Conversation]]
  def getBySession(sessionId:     AgentSessionId): RepositoryTask[List[Conversation]]
  def create(conversation:        Conversation):   RepositoryTask[Conversation]
  def getMessages(conversationId: ConversationId): RepositoryTask[List[Message]]
  def addMessage(message:         Message):        RepositoryTask[Message]

}

trait SkillRepository {

  def getById(id:          SkillId):             RepositoryTask[Option[Skill]]
  def getAll:                                    RepositoryTask[List[Skill]]
  def upsert(skill:        Skill):               RepositoryTask[Skill]
  def getVersion(id:       SkillVersionId):      RepositoryTask[Option[SkillVersion]]
  def getVersions(skillId: SkillId):             RepositoryTask[List[SkillVersion]]
  def upsertVersion(v:     SkillVersion):        RepositoryTask[SkillVersion]
  def getConnector(id:     ConnectorInstanceId): RepositoryTask[Option[ConnectorInstance]]
  def getAllConnectors:                          RepositoryTask[List[ConnectorInstance]]
  def upsertConnector(ci:  ConnectorInstance):   RepositoryTask[ConnectorInstance]

}

trait MemoryRepository {

  def getById(id: MemoryRecordId): RepositoryTask[Option[MemoryRecord]]
  def search(
    scope:       MemoryScope,
    userId:      Option[UserId],
    workspaceId: Option[WorkspaceId],
    agentId:     Option[AgentId],
    key:         Option[String],
  ):                                  RepositoryTask[List[MemoryRecord]]
  def upsert(record: MemoryRecord):   RepositoryTask[MemoryRecord]
  def delete(id:     MemoryRecordId): RepositoryTask[Unit]
  def purgeExpired:                   RepositoryTask[Long]

}

trait EventLogRepository {

  def append(event: EventLog): RepositoryTask[EventLog]
  def search(
    eventType: Option[EventType],
    agentId:   Option[AgentId],
    from:      Option[java.time.Instant],
    to:        Option[java.time.Instant],
    limit:     Int,
  ): RepositoryTask[List[EventLog]]

}

trait SchedulerRepository {

  def getJob(id:             SchedulerJobId):   RepositoryTask[Option[SchedulerJob]]
  def getPendingJobs:                           RepositoryTask[List[SchedulerJob]]
  def upsertJob(job:         SchedulerJob):     RepositoryTask[SchedulerJob]
  def getTriggers(jobId:     SchedulerJobId):   RepositoryTask[List[SchedulerTrigger]]
  def upsertTrigger(trigger: SchedulerTrigger): RepositoryTask[SchedulerTrigger]

}

trait ArtifactRepository {

  def getById(id:                 ArtifactId):  RepositoryTask[Option[Artifact]]
  def getByWorkspace(workspaceId: WorkspaceId): RepositoryTask[List[Artifact]]
  def upsert(artifact:            Artifact):    RepositoryTask[Artifact]
  def delete(id:                  ArtifactId):  RepositoryTask[Unit]
  def getWorkspace(id:            WorkspaceId): RepositoryTask[Option[Workspace]]
  def getAllWorkspaces(ownerId:   UserId):      RepositoryTask[List[Workspace]]
  def upsertWorkspace(ws:         Workspace):   RepositoryTask[Workspace]

}

trait PermissionRepository {

  def getRolesForUser(userId:          UserId):            RepositoryTask[List[Role]]
  def getPermissionsForRole(roleId:    RoleId):            RepositoryTask[List[Permission]]
  def getPermissionsForUser(userId:    UserId):            RepositoryTask[List[Permission]]
  def upsertCapabilityGrant(grant:     CapabilityGrant):   RepositoryTask[CapabilityGrant]
  def getGrantsForUser(userId:         UserId):            RepositoryTask[List[CapabilityGrant]]
  def createApprovalRequest(req:       ApprovalRequest):   RepositoryTask[ApprovalRequest]
  def recordApprovalDecision(decision: ApprovalDecision):  RepositoryTask[ApprovalDecision]
  def getApprovalRequest(id:           ApprovalRequestId): RepositoryTask[Option[ApprovalRequest]]

}
