package jorlan.graphql.client

import caliban.client.CalibanClientError.DecodingError
import caliban.client.FieldBuilder._
import caliban.client._
import caliban.client.__Value._


import jorlan._
import JorlanClientDecoders.given

object JorlanClient {

  type ChannelIdentityId = String
        
type SkillVersionId = String
        
  sealed trait OAuthProvider extends scala.Product with scala.Serializable { def value: String }
          object OAuthProvider {
            case object Google extends OAuthProvider { val value: String = "Google" }
case object Discord extends OAuthProvider { val value: String = "Discord" }
case object Telegram extends OAuthProvider { val value: String = "Telegram" }

            implicit val decoder: ScalarDecoder[OAuthProvider] = {
              case __StringValue ("Google") => Right(OAuthProvider.Google)
case __StringValue ("Discord") => Right(OAuthProvider.Discord)
case __StringValue ("Telegram") => Right(OAuthProvider.Telegram)
              case other => Left(DecodingError(s"Can't build OAuthProvider from input $other"))
            }
            implicit val encoder: ArgEncoder[OAuthProvider] = {
              case OAuthProvider.Google => __EnumValue("Google")
case OAuthProvider.Discord => __EnumValue("Discord")
case OAuthProvider.Telegram => __EnumValue("Telegram")
            }

            val values: scala.collection.immutable.Vector[OAuthProvider] = scala.collection.immutable.Vector(Google, Discord, Telegram)
          }
       
sealed trait SkillStatus extends scala.Product with scala.Serializable { def value: String }
          object SkillStatus {
            case object Draft extends SkillStatus { val value: String = "Draft" }
case object Validated extends SkillStatus { val value: String = "Validated" }
case object PermissionReviewed extends SkillStatus { val value: String = "PermissionReviewed" }
case object SandboxTested extends SkillStatus { val value: String = "SandboxTested" }
case object AwaitingApproval extends SkillStatus { val value: String = "AwaitingApproval" }
case object Active extends SkillStatus { val value: String = "Active" }
case object Deprecated extends SkillStatus { val value: String = "Deprecated" }
case object Revoked extends SkillStatus { val value: String = "Revoked" }

            implicit val decoder: ScalarDecoder[SkillStatus] = {
              case __StringValue ("Draft") => Right(SkillStatus.Draft)
case __StringValue ("Validated") => Right(SkillStatus.Validated)
case __StringValue ("PermissionReviewed") => Right(SkillStatus.PermissionReviewed)
case __StringValue ("SandboxTested") => Right(SkillStatus.SandboxTested)
case __StringValue ("AwaitingApproval") => Right(SkillStatus.AwaitingApproval)
case __StringValue ("Active") => Right(SkillStatus.Active)
case __StringValue ("Deprecated") => Right(SkillStatus.Deprecated)
case __StringValue ("Revoked") => Right(SkillStatus.Revoked)
              case other => Left(DecodingError(s"Can't build SkillStatus from input $other"))
            }
            implicit val encoder: ArgEncoder[SkillStatus] = {
              case SkillStatus.Draft => __EnumValue("Draft")
case SkillStatus.Validated => __EnumValue("Validated")
case SkillStatus.PermissionReviewed => __EnumValue("PermissionReviewed")
case SkillStatus.SandboxTested => __EnumValue("SandboxTested")
case SkillStatus.AwaitingApproval => __EnumValue("AwaitingApproval")
case SkillStatus.Active => __EnumValue("Active")
case SkillStatus.Deprecated => __EnumValue("Deprecated")
case SkillStatus.Revoked => __EnumValue("Revoked")
            }

            val values: scala.collection.immutable.Vector[SkillStatus] = scala.collection.immutable.Vector(Draft, Validated, PermissionReviewed, SandboxTested, AwaitingApproval, Active, Deprecated, Revoked)
          }
       
  
  type AgentSession
object AgentSession {
  
final case class AgentSessionView(id: jorlan.AgentSessionId, agentId: jorlan.AgentId, userId: jorlan.UserId, workspaceId: scala.Option[jorlan.WorkspaceId], status: jorlan.SessionStatus, modelId: scala.Option[jorlan.ModelId], chatRef: scala.Option[String], createdAt: java.time.Instant, updatedAt: java.time.Instant)




type ViewSelection = SelectionBuilder[AgentSession, AgentSessionView]

def view: ViewSelection = (id ~ agentId ~ userId ~ workspaceId ~ status ~ modelId ~ chatRef ~ createdAt ~ updatedAt).map { case (id, agentId, userId, workspaceId, status, modelId, chatRef, createdAt, updatedAt) => AgentSessionView(id, agentId, userId, workspaceId, status, modelId, chatRef, createdAt, updatedAt) }

