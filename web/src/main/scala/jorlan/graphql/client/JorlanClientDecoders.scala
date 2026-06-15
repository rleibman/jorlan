/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.graphql.client

import caliban.client.CalibanClientError.DecodingError
import caliban.client.__Value.{__NumberValue, __StringValue}
import caliban.client.{ArgEncoder, ScalarDecoder}
import jorlan.*
import jorlan.graphql.client.JorlanClient.SchedulerJob.SchedulerJobView
import jorlan.graphql.client.JorlanClient.SchedulerTrigger.SchedulerTriggerView

import scala.util.Try

/** ScalarDecoder and ArgEncoder instances for the Jorlan opaque ID types. Wildcard-imported by the generated
  * JorlanClient via --imports.
  *
  * Uses `implicit val` (not `given`) so that `import JorlanClientDecoders._` picks them up. In Scala 3, `given`
  * instances are NOT imported by wildcard `._` — only by `import given`. The generated Caliban client uses Scala
  * 2-style implicit resolution via `._` imports.
  *
  * Note: Instant, Long, and Unit already have instances in ScalarDecoder / ArgEncoder companions so they are not
  * repeated here.
  */
object JorlanClientDecoders {

  private def longDecoder[A](
    wrap: Long => A,
    name: String,
  ): ScalarDecoder[A] = {
    case __NumberValue(v) =>
      Try(v.toLongExact).toEither.left
        .map(e => DecodingError(s"Can't build $name from $v", Some(e)))
        .map(wrap)
    case other => Left(DecodingError(s"Expected number for $name, got: $other"))
  }

  private def longEncoder[A](unwrap: A => Long): ArgEncoder[A] = (a: A) => ArgEncoder.long.encode(unwrap(a))

  given ScalarDecoder[UserId] = longDecoder(UserId(_), "UserId")
  given ScalarDecoder[RoleId] = longDecoder(RoleId(_), "RoleId")
  given ScalarDecoder[PermissionId] = longDecoder(PermissionId(_), "PermissionId")
  given ScalarDecoder[AgentId] = longDecoder(AgentId(_), "AgentId")
  given ScalarDecoder[AgentSessionId] = longDecoder(AgentSessionId(_), "AgentSessionId")
  given ScalarDecoder[ApprovalRequestId] = longDecoder(ApprovalRequestId(_), "ApprovalRequestId")
  given ScalarDecoder[EventLogId] = longDecoder(EventLogId(_), "EventLogId")
  given ScalarDecoder[WorkspaceId] = longDecoder(WorkspaceId(_), "WorkspaceId")
  given ScalarDecoder[MemoryRecordId] = longDecoder(MemoryRecordId(_), "MemoryRecordId")
  given ScalarDecoder[CapabilityGrantId] = longDecoder(CapabilityGrantId(_), "CapabilityGrantId")
  given ScalarDecoder[SchedulerJobId] = longDecoder(SchedulerJobId(_), "SchedulerJobId")
  given ScalarDecoder[SchedulerTriggerId] = longDecoder(SchedulerTriggerId(_), "SchedulerTriggerId")
  given ScalarDecoder[SkillId] = longDecoder(SkillId(_), "SkillId")

  given ScalarDecoder[ModelId] = {
    case __StringValue(v) => Right(ModelId(v))
    case other            => Left(DecodingError(s"Expected string for ModelId, got: $other"))
  }

  // ArgEncoder instances mirror ScalarDecoder above.
  // P7-030: A generic helper is not possible because opaque types do not expose a shared `.value`
  // typeclass — each type must be listed explicitly. A cross-module `HasLongValue[T]` typeclass
  // in the `model` module could eliminate this repetition in a future phase.
  given ArgEncoder[UserId] = longEncoder(_.value)
  given ArgEncoder[RoleId] = longEncoder(_.value)
  given ArgEncoder[PermissionId] = longEncoder(_.value)
  given ArgEncoder[AgentId] = longEncoder(_.value)
  given ArgEncoder[AgentSessionId] = longEncoder(_.value)
  given ArgEncoder[ApprovalRequestId] = longEncoder(_.value)
  given ArgEncoder[EventLogId] = longEncoder(_.value)
  given ArgEncoder[WorkspaceId] = longEncoder(_.value)
  given ArgEncoder[MemoryRecordId] = longEncoder(_.value)
  given ArgEncoder[CapabilityGrantId] = longEncoder(_.value)
  given ArgEncoder[SchedulerJobId] = longEncoder(_.value)
  given ArgEncoder[SchedulerTriggerId] = longEncoder(_.value)
  given ArgEncoder[SkillId] = longEncoder(_.value)
  given ArgEncoder[ModelId] = (id: ModelId) => ArgEncoder.string.encode(id.value)
  given ArgEncoder[CapabilityName] = (cn: CapabilityName) => ArgEncoder.string.encode(cn.value)

