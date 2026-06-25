/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell.client

import jorlan.*
import jorlan.graphql.client.JorlanClient
import jorlan.graphql.client.JorlanClientDecoders.given
import jorlan.service.{CheckpointPolicyConfig, EventLogFilter}
import zio.*
import zio.json.ast.Json

import java.time.Instant
import scala.language.unsafeNulls

/** Shell-side implementation of [[Repositories]] backed by GraphQL calls.
  *
  * Sub-repos provide data-access operations; application-level operations (createSession, submitMessage, etc.) are
  * additional methods. All GQL view → domain conversions are private to [[ZIOClientRepositoriesLive]].
  */
trait ZIOClientRepositories extends Repositories[[A] =>> IO[String, A]] {

  def requestCheckpoint(sessionId: AgentSessionId): IO[String, Boolean]
  def getCheckpointPolicy:                          IO[String, CheckpointPolicyConfig]
  def updateCheckpointPolicy(
    onSessionEnd:         Option[Boolean] = None,
    onUserRequest:        Option[Boolean] = None,
    timedIntervalTurns:   Option[Option[Int]] = None,
    beforeExternalEffect: Option[Boolean] = None,
  ): IO[String, CheckpointPolicyConfig]

}

object ZIOClientRepositories {

  val live: URLayer[GraphQLClient, ZIOClientRepositories] = ZLayer.fromFunction(ZIOClientRepositoriesLive(_))

}

private class ZIOClientRepositoriesLive(gqlClient: GraphQLClient) extends ZIOClientRepositories {

  // ── Sub-repo: Agent ────────────────────────────────────────────────────────

  override val agent: AgentRepository[[A] =>> IO[String, A]] = new AgentRepository[[A] =>> IO[String, A]] {

    override def searchSessions(s: AgentSessionSearch): IO[String, List[AgentSession]] =
      gqlClient
        .run(JorlanClient.Queries.listSessions()(JorlanClient.AgentSession.view))
        .map(_.getOrElse(List.empty).map(toAgentSession))

    override def getById(id:            AgentId):         IO[String, Option[Agent]] = ZIO.succeed(None)
    override def search(s:              AgentSearch):     IO[String, List[Agent]] = ZIO.succeed(List.empty)
    override def upsert(a:              Agent):           IO[String, Agent] = ZIO.fail("not implemented")
    override def delete(id:             AgentId):         IO[String, Long] = ZIO.fail("not implemented")
    override def getSession(id:         AgentSessionId):  IO[String, Option[AgentSession]] = ZIO.succeed(None)
    override def upsertSession(session: AgentSession):    IO[String, AgentSession] = ZIO.fail("not implemented")
    override def createSession(modelId: Option[ModelId]): IO[String, Option[AgentSession]] =
      gqlClient
        .run(JorlanClient.Mutations.createSession(modelId)(JorlanClient.AgentSession.view))
        .map(_.map(toAgentSession))
    override def terminateSession(sessionId: AgentSessionId): IO[String, Unit] =
      gqlClient.execute(s"mutation { terminateSession(value: ${sessionId.value}) }").unit
    override def availableModels(): IO[String, List[ModelInfo]] =
      gqlClient
        .run(JorlanClient.Queries.availableModels(JorlanClient.ModelInfo.view))
        .map(_.getOrElse(List.empty).map(toModelInfo))
    override def submitMessage(
      sessionId: AgentSessionId,
      content:   String,
    ): IO[String, Unit] =
      gqlClient.run(JorlanClient.Mutations.submitMessage(sessionId, content)).unit

  }

  // ── Sub-repo: Memory ───────────────────────────────────────────────────────

