/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai

// $COVERAGE-OFF$
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.{EmbeddingModel => JEmbeddingModel}
import dev.langchain4j.store.embedding.mariadb.MariaDbEmbeddingStore
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore
import zio.*

import javax.sql.DataSource

type EmbeddingStore = dev.langchain4j.store.embedding.EmbeddingStore[TextSegment]
type EmbeddingModel = JEmbeddingModel

object EmbeddingStore {

  def mariadb(tableName: String): ZLayer[LangChainConfig & DataSource, Throwable, EmbeddingStore] = {
    ZLayer.fromZIO {
      for {
        config     <- ZIO.service[LangChainConfig]
        dataSource <- ZIO.service[DataSource]
        ret        <- ZIO.attemptBlocking(
          MariaDbEmbeddingStore
            .builder()
            .datasource(dataSource)
            .table(tableName)
            .dimension(config.embeddingDimensions)
            .createTable(false)
            .build(),
        )
      } yield ret: EmbeddingStore
    }
  }

  def qdrant(collectionName: String): ZLayer[LangChainConfig, Throwable, EmbeddingStore] = {
    ZLayer.fromZIO {
      for {
        config <- ZIO.service[LangChainConfig]
        ret    <-
          ZIO.scoped {
            ZIO.acquireRelease(
              ZIO.attemptBlocking(
                QdrantEmbeddingStore
                  .builder()
                  .host(config.qdrantHost)
                  .port(config.qdrantRPCPort)
                  .collectionName(collectionName)
                  .build(),
              ),
            ) { store =>
              ZIO.logInfo("Closing store") *> ZIO
                .attemptBlocking(store.close()).tapError(e => ZIO.logError(s"Error closing store: $e")).orDie
            }
          }
      } yield ret: EmbeddingStore
    }
  }

}
// $COVERAGE-ON$
