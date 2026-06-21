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
import jorlan.*
import jorlan.service.{DriveProvider, OAuthCredentialService}
import zio.{IO, Ref, ZIO}

import java.io.ByteArrayOutputStream
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

/** Google Drive-backed [[jorlan.service.DriveProvider]] using the Google Java API client library.
  *
  * Access tokens are refreshed lazily via [[OAuthCredentialService.refreshAccessToken]] (only when near expiry).
  */
class GoogleDriveProvider private (
  credentials: OAuthCredentialService,
  transport:   com.google.api.client.http.HttpTransport,
  jsonFactory: com.google.api.client.json.JsonFactory,
  clientCache: Ref[Map[UserId, (String, Drive)]],
) extends GoogleApiProvider[Drive](credentials, transport, jsonFactory, clientCache)
    with DriveProvider[[A] =>> IO[JorlanError, A]] {

  override protected val apiName: String = "Drive"

  override protected def makeClient(accessToken: String): Drive =
    new Drive.Builder(transport, jsonFactory, buildAdapter(accessToken))
      .setApplicationName("Jorlan")
      .build()
      .nn

  private def withDrive[A](userId: UserId)(f: Drive => A): IO[JorlanError, A] = withClient(userId)(f)

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
      val qOpt = Option.when(qParts.nonEmpty)(qParts.mkString(" and "))
      val req = drive
        .files().list().nn
        .setFields("files(id,name,mimeType,size,modifiedTime,parents,webViewLink)")
        .setPageSize(maxResults)
      qOpt.foreach(req.setQ)
      val result = req.execute().nn
      Option(result.getFiles).map(_.asScala.toList).getOrElse(List.empty).map { f =>
        DriveFile(
          id = DriveFileId(Option(f.getId).getOrElse("").nn),
          name = Option(f.getName).getOrElse("").nn,
          mimeType = Option(f.getMimeType).getOrElse("").nn,
          sizeBytes = Option(f.getSize).map(_.toLong),
          modifiedAt = Option(f.getModifiedTime).map(t => Instant.ofEpochMilli(t.getValue)).getOrElse(Instant.EPOCH),
          parents = Option(f.getParents).map(_.asScala.toList).getOrElse(List.empty),
          webViewLink = Option(f.getWebViewLink),
        )
      }
    }

  override def readTextFile(
    userId: UserId,
    fileId: DriveFileId,
  ): IO[JorlanError, String] =
    withDrive(userId) { drive =>
      val out = new ByteArrayOutputStream()
      drive.files().`export`(fileId.value, "text/plain").nn.executeMediaAndDownloadTo(out)
      out.toString("UTF-8").nn
    }

  override def downloadFile(
    userId: UserId,
    fileId: DriveFileId,
  ): IO[JorlanError, Array[Byte]] =
    withDrive(userId) { drive =>
      val out = new ByteArrayOutputStream()
      drive.files().get(fileId.value).nn.executeMediaAndDownloadTo(out)
      out.toByteArray.nn
    }

}

object GoogleDriveProvider {

  def apply(credentials: OAuthCredentialService): IO[JorlanError, GoogleDriveProvider] =
    for {
      transport <- ZIO
        .attemptBlocking(GoogleNetHttpTransport.newTrustedTransport().nn)
        .mapError(e => JorlanError(s"Failed to initialize Drive transport: ${e.getMessage}", Some(e)))
      jsonFactory = GsonFactory.getDefaultInstance.nn
      cache <- Ref.make(Map.empty[UserId, (String, Drive)])
    } yield new GoogleDriveProvider(credentials, transport, jsonFactory, cache)

}