  override val memory: MemoryRepository[[A] =>> IO[String, A]] = new MemoryRepository[[A] =>> IO[String, A]] {

    override def search(s: MemorySearch): IO[String, List[MemoryRecord]] =
      gqlClient
        .run(JorlanClient.Queries.listMemory(s.scope, s.textSearch)(JorlanClient.MemoryRecord.view))
        .map(_.getOrElse(List.empty).map(toMemoryRecord))

    override def upsert(record: MemoryRecord): IO[String, MemoryRecord] = {
      val text = record.value match {
        case Json.Str(s) => s
        case other       => other.toString
      }
      gqlClient
        .run(JorlanClient.Mutations.storeMemory(record.recordKey, text, record.scope)(JorlanClient.MemoryRecord.view))
        .map(_.fold(record)(toMemoryRecord))
    }

    override def delete(id: MemoryRecordId): IO[String, Long] =
      gqlClient.run(JorlanClient.Mutations.forgetMemory(id)).map(b => if (b.getOrElse(false)) 1L else 0L)

    override def updateScope(
      id:    MemoryRecordId,
      scope: MemoryScope,
    ): IO[String, Long] = {
      val mutation = scope match {
        case MemoryScope.Shared  => JorlanClient.Mutations.markMemoryShared(id)(JorlanClient.MemoryRecord.view)
        case MemoryScope.Private => JorlanClient.Mutations.markMemoryPrivate(id)(JorlanClient.MemoryRecord.view)
        case _                   => JorlanClient.Mutations.markMemoryPrivate(id)(JorlanClient.MemoryRecord.view)
      }
      gqlClient.run(mutation).map(o => if (o.isDefined) 1L else 0L)
    }

    override def getById(id: MemoryRecordId): IO[String, Option[MemoryRecord]] = ZIO.succeed(None)
    override def getByKey(
      key:     String,
      userId:  Option[UserId],
      agentId: Option[AgentId],
    ):                         IO[String, Option[MemoryRecord]] = ZIO.succeed(None)
    override def purgeExpired: IO[String, Long] = ZIO.succeed(0L)

  }

  // ── Sub-repo: Permission ───────────────────────────────────────────────────

