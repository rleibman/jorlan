/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.db.repository.{EventLogZIORepository, MemoryZIORepository, RepositoryTask}
import jorlan.domain.*
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object MemoryServiceSpec extends ZIOSpecDefault {

  private val T0: Instant = Instant.parse("2026-01-15T12:00:00Z")

  private class InMemoryMemoryRepo(
    idGen: Ref[Long],
    store: Ref[Map[Long, MemoryRecord]],
  ) extends MemoryZIORepository {

    override def getById(id: MemoryRecordId): RepositoryTask[Option[MemoryRecord]] =
      store.get.map(_.get(id.value))

    override def search(s: MemorySearch): RepositoryTask[List[MemoryRecord]] =
      store.get.map { records =>
        records.values.toList
          .filter(r => r.scope == s.scope)
          .filter(r => s.userId.forall(id => r.userId.contains(id)))
          .filter(r => s.key.forall(k => r.recordKey == k))
      }

    override def upsert(record: MemoryRecord): RepositoryTask[MemoryRecord] =
      for {
        id <- if (record.id == MemoryRecordId.empty) idGen.updateAndGet(_ + 1) else ZIO.succeed(record.id.value)
        saved = record.copy(id = MemoryRecordId(id))
        _ <- store.update(_.updated(id, saved))
      } yield saved

    override def delete(id: MemoryRecordId): RepositoryTask[Long] =
      store.modify { m =>
        if (m.contains(id.value)) (1L, m.removed(id.value))
        else (0L, m)
      }

    override def purgeExpired: RepositoryTask[Long] =
      store.modify { m =>
        val now = Instant.now()
        val expired = m.filter { case (_, r) => r.ttl.exists(_.isBefore(now)) }
        (expired.size.toLong, m.removedAll(expired.keys))
      }

  }

  private object InMemoryMemoryRepo {

    def make: UIO[InMemoryMemoryRepo] =
      for {
        idGen <- Ref.make(0L)
        store <- Ref.make(Map.empty[Long, MemoryRecord])
      } yield new InMemoryMemoryRepo(idGen, store)

  }

  private class InMemoryEventLogRepo(
    idGen: Ref[Long],
    store: Ref[List[EventLog[Json]]],
  ) extends EventLogZIORepository {

    override def append[R: zio.json.JsonEncoder](event: EventLog[R]): RepositoryTask[EventLog[R]] =
      for {
        nextId <- idGen.updateAndGet(_ + 1)
        saved = event.copy(id = EventLogId(nextId))
        generic = EventLog[Json](
          id = saved.id,
          eventType = saved.eventType,
          actorId = saved.actorId,
          agentId = saved.agentId,
          sessionId = saved.sessionId,
          resource = None,
          payloadJson = saved.payloadJson,
          occurredAt = saved.occurredAt,
        )
        _ <- store.update(generic :: _)
      } yield saved

    override def search(filter: EventLogFilter): RepositoryTask[List[EventLog[Json]]] =
      store.get.map(_.filter(e => filter.eventType.forall(_ == e.eventType)))

    override def replaySession(
      sessionId: AgentSessionId,
      limit:     Int,
    ): RepositoryTask[List[EventLog[Json]]] = ZIO.succeed(Nil)

  }

  private object InMemoryEventLogRepo {

    def make: UIO[InMemoryEventLogRepo] =
      for {
        idGen <- Ref.make(0L)
        store <- Ref.make(List.empty[EventLog[Json]])
      } yield new InMemoryEventLogRepo(idGen, store)

  }

  private def freshLayers: ULayer[MemoryService & EventLogService] = {
    val memRepoLayer: ULayer[MemoryZIORepository] =
      ZLayer(InMemoryMemoryRepo.make.map(r => r: MemoryZIORepository))
    val logRepoLayer: ULayer[EventLogZIORepository] =
      ZLayer(InMemoryEventLogRepo.make.map(r => r: EventLogZIORepository))
    val eventLogLayer: ULayer[EventLogService] = logRepoLayer >>> EventLogServiceImpl.live
    (memRepoLayer ++ eventLogLayer) >>> MemoryServiceImpl.live ++ eventLogLayer
  }

  private def record(
    key:   String,
    scope: MemoryScope = MemoryScope.User,
  ): MemoryRecord =
    MemoryRecord(
      id = MemoryRecordId.empty,
      scope = scope,
      userId = Some(UserId(1L)),
      workspaceId = None,
      agentId = None,
      recordKey = key,
      value = Json.Str(s"value-$key"),
      ttl = None,
      createdAt = T0,
      updatedAt = T0,
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MemoryService")(
      test("upsert assigns a generated id") {
        for {
          svc   <- ZIO.service[MemoryService]
          saved <- svc.upsert(record("key1"), actorId = None, agentId = None)
        } yield assertTrue(saved.id.value > 0L, saved.recordKey == "key1")
      }.provide(freshLayers),
      test("upsert logs MemoryWritten event") {
        for {
          svc    <- ZIO.service[MemoryService]
          log    <- ZIO.service[EventLogService]
          _      <- svc.upsert(record("key2"), actorId = Some(UserId(1L)), agentId = None)
          events <- log.query(EventLogFilter(eventType = Some(EventType.MemoryWritten)))
        } yield assertTrue(events.nonEmpty, events.head.actorId.contains(UserId(1L)))
      }.provide(freshLayers),
      test("getById returns the record after upsert") {
        for {
          svc   <- ZIO.service[MemoryService]
          saved <- svc.upsert(record("key3"), None, None)
          found <- svc.getById(saved.id)
        } yield assertTrue(found.contains(saved))
      }.provide(freshLayers),
      test("getById returns None for unknown id") {
        for {
          svc    <- ZIO.service[MemoryService]
          result <- svc.getById(MemoryRecordId(999L))
        } yield assertTrue(result.isEmpty)
      }.provide(freshLayers),
      test("delete removes the record") {
        for {
          svc     <- ZIO.service[MemoryService]
          saved   <- svc.upsert(record("key4"), None, None)
          count   <- svc.delete(saved.id)
          missing <- svc.getById(saved.id)
        } yield assertTrue(count == 1L, missing.isEmpty)
      }.provide(freshLayers),
      test("delete returns 0 for unknown id") {
        for {
          svc   <- ZIO.service[MemoryService]
          count <- svc.delete(MemoryRecordId(999L))
        } yield assertTrue(count == 0L)
      }.provide(freshLayers),
      test("search filters by scope and key") {
        for {
          svc     <- ZIO.service[MemoryService]
          _       <- svc.upsert(record("alpha", MemoryScope.User), None, None)
          _       <- svc.upsert(record("beta", MemoryScope.User), None, None)
          _       <- svc.upsert(record("alpha", MemoryScope.Shared), None, None)
          results <- svc.search(
            MemorySearch(scope = MemoryScope.User, userId = Some(UserId(1L)), key = Some("alpha")),
          )
        } yield assertTrue(
          results.length == 1,
          results.head.recordKey == "alpha",
          results.head.scope == MemoryScope.User,
        )
      }.provide(freshLayers),
      test("purgeExpired removes records with past ttl") {
        val expired = record("old").copy(ttl = Some(T0.minusSeconds(1)))
        val valid = record("new").copy(ttl = Some(Instant.now().plusSeconds(3600)))
        for {
          svc      <- ZIO.service[MemoryService]
          expSaved <- svc.upsert(expired, None, None)
          valSaved <- svc.upsert(valid, None, None)
          count    <- svc.purgeExpired
          expFound <- svc.getById(expSaved.id)
          valFound <- svc.getById(valSaved.id)
        } yield assertTrue(count >= 1L, expFound.isEmpty, valFound.isDefined)
      }.provide(freshLayers),
      suite("MemoryService companion accessors")(
        test("all companion methods delegate to implementation") {
          for {
            saved <- MemoryService.upsert(record("companion-key"), actorId = None, agentId = None)
            _     <- MemoryService.getById(saved.id)
            _     <- MemoryService.search(MemorySearch(scope = MemoryScope.User, userId = Some(UserId(1L))))
            _     <- MemoryService.delete(saved.id)
            _     <- MemoryService.purgeExpired
          } yield assertCompletes
        },
      ).provide(freshLayers),
    )

}
