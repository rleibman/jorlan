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

import jorlan.service.EventLogFilter
import zio.json.JsonEncoder
import zio.json.ast.Json

import java.time.Instant

// ─── Search infrastructure ────────────────────────────────────────────────────

enum OrderDirection {

  case Asc, Desc

}

case class Sort[OrderType](
  orderType: OrderType,
  direction: OrderDirection,
)

trait Search[OrderType] {

  def page:     Int
  def pageSize: Int
  def sorts:    Option[Sort[OrderType]]

}

// ─── Per-entity search types ──────────────────────────────────────────────────

enum UserOrder { case Id, DisplayName, CreatedAt }
case class UserSearch(
  active:       Option[Boolean] = None,
  nameContains: Option[String] = None,
  fuzzyName:    Option[String] = None,
  page:         Int = 0,
  pageSize:     Int = 20,
  sorts:        Option[Sort[UserOrder]] = None,
) extends Search[UserOrder]

enum AgentOrder { case Id, Name, CreatedAt }
case class AgentSearch(
  page:     Int = 0,
  pageSize: Int = 20,
  sorts:    Option[Sort[AgentOrder]] = None,
) extends Search[AgentOrder]

enum AgentSessionOrder { case Id, CreatedAt }
case class AgentSessionSearch(
  agentId:                 Option[AgentId] = None,
  userId:                  Option[UserId] = None,
  chatRef:                 Option[String] = None,
  page:                    Int = 0,
  pageSize:                Int = 20,
  sorts:                   Option[Sort[AgentSessionOrder]] = None,
  hideOldTerminatedBefore: Option[java.time.Instant] = None,
) extends Search[AgentSessionOrder]

enum ConversationOrder { case Id, StartedAt }
case class ConversationSearch(
  sessionId: AgentSessionId,
  page:      Int = 0,
  pageSize:  Int = 20,
  sorts:     Option[Sort[ConversationOrder]] = None,
) extends Search[ConversationOrder]

enum MessageOrder { case Id, CreatedAt }
case class MessageSearch(
  conversationId: ConversationId,
  page:           Int = 0,
  pageSize:       Int = 20,
  sorts:          Option[Sort[MessageOrder]] = None,
) extends Search[MessageOrder]

enum SkillOrder { case Id, Name, Tier, CreatedAt }
case class SkillSearch(
  page:     Int = 0,
  pageSize: Int = 20,
  sorts:    Option[Sort[SkillOrder]] = None,
) extends Search[SkillOrder]

enum SkillVersionOrder { case Id, Version, CreatedAt }
case class SkillVersionSearch(
  skillId:  SkillId,
  page:     Int = 0,
  pageSize: Int = 20,
  sorts:    Option[Sort[SkillVersionOrder]] = None,
) extends Search[SkillVersionOrder]

enum ConnectorOrder { case Id, ConnectorType, Name }
case class ConnectorSearch(
  page:     Int = 0,
  pageSize: Int = 20,
  sorts:    Option[Sort[ConnectorOrder]] = None,
) extends Search[ConnectorOrder]

enum MemoryOrder { case Id, RecordKey, CreatedAt, UpdatedAt }

/** Search criteria for [[MemoryRepository]].
  *
  * @param scope
  *   required — only records with this [[MemoryScope]] are returned
  * @param userId
  *   if `Some`, only records whose `userId` matches are returned (omit for `Shared`/`Workspace` scopes)
  * @param workspaceId
  *   optional workspace filter; only relevant for `Workspace`-scoped records
  * @param agentId
  *   optional agent filter applied at the DB level; access policy enforces `Private` scope independently
  * @param key
  *   optional exact match on `recordKey`
  * @param textSearch
  *   optional text filter applied in-memory after the DB fetch — pagination counts may be approximate when this is set,
  *   because the filter runs after SQL LIMIT/OFFSET
  */