  override val permission: PermissionRepository[[A] =>> IO[String, A]] =
    new PermissionRepository[[A] =>> IO[String, A]] {

      override def listPendingApprovals(userId: UserId): IO[String, List[ApprovalRequest]] =
        gqlClient
          .run(JorlanClient.Queries.listApprovals(JorlanClient.ApprovalRequest.view))
          .map(_.getOrElse(List.empty).map(toApprovalRequest))

      override def getUserRoleIds(userId: UserId): IO[String, List[RoleId]] = ZIO.succeed(List.empty)

      override def searchGrants(s: GrantSearch): IO[String, List[CapabilityGrant]] =
        (s.userId, s.roleId) match {
          case (Some(uid), _) =>
            gqlClient
              .run(JorlanClient.Queries.userCapabilityGrants(uid)(JorlanClient.CapabilityGrant.view))
              .map(_.getOrElse(List.empty).map(toCapabilityGrant))
          case (_, Some(rid)) =>
            gqlClient
              .run(JorlanClient.Queries.roleCapabilityGrants(rid)(JorlanClient.CapabilityGrant.view))
              .map(_.getOrElse(List.empty).map(toCapabilityGrant))
          case _ => ZIO.succeed(List.empty)
        }

      override def listCapabilities(): IO[String, List[CapabilityGrant]] =
        gqlClient
          .run(JorlanClient.Queries.listCapabilities(JorlanClient.CapabilityGrant.view))
          .map(_.getOrElse(List.empty).map(toCapabilityGrant))
      override def listApprovals(): IO[String, List[ApprovalRequest]] =
        gqlClient
          .run(JorlanClient.Queries.listApprovals(JorlanClient.ApprovalRequest.view))
          .map(_.getOrElse(List.empty).map(toApprovalRequest))
      override def decideApproval(
        id:       ApprovalRequestId,
        approved: Boolean,
        note:     Option[String] = None,
      ): IO[String, Boolean] =
        gqlClient.run(JorlanClient.Mutations.decideApproval(id, approved, note)).map(_.getOrElse(false))
      override def getRole(id: RoleId):        IO[String, Option[Role]] = ZIO.succeed(None)
      override def searchRoles(s: RoleSearch): IO[String, List[Role]] =
        s.userId match {
          case Some(uid) =>
            gqlClient
              .run(JorlanClient.Queries.roles(uid)(JorlanClient.Role.view))
              .map(_.getOrElse(List.empty).map(toRole))
          case None =>
            gqlClient
              .run(JorlanClient.Queries.allRoles()(JorlanClient.Role.view))
              .map(_.getOrElse(List.empty).map(toRole))
        }
      override def upsertRole(role: Role): IO[String, Role] =
        gqlClient
          .run(JorlanClient.Mutations.createRole(role.name, role.description)(JorlanClient.Role.view))
          .flatMap(r => ZIO.fromOption(r).orElseFail("createRole returned no role"))
          .map(toRole)
      override def deleteRole(id: RoleId): IO[String, Long] = ZIO.succeed(0L)
      override def assignRole(
        userId: UserId,
        roleId: RoleId,
      ): IO[String, Unit] =
        gqlClient.run(JorlanClient.Mutations.assignRole(userId, roleId)).unit
      override def removeRole(
        userId: UserId,
        roleId: RoleId,
      ): IO[String, Unit] =
        gqlClient.run(JorlanClient.Mutations.revokeRole(userId, roleId)).unit
      override def searchPermissions(s:   PermissionSearch):      IO[String, List[Permission]] = ZIO.succeed(List.empty)
      override def upsertPermission(perm: Permission):            IO[String, Permission] = ZIO.fail("not implemented")
      override def deletePermission(id:   PermissionId):          IO[String, Long] = ZIO.succeed(0L)
      override def upsertCapabilityGrant(grant: CapabilityGrant): IO[String, CapabilityGrant] = {
        val mutation = grant.granteeType match {
          case GranteeType.Role =>
            JorlanClient.Mutations.grantCapabilityToRole(
              RoleId(grant.granteeId),
              grant.capability,
              grant.approvalMode,
            )(JorlanClient.CapabilityGrant.view)
          case GranteeType.User =>
            JorlanClient.Mutations.grantCapability(
              UserId(grant.granteeId),
              grant.capability,
              grant.approvalMode,
            )(JorlanClient.CapabilityGrant.view)
        }
        gqlClient
          .run(mutation)
          .flatMap(r => ZIO.fromOption(r).orElseFail("grantCapability returned nothing"))
          .map(toCapabilityGrant)
      }
      override def revokeGrant(id: CapabilityGrantId): IO[String, Long] =
        gqlClient
          .run(JorlanClient.Mutations.revokeCapabilityGrant(id))
          .map(r => if (r.getOrElse(false)) 1L else 0L)
      override def createApprovalRequest(req: ApprovalRequest): IO[String, ApprovalRequest] =
        ZIO.fail("not implemented")
      override def cancelApprovalRequest(id: ApprovalRequestId):       IO[String, Long] = ZIO.succeed(0L)
      override def expireApprovalRequest(id: ApprovalRequestId):       IO[String, Long] = ZIO.succeed(0L)
      override def expireAllStaleApprovalRequests():                   IO[String, Long] = ZIO.succeed(0L)
      override def recordApprovalDecision(decision: ApprovalDecision): IO[String, ApprovalDecision] =
        ZIO.fail("not implemented")
      override def getApprovalRequest(id: ApprovalRequestId): IO[String, Option[ApprovalRequest]] = ZIO.succeed(None)
      override def getExpiredApprovalRequests: IO[String, List[ApprovalRequest]] = ZIO.succeed(List.empty)
      override def getGrantsForCapability(
        userId:     UserId,
        capability: CapabilityName,
      ): IO[String, List[CapabilityGrant]] = ZIO.succeed(List.empty)
      override def hasDirectPermission(
        userId:   UserId,
        resource: String,
        action:   String,
      ): IO[String, Boolean] =
        ZIO.succeed(false)
      override def hasRolePermission(
        userId:   UserId,
        resource: String,
        action:   String,
      ): IO[String, Boolean] =
        ZIO.succeed(false)
      override def findApprovedRequest(
        capability: CapabilityName,
        userId:     UserId,
        sessionId:  Option[AgentSessionId],
      ): IO[String, Option[ApprovalRequest]] = ZIO.succeed(None)

    }