  given ScalarDecoder[CapabilityName] = {
    case __StringValue(v) => Right(CapabilityName(v))
    case other            => Left(DecodingError(s"Expected string for CapabilityName, got: $other"))
  }

  // ─── Enum decoders / encoders ─────────────────────────────────────────────────

  private def enumDecoder[A](
    valueOf: String => A,
    name:    String,
  ): ScalarDecoder[A] = {
    case __StringValue(v) =>
      Try(valueOf(v)).toEither.left.map(e => DecodingError(s"Can't build $name from '$v'", Some(e)))
    case other => Left(DecodingError(s"Expected string for $name, got: $other"))
  }

  private def enumEncoder[A](stringify: A => String): ArgEncoder[A] =
    (a: A) => caliban.client.__Value.__EnumValue(stringify(a))

  given ScalarDecoder[EventType] = enumDecoder(EventType.valueOf, "EventType")
  given ScalarDecoder[SessionStatus] = enumDecoder(SessionStatus.valueOf, "SessionStatus")
  given ScalarDecoder[ApprovalStatus] = enumDecoder(ApprovalStatus.valueOf, "ApprovalStatus")
  given ScalarDecoder[ApprovalMode] = enumDecoder(ApprovalMode.valueOf, "ApprovalMode")
  given ScalarDecoder[MemoryScope] = enumDecoder(MemoryScope.valueOf, "MemoryScope")
  given ScalarDecoder[Formality] = enumDecoder(Formality.valueOf, "Formality")
  given ScalarDecoder[ChannelType] = enumDecoder(ChannelType.valueOf, "ChannelType")
  given ScalarDecoder[RiskClass] = enumDecoder(RiskClass.valueOf, "RiskClass")
  given ArgEncoder[EventType] = enumEncoder(_.toString)
  given ArgEncoder[SessionStatus] = enumEncoder(_.toString)
  given ArgEncoder[ApprovalStatus] = enumEncoder(_.toString)
  given ArgEncoder[ApprovalMode] = enumEncoder(_.toString)
  given ArgEncoder[MemoryScope] = enumEncoder(_.toString)
  given ArgEncoder[Formality] = enumEncoder(_.toString)
  given ArgEncoder[ChannelType] = enumEncoder(_.toString)
  given ArgEncoder[RiskClass] = enumEncoder(_.toString)
  given ScalarDecoder[JobStatus] = enumDecoder(JobStatus.valueOf, "JobStatus")
  given ScalarDecoder[TriggerType] = enumDecoder(TriggerType.valueOf, "TriggerType")
  given ScalarDecoder[RetryBackoffPolicy] = enumDecoder(RetryBackoffPolicy.valueOf, "RetryBackoffPolicy")
  given ScalarDecoder[MissedRunPolicy] = enumDecoder(MissedRunPolicy.valueOf, "MissedRunPolicy")
  given ArgEncoder[TriggerType] = enumEncoder(_.toString)
  given ArgEncoder[RetryBackoffPolicy] = enumEncoder(_.toString)
  given ArgEncoder[MissedRunPolicy] = enumEncoder(_.toString)

  given Conversion[SchedulerJobView, SchedulerJob] =
    (j: SchedulerJobView) =>
      SchedulerJob(
        id = j.id,
        agentId = j.agentId,
        userId = j.userId,
        skillId = j.skillId,
        name = j.name,
        inputJson = j.inputJson,
        status = j.status,
        scheduledAt = j.scheduledAt,
        startedAt = j.startedAt,
        finishedAt = j.finishedAt,
        resultJson = j.resultJson,
        maxRetries = j.maxRetries,
        retryCount = j.retryCount,
        backoffSeconds = j.backoffSeconds,
        backoffPolicy = j.backoffPolicy,
        missedRunPolicy = j.missedRunPolicy,
        leasedAt = j.leasedAt,
        leasedBy = j.leasedBy,
        createdAt = j.createdAt,
      )

  given Conversion[SchedulerTriggerView, SchedulerTrigger] =
    (t: SchedulerTriggerView) =>
      SchedulerTrigger(
        id = t.id,
        jobId = t.jobId,
        triggerType = t.triggerType,
        expression = t.expression,
        enabled = t.enabled,
        createdAt = t.createdAt,
      )

}
