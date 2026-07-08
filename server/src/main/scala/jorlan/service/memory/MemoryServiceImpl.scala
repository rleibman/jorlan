/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.memory

import ai.{EmbeddingModel, EmbeddingStore}
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.segment.TextSegment
import jorlan.*
import jorlan.db.repository.ZIORepositories
import jorlan.service.*
import zio.*
import zio.json.*

import scala.jdk.CollectionConverters.*

class MemoryServiceImpl(
  repo:           ZIORepositories,
  accessPolicy:   MemoryAccessPolicy,
  summarizer:     CheckpointSummarizer,
  classifier:     MemoryClassifier,
  policyRef:      Ref[CheckpointPolicyConfig],
  embeddingStore: EmbeddingStore,
  embeddingModel: EmbeddingModel,
) extends MemoryService {

  private def embedAndStore(stored: MemoryRecord): UIO[Unit] = {
    val (text, segment) = MemoryServiceImpl.embeddingSegment(stored)
    ZIO
      .attemptBlocking {
        val embedding = embeddingModel.embed(text).content()
        embeddingStore.add(embedding, segment)
      }
      // A failed embedding leaves the record permanently invisible to semantic recall until the startup
      // reconciler backfills it — worth a warning, never worth failing the store itself.
      .tapError(e => ZIO.logWarning(s"Failed to embed memory record ${stored.id.value}: ${e.getMessage}"))
      .forkDaemon.ignore
  }

  override def store(record: MemoryRecord): IO[JorlanError, MemoryRecord] =
    repo.memory.upsert(record).mapError(JorlanError(_)).tap(embedAndStore)

  override def query(
    scope:   MemoryScope,
    userId:  UserId,
    agentId: AgentId,
    text:    Option[String] = None,
  ): IO[JorlanError, List[MemoryRecord]] = {
    val userFilter = scope match {
      case MemoryScope.Shared | MemoryScope.Workspace => None
      case _                                          => Some(userId)
    }
    repo.memory
      .search(MemorySearch(scope = scope, userId = userFilter, textSearch = text))
      .mapError(JorlanError(_))
      .flatMap(records => accessPolicy.filter(userId, agentId, records))
  }

  override def forget(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, Boolean] =
    repo.memory
      .getById(id)
      .mapError(JorlanError(_))
      .flatMap {
        case None    => ZIO.succeed(false)
        case Some(r) =>
          ZIO.unless(r.userId.contains(requestingUserId))(
            ZIO.fail(JorlanError(s"Access denied: cannot delete another user's memory record")),
          ) *> repo.memory.delete(id).mapBoth(JorlanError(_), _ > 0L)
      }

  override def markShared(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord] =
    rescope(id, requestingUserId, MemoryScope.Shared)

  override def markPrivate(
    id:               MemoryRecordId,
    requestingUserId: UserId,
  ): IO[JorlanError, MemoryRecord] =
    rescope(id, requestingUserId, MemoryScope.Private)

  private def rescope(
    id:               MemoryRecordId,
    requestingUserId: UserId,
    newScope:         MemoryScope,
  ): IO[JorlanError, MemoryRecord] =
    repo.memory
      .getById(id)
      .mapError(JorlanError(_))
      .flatMap(ZIO.fromOption(_).orElseFail(JorlanError(s"MemoryRecord $id not found")))
      .flatMap { r =>
        ZIO.unless(r.userId.contains(requestingUserId))(
          ZIO.fail(JorlanError(s"Access denied: cannot modify another user's memory record")),
        ) *>
          repo.memory.updateScope(id, newScope).mapBoth(JorlanError(_), _ => r.copy(scope = newScope))
      }

  @scala.annotation.nowarn("msg=IsUnionOf")
  override def checkpoint(
    sessionId: AgentSessionId,
    messages:  List[Message],
    userId:    UserId,
    agentId:   AgentId,
    trigger:   CheckpointTrigger,
  ): IO[JorlanError, Unit] =
    for {
      config  <- policyRef.get
      should  <- CheckpointPolicy.fromConfig(config).shouldCheckpoint(trigger, None)
      records <- ZIO.when(should)(summarizer.summarize(messages, userId, agentId))
      _       <- ZIO.foreachParDiscard(records.getOrElse(List.empty)) { record =>
        val contentText = record.value.asObject
          .flatMap(_.get("text"))
          .flatMap(_.asString)
          .getOrElse(record.value.toString)
        classifier.classify(contentText).flatMap { scope =>
          repo.memory.upsert(record.copy(scope = scope)).mapError(JorlanError(_)).tap(embedAndStore)
        }
      }
      _ <- ZIO.when(should) {
        Clock.instant.flatMap { now =>
          repo.eventLog
            .append(
              EventLog[Nothing](
                id = EventLogId.empty,
                eventType = EventType.MemoryCheckpointed,
                actorId = Some(userId),
                agentId = Option.when(agentId != AgentId.empty)(agentId),
                sessionId = Some(sessionId),
                resource = None,
                payloadJson = None,
                occurredAt = now,
              ),
            )
            .mapError(JorlanError(_))
            .unit
        }
      }
    } yield ()

  override def requestCheckpoint(
    sessionId: AgentSessionId,
    userId:    UserId,
    agentId:   AgentId,
  ): IO[JorlanError, Unit] =
    for {
      convOpt <- repo.conversation
        .search(
          ConversationSearch(
            sessionId = sessionId,
            pageSize = 1,
            sorts = Some(Sort(ConversationOrder.StartedAt, OrderDirection.Desc)),
          ),
        )
        .mapError(JorlanError(_))
      messages <- convOpt.headOption match {
        case None    => ZIO.succeed(List.empty)
        case Some(c) =>
          repo.conversation
            .searchMessages(
              MessageSearch(
                conversationId = c.id,
                pageSize = 100,
                sorts = Some(Sort(MessageOrder.CreatedAt, OrderDirection.Asc)),
              ),
            )
            .mapError(JorlanError(_))
      }
      _ <- checkpoint(sessionId, messages, userId, agentId, CheckpointTrigger.UserRequest)
    } yield ()

  override def semanticQuery(
    scope:     MemoryScope,
    userId:    UserId,
    agentId:   AgentId,
    queryText: String,
    limit:     Int,
  ): IO[JorlanError, List[MemoryRecord]] = {
    import dev.langchain4j.store.embedding.EmbeddingSearchRequest
    import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder
    ZIO
      .attemptBlocking {
        val queryEmbedding = embeddingModel.embed(queryText).content()
        // Deliberately filters on userId only, NOT on `scope`: semantic recall should surface any memory the
        // user can see (User, Workspace, Shared) — household facts like the timezone live in Workspace scope
        // and must still reach prompts.
        val filter = MetadataFilterBuilder.metadataKey("userId").isEqualTo(userId.value.toString)
        val request = EmbeddingSearchRequest
          .builder()
          .queryEmbedding(queryEmbedding)
          .maxResults(limit)
          .minScore(MemoryServiceImpl.semanticQueryMinScore)
          .filter(filter)
          .build()
        embeddingStore.search(request).matches().asScala.toList.flatMap { m =>
          val meta = m.embedded().metadata()
          meta.getString("memoryRecordId").nn.toLongOption.map(MemoryRecordId(_))
        }
      }.mapError(e => JorlanError(e.getMessage.nn))
      .flatMap { ids =>
        ZIO.foreach(ids)(id => repo.memory.getById(id).mapError(JorlanError(_))).map(_.flatten)
      }
      .catchAll(_ => ZIO.succeed(List.empty))
  }

  override def getCheckpointPolicy: UIO[CheckpointPolicyConfig] = policyRef.get

  override def updateCheckpointPolicy(config: CheckpointPolicyConfig): IO[JorlanError, Unit] =
    policyRef.set(config) *>
      config.toJsonAST.fold(
        err => ZIO.fail(JorlanError(s"Failed to serialise CheckpointPolicyConfig: $err")),
        json => repo.setting.set(CheckpointPolicyConfig.serverSettingsKey, json).mapError(JorlanError(_)),
      )

}