  // ── Sub-repo: Scheduler ────────────────────────────────────────────────────

  override val scheduler: SchedulerRepository[[A] =>> IO[String, A]] = new SchedulerRepository[[A] =>> IO[String, A]] {

    override def listJobs(
      agentId: Option[AgentId],
      limit:   Int,
    ): IO[String, List[SchedulerJob]] =
      gqlClient
        .run(JorlanClient.Queries.jobs(agentId)(JorlanClient.SchedulerJob.view))
        .map(_.getOrElse(List.empty).map(toSchedulerJob))

    override def searchTriggers(s: TriggerSearch): IO[String, List[SchedulerTrigger]] =
      gqlClient
        .run(JorlanClient.Queries.triggers(s.jobId)(JorlanClient.SchedulerTrigger.view))
        .map(_.getOrElse(List.empty).map(toSchedulerTrigger))

    override def getJob(id: SchedulerJobId): IO[String, Option[SchedulerJob]] =
      gqlClient
        .run(JorlanClient.Queries.job(id)(JorlanClient.SchedulerJob.view))
        .map(_.map(toSchedulerJob))
    override def getPendingJobs:               IO[String, List[SchedulerJob]] = ZIO.succeed(List.empty)
    override def upsertJob(job: SchedulerJob): IO[String, SchedulerJob] = ZIO.fail("not implemented")
    override def updateJobConfig(
      id:              SchedulerJobId,
      name:            String,
      prompt:          String,
      maxRetries:      Int,
      backoffSeconds:  Int,
      backoffPolicy:   RetryBackoffPolicy,
      missedRunPolicy: MissedRunPolicy,
    ):                                          IO[String, Boolean] = ZIO.fail("not implemented")
    override def deleteJob(id: SchedulerJobId): IO[String, Boolean] =
      gqlClient.run(JorlanClient.Mutations.deleteJob(id)).map(_.getOrElse(false))
    override def pauseJob(id: SchedulerJobId): IO[String, Boolean] =
      gqlClient.run(JorlanClient.Mutations.pauseJob(id)).map(_.getOrElse(false))
    override def resumeJob(id: SchedulerJobId): IO[String, Boolean] =
      gqlClient.run(JorlanClient.Mutations.resumeJob(id)).map(_.getOrElse(false))
    override def cancelJob(id: SchedulerJobId): IO[String, Boolean] =
      gqlClient.run(JorlanClient.Mutations.cancelJob(id)).map(_.getOrElse(false))
    override def triggerNow(id: SchedulerJobId): IO[String, Boolean] =
      gqlClient.run(JorlanClient.Mutations.triggerNow(id)).map(_.getOrElse(false))
    override def upsertTrigger(t:  SchedulerTrigger):   IO[String, SchedulerTrigger] = ZIO.fail("not implemented")
    override def deleteTrigger(id: SchedulerTriggerId): IO[String, Long] = ZIO.succeed(0L)
    override def claimJob(
      id:              SchedulerJobId,
      workerId:        String,
      now:             Instant,
      leaseTtlSeconds: Int,
    ): IO[String, Boolean] =
      ZIO.succeed(false)
    override def releaseJob(
      id:         SchedulerJobId,
      status:     JobStatus,
      resultJson: Option[String],
      finishedAt: Instant,
    ):                                             IO[String, Unit] = ZIO.unit
    override def expireLeases(olderThan: Instant): IO[String, Long] = ZIO.succeed(0L)

  }

  // ── Sub-repo: User ─────────────────────────────────────────────────────────

