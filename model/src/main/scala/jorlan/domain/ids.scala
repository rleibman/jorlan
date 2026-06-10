/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.domain

import zio.json.{JsonDecoder, JsonEncoder}
import zio.{UIO, ZIO}

import java.util.UUID
import scala.util.Try

/** Opaque primary-key types, one per entity.
  *
  * Each type is backed by a `Long` (MariaDB auto-increment) and is zero-cost at runtime. The companion provides `apply`
  * for construction, `empty` (value `0L`) as a sentinel for unsaved records, a `value` extension to unwrap, and JSON
  * codecs so IDs round-trip transparently through the API layer.
  *
  * All 19 types erase to `Long` on the JVM; keeping them in separate Quill repository implementation classes avoids the
  * "Conflicting definitions" compile error that arises from type erasure when all opaque-typed methods live in the same
  * class.
  */

/** Primary key for [[User]] records. */
opaque type UserId = Long
object UserId {

  def apply(v: Long): UserId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: UserId = 0L
  extension (id: UserId) { def value: Long = id }
  given JsonEncoder[UserId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[UserId] = JsonDecoder[Long].map(UserId(_))

}

/** Primary key for [[Role]] records. */
opaque type RoleId = Long
object RoleId {

  def apply(v: Long): RoleId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: RoleId = 0L
  extension (id: RoleId) { def value: Long = id }
  given JsonEncoder[RoleId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[RoleId] = JsonDecoder[Long].map(RoleId(_))

}

/** Primary key for [[CapabilityGrant]] records. */
opaque type CapabilityGrantId = Long
object CapabilityGrantId {

  def apply(v: Long): CapabilityGrantId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: CapabilityGrantId = 0L
  extension (id: CapabilityGrantId) { def value: Long = id }
  given JsonEncoder[CapabilityGrantId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[CapabilityGrantId] = JsonDecoder[Long].map(CapabilityGrantId(_))

}

/** Primary key for [[ApprovalRequest]] records. */
opaque type ApprovalRequestId = Long
object ApprovalRequestId {

  def apply(v: Long): ApprovalRequestId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: ApprovalRequestId = 0L
  extension (id: ApprovalRequestId) { def value: Long = id }
  given JsonEncoder[ApprovalRequestId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ApprovalRequestId] = JsonDecoder[Long].map(ApprovalRequestId(_))

}

/** Primary key for [[ApprovalDecision]] records. */
opaque type ApprovalDecisionId = Long
object ApprovalDecisionId {

  def apply(v: Long): ApprovalDecisionId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: ApprovalDecisionId = 0L
  extension (id: ApprovalDecisionId) { def value: Long = id }
  given JsonEncoder[ApprovalDecisionId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ApprovalDecisionId] = JsonDecoder[Long].map(ApprovalDecisionId(_))

}

/** Primary key for [[Agent]] records. */
opaque type AgentId = Long
object AgentId {

  def apply(v: Long): AgentId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: AgentId = 0L
  extension (id: AgentId) { def value: Long = id }
  given JsonEncoder[AgentId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[AgentId] = JsonDecoder[Long].map(AgentId(_))

}

/** Primary key for [[AgentSession]] records. */
opaque type AgentSessionId = Long
object AgentSessionId {

  def apply(v: Long): AgentSessionId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: AgentSessionId = 0L
  extension (id: AgentSessionId) { def value: Long = id }
  given JsonEncoder[AgentSessionId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[AgentSessionId] = JsonDecoder[Long].map(AgentSessionId(_))

}

/** Primary key for [[Conversation]] records. */
opaque type ConversationId = Long
object ConversationId {

  def apply(v: Long): ConversationId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: ConversationId = 0L
  extension (id: ConversationId) { def value: Long = id }
  given JsonEncoder[ConversationId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ConversationId] = JsonDecoder[Long].map(ConversationId(_))

}

/** Primary key for [[Message]] records. */
opaque type MessageId = Long
object MessageId {

  def apply(v: Long): MessageId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: MessageId = 0L
  extension (id: MessageId) { def value: Long = id }
  given JsonEncoder[MessageId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[MessageId] = JsonDecoder[Long].map(MessageId(_))

}

/** Primary key for [[SkillRecord]] records. */
opaque type SkillId = Long
object SkillId {

  def apply(v: Long): SkillId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: SkillId = 0L
  extension (id: SkillId) { def value: Long = id }
  given JsonEncoder[SkillId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[SkillId] = JsonDecoder[Long].map(SkillId(_))

}

/** Primary key for [[SkillVersion]] records. */
opaque type SkillVersionId = Long
object SkillVersionId {

  def apply(v: Long): SkillVersionId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: SkillVersionId = 0L
  extension (id: SkillVersionId) { def value: Long = id }
  given JsonEncoder[SkillVersionId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[SkillVersionId] = JsonDecoder[Long].map(SkillVersionId(_))

}

/** Primary key for [[ConnectorInstance]] records. */
opaque type ConnectorInstanceId = Long
object ConnectorInstanceId {

  def apply(v: Long): ConnectorInstanceId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: ConnectorInstanceId = 0L
  extension (id: ConnectorInstanceId) { def value: Long = id }
  given JsonEncoder[ConnectorInstanceId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ConnectorInstanceId] = JsonDecoder[Long].map(ConnectorInstanceId(_))

}

/** Primary key for [[MemoryRecord]] records. */
opaque type MemoryRecordId = Long
object MemoryRecordId {