object MemoryServiceImpl {

  /** Minimum cosine similarity for a memory record to be considered relevant to the current prompt. Below this, a
    * record is treated as noise rather than context worth spending prompt budget on.
    */
  private val semanticQueryMinScore: Double = 0.6

  /** The text and metadata segment a memory record is embedded as. Shared with [[MemoryEmbeddingReconciler]] so the
    * startup backfill produces byte-identical index entries to the live [[MemoryService.store]] path.
    */
  def embeddingSegment(record: MemoryRecord): (String, TextSegment) = {
    val text = s"${record.recordKey}: ${record.value}"
    val metaMap = Map[String, AnyRef](
      "memoryRecordId" -> record.id.value.toString,
      "userId"         -> record.userId.map(_.value.toString).getOrElse(""),
      "agentId"        -> record.agentId.map(_.value.toString).getOrElse(""),
      "scope"          -> record.scope.toString,
    ).asJava
    (text, TextSegment.from(text, Metadata.from(metaMap)))
  }

  val live: URLayer[ZIORepositories & ModelGateway & EmbeddingStore & EmbeddingModel, MemoryService] =
    ZLayer.fromZIO(
      for {
        repo           <- ZIO.service[ZIORepositories]
        gateway        <- ZIO.service[ModelGateway]
        embeddingStore <- ZIO.service[EmbeddingStore]
        embeddingModel <- ZIO.service[EmbeddingModel]
        savedConfig    <- repo.setting
          .get(CheckpointPolicyConfig.serverSettingsKey).mapBoth(
            JorlanError(_),
            {
              case Some(json) => json.as[CheckpointPolicyConfig].getOrElse(CheckpointPolicyConfig.default)
              case None       => CheckpointPolicyConfig.default
            },
          ).orElseSucceed(CheckpointPolicyConfig.default)
        policyRef <- Ref.make(savedConfig)
      } yield MemoryServiceImpl(
        repo,
        MemoryAccessPolicyImpl(),
        CheckpointSummarizerImpl(gateway),
        MemoryClassifierImpl(),
        policyRef,
        embeddingStore,
        embeddingModel,
      ),
    )

}