  override val user: UserRepository[[A] =>> IO[String, A]] = new UserRepository[[A] =>> IO[String, A]] {

    override def search(s: UserSearch): IO[String, List[User]] =
      gqlClient
        .run(
          JorlanClient.Queries.users(s.active, s.nameContains, Some(s.page), Some(s.pageSize))(JorlanClient.User.view),
        )
        .map(_.getOrElse(List.empty).map(toUser))

    override def getById(id: UserId): IO[String, Option[User]] =
      gqlClient
        .run(JorlanClient.Queries.user(id)(JorlanClient.User.view))
        .map(_.map(toUser))

    override def upsert(u: User): IO[String, User] =
      if (u.id == UserId.empty)
        gqlClient
          .run(JorlanClient.Mutations.createUser(u.displayName, Some(u.email))(JorlanClient.User.view))
          .flatMap(r => ZIO.fromOption(r).orElseFail("createUser returned no user"))
          .map(toUser)
      else
        gqlClient
          .run(JorlanClient.Mutations.updateUser(u.id, u.displayName, Some(u.email), u.active)(JorlanClient.User.view))
          .flatMap(r => ZIO.fromOption(r).orElseFail("updateUser returned no user"))
          .map(toUser)

    override def deactivate(id: UserId): IO[String, Long] =
      gqlClient
        .run(JorlanClient.Mutations.deactivateUser(id))
        .map(r => if (r.getOrElse(false)) 1L else 0L)
    override def getChannelIdentities(userId: UserId): IO[String, List[ChannelIdentity]] =
      gqlClient
        .run(JorlanClient.Queries.userChannelIdentities(userId)(JorlanClient.ChannelIdentity.view))
        .map(_.getOrElse(List.empty).map(toChannelIdentity))
    override def upsertChannelIdentity(ci: ChannelIdentity): IO[String, ChannelIdentity] =
      gqlClient
        .run(
          JorlanClient.Mutations.linkChannelIdentity(ci.userId, ci.channelType.toString, ci.channelUserId)(
            JorlanClient.ChannelIdentity.view,
          ),
        )
        .flatMap(r => ZIO.fromOption(r).orElseFail("linkChannelIdentity returned nothing"))
        .map(toChannelIdentity)
    override def deleteChannelIdentity(id: ChannelIdentityId): IO[String, Long] =
      gqlClient
        .run(JorlanClient.Mutations.unlinkChannelIdentity(id.value.toString))
        .map(r => if (r.getOrElse(false)) 1L else 0L)
    override def login(
      email:    String,
      password: String,
    ):                                       IO[String, Option[User]] = ZIO.succeed(None)
    override def userByEmail(email: String): IO[String, Option[User]] = ZIO.succeed(None)
    override def changePassword(
      id:          UserId,
      newPassword: String,
    ): IO[String, Unit] = ZIO.unit
    override def userByChannelIdentity(
      channelType:   ChannelType,
      channelUserId: String,
    ):                                                  IO[String, Option[User]] = ZIO.succeed(None)
    override def findContacts(nameOpt: Option[String]): IO[String, Json] = {
      val name = nameOpt.getOrElse("")
      val query =
        s"""query { contacts(name: ${'"'}$name${'"'}) { userId displayName identities { channelType channelUserId } } }"""
      gqlClient.execute(query)
    }

  }

  // ── Sub-repos: Stubs ───────────────────────────────────────────────────────

  override val conversation: ConversationRepository[[A] =>> IO[String, A]] =
    new ConversationRepository[[A] =>> IO[String, A]] {
      override def getById(id:          ConversationId):     IO[String, Option[Conversation]] = ZIO.succeed(None)
      override def search(s:            ConversationSearch): IO[String, List[Conversation]] = ZIO.succeed(List.empty)
      override def create(conversation: Conversation):       IO[String, Conversation] = ZIO.fail("not implemented")
      override def searchMessages(s:    MessageSearch):      IO[String, List[Message]] = ZIO.succeed(List.empty)
      override def addMessage(message:  Message):            IO[String, Message] = ZIO.fail("not implemented")
    }

