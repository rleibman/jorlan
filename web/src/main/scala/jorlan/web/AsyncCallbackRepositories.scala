/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web

import caliban.{ScalaJSClientAdapter, WebSocketHandler}
import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.callback.AsyncCallback
import jorlan.*
import jorlan.graphql.client.JorlanClient
import jorlan.graphql.client.JorlanClientDecoders.given
import jorlan.service.EventLogFilter
import zio.json.ast.Json

import java.time.Instant
import scala.language.unsafeNulls

/** Client-side implementation of [[Repositories]] backed by GraphQL calls.
  *
  * Sub-repos provide data-access operations; application-level operations (createSession, submitMessage, etc.) are
  * additional methods on this object. All GQL view → domain conversions are private to this object.
  */
object AsyncCallbackRepositories extends Repositories[AsyncCallback] {

  private val adapter: ScalaJSClientAdapter = JorlanWebApp.makeAdapter()

  def makeAdapter(): ScalaJSClientAdapter = JorlanWebApp.makeAdapter()

  // ── Sub-repo: Agent ────────────────────────────────────────────────────────

  override val agent: AgentRepository[AsyncCallback] = new AgentRepository[AsyncCallback] {

    override def searchSessions(s: AgentSessionSearch): AsyncCallback[List[AgentSession]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Queries.listSessions()(JorlanClient.AgentSession.view))
        .map(_.getOrElse(Nil).map(toAgentSession))

    override def getById(id:            AgentId):         AsyncCallback[Option[Agent]] = AsyncCallback.pure(None)
    override def search(s:              AgentSearch):     AsyncCallback[List[Agent]] = AsyncCallback.pure(Nil)
    override def upsert(a:              Agent):           AsyncCallback[Agent] = ???
    override def delete(id:             AgentId):         AsyncCallback[Long] = ???
    override def getSession(id:         AgentSessionId):  AsyncCallback[Option[AgentSession]] = AsyncCallback.pure(None)
    override def upsertSession(session: AgentSession):    AsyncCallback[AgentSession] = ???
    override def createSession(modelId: Option[ModelId]): AsyncCallback[Option[AgentSession]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Mutations.createSession(modelId)(JorlanClient.AgentSession.view))
        .map(_.map(toAgentSession))
    override def terminateSession(sessionId: AgentSessionId): AsyncCallback[Unit] =
      adapter.asyncCalibanCallWithAuth(JorlanClient.Mutations.terminateSession(sessionId)).map(_ => ())
    override def availableModels(): AsyncCallback[List[ModelInfo]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Queries.availableModels(JorlanClient.ModelInfo.view))
        .map(_.getOrElse(Nil).map(toModelInfo))
    override def submitMessage(
      sessionId: AgentSessionId,
      content:   String,
    ): AsyncCallback[Unit] =
      adapter.asyncCalibanCallWithAuth(JorlanClient.Mutations.submitMessage(sessionId, content)).map(_ => ())

  }

  // ── Sub-repo: Memory ───────────────────────────────────────────────────────

  override val memory: MemoryRepository[AsyncCallback] = new MemoryRepository[AsyncCallback] {

    override def search(s: MemorySearch): AsyncCallback[List[MemoryRecord]] =
      adapter
        .asyncCalibanCallWithAuth(
          JorlanClient.Queries.listMemory(s.scope, s.textSearch)(JorlanClient.MemoryRecord.view),
        )
        .map(_.getOrElse(Nil).map(toMemoryRecord))

    override def upsert(record: MemoryRecord): AsyncCallback[MemoryRecord] = {
      val text = record.value match {
        case Json.Str(s) => s
        case other       => other.toString
      }
      adapter
        .asyncCalibanCallWithAuth(
          JorlanClient.Mutations.storeMemory(record.recordKey, text, record.scope)(JorlanClient.MemoryRecord.view),
        )
        .map(_.fold(record)(toMemoryRecord))
    }

    override def delete(id: MemoryRecordId): AsyncCallback[Long] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Mutations.forgetMemory(id))
        .map(b => if (b.getOrElse(false)) 1L else 0L)

