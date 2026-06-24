/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.memory

import jorlan.*
import jorlan.db.repository.*
import jorlan.*
import jorlan.service.llm.FakeModelGateway
import jorlan.service.memory.MemoryServiceImpl
import jorlan.service.{CheckpointTrigger, MemoryService}
import jorlan.testing.{InMemoryRepositories, NoOpEmbeddingLayers}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

/** Covers [[MemoryServiceImpl]] `.mapError(JorlanError(_))` lambdas by injecting a memory repository that fails on
  * specific operations.
  */
object MemoryServiceFailingRepoSpec extends ZIOSpecDefault {

  private val userId = UserId(1L)
  private val agentId = AgentId(1L)

  private val repoError: RepositoryError = RepositoryError("simulated memory repo failure")

  private def makeRecord(key: String): MemoryRecord = {
    val now = Instant.now()
    MemoryRecord(
      id = MemoryRecordId.empty,
      scope = MemoryScope.User,
      userId = Some(userId),
      workspaceId = None,
      agentId = Some(agentId),
      recordKey = key,
      value = Json.Obj("text" -> Json.Str("test content")),
      ttl = None,
      createdAt = now,
      updatedAt = now,
    )
  }

  private val storedRecord: MemoryRecord = makeRecord("key1").copy(id = MemoryRecordId(42L))

  private def alwaysFail[A]: RepositoryTask[A] = ZIO.fail(repoError)

  private def makeMemoryRepo(
    getByIdFn:     MemoryRecordId => RepositoryTask[Option[MemoryRecord]] = _ => alwaysFail,
    searchFn:      MemorySearch => RepositoryTask[List[MemoryRecord]] = _ => alwaysFail,
    upsertFn:      MemoryRecord => RepositoryTask[MemoryRecord] = _ => alwaysFail,
    updateScopeFn: (MemoryRecordId, MemoryScope) => RepositoryTask[Long] = (
      _,
      _,
    ) => alwaysFail,
    deleteFn: MemoryRecordId => RepositoryTask[Long] = _ => alwaysFail,
  ): ZIOMemoryRepository =
    new ZIOMemoryRepository {
      override def getById(id: MemoryRecordId): RepositoryTask[Option[MemoryRecord]] = getByIdFn(id)
      override def getByKey(
        key:     String,
        userId:  Option[UserId],
        agentId: Option[AgentId],
      ):                                         RepositoryTask[Option[MemoryRecord]] = alwaysFail
      override def search(s:      MemorySearch): RepositoryTask[List[MemoryRecord]] = searchFn(s)
      override def upsert(record: MemoryRecord): RepositoryTask[MemoryRecord] = upsertFn(record)
      override def updateScope(
        id:    MemoryRecordId,
        scope: MemoryScope,
      ):                                       RepositoryTask[Long] = updateScopeFn(id, scope)
      override def delete(id: MemoryRecordId): RepositoryTask[Long] = deleteFn(id)
      override def purgeExpired:               RepositoryTask[Long] = alwaysFail
    }

  private def serviceLayer(memRepo: ZIOMemoryRepository): ULayer[MemoryService] =
    ZLayer.make[MemoryService](
      InMemoryRepositories.live(memoryRepoOpt = Some(memRepo)),
      FakeModelGateway.layer(List("- checkpoint key\n")),
      NoOpEmbeddingLayers.embeddingStoreLayer,
      NoOpEmbeddingLayers.embeddingModelLayer,
      MemoryServiceImpl.live,
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MemoryServiceImpl — failing repo mapError coverage")(
      test("store: upsert failure covers line-27 lambda") {
        for {
          svc    <- ZIO.service[MemoryService]
          result <- svc.store(makeRecord("x")).either
        } yield assertTrue(result.isLeft)
      }.provide(serviceLayer(makeMemoryRepo())),
      test("query: search failure covers line-42 lambda") {
        for {
          svc    <- ZIO.service[MemoryService]
          result <- svc.query(MemoryScope.User, userId, agentId).either
        } yield assertTrue(result.isLeft)
      }.provide(serviceLayer(makeMemoryRepo())),
      test("forget: getById failure covers line-52 lambda") {
        for {
          svc    <- ZIO.service[MemoryService]
          result <- svc.forget(MemoryRecordId(1L), userId).either
        } yield assertTrue(result.isLeft)
      }.provide(serviceLayer(makeMemoryRepo())),
      test("forget: delete failure when getById succeeds covers line-58 lambda") {
        for {
          svc    <- ZIO.service[MemoryService]
          result <- svc.forget(storedRecord.id, userId).either
        } yield assertTrue(result.isLeft)
      }.provide(
        serviceLayer(
          makeMemoryRepo(
            getByIdFn = _ => ZIO.succeed(Some(storedRecord)),
            deleteFn = _ => ZIO.fail(repoError),
          ),
        ),
      ),
      test("markShared: getById failure in rescope covers line-80 lambda") {
        for {
          svc    <- ZIO.service[MemoryService]
          result <- svc.markShared(MemoryRecordId(1L), userId).either
        } yield assertTrue(result.isLeft)
      }.provide(serviceLayer(makeMemoryRepo())),
      test("markShared: updateScope failure when getById succeeds covers line-86 lambda") {
        for {
          svc    <- ZIO.service[MemoryService]
          result <- svc.markShared(storedRecord.id, userId).either
        } yield assertTrue(result.isLeft)
      }.provide(
        serviceLayer(
          makeMemoryRepo(
            getByIdFn = _ => ZIO.succeed(Some(storedRecord)),
            updateScopeFn = (
              _,
              _,
            ) => ZIO.fail(repoError),
          ),
        ),
      ),
      test("checkpoint: upsert failure during checkpoint covers line-105 lambda") {
        val now = Instant.now()
        val msgs = List(Message(MessageId.empty, ConversationId.empty, MessageRole.User, "hi", None, now))
        for {
          svc    <- ZIO.service[MemoryService]
          result <- svc.checkpoint(AgentSessionId(1L), msgs, userId, agentId, CheckpointTrigger.UserRequest).either
        } yield assertTrue(result.isLeft)
      }.provide(serviceLayer(makeMemoryRepo())),
    )

}
