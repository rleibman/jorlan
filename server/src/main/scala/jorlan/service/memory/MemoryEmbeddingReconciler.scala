/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.memory

import ai.{EmbeddingModel, EmbeddingStore}
import jorlan.*
import jorlan.db.repository.ZIORepositories
import zio.*

import javax.sql.DataSource
import scala.collection.mutable.ListBuffer

/** Startup backfill for the derived vector index.
  *
  * The relational `memoryRecord` table is the canonical source of truth; the `jorlan_memory` embedding table is derived
  * from it (Architecture Principle #5). But the live embedding write ([[MemoryServiceImpl]]'s `embedAndStore`) is a
  * fire-and-forget fiber — if the embedding model is unavailable at that moment (e.g. Ollama overloaded), the record is
  * stored relationally but never indexed, and stays permanently invisible to semantic recall. This reconciler runs once
  * at startup, diffs the two tables, and re-embeds whatever is missing.
  */
object MemoryEmbeddingReconciler {

  /** IDs already present in the embedding table, read from each row's `memoryRecordId` metadata entry. */
  private def embeddedIds(ds: DataSource): Task[Set[Long]] =
    ZIO.attemptBlocking {
      val conn = ds.getConnection
      try {
        val st = conn.createStatement()
        try {
          val rs = st.executeQuery(
            "SELECT DISTINCT JSON_VALUE(metadata, '$.memoryRecordId') FROM jorlan_memory",
          )
          val ids = ListBuffer.empty[Long]
          while (rs.next())
            Option(rs.getString(1)).flatMap(_.toLongOption).foreach(ids += _)
          ids.toSet
        } finally st.close()
      } finally conn.close()
    }

  private def allRecordIds(ds: DataSource): Task[Set[Long]] =
    ZIO.attemptBlocking {
      val conn = ds.getConnection
      try {
        val st = conn.createStatement()
        try {
          val rs = st.executeQuery("SELECT id FROM memoryRecord")
          val ids = ListBuffer.empty[Long]
          while (rs.next()) ids += rs.getLong(1)
          ids.toSet
        } finally st.close()
      } finally conn.close()
    }

  /** Diff the canonical table against the index and embed every missing record. Failures on individual records are
    * logged and skipped — the next startup retries them.
    */
  val run: ZIO[DataSource & EmbeddingStore & EmbeddingModel & ZIORepositories, Nothing, Unit] = {
    val effect = for {
      ds             <- ZIO.service[DataSource]
      embeddingStore <- ZIO.service[EmbeddingStore]
      embeddingModel <- ZIO.service[EmbeddingModel]
      repo           <- ZIO.service[ZIORepositories]
      embedded       <- embeddedIds(ds)
      all            <- allRecordIds(ds)
      missing = (all -- embedded).toList.sorted
      _ <- ZIO.logInfo(
        s"Memory embedding reconciler: ${all.size} canonical record(s), ${embedded.size} indexed, ${missing.size} missing",
      )
      backfilled <- ZIO.foldLeft(missing)(0) {
        (
          count,
          id,
        ) =>
          repo.memory
            .getById(MemoryRecordId(id))
            .mapError(e => new RuntimeException(e))
            .flatMap {
              case None         => ZIO.succeed(count)
              case Some(record) =>
                ZIO
                  .attemptBlocking {
                    val (text, segment) = MemoryServiceImpl.embeddingSegment(record)
                    val embedding = embeddingModel.embed(text).content()
                    embeddingStore.add(embedding, segment)
                  }
                  .as(count + 1)
            }
            .catchAll(e => ZIO.logWarning(s"Reconciler could not embed memory record $id: ${e.getMessage}").as(count))
      }
      _ <- ZIO.when(missing.nonEmpty)(
        ZIO.logInfo(s"Memory embedding reconciler: backfilled $backfilled of ${missing.size} record(s)"),
      )
    } yield ()

    effect.catchAll(e => ZIO.logWarning(s"Memory embedding reconciler failed: ${e.getMessage}"))
  }

}
