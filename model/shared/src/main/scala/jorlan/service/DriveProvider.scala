/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*

/** Abstract file-storage backend. `F[_]` is the effect type — typically `IO[JorlanError, *]` in production. The live
  * implementation is [[jorlan.google.GoogleDriveProvider]].
  */
trait DriveProvider[F[_]] {

  def listFiles(
    userId:     UserId,
    folderId:   Option[String],
    query:      Option[String],
    maxResults: Int,
  ): F[List[DriveFile]]
  def readTextFile(
    userId: UserId,
    fileId: DriveFileId,
  ): F[String]
  def downloadFile(
    userId: UserId,
    fileId: DriveFileId,
  ): F[Array[Byte]]

}
