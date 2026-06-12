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

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.{AccessToken, GoogleCredentials}
import jorlan.JorlanError
import jorlan.domain.*
import jorlan.service.{DriveProvider, OAuthCredentialService}
import zio.{IO, ZIO}

import java.io.ByteArrayOutputStream
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

class GoogleDriveProvider(
  credentials: OAuthCredentialService,
) extends DriveProvider[[A] =>> IO[JorlanError, A]] {

  private val transport   = GoogleNetHttpTransport.newTrustedTransport().nn
  private val jsonFactory = GsonFactory.getDefaultInstance.nn

  private def makeDrive(accessToken: String): Drive = {
    val googleCreds = GoogleCredentials.create(new AccessToken(accessToken, null)).nn
    val adapter     = new HttpCredentialsAdapter(googleCreds)
    new Drive.Builder(transport, jsonFactory, adapter)
      .setApplicationName("Jorlan")
      .build()
      .nn
  }

  private def withDrive[A](userId: UserId)(f: Drive => A): IO[JorlanError, A] =
    for {
      token  <- credentials.refreshAccessToken(userId, "google")
      result <- ZIO.attemptBlocking(f(makeDrive(token)))
        .mapError(e => JorlanError(s"Drive API error: ${e.getMessage}", Some(e)))
    } yield result

  override def listFiles(
    userId:     UserId,
    folderId:   Option[String],
    query:      Option[String],
    maxResults: Int,
  ): IO[JorlanError, List[DriveFile]] =
    withDrive(userId) { drive =>
      val qParts = List(
        folderId.map(id => s"'$id' in parents"),
        query,
      ).flatten
      val q = if (qParts.nonEmpty) qParts.mkString(" and ") else null
      val req = drive.files().list().nn
        .setFields("files(id,name,mimeType,size,modifiedTime,parents,webViewLink)")
        .setPageSize(maxResults)
      if (q != null) req.setQ(q)
      val result = req.execute().nn
      Option(result.getFiles).map(_.asScala.toList).getOrElse(Nil).map { f =>
        DriveFile(
          id          = DriveFileId(Option(f.getId).getOrElse("").nn),
          name        = Option(f.getName).getOrElse("").nn,
          mimeType    = Option(f.getMimeType).getOrElse("").nn,
          sizeBytes   = Option(f.getSize).map(_.toLong),
          modifiedAt  = Option(f.getModifiedTime).map(t => Instant.ofEpochMilli(t.getValue)).getOrElse(Instant.EPOCH),
          parents     = Option(f.getParents).map(_.asScala.toList).getOrElse(Nil),
          webViewLink = Option(f.getWebViewLink),
        )
      }
    }

  override def readTextFile(userId: UserId, fileId: DriveFileId): IO[JorlanError, String] =
    withDrive(userId) { drive =>
      val out = new ByteArrayOutputStream()
      drive.files().`export`(fileId.value, "text/plain").nn.executeMediaAndDownloadTo(out)
      out.toString("UTF-8").nn
    }

  override def downloadFile(userId: UserId, fileId: DriveFileId): IO[JorlanError, Array[Byte]] =
    withDrive(userId) { drive =>
      val out = new ByteArrayOutputStream()
      drive.files().get(fileId.value).nn.executeMediaAndDownloadTo(out)
      out.toByteArray.nn
    }

}
