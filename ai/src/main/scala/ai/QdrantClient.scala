/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai
// $COVERAGE-OFF$

import io.qdrant.client.grpc.Collections
import io.qdrant.client.grpc.Common.Filter
import io.qdrant.client.{QdrantClient as JavaQdrantClient, QdrantGrpcClient}
import zio.*

object QdrantClient {

  def apply(
    host:                      String,
    rpcPort:                   Int,
    useTransportLayerSecurity: Boolean = false,
  ): Task[QdrantClient] = {
    ZIO.attempt(
      QdrantClient(JavaQdrantClient(QdrantGrpcClient.newBuilder(host, rpcPort, useTransportLayerSecurity).build())),
    )
  }

  def apply(
    grpc: QdrantGrpcClient,
  ): QdrantClient = QdrantClient(JavaQdrantClient(grpc))

}

case class QdrantClient(jclient: JavaQdrantClient) {

  def collectionExists(collectionName: String): Task[Boolean] = {
    ZIO
      .fromFutureJava {
        jclient.collectionExistsAsync(collectionName)
      }.map(Boolean.unbox)
  }

  def createCollection(
    collectionName: String,
    distance:       Collections.Distance = Collections.Distance.Cosine,
    dimension:      Int = 384,
  ): Task[Collections.CollectionOperationResponse] = {
    ZIO.fromFutureJava {
      jclient.createCollectionAsync(
        collectionName,
        Collections.VectorParams.newBuilder().setDistance(distance).setSize(dimension).build(),
      )
    }
  }

  def count(
    collectionName: String,
    filter:         Option[Filter] = None,
  ): Task[Long] = {
    import scala.language.unsafeNulls
    ZIO
      .fromFutureJava {
        jclient.countAsync(collectionName, filter.orNull, true)
      }.map(Long.unbox)
  }

}
// $COVERAGE-ON$