  override val skill: SkillRepository[[A] =>> IO[String, A]] = new SkillRepository[[A] =>> IO[String, A]] {
    override def getById(id:         SkillId):             IO[String, Option[SkillRecord]] = ZIO.succeed(None)
    override def search(s:           SkillSearch):         IO[String, List[SkillRecord]] = ZIO.succeed(List.empty)
    override def searchByTier(tiers: List[SkillTier]):     IO[String, List[SkillRecord]] = ZIO.succeed(List.empty)
    override def upsert(skill:       SkillRecord):         IO[String, SkillRecord] = ZIO.fail("not implemented")
    override def getVersion(id:      SkillVersionId):      IO[String, Option[SkillVersion]] = ZIO.succeed(None)
    override def searchVersions(s:   SkillVersionSearch):  IO[String, List[SkillVersion]] = ZIO.succeed(List.empty)
    override def upsertVersion(v:    SkillVersion):        IO[String, SkillVersion] = ZIO.fail("not implemented")
    override def upsertVersionStatus(
      id:         SkillVersionId,
      status:     SkillStatus,
      reviewNote: Option[String],
    ): IO[String, Unit] = ZIO.unit
    override def getVersionWithSkillName(id: SkillVersionId): IO[String, Option[(SkillVersion, String)]] =
      ZIO.succeed(None)
    override def getConnector(id:    ConnectorInstanceId): IO[String, Option[ConnectorInstance]] = ZIO.succeed(None)
    override def searchConnectors(s: ConnectorSearch):     IO[String, List[ConnectorInstance]] = ZIO.succeed(List.empty)
    override def upsertConnector(ci: ConnectorInstance):   IO[String, ConnectorInstance] = ZIO.fail("not implemented")
    override def listSkills():                             IO[String, List[SkillInfo]] =
      gqlClient
        .run(JorlanClient.Queries.skills(JorlanClient.SkillInfo.view(JorlanClient.SkillToolInfo.view)))
        .map(_.getOrElse(List.empty).map(toSkillInfo))
    override def invokeTool(
      toolName: String,
      argsJson: String,
    ): IO[String, Option[String]] =
      gqlClient.run(JorlanClient.Mutations.invokeTool(toolName, argsJson))
    override def enableSkill(name: String): IO[String, Unit] =
      gqlClient.run(JorlanClient.Mutations.enableSkill(name)).unit
    override def disableSkill(name: String): IO[String, Unit] =
      gqlClient.run(JorlanClient.Mutations.disableSkill(name)).unit
    override def getSkillConfig(name: String): IO[String, Option[String]] =
      gqlClient.run(JorlanClient.Queries.skillConfig(name))
    override def updateSkillConfig(
      name:       String,
      configJson: String,
    ): IO[String, Boolean] =
      gqlClient.run(JorlanClient.Mutations.updateSkillConfig(name, configJson)).map(_.getOrElse(false))
  }

  override val eventLog: EventLogRepository[[A] =>> IO[String, A]] = new EventLogRepository[[A] =>> IO[String, A]] {
    override def append[R: zio.json.JsonEncoder](event: EventLog[R]): IO[String, EventLog[R]] =
      ZIO.fail("not implemented")
    override def search(filter: EventLogFilter): IO[String, List[EventLog[Json]]] = ZIO.succeed(List.empty)
    override def replaySession(
      sessionId: AgentSessionId,
      limit:     Int,
    ): IO[String, List[EventLog[Json]]] = ZIO.succeed(List.empty)
  }

  override val artifact: ArtifactRepository[[A] =>> IO[String, A]] = new ArtifactRepository[[A] =>> IO[String, A]] {
    override def getById(id:         ArtifactId):      IO[String, Option[Artifact]] = ZIO.succeed(None)
    override def search(s:           ArtifactSearch):  IO[String, List[Artifact]] = ZIO.succeed(List.empty)
    override def upsert(artifact:    Artifact):        IO[String, Artifact] = ZIO.fail("not implemented")
    override def delete(id:          ArtifactId):      IO[String, Long] = ZIO.succeed(0L)
    override def getWorkspace(id:    WorkspaceId):     IO[String, Option[Workspace]] = ZIO.succeed(None)
    override def searchWorkspaces(s: WorkspaceSearch): IO[String, List[Workspace]] = ZIO.succeed(List.empty)
    override def upsertWorkspace(ws: Workspace):       IO[String, Workspace] = ZIO.fail("not implemented")
  }