case class MemorySearch(
  scope:         MemoryScope,
  userId:        Option[UserId] = None,
  workspaceId:   Option[WorkspaceId] = None,
  agentId:       Option[AgentId] = None,
  key:           Option[String] = None,
  textSearch:    Option[String] = None,
  minImportance: Option[Int] = None,
  page:          Int = 0,
  pageSize:      Int = 50,
  sorts:         Option[Sort[MemoryOrder]] = None,
) extends Search[MemoryOrder]

enum ArtifactOrder { case Id, Name, CreatedAt }
case class ArtifactSearch(
  workspaceId: WorkspaceId,
  page:        Int = 0,
  pageSize:    Int = 20,
  sorts:       Option[Sort[ArtifactOrder]] = None,
) extends Search[ArtifactOrder]

enum WorkspaceOrder { case Id, Name, CreatedAt }
case class WorkspaceSearch(
  ownerId:  UserId,
  page:     Int = 0,
  pageSize: Int = 20,
  sorts:    Option[Sort[WorkspaceOrder]] = None,
) extends Search[WorkspaceOrder]

enum RoleOrder { case Id, Name }
case class RoleSearch(
  userId:   Option[UserId] = None,
  page:     Int = 0,
  pageSize: Int = 20,
  sorts:    Option[Sort[RoleOrder]] = None,
) extends Search[RoleOrder]

enum PermissionOrder { case Id, Resource, Action }
case class PermissionSearch(
  roleId:   Option[RoleId] = None,
  userId:   Option[UserId] = None,
  page:     Int = 0,
  pageSize: Int = 20,
  sorts:    Option[Sort[PermissionOrder]] = None,
) extends Search[PermissionOrder]

enum GrantOrder { case Id, GrantedAt }
case class GrantSearch(
  userId:   UserId,
  page:     Int = 0,
  pageSize: Int = 20,
  sorts:    Option[Sort[GrantOrder]] = None,
) extends Search[GrantOrder]

enum TriggerOrder { case Id }
case class TriggerSearch(
  jobId:    SchedulerJobId,
  page:     Int = 0,
  pageSize: Int = 20,
  sorts:    Option[Sort[TriggerOrder]] = None,
) extends Search[TriggerOrder]

// ─── Repository interfaces ────────────────────────────────────────────────────

/** Abstract repository interfaces parameterised by effect type `F[_]`.
  *
  * Keeping these traits in `model` rather than `db` means any module — including a GraphQL client or an in-memory test
  * double — can implement the same interface without depending on Quill or ZIO. The concrete ZIO implementations in
  * `db` fix `F = IO[RepositoryError, *]`.
  */

/** Repository for [[User]] and [[ChannelIdentity]] records. */
trait UserRepository[F[_]] {

  def getById(id:                  UserId):            F[Option[User]]
  def search(s:                    UserSearch):        F[List[User]]
  def upsert(user:                 User):              F[User]
  def deactivate(id:               UserId):            F[Long]
  def getChannelIdentities(userId: UserId):            F[List[ChannelIdentity]]
  def upsertChannelIdentity(ci:    ChannelIdentity):   F[ChannelIdentity]
  def deleteChannelIdentity(id:    ChannelIdentityId): F[Long]

  /** Verify credentials via `SHA2(password, 512)` comparison in SQL. Returns the user if active and credentials match,
    * `None` otherwise. The plain-text password never leaves this method.
    */
  def login(
    email:    String,
    password: String,
  ): F[Option[User]]

  def userByEmail(email: String): F[Option[User]]

  def changePassword(
    id:          UserId,
    newPassword: String,
  ): F[Unit]

  /** Look up the user linked to a specific OAuth provider identity. */
  def userByChannelIdentity(
    channelType:   ChannelType,
    channelUserId: String,
  ): F[Option[User]]

  def findContacts(nameOpt: Option[String]): F[Json]

}

/** Repository for [[Agent]] definitions and [[AgentSession]] runtime records.
  */
trait AgentRepository[F[_]] {

