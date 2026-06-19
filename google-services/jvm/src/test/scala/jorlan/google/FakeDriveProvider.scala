/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.google

import jorlan.*
import jorlan.service.DriveProvider
import zio.*

class FakeDriveProvider(
  filesRef:   Ref[List[DriveFile]],
  contentMap: Ref[Map[String, Array[Byte]]],
) extends DriveProvider[[A] =>> IO[JorlanError, A]] {

  override def listFiles(
    userId:     UserId,
    folderId:   Option[String],
    query:      Option[String],
    maxResults: Int,
  ): IO[JorlanError, List[DriveFile]] =
    filesRef.get.map { files =>
      val filtered = files
        .filter(f => folderId.forall(id => f.parents.contains(id)))
        .filter(f => query.forall(q => f.name.contains(q)))
      filtered.take(maxResults)
    }

  override def readTextFile(
    userId: UserId,
    fileId: DriveFileId,
  ): IO[JorlanError, String] =
    contentMap.get.flatMap { m =>
      ZIO
        .fromOption(m.get(fileId.value).map(new String(_, "UTF-8")))
        .orElseFail(JorlanError(s"File not found: ${fileId.value}"))
    }

  override def downloadFile(
    userId: UserId,
    fileId: DriveFileId,
  ): IO[JorlanError, Array[Byte]] =
    contentMap.get.flatMap { m =>
      ZIO
        .fromOption(m.get(fileId.value))
        .orElseFail(JorlanError(s"File not found: ${fileId.value}"))
    }

}

object FakeDriveProvider {

  def make(
    files:   List[DriveFile] = List.empty,
    content: Map[String, Array[Byte]] = Map.empty,
  ): UIO[FakeDriveProvider] =
    for {
      fr <- Ref.make(files)
      cr <- Ref.make(content)
    } yield FakeDriveProvider(fr, cr)

}
