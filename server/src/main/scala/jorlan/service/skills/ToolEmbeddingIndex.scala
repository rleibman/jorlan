/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import ai.{EmbeddingModel, EmbeddingStore, LangChainConfig}
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder
import dev.langchain4j.store.embedding.mariadb.MariaDbEmbeddingStore
import zio.*

import javax.sql.DataSource
// $COVERAGE-OFF$
import scala.jdk.CollectionConverters.*

/** Stores tool descriptors as vector embeddings for cosine-similarity selection in [[SkillRegistry.filteredToolSpecs]].
  *
  * At [[indexTool]] time, the tool's text is embedded and stored in the `jorlan_tools` MariaDB VECTOR table. At
  * [[searchTools]] time, the query is embedded and the top-K most similar tool names are returned. Indexing is
  * fire-and-forget (daemon fiber) so a slow or unavailable Ollama does not block registration.
  */
trait ToolEmbeddingIndex {

  def indexTool(
    toolName:  String,
    skillName: String,
    text:      String,
  ):                                       UIO[Unit]
  def purgeBySkillName(skillName: String): UIO[Unit]
  def searchTools(
    query: String,
    limit: Int,
  ): UIO[List[String]]

}

class ToolEmbeddingIndexLive(
  store: EmbeddingStore,
  model: EmbeddingModel,
) extends ToolEmbeddingIndex {

  override def indexTool(
    toolName:  String,
    skillName: String,
    text:      String,
  ): UIO[Unit] = {
    val metaMap = Map[String, AnyRef](
      "toolName"  -> toolName,
      "skillName" -> skillName,
    ).asJava
    val segment = TextSegment.from(text, Metadata.from(metaMap))
    ZIO
      .attemptBlocking {
        val embedding = model.embed(text).content()
        store.add(embedding, segment)
      }.forkDaemon.ignore
  }

  override def purgeBySkillName(skillName: String): UIO[Unit] =
    ZIO.attemptBlocking {
      val filter = MetadataFilterBuilder.metadataKey("skillName").isEqualTo(skillName)
      store.removeAll(filter)
    }.ignore

  override def searchTools(
    query: String,
    limit: Int,
  ): UIO[List[String]] =
    ZIO
      .attemptBlocking {
        val queryEmbedding = model.embed(query).content()
        val request = EmbeddingSearchRequest
          .builder()
          .queryEmbedding(queryEmbedding)
          .maxResults(limit)
          .build()
        store.search(request).matches().asScala.toList.flatMap { m =>
          Option(m.embedded().metadata().getString("toolName")).toList
        }
      }.orElseSucceed(List.empty)

}

// $COVERAGE-ON$

object ToolEmbeddingIndex {

  val live: ZLayer[LangChainConfig & DataSource & EmbeddingModel, Throwable, ToolEmbeddingIndex] =
    ZLayer.fromZIO {
      for {
        config     <- ZIO.service[LangChainConfig]
        dataSource <- ZIO.service[DataSource]
        model      <- ZIO.service[EmbeddingModel]
        store      <- ZIO.attemptBlocking(
          MariaDbEmbeddingStore
            .builder()
            .datasource(dataSource)
            .table("jorlan_tools")
            .dimension(config.embeddingDimensions)
            .createTable(false)
            .build(): EmbeddingStore,
        )
      } yield new ToolEmbeddingIndexLive(store, model): ToolEmbeddingIndex
    }

  val noOp: ULayer[ToolEmbeddingIndex] =
    ZLayer.succeed(
      new ToolEmbeddingIndex {
        def indexTool(
          toolName:  String,
          skillName: String,
          text:      String,
        ):                                       UIO[Unit] = ZIO.unit
        def purgeBySkillName(skillName: String): UIO[Unit] = ZIO.unit
        def searchTools(
          query: String,
          limit: Int,
        ): UIO[List[String]] = ZIO.succeed(List.empty)
      },
    )

}
