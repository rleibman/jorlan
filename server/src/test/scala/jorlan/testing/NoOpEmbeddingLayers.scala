/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.testing

import ai.{EmbeddingModel, EmbeddingStore}
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.{EmbeddingModel => JEmbeddingModel}
import dev.langchain4j.model.output.Response
import dev.langchain4j.store.embedding.{EmbeddingSearchRequest, EmbeddingSearchResult}
import zio.*

object NoOpEmbeddingLayers {

  private class NoOpEmbeddingModelImpl extends JEmbeddingModel {
    override def embed(text: String): Response[Embedding] =
      Response.from(Embedding.from(new Array[Float](768)))
    override def embedAll(textSegments: java.util.List[TextSegment]): Response[java.util.List[Embedding]] =
      Response.from(java.util.Collections.emptyList())
  }

  private class NoOpEmbeddingStoreImpl extends EmbeddingStore {
    override def add(embedding: Embedding): String = java.util.UUID.randomUUID().toString()
    override def add(id: String, embedding: Embedding): Unit = ()
    override def add(embedding: Embedding, embedded: TextSegment): String =
      java.util.UUID.randomUUID().toString()
    override def addAll(embeddings: java.util.List[Embedding]): java.util.List[String] =
      java.util.Collections.emptyList()
    override def search(request: EmbeddingSearchRequest): EmbeddingSearchResult[TextSegment] =
      new EmbeddingSearchResult(java.util.List.of())
  }

  val embeddingModelLayer: ULayer[EmbeddingModel] =
    ZLayer.succeed(new NoOpEmbeddingModelImpl(): EmbeddingModel)

  val embeddingStoreLayer: ULayer[EmbeddingStore] =
    ZLayer.succeed(new NoOpEmbeddingStoreImpl(): EmbeddingStore)

}