  def getById(id:                 AgentId):            F[Option[Agent]]
  def search(s:                   AgentSearch):        F[List[Agent]]
  def upsert(agent:               Agent):              F[Agent]
  def delete(id:                  AgentId):            F[Long]
  def getSession(id:              AgentSessionId):     F[Option[AgentSession]]
  def searchSessions(s:           AgentSessionSearch): F[List[AgentSession]]
  def upsertSession(session:      AgentSession):       F[AgentSession]
  def createSession(modelId:      Option[ModelId]):    F[Option[AgentSession]]
  def terminateSession(sessionId: AgentSessionId):     F[Unit]
  def availableModels():                               F[List[ModelInfo]]
  def submitMessage(
    sessionId: AgentSessionId,
    content:   String,
  ): F[Unit]

}

/** Repository for [[Conversation]] threads and [[Message]] records. */
trait ConversationRepository[F[_]] {

  def getById(id:          ConversationId):     F[Option[Conversation]]
  def search(s:            ConversationSearch): F[List[Conversation]]
  def create(conversation: Conversation):       F[Conversation]
  def searchMessages(s:    MessageSearch):      F[List[Message]]
  def addMessage(message:  Message):            F[Message]

}

/** Repository for [[SkillRecord]] entries, [[SkillVersion]] snapshots, and [[ConnectorInstance]] configurations.
  */
trait SkillRepository[F[_]] {

  def getById(id:         SkillId):             F[Option[SkillRecord]]
  def search(s:           SkillSearch):         F[List[SkillRecord]]
  def upsert(skill:       SkillRecord):         F[SkillRecord]
  def getVersion(id:      SkillVersionId):      F[Option[SkillVersion]]
  def searchVersions(s:   SkillVersionSearch):  F[List[SkillVersion]]
  def upsertVersion(v:    SkillVersion):        F[SkillVersion]
  def getConnector(id:    ConnectorInstanceId): F[Option[ConnectorInstance]]
  def searchConnectors(s: ConnectorSearch):     F[List[ConnectorInstance]]
  def upsertConnector(ci: ConnectorInstance):   F[ConnectorInstance]
  def listSkills():                             F[List[SkillInfo]]
  def enableSkill(name:  String):              F[Unit]
  def disableSkill(name: String):              F[Unit]
  def invokeTool(
    toolName: String,
    argsJson: String,
  ): F[Option[String]]

}

/** Repository for [[MemoryRecord]] key-value entries.
  *
  * `purgeExpired` is intended to be called periodically (e.g. by the scheduler) to remove records whose `ttl` has
  * passed.
  */
trait MemoryRepository[F[_]] {

  def getById(id: MemoryRecordId): F[Option[MemoryRecord]]
  def getByKey(
    key:     String,
    userId:  Option[UserId],
    agentId: Option[AgentId],
  ):                                F[Option[MemoryRecord]]
  def search(s:      MemorySearch): F[List[MemoryRecord]]
  def upsert(record: MemoryRecord): F[MemoryRecord]
  def updateScope(
    id:    MemoryRecordId,
    scope: MemoryScope,
  ):                              F[Long]
  def delete(id: MemoryRecordId): F[Long]
  def purgeExpired:               F[Long]

}

/** Append-only repository for [[EventLog]] records. No update or delete operations are defined — the event log must
  * remain immutable for audit purposes.
  *
  * `append` is generic on the resource type `R` so callers can record typed entity references. `search` returns
  * `EventLog[Json]` since rows from different event types carry heterogeneous resource types.
  */
trait EventLogRepository[F[_]] {

  def append[R: JsonEncoder](event: EventLog[R]):    F[EventLog[R]]
  def search(filter:                EventLogFilter): F[List[EventLog[Json]]]
  def replaySession(
    sessionId: AgentSessionId,
    limit:     Int = 1000,
  ): F[List[EventLog[Json]]]

}

/** Repository for [[jorlan.SchedulerJob]] and [[SchedulerTrigger]] records. The `getPendingJobs` query is used by the
  * trigger engine to claim jobs for execution.
  */
trait SchedulerRepository[F[_]] {

