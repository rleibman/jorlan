/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import io.getquill.*
import jorlan.domain.*

import java.time.{Instant, LocalDateTime, ZoneOffset}

given Ordering[Instant] = Ordering.by(_.toEpochMilli)

// Instant ↔ LocalDateTime (Quill / MariaDB stores DATETIME as LocalDateTime)
given MappedEncoding[LocalDateTime, Instant] = MappedEncoding[LocalDateTime, Instant](_.toInstant(ZoneOffset.UTC))
given MappedEncoding[Instant, LocalDateTime] =
  MappedEncoding[Instant, LocalDateTime](LocalDateTime.ofInstant(_, ZoneOffset.UTC))

// Opaque ID encodings
given MappedEncoding[Long, UserId] = MappedEncoding(UserId.apply)
given MappedEncoding[UserId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, RoleId] = MappedEncoding(RoleId.apply)
given MappedEncoding[RoleId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, CapabilityGrantId] = MappedEncoding(CapabilityGrantId.apply)
given MappedEncoding[CapabilityGrantId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, ApprovalRequestId] = MappedEncoding(ApprovalRequestId.apply)
given MappedEncoding[ApprovalRequestId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, ApprovalDecisionId] = MappedEncoding(ApprovalDecisionId.apply)
given MappedEncoding[ApprovalDecisionId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, AgentId] = MappedEncoding(AgentId.apply)
given MappedEncoding[AgentId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, AgentSessionId] = MappedEncoding(AgentSessionId.apply)
given MappedEncoding[AgentSessionId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, ConversationId] = MappedEncoding(ConversationId.apply)
given MappedEncoding[ConversationId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, MessageId] = MappedEncoding(MessageId.apply)
given MappedEncoding[MessageId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, SkillId] = MappedEncoding(SkillId.apply)
given MappedEncoding[SkillId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, SkillVersionId] = MappedEncoding(SkillVersionId.apply)
given MappedEncoding[SkillVersionId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, ConnectorInstanceId] = MappedEncoding(ConnectorInstanceId.apply)
given MappedEncoding[ConnectorInstanceId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, MemoryRecordId] = MappedEncoding(MemoryRecordId.apply)
given MappedEncoding[MemoryRecordId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, MemoryEmbeddingId] = MappedEncoding(MemoryEmbeddingId.apply)
given MappedEncoding[MemoryEmbeddingId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, SchedulerJobId] = MappedEncoding(SchedulerJobId.apply)
given MappedEncoding[SchedulerJobId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, SchedulerTriggerId] = MappedEncoding(SchedulerTriggerId.apply)
given MappedEncoding[SchedulerTriggerId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, EventLogId] = MappedEncoding(EventLogId.apply)
given MappedEncoding[EventLogId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, ArtifactId] = MappedEncoding(ArtifactId.apply)
given MappedEncoding[ArtifactId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, WorkspaceId] = MappedEncoding(WorkspaceId.apply)
given MappedEncoding[WorkspaceId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, OrchestratorId] = MappedEncoding(OrchestratorId.apply)
given MappedEncoding[OrchestratorId, Long] = MappedEncoding(_.value)

// Enum encodings (stored as VARCHAR in DB)
given MappedEncoding[String, ChannelType] = MappedEncoding(ChannelType.valueOf)
given MappedEncoding[ChannelType, String] = MappedEncoding(_.toString)
given MappedEncoding[String, SessionStatus] = MappedEncoding(SessionStatus.valueOf)
given MappedEncoding[SessionStatus, String] = MappedEncoding(_.toString)
given MappedEncoding[String, MessageRole] = MappedEncoding(MessageRole.valueOf)
given MappedEncoding[MessageRole, String] = MappedEncoding(_.toString)
given MappedEncoding[String, SkillTier] = MappedEncoding(SkillTier.valueOf)
given MappedEncoding[SkillTier, String] = MappedEncoding(_.toString)
given MappedEncoding[String, SkillStatus] = MappedEncoding(SkillStatus.valueOf)
given MappedEncoding[SkillStatus, String] = MappedEncoding(_.toString)
given MappedEncoding[String, ConnectorType] = MappedEncoding(ConnectorType.valueOf)
given MappedEncoding[ConnectorType, String] = MappedEncoding(_.toString)
given MappedEncoding[String, MemoryScope] = MappedEncoding(MemoryScope.valueOf)
given MappedEncoding[MemoryScope, String] = MappedEncoding(_.toString)
given MappedEncoding[String, EventType] = MappedEncoding(EventType.valueOf)
given MappedEncoding[EventType, String] = MappedEncoding(_.toString)
given MappedEncoding[String, JobStatus] = MappedEncoding(JobStatus.valueOf)
given MappedEncoding[JobStatus, String] = MappedEncoding(_.toString)
given MappedEncoding[String, TriggerType] = MappedEncoding(TriggerType.valueOf)
given MappedEncoding[TriggerType, String] = MappedEncoding(_.toString)
given MappedEncoding[String, ApprovalMode] = MappedEncoding(ApprovalMode.valueOf)
given MappedEncoding[ApprovalMode, String] = MappedEncoding(_.toString)
given MappedEncoding[String, ApprovalStatus] = MappedEncoding(ApprovalStatus.valueOf)
given MappedEncoding[ApprovalStatus, String] = MappedEncoding(_.toString)
