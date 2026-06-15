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