  override val setting: ServerSettingsRepository[[A] =>> IO[String, A]] =
    new ServerSettingsRepository[[A] =>> IO[String, A]] {
      override def get(key: String): IO[String, Option[Json]] = ZIO.succeed(None)
      override def set(
        key:   String,
        value: Json,
      ): IO[String, Unit] = ZIO.unit

      override def serverPersonality(): IO[String, Option[Personality]] =
        gqlClient
          .run(JorlanClient.Queries.serverPersonality(JorlanClient.Personality.view))
          .map(_.map(toPersonality))

      override def updatePersonality(
        name:      String,
        formality: Formality,
        languages: List[String],
        expertise: List[String],
        prompt:    String,
      ): IO[String, Option[Personality]] =
        gqlClient
          .run(
            JorlanClient.Mutations.updatePersonality(name, formality, languages, expertise, prompt)(
              JorlanClient.Personality.view,
            ),
          ).map(_.map(toPersonality))
    }

  override val extCredential: ExternalCredentialRepository[[A] =>> IO[String, A]] =
    new ExternalCredentialRepository[[A] =>> IO[String, A]] {
      override def upsert(
        userId:        UserId,
        provider:      String,
        encryptedData: Json,
        expiresAt:     Option[Instant],
        scopes:        Option[String],
      ): IO[String, Unit] = ZIO.unit
      override def find(
        userId:   UserId,
        provider: String,
      ): IO[String, Option[ExternalCredential]] = ZIO.succeed(None)
      override def delete(
        userId:   UserId,
        provider: String,
      ):                                       IO[String, Unit] = ZIO.unit
      override def listByUser(userId: UserId): IO[String, List[ExternalCredential]] = ZIO.succeed(List.empty)
      override def listOAuthProviders():       IO[String, List[String]] =
        gqlClient.run(JorlanClient.Queries.listOAuthProviders).map(_.getOrElse(List.empty))
      override def oauthStatus(provider: String): IO[String, Option[OAuthStatus]] =
        gqlClient
          .run(JorlanClient.Queries.oauthStatus(provider)(JorlanClient.OAuthStatus.view))
          .map(_.map(v => OAuthStatus(v.connected, v.expiresAt)))
      override def startOAuth(provider: String): IO[String, Option[String]] =
        gqlClient.run(JorlanClient.Mutations.startOAuth(provider)(JorlanClient.OAuthStartResult.authUrl))
      override def revokeOAuth(provider: String): IO[String, Unit] =
        gqlClient.run(JorlanClient.Mutations.revokeOAuth(provider)).unit
    }

  // ── Sub-repo: ServerInfo ──────────────────────────────────────────────────

  override val serverInfo: ServerInfoRepository[[A] =>> IO[String, A]] =
    new ServerInfoRepository[[A] =>> IO[String, A]] {
      override def statusCheck(): IO[String, Json] = gqlClient.execute("{ __typename }")
    }

  // ── Sub-repo: SkillIndex (client-side no-op; filtering is server-side) ────

  override val skillIndex: SkillIndexRepository[[A] =>> IO[String, A]] =
    new SkillIndexRepository[[A] =>> IO[String, A]] {
      override def upsert(
        skillId:    SkillId,
        keywords:   String,
        searchText: String,
      ): IO[String, Unit] = ZIO.unit
      override def search(
        query: String,
        limit: Int,
      ):                                                  IO[String, List[(SkillId, String)]] = ZIO.succeed(List.empty)
      override def removeBySkillId(skillId:     SkillId): IO[String, Unit] = ZIO.unit
      override def removeBySkillName(skillName: String):  IO[String, Unit] = ZIO.unit
      override def keepOnly(skillNames: Set[String]): IO[String, Unit] = ZIO.unit
    }

