/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package ai

// $COVERAGE-OFF$
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore
import zio.*

case class EmbeddingStoreWrapper(store: QdrantEmbeddingStore) {

  def close(): Task[Unit] = ZIO.attemptBlocking(store.close())

}

object EmbeddingStoreWrapper {

  def qdrantStoreLayer(collectionName: String): ZLayer[LangChainConfig, Throwable, EmbeddingStoreWrapper] =
    for {
      config <- ZLayer.service[LangChainConfig]
      ret <-
        ZLayer.scoped {
          ZIO.acquireRelease(
            ZIO.attemptBlocking(
              EmbeddingStoreWrapper(
                QdrantEmbeddingStore
                  .builder()
                  .host(config.get.qdrantHost)
                  .port(config.get.qdrantRPCPort)
                  .collectionName(collectionName)
                  .build(),
              ),
            ),
          ) { store =>
            ZIO.logInfo("Closing store") *> store.close().tapError(e => ZIO.logError(s"Error closing store: $e")).orDie
          }
        }
    } yield ret

}
// $COVERAGE-ON$