  def getJob(id: SchedulerJobId): F[Option[SchedulerJob]]
  def listJobs(
    agentId: Option[AgentId],
    limit:   Int = 200,
  ):                                              F[List[SchedulerJob]]
  def getPendingJobs:                             F[List[SchedulerJob]]
  def upsertJob(job:         SchedulerJob):       F[SchedulerJob]
  def deleteJob(id:          SchedulerJobId):     F[Boolean]
  def pauseJob(id:           SchedulerJobId):     F[Boolean]
  def resumeJob(id:          SchedulerJobId):     F[Boolean]
  def cancelJob(id:          SchedulerJobId):     F[Boolean]
  def triggerNow(id:         SchedulerJobId):     F[Boolean]
  def searchTriggers(s:      TriggerSearch):      F[List[SchedulerTrigger]]
  def upsertTrigger(trigger: SchedulerTrigger):   F[SchedulerTrigger]
  def deleteTrigger(id:      SchedulerTriggerId): F[Long]

  /** Atomically claim a job for execution. Returns `true` if the claim succeeded (only one worker wins the race). Uses
    * a single UPDATE with a WHERE clause that checks the job is still `Pending` and unclaimed.
    *
    * @param leaseTtlSeconds
    *   Stale leases older than this will also be overwritten.
    */
  def claimJob(
    id:              SchedulerJobId,
    workerId:        String,
    now:             Instant,
    leaseTtlSeconds: Int,
  ): F[Boolean]

  /** Release a completed or failed job: sets final `status`, `resultJson`, `finishedAt`, and clears the lease. */
  def releaseJob(
    id:         SchedulerJobId,
    status:     JobStatus,
    resultJson: Option[String],
    finishedAt: Instant,
  ): F[Unit]

  /** Reset stale leases (where `leasedAt < olderThan`) back to `Pending` so another worker can pick them up. */
  def expireLeases(olderThan: Instant): F[Long]

}

/** Repository for [[Artifact]] files and [[Workspace]] records. */
trait ArtifactRepository[F[_]] {

  def getById(id:         ArtifactId):      F[Option[Artifact]]
  def search(s:           ArtifactSearch):  F[List[Artifact]]
  def upsert(artifact:    Artifact):        F[Artifact]
  def delete(id:          ArtifactId):      F[Long]
  def getWorkspace(id:    WorkspaceId):     F[Option[Workspace]]
  def searchWorkspaces(s: WorkspaceSearch): F[List[Workspace]]
  def upsertWorkspace(ws: Workspace):       F[Workspace]

}

/** Repository for roles, permissions, capability grants, and the approval request/decision lifecycle. This is the
  * persistence backing for the capability evaluator.
  */
trait PermissionRepository[F[_]] {

  def getRole(id:      RoleId):     F[Option[Role]]
  def searchRoles(s:   RoleSearch): F[List[Role]]
  def upsertRole(role: Role):       F[Role]
  def deleteRole(id:   RoleId):     F[Long]
  def assignRole(
    userId: UserId,
    roleId: RoleId,
  ): F[Unit]
  def removeRole(
    userId: UserId,
    roleId: RoleId,
  ):                                                   F[Unit]
  def searchPermissions(s:         PermissionSearch):  F[List[Permission]]
  def upsertPermission(permission: Permission):        F[Permission]
  def deletePermission(id:         PermissionId):      F[Long]
  def upsertCapabilityGrant(grant: CapabilityGrant):   F[CapabilityGrant]
  def revokeGrant(id:              CapabilityGrantId): F[Long]
  def searchGrants(s:              GrantSearch):       F[List[CapabilityGrant]]
  def listCapabilities():                              F[List[CapabilityGrant]]
  def listApprovals():                                 F[List[ApprovalRequest]]
  def decideApproval(
    id:       ApprovalRequestId,
    approved: Boolean,
    note:     Option[String] = None,
  ):                                                       F[Boolean]
  def createApprovalRequest(req:       ApprovalRequest):   F[ApprovalRequest]
  def cancelApprovalRequest(id:        ApprovalRequestId): F[Long]
  def expireApprovalRequest(id:        ApprovalRequestId): F[Long]
  def expireAllStaleApprovalRequests():                    F[Long]
  def recordApprovalDecision(decision: ApprovalDecision):  F[ApprovalDecision]
  def getApprovalRequest(id:           ApprovalRequestId): F[Option[ApprovalRequest]]
  def listPendingApprovals(userId:     UserId):            F[List[ApprovalRequest]]
  def getExpiredApprovalRequests:                          F[List[ApprovalRequest]]