    override def updateScope(
      id:    MemoryRecordId,
      scope: MemoryScope,
    ): AsyncCallback[Long] = {
      val mutation = scope match {
        case MemoryScope.Shared  => JorlanClient.Mutations.markMemoryShared(id)(JorlanClient.MemoryRecord.view)
        case MemoryScope.Private => JorlanClient.Mutations.markMemoryPrivate(id)(JorlanClient.MemoryRecord.view)
        case _                   => JorlanClient.Mutations.markMemoryPrivate(id)(JorlanClient.MemoryRecord.view)
      }
      adapter.asyncCalibanCallWithAuth(mutation).map(o => if (o.isDefined) 1L else 0L)
    }

    override def getById(id: MemoryRecordId): AsyncCallback[Option[MemoryRecord]] = AsyncCallback.pure(None)
    override def getByKey(
      key:     String,
      userId:  Option[UserId],
      agentId: Option[AgentId],
    ):                         AsyncCallback[Option[MemoryRecord]] = AsyncCallback.pure(None)
    override def purgeExpired: AsyncCallback[Long] = AsyncCallback.pure(0L)

  }

  // ── Sub-repo: Permission ───────────────────────────────────────────────────

  override val permission: PermissionRepository[AsyncCallback] = new PermissionRepository[AsyncCallback] {

    override def listPendingApprovals(userId: UserId): AsyncCallback[List[ApprovalRequest]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Queries.listApprovals(JorlanClient.ApprovalRequest.view))
        .map(_.getOrElse(Nil).map(toApprovalRequest))

    override def searchGrants(s: GrantSearch): AsyncCallback[List[CapabilityGrant]] =
      adapter
        .asyncCalibanCallWithAuth(
          JorlanClient.Queries.userCapabilityGrants(s.userId)(JorlanClient.CapabilityGrant.view),
        )
        .map(_.getOrElse(Nil).map(toCapabilityGrant))

    override def getRole(id: RoleId):        AsyncCallback[Option[Role]] = AsyncCallback.pure(None)
    override def searchRoles(s: RoleSearch): AsyncCallback[List[Role]] =
      s.userId match {
        case Some(uid) =>
          adapter
            .asyncCalibanCallWithAuth(JorlanClient.Queries.roles(uid)(JorlanClient.Role.view))
            .map(_.getOrElse(Nil).map(toRole))
        case None =>
          adapter
            .asyncCalibanCallWithAuth(JorlanClient.Queries.allRoles()(JorlanClient.Role.view))
            .map(_.getOrElse(Nil).map(toRole))
      }
    override def upsertRole(role: Role): AsyncCallback[Role] =
      adapter
        .asyncCalibanCallWithAuth(
          JorlanClient.Mutations.createRole(role.name, role.description)(JorlanClient.Role.view),
        )
        .flatMap(r =>
          r.fold(
            AsyncCallback.throwException[JorlanClient.Role.RoleView](RuntimeException("createRole returned no role")),
          )(AsyncCallback.pure),
        )
        .map(toRole)
    override def deleteRole(id: RoleId): AsyncCallback[Long] = AsyncCallback.pure(0L)
    override def assignRole(
      userId: UserId,
      roleId: RoleId,
    ): AsyncCallback[Unit] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Mutations.assignRole(userId, roleId))
        .map(_ => ())
    override def removeRole(
      userId: UserId,
      roleId: RoleId,
    ): AsyncCallback[Unit] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Mutations.revokeRole(userId, roleId))
        .map(_ => ())
    override def searchPermissions(s:   PermissionSearch): AsyncCallback[List[Permission]] = AsyncCallback.pure(Nil)
    override def upsertPermission(perm: Permission):       AsyncCallback[Permission] = ???
    override def deletePermission(id:   PermissionId):     AsyncCallback[Long] = AsyncCallback.pure(0L)
    override def upsertCapabilityGrant(grant: CapabilityGrant): AsyncCallback[CapabilityGrant] =
      adapter
        .asyncCalibanCallWithAuth(
          JorlanClient.Mutations.grantCapability(grant.granteeId, grant.capability, grant.approvalMode)(
            JorlanClient.CapabilityGrant.view,
          ),
        )
        .flatMap(r =>
          r.fold(
            AsyncCallback.throwException[JorlanClient.CapabilityGrant.CapabilityGrantView](
              RuntimeException("grantCapability returned nothing"),
            ),
          )(AsyncCallback.pure),
        )
        .map(toCapabilityGrant)
    override def revokeGrant(id: CapabilityGrantId): AsyncCallback[Long] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Mutations.revokeCapabilityGrant(id))
        .map(r => if (r.getOrElse(false)) 1L else 0L)
    override def createApprovalRequest(req:       ApprovalRequest):   AsyncCallback[ApprovalRequest] = ???
    override def cancelApprovalRequest(id:        ApprovalRequestId): AsyncCallback[Long] = AsyncCallback.pure(0L)
    override def expireApprovalRequest(id:        ApprovalRequestId): AsyncCallback[Long] = AsyncCallback.pure(0L)
    override def expireAllStaleApprovalRequests():                    AsyncCallback[Long] = AsyncCallback.pure(0L)
    override def recordApprovalDecision(decision: ApprovalDecision):  AsyncCallback[ApprovalDecision] = ???
    override def getApprovalRequest(id: ApprovalRequestId):           AsyncCallback[Option[ApprovalRequest]] =
      AsyncCallback.pure(None)
    override def getExpiredApprovalRequests: AsyncCallback[List[ApprovalRequest]] = AsyncCallback.pure(Nil)
    override def getGrantsForCapability(
      userId:     UserId,
      capability: CapabilityName,
    ): AsyncCallback[List[CapabilityGrant]] = AsyncCallback.pure(Nil)
    override def hasDirectPermission(
      userId:   UserId,
      resource: String,
      action:   String,
    ): AsyncCallback[Boolean] =
      AsyncCallback.pure(false)
    override def hasRolePermission(
      userId:   UserId,
      resource: String,
      action:   String,
    ): AsyncCallback[Boolean] =
      AsyncCallback.pure(false)
    override def findApprovedRequest(
      capability: CapabilityName,
      userId:     UserId,
      sessionId:  Option[AgentSessionId],
    ):                               AsyncCallback[Option[ApprovalRequest]] = AsyncCallback.pure(None)
    override def listCapabilities(): AsyncCallback[List[CapabilityGrant]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Queries.listCapabilities(JorlanClient.CapabilityGrant.view))
        .map(_.getOrElse(Nil).map(toCapabilityGrant))
    override def listApprovals(): AsyncCallback[List[ApprovalRequest]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Queries.listApprovals(JorlanClient.ApprovalRequest.view))
        .map(_.getOrElse(Nil).map(toApprovalRequest))
    override def decideApproval(
      id:       ApprovalRequestId,
      approved: Boolean,
      note:     Option[String] = None,
    ): AsyncCallback[Boolean] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Mutations.decideApproval(id, approved, note))
        .map(_.getOrElse(false))

  }

  // ── Sub-repo: Scheduler ────────────────────────────────────────────────────

  override val scheduler: SchedulerRepository[AsyncCallback] = new SchedulerRepository[AsyncCallback] {

    override def listJobs(
      agentId: Option[AgentId],
      limit:   Int,
    ): AsyncCallback[List[SchedulerJob]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Queries.jobs(agentId)(JorlanClient.SchedulerJob.view))
        .map(_.getOrElse(Nil).map(summon[Conversion[JorlanClient.SchedulerJob.SchedulerJobView, SchedulerJob]]))

    override def searchTriggers(s: TriggerSearch): AsyncCallback[List[SchedulerTrigger]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Queries.triggers(s.jobId)(JorlanClient.SchedulerTrigger.view))
        .map(
          _.getOrElse(Nil)
            .map(summon[Conversion[JorlanClient.SchedulerTrigger.SchedulerTriggerView, SchedulerTrigger]]),
        )

    override def getJob(id: SchedulerJobId):   AsyncCallback[Option[SchedulerJob]] = AsyncCallback.pure(None)
    override def getPendingJobs:               AsyncCallback[List[SchedulerJob]] = AsyncCallback.pure(Nil)
    override def upsertJob(job: SchedulerJob): AsyncCallback[SchedulerJob] =
      AsyncCallback.throwException(new UnsupportedOperationException("upsertJob not available on web client"))

    override def deleteJob(id: SchedulerJobId): AsyncCallback[Boolean] =
      adapter.asyncCalibanCallWithAuth(JorlanClient.Mutations.deleteJob(id)).map(_.getOrElse(false))
    override def pauseJob(id: SchedulerJobId): AsyncCallback[Boolean] =
      adapter.asyncCalibanCallWithAuth(JorlanClient.Mutations.pauseJob(id)).map(_.getOrElse(false))
    override def resumeJob(id: SchedulerJobId): AsyncCallback[Boolean] =
      adapter.asyncCalibanCallWithAuth(JorlanClient.Mutations.resumeJob(id)).map(_.getOrElse(false))
    override def cancelJob(id: SchedulerJobId): AsyncCallback[Boolean] =
      adapter.asyncCalibanCallWithAuth(JorlanClient.Mutations.cancelJob(id)).map(_.getOrElse(false))
    override def triggerNow(id: SchedulerJobId): AsyncCallback[Boolean] =
      adapter.asyncCalibanCallWithAuth(JorlanClient.Mutations.triggerNow(id)).map(_.getOrElse(false))
    override def upsertTrigger(t:  SchedulerTrigger):   AsyncCallback[SchedulerTrigger] = ???
    override def deleteTrigger(id: SchedulerTriggerId): AsyncCallback[Long] = AsyncCallback.pure(0L)
    override def claimJob(
      id:              SchedulerJobId,
      workerId:        String,
      now:             Instant,
      leaseTtlSeconds: Int,
    ): AsyncCallback[Boolean] =
      AsyncCallback.pure(false)
    override def releaseJob(
      id:         SchedulerJobId,
      status:     JobStatus,
      resultJson: Option[String],
      finishedAt: Instant,
    ):                                             AsyncCallback[Unit] = AsyncCallback.pure(())
    override def expireLeases(olderThan: Instant): AsyncCallback[Long] = AsyncCallback.pure(0L)

  }

  def createJob(
    name:            String,
    prompt:          String,
    maxRetries:      Int,
    backoffSeconds:  Int,
    backoffPolicy:   RetryBackoffPolicy,
    missedRunPolicy: MissedRunPolicy,
  ): AsyncCallback[SchedulerJob] =
    adapter
      .asyncCalibanCallWithAuth(
        JorlanClient.Mutations.createJob(
          name = name,
          prompt = prompt,
          maxRetries = maxRetries,
          backoffSeconds = backoffSeconds,
          backoffPolicy = backoffPolicy,
          missedRunPolicy = missedRunPolicy,
        )(JorlanClient.SchedulerJob.view),
      )
      .flatMap {
        case Some(v) =>
          AsyncCallback.pure(summon[Conversion[JorlanClient.SchedulerJob.SchedulerJobView, SchedulerJob]](v))
        case None => AsyncCallback.throwException(new RuntimeException("createJob returned no job"))
      }

  def addTrigger(
    jobId:       SchedulerJobId,
    triggerType: TriggerType,
    expression:  String,
  ): AsyncCallback[SchedulerTrigger] =
    adapter
      .asyncCalibanCallWithAuth(
        JorlanClient.Mutations.addTrigger(jobId, triggerType, expression)(JorlanClient.SchedulerTrigger.view),
      )
      .flatMap {
        case Some(v) =>
          AsyncCallback.pure(
            summon[Conversion[JorlanClient.SchedulerTrigger.SchedulerTriggerView, SchedulerTrigger]](v),
          )
        case None => AsyncCallback.throwException(new RuntimeException("addTrigger returned no trigger"))
      }

  // ── Sub-repo: User ─────────────────────────────────────────────────────────

  override val user: UserRepository[AsyncCallback] = new UserRepository[AsyncCallback] {

    override def search(s: UserSearch): AsyncCallback[List[User]] =
      adapter
        .asyncCalibanCallWithAuth(
          JorlanClient.Queries.users(s.active, s.nameContains, Some(s.page), Some(s.pageSize))(JorlanClient.User.view),
        )
        .map(_.getOrElse(Nil).map(toUser))

    override def getById(id: UserId): AsyncCallback[Option[User]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Queries.user(id)(JorlanClient.User.view))
        .map(_.map(toUser))

    override def upsert(u: User): AsyncCallback[User] =
      if (u.id == UserId.empty)
        adapter
          .asyncCalibanCallWithAuth(
            JorlanClient.Mutations.createUser(u.displayName, Some(u.email))(JorlanClient.User.view),
          )
          .flatMap(r =>
            r.fold(
              AsyncCallback.throwException[JorlanClient.User.UserView](RuntimeException("createUser returned no user")),
            )(AsyncCallback.pure),
          )
          .map(toUser)
      else
        adapter
          .asyncCalibanCallWithAuth(
            JorlanClient.Mutations.updateUser(u.id, u.displayName, Some(u.email), u.active)(JorlanClient.User.view),
          )
          .flatMap(r =>
            r.fold(
              AsyncCallback.throwException[JorlanClient.User.UserView](RuntimeException("updateUser returned no user")),
            )(AsyncCallback.pure),
          )
          .map(toUser)

    override def deactivate(id: UserId): AsyncCallback[Long] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Mutations.deactivateUser(id))
        .map(r => if (r.getOrElse(false)) 1L else 0L)
    override def getChannelIdentities(userId: UserId): AsyncCallback[List[ChannelIdentity]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Queries.userChannelIdentities(userId)(JorlanClient.ChannelIdentity.view))
        .map(_.getOrElse(Nil).map(toChannelIdentity))
    override def upsertChannelIdentity(ci: ChannelIdentity): AsyncCallback[ChannelIdentity] =
      adapter
        .asyncCalibanCallWithAuth(
          JorlanClient.Mutations.linkChannelIdentity(ci.userId, ci.channelType.toString, ci.channelUserId)(
            JorlanClient.ChannelIdentity.view,
          ),
        )
        .flatMap(r =>
          r.fold(
            AsyncCallback.throwException[JorlanClient.ChannelIdentity.ChannelIdentityView](
              RuntimeException("linkChannelIdentity returned nothing"),
            ),
          )(AsyncCallback.pure),
        )
        .map(toChannelIdentity)
    override def deleteChannelIdentity(id: ChannelIdentityId): AsyncCallback[Long] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Mutations.unlinkChannelIdentity(id.value.toString))
        .map(r => if (r.getOrElse(false)) 1L else 0L)
    override def login(
      email:    String,
      password: String,
    ):                                       AsyncCallback[Option[User]] = AsyncCallback.pure(None)
    override def userByEmail(email: String): AsyncCallback[Option[User]] = AsyncCallback.pure(None)
    override def changePassword(
      id:          UserId,
      newPassword: String,
    ): AsyncCallback[Unit] = AsyncCallback.pure(())
    override def userByChannelIdentity(
      channelType:   ChannelType,
      channelUserId: String,
    ):                                                  AsyncCallback[Option[User]] = AsyncCallback.pure(None)
    override def findContacts(nameOpt: Option[String]): AsyncCallback[Json] = AsyncCallback.pure(Json.Null)

  }

  // ── Sub-repos: Stubs ───────────────────────────────────────────────────────

  override val conversation: ConversationRepository[AsyncCallback] = new ConversationRepository[AsyncCallback] {
    override def getById(id: ConversationId):     AsyncCallback[Option[Conversation]] = AsyncCallback.pure(None)
    override def search(s:   ConversationSearch): AsyncCallback[List[Conversation]] = AsyncCallback.pure(Nil)
    override def create(conversation: Conversation):  AsyncCallback[Conversation] = ???
    override def searchMessages(s:    MessageSearch): AsyncCallback[List[Message]] = AsyncCallback.pure(Nil)
    override def addMessage(message:  Message):       AsyncCallback[Message] = ???
  }

  override val skill: SkillRepository[AsyncCallback] = new SkillRepository[AsyncCallback] {
    override def getById(id:       SkillId):            AsyncCallback[Option[SkillRecord]] = AsyncCallback.pure(None)
    override def search(s:         SkillSearch):        AsyncCallback[List[SkillRecord]] = AsyncCallback.pure(Nil)
    override def upsert(skill:     SkillRecord):        AsyncCallback[SkillRecord] = ???
    override def getVersion(id:    SkillVersionId):     AsyncCallback[Option[SkillVersion]] = AsyncCallback.pure(None)
    override def searchVersions(s: SkillVersionSearch): AsyncCallback[List[SkillVersion]] = AsyncCallback.pure(Nil)
    override def upsertVersion(v:  SkillVersion):       AsyncCallback[SkillVersion] = ???
    override def getConnector(id: ConnectorInstanceId): AsyncCallback[Option[ConnectorInstance]] =
      AsyncCallback.pure(None)
    override def searchConnectors(s: ConnectorSearch): AsyncCallback[List[ConnectorInstance]] = AsyncCallback.pure(Nil)
    override def upsertConnector(ci: ConnectorInstance): AsyncCallback[ConnectorInstance] = ???
    override def listSkills():                           AsyncCallback[List[SkillInfo]] =
      adapter
        .asyncCalibanCallWithAuth(
          JorlanClient.Queries.skills(JorlanClient.SkillInfo.view(JorlanClient.SkillToolInfo.view)),
        )
        .map(_.getOrElse(Nil).map(toSkillInfo))
    override def invokeTool(
      toolName: String,
      argsJson: String,
    ): AsyncCallback[Option[String]] =
      adapter.asyncCalibanCallWithAuth(JorlanClient.Mutations.invokeTool(toolName, argsJson))
  }

  override val eventLog: EventLogRepository[AsyncCallback] = new EventLogRepository[AsyncCallback] {
    override def append[R: zio.json.JsonEncoder](event: EventLog[R]): AsyncCallback[EventLog[R]] = ???
    override def search(filter: EventLogFilter): AsyncCallback[List[EventLog[Json]]] = AsyncCallback.pure(Nil)
    override def replaySession(
      sessionId: AgentSessionId,
      limit:     Int,
    ): AsyncCallback[List[EventLog[Json]]] =
      AsyncCallback.pure(Nil)
  }

  override val artifact: ArtifactRepository[AsyncCallback] = new ArtifactRepository[AsyncCallback] {
    override def getById(id:         ArtifactId):      AsyncCallback[Option[Artifact]] = AsyncCallback.pure(None)
    override def search(s:           ArtifactSearch):  AsyncCallback[List[Artifact]] = AsyncCallback.pure(Nil)
    override def upsert(artifact:    Artifact):        AsyncCallback[Artifact] = ???
    override def delete(id:          ArtifactId):      AsyncCallback[Long] = AsyncCallback.pure(0L)
    override def getWorkspace(id:    WorkspaceId):     AsyncCallback[Option[Workspace]] = AsyncCallback.pure(None)
    override def searchWorkspaces(s: WorkspaceSearch): AsyncCallback[List[Workspace]] = AsyncCallback.pure(Nil)
    override def upsertWorkspace(ws: Workspace):       AsyncCallback[Workspace] = ???
  }

  override val setting: ServerSettingsRepository[AsyncCallback] = new ServerSettingsRepository[AsyncCallback] {
    override def get(key: String): AsyncCallback[Option[Json]] = AsyncCallback.pure(None)
    override def set(
      key:   String,
      value: Json,
    ): AsyncCallback[Unit] = AsyncCallback.pure(())

    override def serverPersonality(): AsyncCallback[Option[Personality]] =
      adapter
        .asyncCalibanCallWithAuth(JorlanClient.Queries.serverPersonality(JorlanClient.Personality.view))
        .map(_.map(toPersonality))

    override def updatePersonality(
      name:      String,
      formality: Formality,
      languages: List[String],
      expertise: List[String],
      prompt:    String,
    ): AsyncCallback[Option[Personality]] =
      adapter
        .asyncCalibanCallWithAuth(
          JorlanClient.Mutations.updatePersonality(name, formality, languages, expertise, prompt)(
            JorlanClient.Personality.view,
          ),
        )
        .map(_.map(toPersonality))
  }

  override val extCredential: ExternalCredentialRepository[AsyncCallback] =
    new ExternalCredentialRepository[AsyncCallback] {
      override def upsert(
        userId:        UserId,
        provider:      String,
        encryptedData: Json,
        expiresAt:     Option[Instant],
        scopes:        Option[String],
      ): AsyncCallback[Unit] = AsyncCallback.pure(())
      override def find(
        userId:   UserId,
        provider: String,
      ): AsyncCallback[Option[ExternalCredential]] =
        AsyncCallback.pure(None)
      override def delete(
        userId:   UserId,
        provider: String,
      ):                                       AsyncCallback[Unit] = AsyncCallback.pure(())
      override def listByUser(userId: UserId): AsyncCallback[List[ExternalCredential]] = AsyncCallback.pure(Nil)
      override def listOAuthProviders():       AsyncCallback[List[String]] =
        adapter.asyncCalibanCallWithAuth(JorlanClient.Queries.listOAuthProviders).map(_.getOrElse(Nil))
      override def startOAuth(provider: String): AsyncCallback[Option[String]] =
        adapter.asyncCalibanCallWithAuth(
          JorlanClient.Mutations.startOAuth(provider)(JorlanClient.OAuthStartResult.authUrl),
        )
      override def revokeOAuth(provider: String): AsyncCallback[Unit] =
        adapter.asyncCalibanCallWithAuth(JorlanClient.Mutations.revokeOAuth(provider)).map(_ => ())
      override def oauthStatus(provider: String): AsyncCallback[Option[OAuthStatus]] =
        adapter
          .asyncCalibanCallWithAuth(JorlanClient.Queries.oauthStatus(provider)(JorlanClient.OAuthStatus.view))
          .map(_.map(v => OAuthStatus(v.connected, v.expiresAt)))
    }

  // ── Sub-repo: ServerInfo ──────────────────────────────────────────────────

  override val serverInfo: ServerInfoRepository[AsyncCallback] = new ServerInfoRepository[AsyncCallback] {
    override def statusCheck(): AsyncCallback[Json] = AsyncCallback.pure(Json.Null)
  }

  // ── Subscription helpers ───────────────────────────────────────────────────

  def subscribeToAgentStream(
    sessionId:      AgentSessionId,
    onData:         ResponseChunk => Callback,
    onConnected:    Callback = Callback.empty,
    onDisconnected: Callback = Callback.empty,
    onClientError:  Throwable => Callback = _ => Callback.empty,
  ): WebSocketHandler =
    adapter.makeWebSocketClient(
      webSocket = None,
      query = JorlanClient.Subscriptions.agentResponseStream(sessionId)(JorlanClient.ResponseChunk.view),
      operationId = s"chat-stream-${sessionId.value}",
      socketConnectionId = s"chat-${sessionId.value}",
      onData = {
        (
          _,
          dataOpt,
        ) =>
          dataOpt.flatten.fold(Callback.empty) { v =>
            onData(ResponseChunk(v.sessionId, v.content, v.finished, v.isError))
          }
      },
      onConnected = (
        _,
        _,
      ) => onConnected,
      onDisconnected = (
        _,
        _,
      ) => onDisconnected,
      onClientError = onClientError,
    )

  def subscribeToApprovals(
    onData:         ApprovalRequest => Callback,
    onConnected:    Callback = Callback.empty,
    onDisconnected: Callback = Callback.empty,
    onClientError:  Throwable => Callback = _ => Callback.empty,
  ): WebSocketHandler =
    adapter.makeWebSocketClient(
      webSocket = None,
      query = JorlanClient.Subscriptions.approvalNotifications(JorlanClient.ApprovalRequest.view),
      operationId = "approvals-subscription",
      socketConnectionId = "approvals",
      onData = {
        (
          _,
          dataOpt,
        ) =>
          dataOpt.flatten.fold(Callback.empty)(v => onData(toApprovalRequest(v)))
      },
      onConnected = (
        _,
        _,
      ) => onConnected,
      onDisconnected = (
        _,
        _,
      ) => onDisconnected,
      onClientError = onClientError,
    )

  def subscribeToEventLog(
    onData:         EventLog[Json] => Callback,
    onConnected:    Callback = Callback.empty,
    onDisconnected: Callback = Callback.empty,
    onClientError:  Throwable => Callback = _ => Callback.empty,
  ): WebSocketHandler =
    adapter.makeWebSocketClient(
      webSocket = None,
      query = JorlanClient.Subscriptions.eventLogTail(JorlanClient.EventLogJson.view),
      operationId = "event-log-tail",
      socketConnectionId = "event-log",
      onData = {
        (
          _,
          dataOpt,
        ) =>
          dataOpt.flatten.fold(Callback.empty)(v => onData(toEventLog(v)))
      },
      onConnected = (
        _,
        _,
      ) => onConnected,
      onDisconnected = (
        _,
        _,
      ) => onDisconnected,
      onClientError = onClientError,
    )

  // ── Conversion helpers ────────────────────────────────────────────────────

  private def toAgentSession(v: JorlanClient.AgentSession.AgentSessionView): AgentSession =
    AgentSession(
      id = v.id,
      agentId = v.agentId,
      userId = v.userId,
      workspaceId = v.workspaceId,
      status = v.status,
      modelId = v.modelId,
      chatRef = v.chatRef,
      createdAt = v.createdAt,
      updatedAt = v.updatedAt,
    )

  private def toMemoryRecord(v: JorlanClient.MemoryRecord.MemoryRecordView): MemoryRecord =
    MemoryRecord(
      id = v.id,
      scope = v.scope,
      userId = v.userId,
      workspaceId = v.workspaceId,
      agentId = v.agentId,
      recordKey = v.recordKey,
      value = Json.Str(v.value),
      ttl = v.ttl,
      createdAt = v.createdAt,
      updatedAt = v.updatedAt,
      importance = v.importance,
    )

  private def toModelInfo(v: JorlanClient.ModelInfo.ModelInfoView): ModelInfo =
    ModelInfo(
      id = v.id,
      provider = v.provider,
      contextWindow = v.contextWindow,
      supportsStreaming = v.supportsStreaming,
    )

  private def toCapabilityGrant(v: JorlanClient.CapabilityGrant.CapabilityGrantView): CapabilityGrant =
    CapabilityGrant(
      id = v.id,
      capability = v.capability,
      scopeJson = v.scopeJson,
      granteeId = v.granteeId,
      grantorId = v.grantorId,
      approvalMode = v.approvalMode,
      expiresAt = v.expiresAt,
      resourceConstraints = v.resourceConstraints,
      createdAt = v.createdAt,
    )

  private def toApprovalRequest(v: JorlanClient.ApprovalRequest.ApprovalRequestView): ApprovalRequest =
    ApprovalRequest(
      id = v.id,
      capability = v.capability,
      scopeJson = v.scopeJson,
      agentId = v.agentId,
      requestorUserId = v.requestorUserId,
      sessionId = v.sessionId,
      riskClass = v.riskClass,
      status = v.status,
      createdAt = v.createdAt,
      expiresAt = v.expiresAt,
    )

  private def toPersonality(v: JorlanClient.Personality.PersonalityView): Personality =
    Personality(
      name = v.name,
      formality = v.formality,
      languages = v.languages,
      expertise = v.expertise,
      prompt = v.prompt,
    )

  private def toSkillInfo(
    v: JorlanClient.SkillInfo.SkillInfoView[JorlanClient.SkillToolInfo.SkillToolInfoView],
  ): SkillInfo =
    SkillInfo(
      name = v.name,
      tier = SkillTier.valueOf(v.tier),
      tools = v.tools.map(t => SkillToolInfo(t.name, t.description, t.requiredCapabilities, t.examplePrompts)),
    )

  private def toUser(v: JorlanClient.User.UserView): User =
    User(
      id = v.id,
      displayName = v.displayName,
      email = v.email,
      createdAt = v.createdAt,
      updatedAt = v.updatedAt,
      active = v.active,
    )

  private def toRole(v: JorlanClient.Role.RoleView): Role =
    Role(id = v.id, name = v.name, description = v.description)

  private def toChannelIdentity(v: JorlanClient.ChannelIdentity.ChannelIdentityView): ChannelIdentity =
    ChannelIdentity(
      id = ChannelIdentityId(v.id.toLong),
      userId = v.userId,
      channelType = v.channelType,
      channelUserId = v.channelUserId,
      verified = v.verified,
      providerData = None,
      createdAt = v.createdAt,
    )

  private def toEventLog(v: JorlanClient.EventLogJson.EventLogJsonView): EventLog[Json] =
    EventLog(
      id = v.id,
      eventType = v.eventType,
      actorId = v.actorId,
      agentId = v.agentId,
      sessionId = v.sessionId,
      resource = None,
      payloadJson = v.payloadJson.map { s =>
        Json.decoder.decodeJson(s).getOrElse(Json.Str(s))
      },
      occurredAt = v.occurredAt,
    )

}
