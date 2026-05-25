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

// One opaque ID type per entity — all backed by Long (DB auto-increment).
// Each companion provides apply, empty, a value extractor extension, and JSON codecs.

opaque type UserId = Long
object UserId {

  def apply(v:   Long): UserId = v
  val empty:            UserId = 0L
  extension (id: UserId) { def value: Long = id }
  given JsonEncoder[UserId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[UserId] = JsonDecoder[Long].map(UserId(_))

}

opaque type RoleId = Long
object RoleId {

  def apply(v:   Long): RoleId = v
  val empty:            RoleId = 0L
  extension (id: RoleId) { def value: Long = id }
  given JsonEncoder[RoleId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[RoleId] = JsonDecoder[Long].map(RoleId(_))

}

opaque type CapabilityGrantId = Long
object CapabilityGrantId {

  def apply(v:   Long): CapabilityGrantId = v
  val empty:            CapabilityGrantId = 0L
  extension (id: CapabilityGrantId) { def value: Long = id }
  given JsonEncoder[CapabilityGrantId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[CapabilityGrantId] = JsonDecoder[Long].map(CapabilityGrantId(_))

}

opaque type ApprovalRequestId = Long
object ApprovalRequestId {

  def apply(v:   Long): ApprovalRequestId = v
  val empty:            ApprovalRequestId = 0L
  extension (id: ApprovalRequestId) { def value: Long = id }
  given JsonEncoder[ApprovalRequestId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ApprovalRequestId] = JsonDecoder[Long].map(ApprovalRequestId(_))

}

opaque type ApprovalDecisionId = Long
object ApprovalDecisionId {

  def apply(v:   Long): ApprovalDecisionId = v
  val empty:            ApprovalDecisionId = 0L
  extension (id: ApprovalDecisionId) { def value: Long = id }
  given JsonEncoder[ApprovalDecisionId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ApprovalDecisionId] = JsonDecoder[Long].map(ApprovalDecisionId(_))

}

opaque type AgentId = Long
object AgentId {

  def apply(v:   Long): AgentId = v
  val empty:            AgentId = 0L
  extension (id: AgentId) { def value: Long = id }
  given JsonEncoder[AgentId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[AgentId] = JsonDecoder[Long].map(AgentId(_))

}

opaque type AgentSessionId = Long
object AgentSessionId {

  def apply(v:   Long): AgentSessionId = v
  val empty:            AgentSessionId = 0L
  extension (id: AgentSessionId) { def value: Long = id }
  given JsonEncoder[AgentSessionId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[AgentSessionId] = JsonDecoder[Long].map(AgentSessionId(_))

}

opaque type ConversationId = Long
object ConversationId {

  def apply(v:   Long): ConversationId = v
  val empty:            ConversationId = 0L
  extension (id: ConversationId) { def value: Long = id }
  given JsonEncoder[ConversationId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ConversationId] = JsonDecoder[Long].map(ConversationId(_))

}

opaque type MessageId = Long
object MessageId {

  def apply(v:   Long): MessageId = v
  val empty:            MessageId = 0L
  extension (id: MessageId) { def value: Long = id }
  given JsonEncoder[MessageId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[MessageId] = JsonDecoder[Long].map(MessageId(_))

}

opaque type SkillId = Long
object SkillId {

  def apply(v:   Long): SkillId = v
  val empty:            SkillId = 0L
  extension (id: SkillId) { def value: Long = id }
  given JsonEncoder[SkillId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[SkillId] = JsonDecoder[Long].map(SkillId(_))

}

opaque type SkillVersionId = Long
object SkillVersionId {

  def apply(v:   Long): SkillVersionId = v
  val empty:            SkillVersionId = 0L
  extension (id: SkillVersionId) { def value: Long = id }
  given JsonEncoder[SkillVersionId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[SkillVersionId] = JsonDecoder[Long].map(SkillVersionId(_))

}

opaque type ConnectorInstanceId = Long
object ConnectorInstanceId {

  def apply(v:   Long): ConnectorInstanceId = v
  val empty:            ConnectorInstanceId = 0L
  extension (id: ConnectorInstanceId) { def value: Long = id }
  given JsonEncoder[ConnectorInstanceId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ConnectorInstanceId] = JsonDecoder[Long].map(ConnectorInstanceId(_))

}

opaque type MemoryRecordId = Long
object MemoryRecordId {

  def apply(v:   Long): MemoryRecordId = v
  val empty:            MemoryRecordId = 0L
  extension (id: MemoryRecordId) { def value: Long = id }
  given JsonEncoder[MemoryRecordId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[MemoryRecordId] = JsonDecoder[Long].map(MemoryRecordId(_))

}

opaque type MemoryEmbeddingId = Long
object MemoryEmbeddingId {

  def apply(v:   Long): MemoryEmbeddingId = v
  val empty:            MemoryEmbeddingId = 0L
  extension (id: MemoryEmbeddingId) { def value: Long = id }
  given JsonEncoder[MemoryEmbeddingId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[MemoryEmbeddingId] = JsonDecoder[Long].map(MemoryEmbeddingId(_))

}

opaque type SchedulerJobId = Long
object SchedulerJobId {

  def apply(v:   Long): SchedulerJobId = v
  val empty:            SchedulerJobId = 0L
  extension (id: SchedulerJobId) { def value: Long = id }
  given JsonEncoder[SchedulerJobId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[SchedulerJobId] = JsonDecoder[Long].map(SchedulerJobId(_))

}

opaque type SchedulerTriggerId = Long
object SchedulerTriggerId {

  def apply(v:   Long): SchedulerTriggerId = v
  val empty:            SchedulerTriggerId = 0L
  extension (id: SchedulerTriggerId) { def value: Long = id }
  given JsonEncoder[SchedulerTriggerId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[SchedulerTriggerId] = JsonDecoder[Long].map(SchedulerTriggerId(_))

}

opaque type EventLogId = Long
object EventLogId {

  def apply(v:   Long): EventLogId = v
  val empty:            EventLogId = 0L
  extension (id: EventLogId) { def value: Long = id }
  given JsonEncoder[EventLogId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[EventLogId] = JsonDecoder[Long].map(EventLogId(_))

}

opaque type ArtifactId = Long
object ArtifactId {

  def apply(v:   Long): ArtifactId = v
  val empty:            ArtifactId = 0L
  extension (id: ArtifactId) { def value: Long = id }
  given JsonEncoder[ArtifactId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[ArtifactId] = JsonDecoder[Long].map(ArtifactId(_))

}

opaque type WorkspaceId = Long
object WorkspaceId {

  def apply(v:   Long): WorkspaceId = v
  val empty:            WorkspaceId = 0L
  extension (id: WorkspaceId) { def value: Long = id }
  given JsonEncoder[WorkspaceId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[WorkspaceId] = JsonDecoder[Long].map(WorkspaceId(_))

}

opaque type OrchestratorId = Long
object OrchestratorId {

  def apply(v:   Long): OrchestratorId = v
  val empty:            OrchestratorId = 0L
  extension (id: OrchestratorId) { def value: Long = id }
  given JsonEncoder[OrchestratorId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[OrchestratorId] = JsonDecoder[Long].map(OrchestratorId(_))

}