  /** All [[CapabilityGrant]] rows for a user + capability that are relevant to the evaluator: `Denied` grants are
    * always included (they drive [[EvaluationResult.ExplicitDeny]]); non-`Denied` grants are filtered to those that
    * have not yet expired (`expiresAt IS NULL OR expiresAt > now`).
    */
  def getGrantsForCapability(
    userId:     UserId,
    capability: CapabilityName,
  ): F[List[CapabilityGrant]]

  /** Returns `true` if the user has a direct (user-scoped) [[Permission]] row matching `resource` and `action`. */
  def hasDirectPermission(
    userId:   UserId,
    resource: String,
    action:   String,
  ): F[Boolean]

  /** Returns `true` if any role assigned to the user has a [[Permission]] row matching `resource` and `action`. */
  def hasRolePermission(
    userId:   UserId,
    resource: String,
    action:   String,
  ): F[Boolean]

  /** Finds an already-approved [[ApprovalRequest]] for the given capability + user, optionally scoped to a session
    * (used for `Session` and `Once` approval modes).
    */
  def findApprovedRequest(
    capability: CapabilityName,
    userId:     UserId,
    sessionId:  Option[AgentSessionId],
  ): F[Option[ApprovalRequest]]

}

/** Read/write access to the `server_settings` key-value table.
  *
  * All values are stored as JSON so settings can hold scalars, strings, or nested objects without schema changes. The
  * table is created by Flyway migration V017.
  */
trait ServerSettingsRepository[F[_]] {

  /** Retrieves the JSON value for `key`, or `None` if absent. Never fails. */
  def get(key: String): F[Option[Json]]

  /** Upserts `value` under `key`. Never fails. */
  def set(
    key:   String,
    value: Json,
  ): F[Unit]

  /** Reads and decodes the server [[Personality]], or `None` if not yet configured. */
  def serverPersonality(): F[Option[Personality]]

  /** Persists a new [[Personality]] and returns it, or `None` if serialisation fails. */
  def updatePersonality(
    name:      String,
    formality: Formality,
    languages: List[String],
    expertise: List[String],
    prompt:    String,
  ): F[Option[Personality]]

}

/** Repository for [[jorlan.ExternalCredential]] records (OAuth tokens and similar). */
trait ExternalCredentialRepository[F[_]] {

  def upsert(
    userId:        UserId,
    provider:      String,
    encryptedData: Json,
    expiresAt:     Option[Instant],
    scopes:        Option[String],
  ): F[Unit]
  def find(
    userId:   UserId,
    provider: String,
  ): F[Option[ExternalCredential]]
  def delete(
    userId:   UserId,
    provider: String,
  ):                                 F[Unit]
  def listByUser(userId:    UserId): F[List[ExternalCredential]]
  def listOAuthProviders():          F[List[String]]
  def startOAuth(provider:  String): F[Option[String]]
  def revokeOAuth(provider: String): F[Unit]
  def oauthStatus(provider: String): F[Option[OAuthStatus]]

}

/** Lightweight repository for server-level introspection queries (e.g. health check, schema ping). */
trait ServerInfoRepository[F[_]] {

  def statusCheck(): F[Json]

}

/** Aggregate of all repositories, for convenient injection into application services. */
trait Repositories[F[_]] {

  def user:          UserRepository[F]
  def agent:         AgentRepository[F]
  def conversation:  ConversationRepository[F]
  def skill:         SkillRepository[F]
  def memory:        MemoryRepository[F]
  def eventLog:      EventLogRepository[F]
  def scheduler:     SchedulerRepository[F]
  def artifact:      ArtifactRepository[F]
  def permission:    PermissionRepository[F]
  def setting:       ServerSettingsRepository[F]
  def extCredential: ExternalCredentialRepository[F]
  def serverInfo:    ServerInfoRepository[F]

}
