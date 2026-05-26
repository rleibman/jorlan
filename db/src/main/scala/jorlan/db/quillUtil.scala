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

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.getquill.*
import jorlan.{AppConfig, DataSourceConfig}
import jorlan.domain.*
import just.semver.{ParseError, SemVer}
import zio.http.MediaType
import zio.json.*
import zio.json.ast.Json

import java.net.URI
import java.security.{KeyFactory, PublicKey}
import java.security.spec.X509EncodedKeySpec
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.Base64
import scala.language.unsafeNulls

/** Constructs a [[HikariDataSource]] from [[AppConfig]].
  *
  * Kept in the `db` module so `model` does not depend on HikariCP. The pool is unmanaged — callers are responsible for
  * closing it on shutdown.
  */
def makeDataSource(config: AppConfig): HikariDataSource = {
  val c = config.jorlan.db.dataSource
  val hc = new HikariConfig()
  hc.setDriverClassName(c.driver)
  hc.setJdbcUrl(c.url)
  hc.setUsername(c.user)
  hc.setPassword(c.password)
  hc.setMaximumPoolSize(c.maximumPoolSize)
  hc.setMinimumIdle(c.minimumIdle)
  hc.setConnectionTimeout(c.connectionTimeoutMillis)
  hc.setAutoCommit(true)
  new HikariDataSource(hc)
}

/** Quill / MariaDB utility: `Ordering[Instant]` is not provided by the standard library. Required for in-memory
  * post-filter comparisons in queries that Quill cannot translate directly.
  */
given Ordering[Instant] = Ordering.by(_.toEpochMilli)

/** Quill column mappings between `java.time.Instant` and `java.time.LocalDateTime`.
  *
  * MariaDB `DATETIME` columns are surfaced by the JDBC driver as `LocalDateTime`; these `MappedEncoding`s teach Quill
  * how to convert transparently, always treating times as UTC.
  */
given MappedEncoding[LocalDateTime, Instant] = MappedEncoding[LocalDateTime, Instant](_.toInstant(ZoneOffset.UTC))
given MappedEncoding[Instant, LocalDateTime] =
  MappedEncoding[Instant, LocalDateTime](LocalDateTime.ofInstant(_, ZoneOffset.UTC))

/** Quill `MappedEncoding`s for the 19 opaque `Long`-backed ID types.
  *
  * All opaque types in `ids.scala` erase to `Long` at runtime, so a pair of encodings (read and write) is needed for
  * each.
  */
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
given MappedEncoding[Long, PermissionId] = MappedEncoding(PermissionId.apply)
given MappedEncoding[PermissionId, Long] = MappedEncoding(_.value)
given MappedEncoding[Long, ChannelIdentityId] = MappedEncoding(ChannelIdentityId.apply)
given MappedEncoding[ChannelIdentityId, Long] = MappedEncoding(_.value)

/** Quill `MappedEncoding`s for string-backed opaque value types. */
given MappedEncoding[String, ModelId] = MappedEncoding(ModelId.apply)
given MappedEncoding[ModelId, String] = MappedEncoding(_.value)
given MappedEncoding[String, EmbeddingModelId] = MappedEncoding(EmbeddingModelId.apply)
given MappedEncoding[EmbeddingModelId, String] = MappedEncoding(_.value)

/** Quill `MappedEncoding`s for external library types stored as `VARCHAR`/`TEXT` in MariaDB. */
given MappedEncoding[Json, String] = MappedEncoding(_.toJson)
given MappedEncoding[String, Json] =
  MappedEncoding(s => s.fromJson[Json].fold(error => throw new RuntimeException(s"Invalid JSON value: $error"), identity))

given MappedEncoding[Vector[Float], String] = MappedEncoding(v => v.toJson)
given MappedEncoding[String, Vector[Float]] =
  MappedEncoding(s =>
    s.fromJson[Vector[Float]].fold(error => throw new RuntimeException(s"Invalid Vector[Float] JSON value: $error"), identity)
  )

given MappedEncoding[URI, String] = MappedEncoding(_.toString)
given MappedEncoding[String, URI] = MappedEncoding(s => URI.create(s))

given MappedEncoding[SemVer, String] = MappedEncoding(_.render)
given MappedEncoding[String, SemVer] =
  MappedEncoding(s => SemVer.parse(s).fold(e => throw new RuntimeException(ParseError.render(e)), identity))

given MappedEncoding[MediaType, String] = MappedEncoding(_.fullType)
given MappedEncoding[String, MediaType] =
  MappedEncoding { s =>
    MediaType
      .forContentType(s)
      .orElse(MediaType.parseCustomMediaType(s))
      .getOrElse(throw new RuntimeException(s"Unrecognised MediaType: $s"))
  }

given MappedEncoding[PublicKey, String] =
  MappedEncoding { key =>
    val b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded).nn
    s"-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----"
  }
given MappedEncoding[String, PublicKey] =
  MappedEncoding { pem =>
    val cleaned = pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "")
    val bytes = Base64.getDecoder.decode(cleaned.nn)
    val spec = new X509EncodedKeySpec(bytes)
    List("RSA", "EC", "Ed25519").iterator
      .flatMap { alg =>
        try Some(KeyFactory.getInstance(alg).generatePublic(spec))
        catch { case _: Exception => None }
      }
      .nextOption()
      .getOrElse(throw new RuntimeException("Could not parse public key: unknown algorithm"))
  }

/** Quill `MappedEncoding`s for the 12 domain enums stored as `VARCHAR` in MariaDB. Encoding uses `toString` (stored
  * name); decoding uses `valueOf` (case-sensitive).
  */
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