  // ── Conversion helpers ─────────────────────────────────────────────────────

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
      granteeType = v.granteeType,
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
      enabled = v.enabled,
      keywords = v.keywords,
      configKey = v.configKey,
      configJsModule = v.configJsModule,
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

  private def toUser(v: JorlanClient.User.UserView): User =
    User(
      id = v.id,
      displayName = v.displayName,
      email = v.email,
      createdAt = v.createdAt,
      updatedAt = v.updatedAt,
      active = v.active,
    )

  private def toSchedulerJob(v: JorlanClient.SchedulerJob.SchedulerJobView): SchedulerJob =
    SchedulerJob(
      id = v.id,
      agentId = v.agentId,
      userId = v.userId,
      skillId = v.skillId,
      name = v.name,
      prompt = v.prompt,
      inputJson = v.inputJson,
      status = v.status,
      scheduledAt = v.scheduledAt,
      startedAt = v.startedAt,
      finishedAt = v.finishedAt,
      resultJson = v.resultJson,
      maxRetries = v.maxRetries,
      retryCount = v.retryCount,
      backoffSeconds = v.backoffSeconds,
      backoffPolicy = v.backoffPolicy,
      missedRunPolicy = v.missedRunPolicy,
      leasedAt = v.leasedAt,
      leasedBy = v.leasedBy,
      createdAt = v.createdAt,
    )

  private def toSchedulerTrigger(v: JorlanClient.SchedulerTrigger.SchedulerTriggerView): SchedulerTrigger =
    SchedulerTrigger(
      id = v.id,
      jobId = v.jobId,
      triggerType = v.triggerType,
      expression = v.expression,
      enabled = v.enabled,
      createdAt = v.createdAt,
    )

  private def toPolicyConfig(
    v: JorlanClient.CheckpointPolicyConfig.CheckpointPolicyConfigView,
  ): jorlan.service.CheckpointPolicyConfig =
    jorlan.service.CheckpointPolicyConfig(
      onSessionEnd = v.onSessionEnd,
      onUserRequest = v.onUserRequest,
      timedIntervalTurns = v.timedIntervalTurns,
      beforeExternalEffect = v.beforeExternalEffect,
    )

  override def requestCheckpoint(sessionId: AgentSessionId): IO[String, Boolean] =
    gqlClient.run(JorlanClient.Mutations.requestCheckpoint(sessionId)).map(_.getOrElse(false))

  override def getCheckpointPolicy: IO[String, jorlan.service.CheckpointPolicyConfig] =
    gqlClient
      .run(JorlanClient.Queries.checkpointPolicy(JorlanClient.CheckpointPolicyConfig.view))
      .map(_.fold(jorlan.service.CheckpointPolicyConfig.default)(toPolicyConfig))

  override def updateCheckpointPolicy(
    onSessionEnd:         Option[Boolean] = None,
    onUserRequest:        Option[Boolean] = None,
    timedIntervalTurns:   Option[Option[Int]] = None,
    beforeExternalEffect: Option[Boolean] = None,
  ): IO[String, jorlan.service.CheckpointPolicyConfig] =
    getCheckpointPolicy.flatMap { current =>
      val updated = current.copy(
        onSessionEnd = onSessionEnd.getOrElse(current.onSessionEnd),
        onUserRequest = onUserRequest.getOrElse(current.onUserRequest),
        timedIntervalTurns = timedIntervalTurns.getOrElse(current.timedIntervalTurns),
        beforeExternalEffect = beforeExternalEffect.getOrElse(current.beforeExternalEffect),
      )
      gqlClient
        .run(
          JorlanClient.Mutations.updateCheckpointPolicy(
            onSessionEnd = updated.onSessionEnd,
            onUserRequest = updated.onUserRequest,
            timedIntervalTurns = updated.timedIntervalTurns,
            beforeExternalEffect = updated.beforeExternalEffect,
          )(JorlanClient.CheckpointPolicyConfig.view),
        )
        .map(_.fold(updated)(toPolicyConfig))
    }

}
