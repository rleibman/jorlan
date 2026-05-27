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
      new QdrantClient(JavaQdrantClient(QdrantGrpcClient.newBuilder(host, rpcPort, useTransportLayerSecurity).build())),
    )
  }

  def apply(
    grpc: QdrantGrpcClient,
  ): QdrantClient =
    new QdrantClient(
      JavaQdrantClient(grpc),
    )

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