  def id: SelectionBuilder[AgentSession, jorlan.AgentSessionId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def agentId: SelectionBuilder[AgentSession, jorlan.AgentId] = _root_.caliban.client.SelectionBuilder.Field("agentId", Scalar())
  def userId: SelectionBuilder[AgentSession, jorlan.UserId] = _root_.caliban.client.SelectionBuilder.Field("userId", Scalar())
  def workspaceId: SelectionBuilder[AgentSession, scala.Option[jorlan.WorkspaceId]] = _root_.caliban.client.SelectionBuilder.Field("workspaceId", OptionOf(Scalar()))
  def status: SelectionBuilder[AgentSession, jorlan.SessionStatus] = _root_.caliban.client.SelectionBuilder.Field("status", Scalar())
  def modelId: SelectionBuilder[AgentSession, scala.Option[jorlan.ModelId]] = _root_.caliban.client.SelectionBuilder.Field("modelId", OptionOf(Scalar()))
  def chatRef: SelectionBuilder[AgentSession, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("chatRef", OptionOf(Scalar()))
  def createdAt: SelectionBuilder[AgentSession, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
  def updatedAt: SelectionBuilder[AgentSession, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("updatedAt", Scalar())
}


type ApprovalRequest
object ApprovalRequest {
  
final case class ApprovalRequestView(id: jorlan.ApprovalRequestId, capability: jorlan.CapabilityName, scopeJson: scala.Option[String], agentId: scala.Option[jorlan.AgentId], requestorUserId: jorlan.UserId, sessionId: scala.Option[jorlan.AgentSessionId], riskClass: jorlan.RiskClass, status: jorlan.ApprovalStatus, createdAt: java.time.Instant, expiresAt: scala.Option[java.time.Instant])




type ViewSelection = SelectionBuilder[ApprovalRequest, ApprovalRequestView]

def view: ViewSelection = (id ~ capability ~ scopeJson ~ agentId ~ requestorUserId ~ sessionId ~ riskClass ~ status ~ createdAt ~ expiresAt).map { case (id, capability, scopeJson, agentId, requestorUserId, sessionId, riskClass, status, createdAt, expiresAt) => ApprovalRequestView(id, capability, scopeJson, agentId, requestorUserId, sessionId, riskClass, status, createdAt, expiresAt) }

  def id: SelectionBuilder[ApprovalRequest, jorlan.ApprovalRequestId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def capability: SelectionBuilder[ApprovalRequest, jorlan.CapabilityName] = _root_.caliban.client.SelectionBuilder.Field("capability", Scalar())
  def scopeJson: SelectionBuilder[ApprovalRequest, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("scopeJson", OptionOf(Scalar()))
  def agentId: SelectionBuilder[ApprovalRequest, scala.Option[jorlan.AgentId]] = _root_.caliban.client.SelectionBuilder.Field("agentId", OptionOf(Scalar()))
  def requestorUserId: SelectionBuilder[ApprovalRequest, jorlan.UserId] = _root_.caliban.client.SelectionBuilder.Field("requestorUserId", Scalar())
  def sessionId: SelectionBuilder[ApprovalRequest, scala.Option[jorlan.AgentSessionId]] = _root_.caliban.client.SelectionBuilder.Field("sessionId", OptionOf(Scalar()))
  def riskClass: SelectionBuilder[ApprovalRequest, jorlan.RiskClass] = _root_.caliban.client.SelectionBuilder.Field("riskClass", Scalar())
  def status: SelectionBuilder[ApprovalRequest, jorlan.ApprovalStatus] = _root_.caliban.client.SelectionBuilder.Field("status", Scalar())
  def createdAt: SelectionBuilder[ApprovalRequest, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
  def expiresAt: SelectionBuilder[ApprovalRequest, scala.Option[java.time.Instant]] = _root_.caliban.client.SelectionBuilder.Field("expiresAt", OptionOf(Scalar()))
}


type CapabilityGrant
object CapabilityGrant {
  
final case class CapabilityGrantView(id: jorlan.CapabilityGrantId, capability: jorlan.CapabilityName, scopeJson: scala.Option[String], granteeId: Long, granteeType: jorlan.GranteeType, grantorId: scala.Option[jorlan.UserId], approvalMode: jorlan.ApprovalMode, expiresAt: scala.Option[java.time.Instant], resourceConstraints: scala.Option[String], createdAt: java.time.Instant)




type ViewSelection = SelectionBuilder[CapabilityGrant, CapabilityGrantView]

def view: ViewSelection = (id ~ capability ~ scopeJson ~ granteeId ~ granteeType ~ grantorId ~ approvalMode ~ expiresAt ~ resourceConstraints ~ createdAt).map { case (id, capability, scopeJson, granteeId, granteeType, grantorId, approvalMode, expiresAt, resourceConstraints, createdAt) => CapabilityGrantView(id, capability, scopeJson, granteeId, granteeType, grantorId, approvalMode, expiresAt, resourceConstraints, createdAt) }

  def id: SelectionBuilder[CapabilityGrant, jorlan.CapabilityGrantId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def capability: SelectionBuilder[CapabilityGrant, jorlan.CapabilityName] = _root_.caliban.client.SelectionBuilder.Field("capability", Scalar())
  def scopeJson: SelectionBuilder[CapabilityGrant, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("scopeJson", OptionOf(Scalar()))
  def granteeId: SelectionBuilder[CapabilityGrant, Long] = _root_.caliban.client.SelectionBuilder.Field("granteeId", Scalar())
  def granteeType: SelectionBuilder[CapabilityGrant, jorlan.GranteeType] = _root_.caliban.client.SelectionBuilder.Field("granteeType", Scalar())
  def grantorId: SelectionBuilder[CapabilityGrant, scala.Option[jorlan.UserId]] = _root_.caliban.client.SelectionBuilder.Field("grantorId", OptionOf(Scalar()))
  def approvalMode: SelectionBuilder[CapabilityGrant, jorlan.ApprovalMode] = _root_.caliban.client.SelectionBuilder.Field("approvalMode", Scalar())
  def expiresAt: SelectionBuilder[CapabilityGrant, scala.Option[java.time.Instant]] = _root_.caliban.client.SelectionBuilder.Field("expiresAt", OptionOf(Scalar()))
  def resourceConstraints: SelectionBuilder[CapabilityGrant, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("resourceConstraints", OptionOf(Scalar()))
  def createdAt: SelectionBuilder[CapabilityGrant, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
}


type ChannelIdentity
object ChannelIdentity {
  
final case class ChannelIdentityView(id: ChannelIdentityId, userId: jorlan.UserId, channelType: jorlan.ChannelType, channelUserId: String, verified: Boolean, providerData: scala.Option[String], createdAt: java.time.Instant)




type ViewSelection = SelectionBuilder[ChannelIdentity, ChannelIdentityView]

def view: ViewSelection = (id ~ userId ~ channelType ~ channelUserId ~ verified ~ providerData ~ createdAt).map { case (id, userId, channelType, channelUserId, verified, providerData, createdAt) => ChannelIdentityView(id, userId, channelType, channelUserId, verified, providerData, createdAt) }

  def id: SelectionBuilder[ChannelIdentity, ChannelIdentityId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def userId: SelectionBuilder[ChannelIdentity, jorlan.UserId] = _root_.caliban.client.SelectionBuilder.Field("userId", Scalar())
  def channelType: SelectionBuilder[ChannelIdentity, jorlan.ChannelType] = _root_.caliban.client.SelectionBuilder.Field("channelType", Scalar())
  def channelUserId: SelectionBuilder[ChannelIdentity, String] = _root_.caliban.client.SelectionBuilder.Field("channelUserId", Scalar())
  def verified: SelectionBuilder[ChannelIdentity, Boolean] = _root_.caliban.client.SelectionBuilder.Field("verified", Scalar())
  def providerData: SelectionBuilder[ChannelIdentity, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("providerData", OptionOf(Scalar()))
  def createdAt: SelectionBuilder[ChannelIdentity, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
}


type CheckpointPolicyConfig
object CheckpointPolicyConfig {
  
final case class CheckpointPolicyConfigView(onSessionEnd: Boolean, onUserRequest: Boolean, timedIntervalTurns: scala.Option[Int], beforeExternalEffect: Boolean)




type ViewSelection = SelectionBuilder[CheckpointPolicyConfig, CheckpointPolicyConfigView]

def view: ViewSelection = (onSessionEnd ~ onUserRequest ~ timedIntervalTurns ~ beforeExternalEffect).map { case (onSessionEnd, onUserRequest, timedIntervalTurns, beforeExternalEffect) => CheckpointPolicyConfigView(onSessionEnd, onUserRequest, timedIntervalTurns, beforeExternalEffect) }

  def onSessionEnd: SelectionBuilder[CheckpointPolicyConfig, Boolean] = _root_.caliban.client.SelectionBuilder.Field("onSessionEnd", Scalar())
  def onUserRequest: SelectionBuilder[CheckpointPolicyConfig, Boolean] = _root_.caliban.client.SelectionBuilder.Field("onUserRequest", Scalar())
  def timedIntervalTurns: SelectionBuilder[CheckpointPolicyConfig, scala.Option[Int]] = _root_.caliban.client.SelectionBuilder.Field("timedIntervalTurns", OptionOf(Scalar()))
  def beforeExternalEffect: SelectionBuilder[CheckpointPolicyConfig, Boolean] = _root_.caliban.client.SelectionBuilder.Field("beforeExternalEffect", Scalar())
}


type ContactIdentityResult
object ContactIdentityResult {
  
final case class ContactIdentityResultView(channelType: String, channelUserId: String)




type ViewSelection = SelectionBuilder[ContactIdentityResult, ContactIdentityResultView]

def view: ViewSelection = (channelType ~ channelUserId).map { case (channelType, channelUserId) => ContactIdentityResultView(channelType, channelUserId) }

  def channelType: SelectionBuilder[ContactIdentityResult, String] = _root_.caliban.client.SelectionBuilder.Field("channelType", Scalar())
  def channelUserId: SelectionBuilder[ContactIdentityResult, String] = _root_.caliban.client.SelectionBuilder.Field("channelUserId", Scalar())
}


type ContactResult
object ContactResult {
  
final case class ContactResultView[IdentitiesSelection](userId: Long, displayName: String, identities: List[IdentitiesSelection])




type ViewSelection[IdentitiesSelection] = SelectionBuilder[ContactResult, ContactResultView[IdentitiesSelection]]

def view[IdentitiesSelection](identitiesSelection: SelectionBuilder[ContactIdentityResult, IdentitiesSelection]): ViewSelection[IdentitiesSelection] = (userId ~ displayName ~ identities(identitiesSelection)).map { case (userId, displayName, identities) => ContactResultView(userId, displayName, identities) }

  def userId: SelectionBuilder[ContactResult, Long] = _root_.caliban.client.SelectionBuilder.Field("userId", Scalar())
  def displayName: SelectionBuilder[ContactResult, String] = _root_.caliban.client.SelectionBuilder.Field("displayName", Scalar())
  def identities[A](innerSelection: SelectionBuilder[ContactIdentityResult, A]): SelectionBuilder[ContactResult, List[A]] = _root_.caliban.client.SelectionBuilder.Field("identities", ListOf(Obj(innerSelection)))
}


type DashboardStats
object DashboardStats {
  
final case class DashboardStatsView[EventVolumeSeriesSelection, SkillInvocationsByNameSelection, SessionStatusCountsSelection, JobOutcomeCountsSelection](activeSessionCount: Int, eventCountToday: Int, skillInvocationCount: Int, schedulerSuccessRate: Double, eventVolumeSeries: List[EventVolumeSeriesSelection], skillInvocationsByName: List[SkillInvocationsByNameSelection], sessionStatusCounts: List[SessionStatusCountsSelection], jobOutcomeCounts: List[JobOutcomeCountsSelection])




type ViewSelection[EventVolumeSeriesSelection, SkillInvocationsByNameSelection, SessionStatusCountsSelection, JobOutcomeCountsSelection] = SelectionBuilder[DashboardStats, DashboardStatsView[EventVolumeSeriesSelection, SkillInvocationsByNameSelection, SessionStatusCountsSelection, JobOutcomeCountsSelection]]

def view[EventVolumeSeriesSelection, SkillInvocationsByNameSelection, SessionStatusCountsSelection, JobOutcomeCountsSelection](eventVolumeSeriesSelection: SelectionBuilder[TimeSeriesPoint, EventVolumeSeriesSelection], skillInvocationsByNameSelection: SelectionBuilder[NamedCount, SkillInvocationsByNameSelection], sessionStatusCountsSelection: SelectionBuilder[NamedCount, SessionStatusCountsSelection], jobOutcomeCountsSelection: SelectionBuilder[NamedCount, JobOutcomeCountsSelection]): ViewSelection[EventVolumeSeriesSelection, SkillInvocationsByNameSelection, SessionStatusCountsSelection, JobOutcomeCountsSelection] = (activeSessionCount ~ eventCountToday ~ skillInvocationCount ~ schedulerSuccessRate ~ eventVolumeSeries(eventVolumeSeriesSelection) ~ skillInvocationsByName(skillInvocationsByNameSelection) ~ sessionStatusCounts(sessionStatusCountsSelection) ~ jobOutcomeCounts(jobOutcomeCountsSelection)).map { case (activeSessionCount, eventCountToday, skillInvocationCount, schedulerSuccessRate, eventVolumeSeries, skillInvocationsByName, sessionStatusCounts, jobOutcomeCounts) => DashboardStatsView(activeSessionCount, eventCountToday, skillInvocationCount, schedulerSuccessRate, eventVolumeSeries, skillInvocationsByName, sessionStatusCounts, jobOutcomeCounts) }

  def activeSessionCount: SelectionBuilder[DashboardStats, Int] = _root_.caliban.client.SelectionBuilder.Field("activeSessionCount", Scalar())
  def eventCountToday: SelectionBuilder[DashboardStats, Int] = _root_.caliban.client.SelectionBuilder.Field("eventCountToday", Scalar())
  def skillInvocationCount: SelectionBuilder[DashboardStats, Int] = _root_.caliban.client.SelectionBuilder.Field("skillInvocationCount", Scalar())
  def schedulerSuccessRate: SelectionBuilder[DashboardStats, Double] = _root_.caliban.client.SelectionBuilder.Field("schedulerSuccessRate", Scalar())
  def eventVolumeSeries[A](innerSelection: SelectionBuilder[TimeSeriesPoint, A]): SelectionBuilder[DashboardStats, List[A]] = _root_.caliban.client.SelectionBuilder.Field("eventVolumeSeries", ListOf(Obj(innerSelection)))
  def skillInvocationsByName[A](innerSelection: SelectionBuilder[NamedCount, A]): SelectionBuilder[DashboardStats, List[A]] = _root_.caliban.client.SelectionBuilder.Field("skillInvocationsByName", ListOf(Obj(innerSelection)))
  def sessionStatusCounts[A](innerSelection: SelectionBuilder[NamedCount, A]): SelectionBuilder[DashboardStats, List[A]] = _root_.caliban.client.SelectionBuilder.Field("sessionStatusCounts", ListOf(Obj(innerSelection)))
  def jobOutcomeCounts[A](innerSelection: SelectionBuilder[NamedCount, A]): SelectionBuilder[DashboardStats, List[A]] = _root_.caliban.client.SelectionBuilder.Field("jobOutcomeCounts", ListOf(Obj(innerSelection)))
}


type EventLogJson
object EventLogJson {
  
final case class EventLogJsonView(id: jorlan.EventLogId, eventType: jorlan.EventType, actorId: scala.Option[jorlan.UserId], agentId: scala.Option[jorlan.AgentId], sessionId: scala.Option[jorlan.AgentSessionId], resource: scala.Option[String], payloadJson: scala.Option[String], occurredAt: java.time.Instant)




type ViewSelection = SelectionBuilder[EventLogJson, EventLogJsonView]

def view: ViewSelection = (id ~ eventType ~ actorId ~ agentId ~ sessionId ~ resource ~ payloadJson ~ occurredAt).map { case (id, eventType, actorId, agentId, sessionId, resource, payloadJson, occurredAt) => EventLogJsonView(id, eventType, actorId, agentId, sessionId, resource, payloadJson, occurredAt) }

  def id: SelectionBuilder[EventLogJson, jorlan.EventLogId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def eventType: SelectionBuilder[EventLogJson, jorlan.EventType] = _root_.caliban.client.SelectionBuilder.Field("eventType", Scalar())
  def actorId: SelectionBuilder[EventLogJson, scala.Option[jorlan.UserId]] = _root_.caliban.client.SelectionBuilder.Field("actorId", OptionOf(Scalar()))
  def agentId: SelectionBuilder[EventLogJson, scala.Option[jorlan.AgentId]] = _root_.caliban.client.SelectionBuilder.Field("agentId", OptionOf(Scalar()))
  def sessionId: SelectionBuilder[EventLogJson, scala.Option[jorlan.AgentSessionId]] = _root_.caliban.client.SelectionBuilder.Field("sessionId", OptionOf(Scalar()))
  def resource: SelectionBuilder[EventLogJson, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("resource", OptionOf(Scalar()))
  def payloadJson: SelectionBuilder[EventLogJson, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("payloadJson", OptionOf(Scalar()))
  def occurredAt: SelectionBuilder[EventLogJson, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("occurredAt", Scalar())
}


type McpEnvVar
object McpEnvVar {
  
final case class McpEnvVarView(key: String, value: String)




type ViewSelection = SelectionBuilder[McpEnvVar, McpEnvVarView]

def view: ViewSelection = (key ~ value).map { case (key, value) => McpEnvVarView(key, value) }

  def key: SelectionBuilder[McpEnvVar, String] = _root_.caliban.client.SelectionBuilder.Field("key", Scalar())
  def value: SelectionBuilder[McpEnvVar, String] = _root_.caliban.client.SelectionBuilder.Field("value", Scalar())
}


type McpServerView
object McpServerView {
  
final case class McpServerViewView[EnvSelection](name: String, transport: String, command: scala.Option[String], args: List[String], env: List[EnvSelection], url: scala.Option[String], enabled: Boolean)




type ViewSelection[EnvSelection] = SelectionBuilder[McpServerView, McpServerViewView[EnvSelection]]

def view[EnvSelection](envSelection: SelectionBuilder[McpEnvVar, EnvSelection]): ViewSelection[EnvSelection] = (name ~ transport ~ command ~ args ~ env(envSelection) ~ url ~ enabled).map { case (name, transport, command, args, env, url, enabled) => McpServerViewView(name, transport, command, args, env, url, enabled) }

  def name: SelectionBuilder[McpServerView, String] = _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
  def transport: SelectionBuilder[McpServerView, String] = _root_.caliban.client.SelectionBuilder.Field("transport", Scalar())
  def command: SelectionBuilder[McpServerView, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("command", OptionOf(Scalar()))
  def args: SelectionBuilder[McpServerView, List[String]] = _root_.caliban.client.SelectionBuilder.Field("args", ListOf(Scalar()))
  def env[A](innerSelection: SelectionBuilder[McpEnvVar, A]): SelectionBuilder[McpServerView, List[A]] = _root_.caliban.client.SelectionBuilder.Field("env", ListOf(Obj(innerSelection)))
  def url: SelectionBuilder[McpServerView, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("url", OptionOf(Scalar()))
  def enabled: SelectionBuilder[McpServerView, Boolean] = _root_.caliban.client.SelectionBuilder.Field("enabled", Scalar())
}


type MemoryRecord
object MemoryRecord {
  
final case class MemoryRecordView(id: jorlan.MemoryRecordId, scope: jorlan.MemoryScope, userId: scala.Option[jorlan.UserId], workspaceId: scala.Option[jorlan.WorkspaceId], agentId: scala.Option[jorlan.AgentId], recordKey: String, value: String, ttl: scala.Option[java.time.Instant], createdAt: java.time.Instant, updatedAt: java.time.Instant, importance: Int)




type ViewSelection = SelectionBuilder[MemoryRecord, MemoryRecordView]

def view: ViewSelection = (id ~ scope ~ userId ~ workspaceId ~ agentId ~ recordKey ~ value ~ ttl ~ createdAt ~ updatedAt ~ importance).map { case (id, scope, userId, workspaceId, agentId, recordKey, value, ttl, createdAt, updatedAt, importance) => MemoryRecordView(id, scope, userId, workspaceId, agentId, recordKey, value, ttl, createdAt, updatedAt, importance) }

  def id: SelectionBuilder[MemoryRecord, jorlan.MemoryRecordId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def scope: SelectionBuilder[MemoryRecord, jorlan.MemoryScope] = _root_.caliban.client.SelectionBuilder.Field("scope", Scalar())
  def userId: SelectionBuilder[MemoryRecord, scala.Option[jorlan.UserId]] = _root_.caliban.client.SelectionBuilder.Field("userId", OptionOf(Scalar()))
  def workspaceId: SelectionBuilder[MemoryRecord, scala.Option[jorlan.WorkspaceId]] = _root_.caliban.client.SelectionBuilder.Field("workspaceId", OptionOf(Scalar()))
  def agentId: SelectionBuilder[MemoryRecord, scala.Option[jorlan.AgentId]] = _root_.caliban.client.SelectionBuilder.Field("agentId", OptionOf(Scalar()))
  def recordKey: SelectionBuilder[MemoryRecord, String] = _root_.caliban.client.SelectionBuilder.Field("recordKey", Scalar())
  def value: SelectionBuilder[MemoryRecord, String] = _root_.caliban.client.SelectionBuilder.Field("value", Scalar())
  def ttl: SelectionBuilder[MemoryRecord, scala.Option[java.time.Instant]] = _root_.caliban.client.SelectionBuilder.Field("ttl", OptionOf(Scalar()))
  def createdAt: SelectionBuilder[MemoryRecord, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
  def updatedAt: SelectionBuilder[MemoryRecord, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("updatedAt", Scalar())
  def importance: SelectionBuilder[MemoryRecord, Int] = _root_.caliban.client.SelectionBuilder.Field("importance", Scalar())
}


type ModelInfo
object ModelInfo {
  
final case class ModelInfoView(id: jorlan.ModelId, provider: String, contextWindow: Int, supportsStreaming: Boolean)




type ViewSelection = SelectionBuilder[ModelInfo, ModelInfoView]

def view: ViewSelection = (id ~ provider ~ contextWindow ~ supportsStreaming).map { case (id, provider, contextWindow, supportsStreaming) => ModelInfoView(id, provider, contextWindow, supportsStreaming) }

  def id: SelectionBuilder[ModelInfo, jorlan.ModelId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def provider: SelectionBuilder[ModelInfo, String] = _root_.caliban.client.SelectionBuilder.Field("provider", Scalar())
  def contextWindow: SelectionBuilder[ModelInfo, Int] = _root_.caliban.client.SelectionBuilder.Field("contextWindow", Scalar())
  def supportsStreaming: SelectionBuilder[ModelInfo, Boolean] = _root_.caliban.client.SelectionBuilder.Field("supportsStreaming", Scalar())
}


type NamedCount
object NamedCount {
  
final case class NamedCountView(name: String, count: Int)




type ViewSelection = SelectionBuilder[NamedCount, NamedCountView]

def view: ViewSelection = (name ~ count).map { case (name, count) => NamedCountView(name, count) }

  def name: SelectionBuilder[NamedCount, String] = _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
  def count: SelectionBuilder[NamedCount, Int] = _root_.caliban.client.SelectionBuilder.Field("count", Scalar())
}


type OAuthStartResult
object OAuthStartResult {
  
final case class OAuthStartResultView(authUrl: String)




type ViewSelection = SelectionBuilder[OAuthStartResult, OAuthStartResultView]

def view: ViewSelection = authUrl.map(authUrl => OAuthStartResultView(authUrl))

  def authUrl: SelectionBuilder[OAuthStartResult, String] = _root_.caliban.client.SelectionBuilder.Field("authUrl", Scalar())
}


type OAuthStatus
object OAuthStatus {
  
final case class OAuthStatusView(connected: Boolean, expiresAt: scala.Option[java.time.Instant])




type ViewSelection = SelectionBuilder[OAuthStatus, OAuthStatusView]

def view: ViewSelection = (connected ~ expiresAt).map { case (connected, expiresAt) => OAuthStatusView(connected, expiresAt) }

  def connected: SelectionBuilder[OAuthStatus, Boolean] = _root_.caliban.client.SelectionBuilder.Field("connected", Scalar())
  def expiresAt: SelectionBuilder[OAuthStatus, scala.Option[java.time.Instant]] = _root_.caliban.client.SelectionBuilder.Field("expiresAt", OptionOf(Scalar()))
}


type Permission
object Permission {
  
final case class PermissionView(id: jorlan.PermissionId, roleId: scala.Option[jorlan.RoleId], userId: scala.Option[jorlan.UserId], resource: String, action: String, scope: scala.Option[String])




type ViewSelection = SelectionBuilder[Permission, PermissionView]

def view: ViewSelection = (id ~ roleId ~ userId ~ resource ~ action ~ scope).map { case (id, roleId, userId, resource, action, scope) => PermissionView(id, roleId, userId, resource, action, scope) }

  def id: SelectionBuilder[Permission, jorlan.PermissionId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def roleId: SelectionBuilder[Permission, scala.Option[jorlan.RoleId]] = _root_.caliban.client.SelectionBuilder.Field("roleId", OptionOf(Scalar()))
  def userId: SelectionBuilder[Permission, scala.Option[jorlan.UserId]] = _root_.caliban.client.SelectionBuilder.Field("userId", OptionOf(Scalar()))
  def resource: SelectionBuilder[Permission, String] = _root_.caliban.client.SelectionBuilder.Field("resource", Scalar())
  def action: SelectionBuilder[Permission, String] = _root_.caliban.client.SelectionBuilder.Field("action", Scalar())
  def scope: SelectionBuilder[Permission, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("scope", OptionOf(Scalar()))
}


type Personality
object Personality {
  
final case class PersonalityView(name: String, formality: jorlan.Formality, languages: List[String], expertise: List[String], prompt: String)




type ViewSelection = SelectionBuilder[Personality, PersonalityView]

def view: ViewSelection = (name ~ formality ~ languages ~ expertise ~ prompt).map { case (name, formality, languages, expertise, prompt) => PersonalityView(name, formality, languages, expertise, prompt) }

  def name: SelectionBuilder[Personality, String] = _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
  def formality: SelectionBuilder[Personality, jorlan.Formality] = _root_.caliban.client.SelectionBuilder.Field("formality", Scalar())
  def languages: SelectionBuilder[Personality, List[String]] = _root_.caliban.client.SelectionBuilder.Field("languages", ListOf(Scalar()))
  def expertise: SelectionBuilder[Personality, List[String]] = _root_.caliban.client.SelectionBuilder.Field("expertise", ListOf(Scalar()))
  def prompt: SelectionBuilder[Personality, String] = _root_.caliban.client.SelectionBuilder.Field("prompt", Scalar())
}


type ResponseChunk
object ResponseChunk {
  
final case class ResponseChunkView(sessionId: jorlan.AgentSessionId, content: String, finished: Boolean, isError: Boolean)




type ViewSelection = SelectionBuilder[ResponseChunk, ResponseChunkView]

def view: ViewSelection = (sessionId ~ content ~ finished ~ isError).map { case (sessionId, content, finished, isError) => ResponseChunkView(sessionId, content, finished, isError) }

  def sessionId: SelectionBuilder[ResponseChunk, jorlan.AgentSessionId] = _root_.caliban.client.SelectionBuilder.Field("sessionId", Scalar())
  def content: SelectionBuilder[ResponseChunk, String] = _root_.caliban.client.SelectionBuilder.Field("content", Scalar())
  def finished: SelectionBuilder[ResponseChunk, Boolean] = _root_.caliban.client.SelectionBuilder.Field("finished", Scalar())
  def isError: SelectionBuilder[ResponseChunk, Boolean] = _root_.caliban.client.SelectionBuilder.Field("isError", Scalar())
}


type Role
object Role {
  
final case class RoleView(id: jorlan.RoleId, name: String, description: scala.Option[String])




type ViewSelection = SelectionBuilder[Role, RoleView]

def view: ViewSelection = (id ~ name ~ description).map { case (id, name, description) => RoleView(id, name, description) }

  def id: SelectionBuilder[Role, jorlan.RoleId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def name: SelectionBuilder[Role, String] = _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
  def description: SelectionBuilder[Role, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("description", OptionOf(Scalar()))
}


type SchedulerJob
object SchedulerJob {
  
final case class SchedulerJobView(id: jorlan.SchedulerJobId, agentId: jorlan.AgentId, userId: jorlan.UserId, skillId: scala.Option[jorlan.SkillId], name: String, prompt: String, inputJson: scala.Option[String], status: jorlan.JobStatus, scheduledAt: java.time.Instant, startedAt: scala.Option[java.time.Instant], finishedAt: scala.Option[java.time.Instant], resultJson: scala.Option[String], maxRetries: Int, retryCount: Int, backoffSeconds: Int, backoffPolicy: jorlan.RetryBackoffPolicy, missedRunPolicy: jorlan.MissedRunPolicy, leasedAt: scala.Option[java.time.Instant], leasedBy: scala.Option[String], createdAt: java.time.Instant)




type ViewSelection = SelectionBuilder[SchedulerJob, SchedulerJobView]

def view: ViewSelection = (id ~ agentId ~ userId ~ skillId ~ name ~ prompt ~ inputJson ~ status ~ scheduledAt ~ startedAt ~ finishedAt ~ resultJson ~ maxRetries ~ retryCount ~ backoffSeconds ~ backoffPolicy ~ missedRunPolicy ~ leasedAt ~ leasedBy ~ createdAt).map { case (id, agentId, userId, skillId, name, prompt, inputJson, status, scheduledAt, startedAt, finishedAt, resultJson, maxRetries, retryCount, backoffSeconds, backoffPolicy, missedRunPolicy, leasedAt, leasedBy, createdAt) => SchedulerJobView(id, agentId, userId, skillId, name, prompt, inputJson, status, scheduledAt, startedAt, finishedAt, resultJson, maxRetries, retryCount, backoffSeconds, backoffPolicy, missedRunPolicy, leasedAt, leasedBy, createdAt) }

  def id: SelectionBuilder[SchedulerJob, jorlan.SchedulerJobId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def agentId: SelectionBuilder[SchedulerJob, jorlan.AgentId] = _root_.caliban.client.SelectionBuilder.Field("agentId", Scalar())
  def userId: SelectionBuilder[SchedulerJob, jorlan.UserId] = _root_.caliban.client.SelectionBuilder.Field("userId", Scalar())
  def skillId: SelectionBuilder[SchedulerJob, scala.Option[jorlan.SkillId]] = _root_.caliban.client.SelectionBuilder.Field("skillId", OptionOf(Scalar()))
  def name: SelectionBuilder[SchedulerJob, String] = _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
  def prompt: SelectionBuilder[SchedulerJob, String] = _root_.caliban.client.SelectionBuilder.Field("prompt", Scalar())
  def inputJson: SelectionBuilder[SchedulerJob, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("inputJson", OptionOf(Scalar()))
  def status: SelectionBuilder[SchedulerJob, jorlan.JobStatus] = _root_.caliban.client.SelectionBuilder.Field("status", Scalar())
  def scheduledAt: SelectionBuilder[SchedulerJob, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("scheduledAt", Scalar())
  def startedAt: SelectionBuilder[SchedulerJob, scala.Option[java.time.Instant]] = _root_.caliban.client.SelectionBuilder.Field("startedAt", OptionOf(Scalar()))
  def finishedAt: SelectionBuilder[SchedulerJob, scala.Option[java.time.Instant]] = _root_.caliban.client.SelectionBuilder.Field("finishedAt", OptionOf(Scalar()))
  def resultJson: SelectionBuilder[SchedulerJob, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("resultJson", OptionOf(Scalar()))
  def maxRetries: SelectionBuilder[SchedulerJob, Int] = _root_.caliban.client.SelectionBuilder.Field("maxRetries", Scalar())
  def retryCount: SelectionBuilder[SchedulerJob, Int] = _root_.caliban.client.SelectionBuilder.Field("retryCount", Scalar())
  def backoffSeconds: SelectionBuilder[SchedulerJob, Int] = _root_.caliban.client.SelectionBuilder.Field("backoffSeconds", Scalar())
  def backoffPolicy: SelectionBuilder[SchedulerJob, jorlan.RetryBackoffPolicy] = _root_.caliban.client.SelectionBuilder.Field("backoffPolicy", Scalar())
  def missedRunPolicy: SelectionBuilder[SchedulerJob, jorlan.MissedRunPolicy] = _root_.caliban.client.SelectionBuilder.Field("missedRunPolicy", Scalar())
  def leasedAt: SelectionBuilder[SchedulerJob, scala.Option[java.time.Instant]] = _root_.caliban.client.SelectionBuilder.Field("leasedAt", OptionOf(Scalar()))
  def leasedBy: SelectionBuilder[SchedulerJob, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("leasedBy", OptionOf(Scalar()))
  def createdAt: SelectionBuilder[SchedulerJob, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
}


type SchedulerTrigger
object SchedulerTrigger {
  
final case class SchedulerTriggerView(id: jorlan.SchedulerTriggerId, jobId: jorlan.SchedulerJobId, triggerType: jorlan.TriggerType, expression: String, enabled: Boolean, createdAt: java.time.Instant)




type ViewSelection = SelectionBuilder[SchedulerTrigger, SchedulerTriggerView]

def view: ViewSelection = (id ~ jobId ~ triggerType ~ expression ~ enabled ~ createdAt).map { case (id, jobId, triggerType, expression, enabled, createdAt) => SchedulerTriggerView(id, jobId, triggerType, expression, enabled, createdAt) }

  def id: SelectionBuilder[SchedulerTrigger, jorlan.SchedulerTriggerId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def jobId: SelectionBuilder[SchedulerTrigger, jorlan.SchedulerJobId] = _root_.caliban.client.SelectionBuilder.Field("jobId", Scalar())
  def triggerType: SelectionBuilder[SchedulerTrigger, jorlan.TriggerType] = _root_.caliban.client.SelectionBuilder.Field("triggerType", Scalar())
  def expression: SelectionBuilder[SchedulerTrigger, String] = _root_.caliban.client.SelectionBuilder.Field("expression", Scalar())
  def enabled: SelectionBuilder[SchedulerTrigger, Boolean] = _root_.caliban.client.SelectionBuilder.Field("enabled", Scalar())
  def createdAt: SelectionBuilder[SchedulerTrigger, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
}


type SkillInfo
object SkillInfo {
  
final case class SkillInfoView[ToolsSelection](name: String, tier: String, tools: List[ToolsSelection], enabled: Boolean, keywords: List[String], configKey: scala.Option[String], configJsModule: scala.Option[String], dashboardJsModule: scala.Option[String], hasDashboardData: Boolean, oauthProvider: scala.Option[OAuthProvider])




type ViewSelection[ToolsSelection] = SelectionBuilder[SkillInfo, SkillInfoView[ToolsSelection]]

def view[ToolsSelection](toolsSelection: SelectionBuilder[SkillToolInfo, ToolsSelection]): ViewSelection[ToolsSelection] = (name ~ tier ~ tools(toolsSelection) ~ enabled ~ keywords ~ configKey ~ configJsModule ~ dashboardJsModule ~ hasDashboardData ~ oauthProvider).map { case (name, tier, tools, enabled, keywords, configKey, configJsModule, dashboardJsModule, hasDashboardData, oauthProvider) => SkillInfoView(name, tier, tools, enabled, keywords, configKey, configJsModule, dashboardJsModule, hasDashboardData, oauthProvider) }

  def name: SelectionBuilder[SkillInfo, String] = _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
  def tier: SelectionBuilder[SkillInfo, String] = _root_.caliban.client.SelectionBuilder.Field("tier", Scalar())
  def tools[A](innerSelection: SelectionBuilder[SkillToolInfo, A]): SelectionBuilder[SkillInfo, List[A]] = _root_.caliban.client.SelectionBuilder.Field("tools", ListOf(Obj(innerSelection)))
  def enabled: SelectionBuilder[SkillInfo, Boolean] = _root_.caliban.client.SelectionBuilder.Field("enabled", Scalar())
  def keywords: SelectionBuilder[SkillInfo, List[String]] = _root_.caliban.client.SelectionBuilder.Field("keywords", ListOf(Scalar()))
  def configKey: SelectionBuilder[SkillInfo, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("configKey", OptionOf(Scalar()))
  def configJsModule: SelectionBuilder[SkillInfo, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("configJsModule", OptionOf(Scalar()))
  def dashboardJsModule: SelectionBuilder[SkillInfo, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("dashboardJsModule", OptionOf(Scalar()))
  def hasDashboardData: SelectionBuilder[SkillInfo, Boolean] = _root_.caliban.client.SelectionBuilder.Field("hasDashboardData", Scalar())
  def oauthProvider: SelectionBuilder[SkillInfo, scala.Option[OAuthProvider]] = _root_.caliban.client.SelectionBuilder.Field("oauthProvider", OptionOf(Scalar()))
}


type SkillLifecycleResultView
object SkillLifecycleResultView {
  
final case class SkillLifecycleResultViewView(versionId: SkillVersionId, newStatus: SkillStatus, errors: List[String], info: List[String])




type ViewSelection = SelectionBuilder[SkillLifecycleResultView, SkillLifecycleResultViewView]

def view: ViewSelection = (versionId ~ newStatus ~ errors ~ info).map { case (versionId, newStatus, errors, info) => SkillLifecycleResultViewView(versionId, newStatus, errors, info) }

  def versionId: SelectionBuilder[SkillLifecycleResultView, SkillVersionId] = _root_.caliban.client.SelectionBuilder.Field("versionId", Scalar())
  def newStatus: SelectionBuilder[SkillLifecycleResultView, SkillStatus] = _root_.caliban.client.SelectionBuilder.Field("newStatus", Scalar())
  def errors: SelectionBuilder[SkillLifecycleResultView, List[String]] = _root_.caliban.client.SelectionBuilder.Field("errors", ListOf(Scalar()))
  def info: SelectionBuilder[SkillLifecycleResultView, List[String]] = _root_.caliban.client.SelectionBuilder.Field("info", ListOf(Scalar()))
}


type SkillToolInfo
object SkillToolInfo {
  
final case class SkillToolInfoView(name: String, description: String, requiredCapabilities: List[String], examplePrompts: List[String])




type ViewSelection = SelectionBuilder[SkillToolInfo, SkillToolInfoView]

def view: ViewSelection = (name ~ description ~ requiredCapabilities ~ examplePrompts).map { case (name, description, requiredCapabilities, examplePrompts) => SkillToolInfoView(name, description, requiredCapabilities, examplePrompts) }

  def name: SelectionBuilder[SkillToolInfo, String] = _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
  def description: SelectionBuilder[SkillToolInfo, String] = _root_.caliban.client.SelectionBuilder.Field("description", Scalar())
  def requiredCapabilities: SelectionBuilder[SkillToolInfo, List[String]] = _root_.caliban.client.SelectionBuilder.Field("requiredCapabilities", ListOf(Scalar()))
  def examplePrompts: SelectionBuilder[SkillToolInfo, List[String]] = _root_.caliban.client.SelectionBuilder.Field("examplePrompts", ListOf(Scalar()))
}


type SkillValidationResult
object SkillValidationResult {
  
final case class SkillValidationResultView(ok: Boolean, message: String)




type ViewSelection = SelectionBuilder[SkillValidationResult, SkillValidationResultView]

def view: ViewSelection = (ok ~ message).map { case (ok, message) => SkillValidationResultView(ok, message) }

  def ok: SelectionBuilder[SkillValidationResult, Boolean] = _root_.caliban.client.SelectionBuilder.Field("ok", Scalar())
  def message: SelectionBuilder[SkillValidationResult, String] = _root_.caliban.client.SelectionBuilder.Field("message", Scalar())
}


type SkillVersionView
object SkillVersionView {
  
final case class SkillVersionViewView(id: SkillVersionId, skillId: jorlan.SkillId, skillName: String, version: String, tier: String, manifestJson: String, status: SkillStatus, reviewNote: scala.Option[String], createdAt: java.time.Instant, createdBy: scala.Option[jorlan.UserId])




type ViewSelection = SelectionBuilder[SkillVersionView, SkillVersionViewView]

def view: ViewSelection = (id ~ skillId ~ skillName ~ version ~ tier ~ manifestJson ~ status ~ reviewNote ~ createdAt ~ createdBy).map { case (id, skillId, skillName, version, tier, manifestJson, status, reviewNote, createdAt, createdBy) => SkillVersionViewView(id, skillId, skillName, version, tier, manifestJson, status, reviewNote, createdAt, createdBy) }

  def id: SelectionBuilder[SkillVersionView, SkillVersionId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def skillId: SelectionBuilder[SkillVersionView, jorlan.SkillId] = _root_.caliban.client.SelectionBuilder.Field("skillId", Scalar())
  def skillName: SelectionBuilder[SkillVersionView, String] = _root_.caliban.client.SelectionBuilder.Field("skillName", Scalar())
  def version: SelectionBuilder[SkillVersionView, String] = _root_.caliban.client.SelectionBuilder.Field("version", Scalar())
  def tier: SelectionBuilder[SkillVersionView, String] = _root_.caliban.client.SelectionBuilder.Field("tier", Scalar())
  def manifestJson: SelectionBuilder[SkillVersionView, String] = _root_.caliban.client.SelectionBuilder.Field("manifestJson", Scalar())
  def status: SelectionBuilder[SkillVersionView, SkillStatus] = _root_.caliban.client.SelectionBuilder.Field("status", Scalar())
  def reviewNote: SelectionBuilder[SkillVersionView, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("reviewNote", OptionOf(Scalar()))
  def createdAt: SelectionBuilder[SkillVersionView, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
  def createdBy: SelectionBuilder[SkillVersionView, scala.Option[jorlan.UserId]] = _root_.caliban.client.SelectionBuilder.Field("createdBy", OptionOf(Scalar()))
}


type TimeSeriesPoint
object TimeSeriesPoint {
  
final case class TimeSeriesPointView(timestampMs: Long, count: Int)




type ViewSelection = SelectionBuilder[TimeSeriesPoint, TimeSeriesPointView]

def view: ViewSelection = (timestampMs ~ count).map { case (timestampMs, count) => TimeSeriesPointView(timestampMs, count) }

  def timestampMs: SelectionBuilder[TimeSeriesPoint, Long] = _root_.caliban.client.SelectionBuilder.Field("timestampMs", Scalar())
  def count: SelectionBuilder[TimeSeriesPoint, Int] = _root_.caliban.client.SelectionBuilder.Field("count", Scalar())
}


type ToolEventResult
object ToolEventResult {
  
final case class ToolEventResultView(sessionId: Long, eventType: String, toolName: String, payload: String)




type ViewSelection = SelectionBuilder[ToolEventResult, ToolEventResultView]

def view: ViewSelection = (sessionId ~ eventType ~ toolName ~ payload).map { case (sessionId, eventType, toolName, payload) => ToolEventResultView(sessionId, eventType, toolName, payload) }

  def sessionId: SelectionBuilder[ToolEventResult, Long] = _root_.caliban.client.SelectionBuilder.Field("sessionId", Scalar())
  def eventType: SelectionBuilder[ToolEventResult, String] = _root_.caliban.client.SelectionBuilder.Field("eventType", Scalar())
  def toolName: SelectionBuilder[ToolEventResult, String] = _root_.caliban.client.SelectionBuilder.Field("toolName", Scalar())
  def payload: SelectionBuilder[ToolEventResult, String] = _root_.caliban.client.SelectionBuilder.Field("payload", Scalar())
}


type User
object User {
  
final case class UserView(id: jorlan.UserId, displayName: String, email: String, createdAt: java.time.Instant, updatedAt: java.time.Instant, active: Boolean)




type ViewSelection = SelectionBuilder[User, UserView]

def view: ViewSelection = (id ~ displayName ~ email ~ createdAt ~ updatedAt ~ active).map { case (id, displayName, email, createdAt, updatedAt, active) => UserView(id, displayName, email, createdAt, updatedAt, active) }

  def id: SelectionBuilder[User, jorlan.UserId] = _root_.caliban.client.SelectionBuilder.Field("id", Scalar())
  def displayName: SelectionBuilder[User, String] = _root_.caliban.client.SelectionBuilder.Field("displayName", Scalar())
  def email: SelectionBuilder[User, String] = _root_.caliban.client.SelectionBuilder.Field("email", Scalar())
  def createdAt: SelectionBuilder[User, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("createdAt", Scalar())
  def updatedAt: SelectionBuilder[User, java.time.Instant] = _root_.caliban.client.SelectionBuilder.Field("updatedAt", Scalar())
  def active: SelectionBuilder[User, Boolean] = _root_.caliban.client.SelectionBuilder.Field("active", Scalar())
}


  final case class McpEnvVarInput(key : String, value : String)
object McpEnvVarInput {
  implicit val encoder: ArgEncoder[McpEnvVarInput] = new ArgEncoder[McpEnvVarInput] {
    override def encode(value: McpEnvVarInput): __Value =
      __ObjectValue(List("key" -> implicitly[ArgEncoder[String]].encode(value.key), "value" -> implicitly[ArgEncoder[String]].encode(value.value)))
  }
}
  type Queries = _root_.caliban.client.Operations.RootQuery
object Queries {
  def user[A](value : jorlan.UserId)(innerSelection: SelectionBuilder[User, A])(implicit encoder0: ArgEncoder[jorlan.UserId]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("user", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "UserId!")))
  def users[A](active : scala.Option[Boolean] = None, nameContains : scala.Option[String] = None, page : scala.Option[Int] = None, pageSize : scala.Option[Int] = None)(innerSelection: SelectionBuilder[User, A])(implicit encoder0: ArgEncoder[scala.Option[Boolean]], encoder1: ArgEncoder[scala.Option[String]], encoder2: ArgEncoder[scala.Option[Int]]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("users", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("active", active, "Boolean"), Argument("nameContains", nameContains, "String"), Argument("page", page, "Int"), Argument("pageSize", pageSize, "Int")))
  def role[A](value : jorlan.RoleId)(innerSelection: SelectionBuilder[Role, A])(implicit encoder0: ArgEncoder[jorlan.RoleId]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("role", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "RoleId!")))
  def roles[A](userId : jorlan.UserId, page : scala.Option[Int] = None, pageSize : scala.Option[Int] = None)(innerSelection: SelectionBuilder[Role, A])(implicit encoder0: ArgEncoder[jorlan.UserId], encoder1: ArgEncoder[scala.Option[Int]]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("roles", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("userId", userId, "UserId!"), Argument("page", page, "Int"), Argument("pageSize", pageSize, "Int")))
  def permissions[A](userId : jorlan.UserId, page : scala.Option[Int] = None, pageSize : scala.Option[Int] = None)(innerSelection: SelectionBuilder[Permission, A])(implicit encoder0: ArgEncoder[jorlan.UserId], encoder1: ArgEncoder[scala.Option[Int]]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("permissions", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("userId", userId, "UserId!"), Argument("page", page, "Int"), Argument("pageSize", pageSize, "Int")))
  def listSessions[A](page : scala.Option[Int] = None, pageSize : scala.Option[Int] = None)(innerSelection: SelectionBuilder[AgentSession, A])(implicit encoder0: ArgEncoder[scala.Option[Int]]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("listSessions", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("page", page, "Int"), Argument("pageSize", pageSize, "Int")))
  def serverPersonality[A](innerSelection: SelectionBuilder[Personality, A]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("serverPersonality", OptionOf(Obj(innerSelection)))
  def listMemory[A](scope : jorlan.MemoryScope, textSearch : scala.Option[String] = None)(innerSelection: SelectionBuilder[MemoryRecord, A])(implicit encoder0: ArgEncoder[jorlan.MemoryScope], encoder1: ArgEncoder[scala.Option[String]]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("listMemory", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("scope", scope, "MemoryScope!"), Argument("textSearch", textSearch, "String")))
  def listCapabilities[A](innerSelection: SelectionBuilder[CapabilityGrant, A]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("listCapabilities", OptionOf(ListOf(Obj(innerSelection))))
  def jobs[A](optAgentId : scala.Option[jorlan.AgentId] = None)(innerSelection: SelectionBuilder[SchedulerJob, A])(implicit encoder0: ArgEncoder[scala.Option[jorlan.AgentId]]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("jobs", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("optAgentId", optAgentId, "AgentId")))
  def job[A](value : jorlan.SchedulerJobId)(innerSelection: SelectionBuilder[SchedulerJob, A])(implicit encoder0: ArgEncoder[jorlan.SchedulerJobId]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("job", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "SchedulerJobId!")))
  def triggers[A](value : jorlan.SchedulerJobId)(innerSelection: SelectionBuilder[SchedulerTrigger, A])(implicit encoder0: ArgEncoder[jorlan.SchedulerJobId]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("triggers", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("value", value, "SchedulerJobId!")))
  def listApprovals[A](innerSelection: SelectionBuilder[ApprovalRequest, A]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("listApprovals", OptionOf(ListOf(Obj(innerSelection))))
  def availableModels[A](innerSelection: SelectionBuilder[ModelInfo, A]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("availableModels", OptionOf(ListOf(Obj(innerSelection))))
  def skills[A](innerSelection: SelectionBuilder[SkillInfo, A]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("skills", OptionOf(ListOf(Obj(innerSelection))))
  def skillConfig(value : String)(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("skillConfig", OptionOf(Scalar()), arguments = List(Argument("value", value, "String!")))
  def contacts[A](value : String)(innerSelection: SelectionBuilder[ContactResult, A])(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("contacts", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("value", value, "String!")))
  def oauthStatus[A](value : String)(innerSelection: SelectionBuilder[OAuthStatus, A])(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("oauthStatus", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "String!")))
  def listOAuthProviders: SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[String]]] = _root_.caliban.client.SelectionBuilder.Field("listOAuthProviders", OptionOf(ListOf(Scalar())))
  def userCapabilityGrants[A](value : jorlan.UserId)(innerSelection: SelectionBuilder[CapabilityGrant, A])(implicit encoder0: ArgEncoder[jorlan.UserId]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("userCapabilityGrants", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("value", value, "UserId!")))
  def roleCapabilityGrants[A](value : jorlan.RoleId)(innerSelection: SelectionBuilder[CapabilityGrant, A])(implicit encoder0: ArgEncoder[jorlan.RoleId]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("roleCapabilityGrants", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("value", value, "RoleId!")))
  def userChannelIdentities[A](value : jorlan.UserId)(innerSelection: SelectionBuilder[ChannelIdentity, A])(implicit encoder0: ArgEncoder[jorlan.UserId]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("userChannelIdentities", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("value", value, "UserId!")))
  def allRoles[A](page : scala.Option[Int] = None, pageSize : scala.Option[Int] = None)(innerSelection: SelectionBuilder[Role, A])(implicit encoder0: ArgEncoder[scala.Option[Int]]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("allRoles", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("page", page, "Int"), Argument("pageSize", pageSize, "Int")))
  def checkpointPolicy[A](innerSelection: SelectionBuilder[CheckpointPolicyConfig, A]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("checkpointPolicy", OptionOf(Obj(innerSelection)))
  def dashboardStats[A](innerSelection: SelectionBuilder[DashboardStats, A]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("dashboardStats", OptionOf(Obj(innerSelection)))
  def skillDashboardData(value : String)(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("skillDashboardData", OptionOf(Scalar()), arguments = List(Argument("value", value, "String!")))
  def mcpServers[A](innerSelection: SelectionBuilder[McpServerView, A]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("mcpServers", OptionOf(ListOf(Obj(innerSelection))))
  def allKnownCapabilities: SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[jorlan.CapabilityName]]] = _root_.caliban.client.SelectionBuilder.Field("allKnownCapabilities", OptionOf(ListOf(Scalar())))
  def skillValidate[A](value : String)(innerSelection: SelectionBuilder[SkillValidationResult, A])(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("skillValidate", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "String!")))
  def skillVersions[A](value : jorlan.SkillId)(innerSelection: SelectionBuilder[SkillVersionView, A])(implicit encoder0: ArgEncoder[jorlan.SkillId]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("skillVersions", OptionOf(ListOf(Obj(innerSelection))), arguments = List(Argument("value", value, "SkillId!")))
  def pendingSkillVersions[A](innerSelection: SelectionBuilder[SkillVersionView, A]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("pendingSkillVersions", OptionOf(ListOf(Obj(innerSelection))))
  def allCustomSkills[A](innerSelection: SelectionBuilder[SkillVersionView, A]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, scala.Option[List[A]]] = _root_.caliban.client.SelectionBuilder.Field("allCustomSkills", OptionOf(ListOf(Obj(innerSelection))))
}


  type Mutations = _root_.caliban.client.Operations.RootMutation
object Mutations {
  def createUser[A](displayName : String, email : scala.Option[String] = None)(innerSelection: SelectionBuilder[User, A])(implicit encoder0: ArgEncoder[String], encoder1: ArgEncoder[scala.Option[String]]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("createUser", OptionOf(Obj(innerSelection)), arguments = List(Argument("displayName", displayName, "String!"), Argument("email", email, "String")))
  def updateUser[A](id : jorlan.UserId, displayName : String, email : scala.Option[String] = None, active : Boolean)(innerSelection: SelectionBuilder[User, A])(implicit encoder0: ArgEncoder[jorlan.UserId], encoder1: ArgEncoder[String], encoder2: ArgEncoder[scala.Option[String]], encoder3: ArgEncoder[Boolean]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("updateUser", OptionOf(Obj(innerSelection)), arguments = List(Argument("id", id, "UserId!"), Argument("displayName", displayName, "String!"), Argument("email", email, "String"), Argument("active", active, "Boolean!")))
  def deactivateUser(value : jorlan.UserId)(implicit encoder0: ArgEncoder[jorlan.UserId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("deactivateUser", OptionOf(Scalar()), arguments = List(Argument("value", value, "UserId!")))
  def createRole[A](name : String, description : scala.Option[String] = None)(innerSelection: SelectionBuilder[Role, A])(implicit encoder0: ArgEncoder[String], encoder1: ArgEncoder[scala.Option[String]]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("createRole", OptionOf(Obj(innerSelection)), arguments = List(Argument("name", name, "String!"), Argument("description", description, "String")))
  def updateRole[A](id : jorlan.RoleId, name : String, description : scala.Option[String] = None)(innerSelection: SelectionBuilder[Role, A])(implicit encoder0: ArgEncoder[jorlan.RoleId], encoder1: ArgEncoder[String], encoder2: ArgEncoder[scala.Option[String]]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("updateRole", OptionOf(Obj(innerSelection)), arguments = List(Argument("id", id, "RoleId!"), Argument("name", name, "String!"), Argument("description", description, "String")))
  def deleteRole(value : jorlan.RoleId)(implicit encoder0: ArgEncoder[jorlan.RoleId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("deleteRole", OptionOf(Scalar()), arguments = List(Argument("value", value, "RoleId!")))
  def assignRole(userId : jorlan.UserId, roleId : jorlan.RoleId)(implicit encoder0: ArgEncoder[jorlan.UserId], encoder1: ArgEncoder[jorlan.RoleId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] = _root_.caliban.client.SelectionBuilder.Field("assignRole", OptionOf(Scalar()), arguments = List(Argument("userId", userId, "UserId!"), Argument("roleId", roleId, "RoleId!")))
  def revokeRole(userId : jorlan.UserId, roleId : jorlan.RoleId)(implicit encoder0: ArgEncoder[jorlan.UserId], encoder1: ArgEncoder[jorlan.RoleId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] = _root_.caliban.client.SelectionBuilder.Field("revokeRole", OptionOf(Scalar()), arguments = List(Argument("userId", userId, "UserId!"), Argument("roleId", roleId, "RoleId!")))
  def grantPermission[A](resource : String, action : String, userId : scala.Option[jorlan.UserId] = None, roleId : scala.Option[jorlan.RoleId] = None)(innerSelection: SelectionBuilder[Permission, A])(implicit encoder0: ArgEncoder[String], encoder1: ArgEncoder[scala.Option[jorlan.UserId]], encoder2: ArgEncoder[scala.Option[jorlan.RoleId]]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("grantPermission", OptionOf(Obj(innerSelection)), arguments = List(Argument("resource", resource, "String!"), Argument("action", action, "String!"), Argument("userId", userId, "UserId"), Argument("roleId", roleId, "RoleId")))
  def revokePermission(value : jorlan.PermissionId)(implicit encoder0: ArgEncoder[jorlan.PermissionId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Long]] = _root_.caliban.client.SelectionBuilder.Field("revokePermission", OptionOf(Scalar()), arguments = List(Argument("value", value, "PermissionId!")))
  def createSession[A](modelId : scala.Option[jorlan.ModelId] = None)(innerSelection: SelectionBuilder[AgentSession, A])(implicit encoder0: ArgEncoder[scala.Option[jorlan.ModelId]]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("createSession", OptionOf(Obj(innerSelection)), arguments = List(Argument("modelId", modelId, "ModelId")))
  def submitMessage(sessionId : jorlan.AgentSessionId, content : String)(implicit encoder0: ArgEncoder[jorlan.AgentSessionId], encoder1: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] = _root_.caliban.client.SelectionBuilder.Field("submitMessage", OptionOf(Scalar()), arguments = List(Argument("sessionId", sessionId, "AgentSessionId!"), Argument("content", content, "String!")))
  def updatePersonality[A](name : String, formality : jorlan.Formality, languages : List[String] = Nil, expertise : List[String] = Nil, prompt : String)(innerSelection: SelectionBuilder[Personality, A])(implicit encoder0: ArgEncoder[String], encoder1: ArgEncoder[jorlan.Formality], encoder2: ArgEncoder[List[String]]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("updatePersonality", OptionOf(Obj(innerSelection)), arguments = List(Argument("name", name, "String!"), Argument("formality", formality, "Formality!"), Argument("languages", languages, "[String!]!"), Argument("expertise", expertise, "[String!]!"), Argument("prompt", prompt, "String!")))
  def storeMemory[A](key : String, text : String, scope : jorlan.MemoryScope)(innerSelection: SelectionBuilder[MemoryRecord, A])(implicit encoder0: ArgEncoder[String], encoder1: ArgEncoder[jorlan.MemoryScope]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("storeMemory", OptionOf(Obj(innerSelection)), arguments = List(Argument("key", key, "String!"), Argument("text", text, "String!"), Argument("scope", scope, "MemoryScope!")))
  def forgetMemory(value : jorlan.MemoryRecordId)(implicit encoder0: ArgEncoder[jorlan.MemoryRecordId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("forgetMemory", OptionOf(Scalar()), arguments = List(Argument("value", value, "MemoryRecordId!")))
  def markMemoryShared[A](value : jorlan.MemoryRecordId)(innerSelection: SelectionBuilder[MemoryRecord, A])(implicit encoder0: ArgEncoder[jorlan.MemoryRecordId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("markMemoryShared", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "MemoryRecordId!")))
  def markMemoryPrivate[A](value : jorlan.MemoryRecordId)(innerSelection: SelectionBuilder[MemoryRecord, A])(implicit encoder0: ArgEncoder[jorlan.MemoryRecordId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("markMemoryPrivate", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "MemoryRecordId!")))
  def createJob[A](name : String, prompt : String, inputJson : scala.Option[String] = None, maxRetries : Int, backoffSeconds : Int, backoffPolicy : jorlan.RetryBackoffPolicy, missedRunPolicy : jorlan.MissedRunPolicy)(innerSelection: SelectionBuilder[SchedulerJob, A])(implicit encoder0: ArgEncoder[String], encoder1: ArgEncoder[scala.Option[String]], encoder2: ArgEncoder[Int], encoder3: ArgEncoder[jorlan.RetryBackoffPolicy], encoder4: ArgEncoder[jorlan.MissedRunPolicy]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("createJob", OptionOf(Obj(innerSelection)), arguments = List(Argument("name", name, "String!"), Argument("prompt", prompt, "String!"), Argument("inputJson", inputJson, "String"), Argument("maxRetries", maxRetries, "Int!"), Argument("backoffSeconds", backoffSeconds, "Int!"), Argument("backoffPolicy", backoffPolicy, "RetryBackoffPolicy!"), Argument("missedRunPolicy", missedRunPolicy, "MissedRunPolicy!")))
  def addTrigger[A](jobId : jorlan.SchedulerJobId, triggerType : jorlan.TriggerType, expression : String)(innerSelection: SelectionBuilder[SchedulerTrigger, A])(implicit encoder0: ArgEncoder[jorlan.SchedulerJobId], encoder1: ArgEncoder[jorlan.TriggerType], encoder2: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("addTrigger", OptionOf(Obj(innerSelection)), arguments = List(Argument("jobId", jobId, "SchedulerJobId!"), Argument("triggerType", triggerType, "TriggerType!"), Argument("expression", expression, "String!")))
  def pauseJob(value : jorlan.SchedulerJobId)(implicit encoder0: ArgEncoder[jorlan.SchedulerJobId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("pauseJob", OptionOf(Scalar()), arguments = List(Argument("value", value, "SchedulerJobId!")))
  def resumeJob(value : jorlan.SchedulerJobId)(implicit encoder0: ArgEncoder[jorlan.SchedulerJobId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("resumeJob", OptionOf(Scalar()), arguments = List(Argument("value", value, "SchedulerJobId!")))
  def cancelJob(value : jorlan.SchedulerJobId)(implicit encoder0: ArgEncoder[jorlan.SchedulerJobId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("cancelJob", OptionOf(Scalar()), arguments = List(Argument("value", value, "SchedulerJobId!")))
  def triggerNow(value : jorlan.SchedulerJobId)(implicit encoder0: ArgEncoder[jorlan.SchedulerJobId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("triggerNow", OptionOf(Scalar()), arguments = List(Argument("value", value, "SchedulerJobId!")))
  def deleteJob(value : jorlan.SchedulerJobId)(implicit encoder0: ArgEncoder[jorlan.SchedulerJobId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("deleteJob", OptionOf(Scalar()), arguments = List(Argument("value", value, "SchedulerJobId!")))
  def updateJob[A](id : jorlan.SchedulerJobId, name : String, prompt : String, maxRetries : Int, backoffSeconds : Int, backoffPolicy : jorlan.RetryBackoffPolicy, missedRunPolicy : jorlan.MissedRunPolicy)(innerSelection: SelectionBuilder[SchedulerJob, A])(implicit encoder0: ArgEncoder[jorlan.SchedulerJobId], encoder1: ArgEncoder[String], encoder2: ArgEncoder[Int], encoder3: ArgEncoder[jorlan.RetryBackoffPolicy], encoder4: ArgEncoder[jorlan.MissedRunPolicy]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("updateJob", OptionOf(Obj(innerSelection)), arguments = List(Argument("id", id, "SchedulerJobId!"), Argument("name", name, "String!"), Argument("prompt", prompt, "String!"), Argument("maxRetries", maxRetries, "Int!"), Argument("backoffSeconds", backoffSeconds, "Int!"), Argument("backoffPolicy", backoffPolicy, "RetryBackoffPolicy!"), Argument("missedRunPolicy", missedRunPolicy, "MissedRunPolicy!")))
  def deleteTrigger(value : jorlan.SchedulerTriggerId)(implicit encoder0: ArgEncoder[jorlan.SchedulerTriggerId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("deleteTrigger", OptionOf(Scalar()), arguments = List(Argument("value", value, "SchedulerTriggerId!")))
  def decideApproval(requestId : jorlan.ApprovalRequestId, approved : Boolean, note : scala.Option[String] = None)(implicit encoder0: ArgEncoder[jorlan.ApprovalRequestId], encoder1: ArgEncoder[Boolean], encoder2: ArgEncoder[scala.Option[String]]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("decideApproval", OptionOf(Scalar()), arguments = List(Argument("requestId", requestId, "ApprovalRequestId!"), Argument("approved", approved, "Boolean!"), Argument("note", note, "String")))
  def terminateSession(value : jorlan.AgentSessionId)(implicit encoder0: ArgEncoder[jorlan.AgentSessionId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("terminateSession", OptionOf(Scalar()), arguments = List(Argument("value", value, "AgentSessionId!")))
  def notifyUser(userId : jorlan.UserId, message : String)(implicit encoder0: ArgEncoder[jorlan.UserId], encoder1: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("notifyUser", OptionOf(Scalar()), arguments = List(Argument("userId", userId, "UserId!"), Argument("message", message, "String!")))
  def startOAuth[A](value : String)(innerSelection: SelectionBuilder[OAuthStartResult, A])(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("startOAuth", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "String!")))
  def revokeOAuth(value : String)(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("revokeOAuth", OptionOf(Scalar()), arguments = List(Argument("value", value, "String!")))
  def invokeTool(toolName : String, argsJson : String)(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[String]] = _root_.caliban.client.SelectionBuilder.Field("invokeTool", OptionOf(Scalar()), arguments = List(Argument("toolName", toolName, "String!"), Argument("argsJson", argsJson, "String!")))
  def grantCapability[A](userId : jorlan.UserId, capability : jorlan.CapabilityName, approvalMode : jorlan.ApprovalMode)(innerSelection: SelectionBuilder[CapabilityGrant, A])(implicit encoder0: ArgEncoder[jorlan.UserId], encoder1: ArgEncoder[jorlan.CapabilityName], encoder2: ArgEncoder[jorlan.ApprovalMode]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("grantCapability", OptionOf(Obj(innerSelection)), arguments = List(Argument("userId", userId, "UserId!"), Argument("capability", capability, "CapabilityName!"), Argument("approvalMode", approvalMode, "ApprovalMode!")))
  def grantCapabilityToRole[A](roleId : jorlan.RoleId, capability : jorlan.CapabilityName, approvalMode : jorlan.ApprovalMode)(innerSelection: SelectionBuilder[CapabilityGrant, A])(implicit encoder0: ArgEncoder[jorlan.RoleId], encoder1: ArgEncoder[jorlan.CapabilityName], encoder2: ArgEncoder[jorlan.ApprovalMode]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("grantCapabilityToRole", OptionOf(Obj(innerSelection)), arguments = List(Argument("roleId", roleId, "RoleId!"), Argument("capability", capability, "CapabilityName!"), Argument("approvalMode", approvalMode, "ApprovalMode!")))
  def revokeCapabilityGrant(value : jorlan.CapabilityGrantId)(implicit encoder0: ArgEncoder[jorlan.CapabilityGrantId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("revokeCapabilityGrant", OptionOf(Scalar()), arguments = List(Argument("value", value, "CapabilityGrantId!")))
  def linkChannelIdentity[A](userId : jorlan.UserId, channelType : String, channelUserId : String)(innerSelection: SelectionBuilder[ChannelIdentity, A])(implicit encoder0: ArgEncoder[jorlan.UserId], encoder1: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("linkChannelIdentity", OptionOf(Obj(innerSelection)), arguments = List(Argument("userId", userId, "UserId!"), Argument("channelType", channelType, "String!"), Argument("channelUserId", channelUserId, "String!")))
  def unlinkChannelIdentity(value : ChannelIdentityId)(implicit encoder0: ArgEncoder[ChannelIdentityId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("unlinkChannelIdentity", OptionOf(Scalar()), arguments = List(Argument("value", value, "ChannelIdentityId!")))
  def requestCheckpoint(value : jorlan.AgentSessionId)(implicit encoder0: ArgEncoder[jorlan.AgentSessionId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("requestCheckpoint", OptionOf(Scalar()), arguments = List(Argument("value", value, "AgentSessionId!")))
  def updateCheckpointPolicy[A](onSessionEnd : Boolean, onUserRequest : Boolean, timedIntervalTurns : scala.Option[Int] = None, beforeExternalEffect : Boolean)(innerSelection: SelectionBuilder[CheckpointPolicyConfig, A])(implicit encoder0: ArgEncoder[Boolean], encoder1: ArgEncoder[scala.Option[Int]]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("updateCheckpointPolicy", OptionOf(Obj(innerSelection)), arguments = List(Argument("onSessionEnd", onSessionEnd, "Boolean!"), Argument("onUserRequest", onUserRequest, "Boolean!"), Argument("timedIntervalTurns", timedIntervalTurns, "Int"), Argument("beforeExternalEffect", beforeExternalEffect, "Boolean!")))
  def reloadMcpServers(value : Unit)(implicit encoder0: ArgEncoder[Unit]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("reloadMcpServers", OptionOf(Scalar()), arguments = List(Argument("value", value, "Unit!")))
  def upsertMcpServer[A](name : String, transport : String, command : scala.Option[String] = None, args : List[String] = Nil, env : List[McpEnvVarInput] = Nil, url : scala.Option[String] = None, enabled : Boolean)(innerSelection: SelectionBuilder[McpServerView, A])(implicit encoder0: ArgEncoder[String], encoder1: ArgEncoder[scala.Option[String]], encoder2: ArgEncoder[List[String]], encoder3: ArgEncoder[List[McpEnvVarInput]], encoder4: ArgEncoder[Boolean]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("upsertMcpServer", OptionOf(Obj(innerSelection)), arguments = List(Argument("name", name, "String!"), Argument("transport", transport, "String!"), Argument("command", command, "String"), Argument("args", args, "[String!]!"), Argument("env", env, "[McpEnvVarInput!]!"), Argument("url", url, "String"), Argument("enabled", enabled, "Boolean!")))
  def deleteMcpServer(value : String)(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("deleteMcpServer", OptionOf(Scalar()), arguments = List(Argument("value", value, "String!")))
  def enableSkill(value : String)(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("enableSkill", OptionOf(Scalar()), arguments = List(Argument("value", value, "String!")))
  def disableSkill(value : String)(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("disableSkill", OptionOf(Scalar()), arguments = List(Argument("value", value, "String!")))
  def updateSkillConfig(name : String, configJson : String)(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Boolean]] = _root_.caliban.client.SelectionBuilder.Field("updateSkillConfig", OptionOf(Scalar()), arguments = List(Argument("name", name, "String!"), Argument("configJson", configJson, "String!")))
  def createSkillDraft[A](value : String)(innerSelection: SelectionBuilder[SkillVersionView, A])(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("createSkillDraft", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "String!")))
  def advanceSkillLifecycle[A](value : SkillVersionId)(innerSelection: SelectionBuilder[SkillLifecycleResultView, A])(implicit encoder0: ArgEncoder[SkillVersionId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("advanceSkillLifecycle", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "SkillVersionId!")))
  def approveSkillVersion[A](value : SkillVersionId)(innerSelection: SelectionBuilder[SkillLifecycleResultView, A])(implicit encoder0: ArgEncoder[SkillVersionId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("approveSkillVersion", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "SkillVersionId!")))
  def rejectSkillVersion[A](versionId : SkillVersionId, reason : String)(innerSelection: SelectionBuilder[SkillLifecycleResultView, A])(implicit encoder0: ArgEncoder[SkillVersionId]): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("rejectSkillVersion", OptionOf(Obj(innerSelection)), arguments = List(Argument("versionId", versionId, "SkillVersionId!"), Argument("reason", reason, "String!")))
}


  type Subscriptions = _root_.caliban.client.Operations.RootSubscription
object Subscriptions {
  def approvalNotifications[A](innerSelection: SelectionBuilder[ApprovalRequest, A]): SelectionBuilder[_root_.caliban.client.Operations.RootSubscription, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("approvalNotifications", OptionOf(Obj(innerSelection)))
  def eventLogTail[A](innerSelection: SelectionBuilder[EventLogJson, A]): SelectionBuilder[_root_.caliban.client.Operations.RootSubscription, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("eventLogTail", OptionOf(Obj(innerSelection)))
  def agentResponseStream[A](value : jorlan.AgentSessionId)(innerSelection: SelectionBuilder[ResponseChunk, A])(implicit encoder0: ArgEncoder[jorlan.AgentSessionId]): SelectionBuilder[_root_.caliban.client.Operations.RootSubscription, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("agentResponseStream", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "AgentSessionId!")))
  def toolEvents[A](value : jorlan.AgentSessionId)(innerSelection: SelectionBuilder[ToolEventResult, A])(implicit encoder0: ArgEncoder[jorlan.AgentSessionId]): SelectionBuilder[_root_.caliban.client.Operations.RootSubscription, scala.Option[A]] = _root_.caliban.client.SelectionBuilder.Field("toolEvents", OptionOf(Obj(innerSelection)), arguments = List(Argument("value", value, "AgentSessionId!")))
}



}