  def apply(v: Long): MemoryRecordId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: MemoryRecordId = 0L
  extension (id: MemoryRecordId) { def value: Long = id }
  given JsonEncoder[MemoryRecordId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[MemoryRecordId] = JsonDecoder[Long].map(MemoryRecordId(_))

}

/** Primary key for [[MemoryEmbedding]] records. */
opaque type MemoryEmbeddingId = Long
object MemoryEmbeddingId {

  def apply(v: Long): MemoryEmbeddingId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: MemoryEmbeddingId = 0L
  extension (id: MemoryEmbeddingId) { def value: Long = id }
  given JsonEncoder[MemoryEmbeddingId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[MemoryEmbeddingId] = JsonDecoder[Long].map(MemoryEmbeddingId(_))

}

/** Primary key for [[SchedulerJob]] records. */
opaque type SchedulerJobId = Long
object SchedulerJobId {

  def apply(v: Long): SchedulerJobId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: SchedulerJobId = 0L
  extension (id: SchedulerJobId) { def value: Long = id }
  given JsonEncoder[SchedulerJobId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[SchedulerJobId] = JsonDecoder[Long].map(SchedulerJobId(_))

}

/** Primary key for [[SchedulerTrigger]] records. */
opaque type SchedulerTriggerId = Long
object SchedulerTriggerId {

  def apply(v: Long): SchedulerTriggerId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: SchedulerTriggerId = 0L
  extension (id: SchedulerTriggerId) { def value: Long = id }
  given JsonEncoder[SchedulerTriggerId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[SchedulerTriggerId] = JsonDecoder[Long].map(SchedulerTriggerId(_))

}

/** Primary key for [[EventLog]] records. */
opaque type EventLogId = Long
object EventLogId {

  def apply(v: Long): EventLogId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: EventLogId = 0L
  extension (id: EventLogId) { def value: Long = id }
  given JsonEncoder[EventLogId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[EventLogId] = JsonDecoder[Long].map(EventLogId(_))

}

/** Primary key for [[Artifact]] records. */
opaque type ArtifactId = Long
object ArtifactId {

  def apply(v: Long): ArtifactId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: ArtifactId = 0L
  extension (id: ArtifactId) { def value: Long = id }
  given JsonEncoder[ArtifactId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ArtifactId] = JsonDecoder[Long].map(ArtifactId(_))

}

/** Primary key for [[Workspace]] records. */
opaque type WorkspaceId = Long
object WorkspaceId {

  def apply(v: Long): WorkspaceId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: WorkspaceId = 0L
  extension (id: WorkspaceId) { def value: Long = id }
  given JsonEncoder[WorkspaceId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[WorkspaceId] = JsonDecoder[Long].map(WorkspaceId(_))

}

/** Primary key for [[OrchestratorIdentity]] records. */
opaque type OrchestratorId = Long
object OrchestratorId {

  def apply(v: Long): OrchestratorId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: OrchestratorId = 0L
  extension (id: OrchestratorId) { def value: Long = id }
  given JsonEncoder[OrchestratorId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[OrchestratorId] = JsonDecoder[Long].map(OrchestratorId(_))

}

/** Primary key for [[jorlan.domain.Permission]] records. */
opaque type PermissionId = Long
object PermissionId {

  def apply(v: Long): PermissionId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: PermissionId = 0L
  extension (id: PermissionId) { def value: Long = id }
  given JsonEncoder[PermissionId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[PermissionId] = JsonDecoder[Long].map(PermissionId(_))

}

/** Primary key for [[jorlan.domain.ChannelIdentity]] records. */
opaque type ChannelIdentityId = Long
object ChannelIdentityId {

  def apply(v: Long): ChannelIdentityId = v

  /** Sentinel value representing an unsaved / default-constructed record. */
  val empty: ChannelIdentityId = 0L
  extension (id: ChannelIdentityId) { def value: Long = id }
  given JsonEncoder[ChannelIdentityId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ChannelIdentityId] = JsonDecoder[Long].map(ChannelIdentityId(_))

}

/** Identifies an LLM model (e.g. `"llama3"`, `"gpt-4o"`). Kept opaque to prevent accidental confusion with other
  * string-typed fields.
  */
opaque type ModelId = String
object ModelId {

  def apply(v:   String): ModelId = v
  extension (id: ModelId) { def value: String = id }
  given JsonEncoder[ModelId] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[ModelId] = JsonDecoder[String].map(ModelId(_))

}

/** Identifies the embedding model that produced a [[jorlan.domain.MemoryEmbedding]] vector (e.g. `"nomic-embed-text"`).
  */
opaque type EmbeddingModelId = String
object EmbeddingModelId {

  def apply(v:   String): EmbeddingModelId = v
  extension (id: EmbeddingModelId) { def value: String = id }
  given JsonEncoder[EmbeddingModelId] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[EmbeddingModelId] = JsonDecoder[String].map(EmbeddingModelId(_))

}

/** Identifies a client connection (browser tab, API client, etc.) within an authenticated session.
  *
  * Backed by a UUID string so it can be generated client-side or server-side without coordination.
  */
opaque type ConnectionId = UUID
object ConnectionId {

  def apply(s:   UUID): ConnectionId = s
  def unsafeRandom:     ConnectionId = java.util.UUID.randomUUID()
  val randomZIO:        UIO[ConnectionId] = ZIO.randomWith(_.nextUUID.map(u => ConnectionId(u)))
  extension (id: ConnectionId) { def value: UUID = id }
  given JsonEncoder[ConnectionId] = JsonEncoder[String].contramap(_.value.toString)
  given JsonDecoder[ConnectionId] =
    JsonDecoder[String].mapOrFail(s =>
      Try(ConnectionId(UUID.fromString(s))).toEither.left.map(t => s"Invalid ConnectionId UUID string: ${t.getMessage}"),
    )

}
