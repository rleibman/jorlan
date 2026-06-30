/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.db.repository.ZIORepositories
import jorlan.google.GoogleDriveSkill
import jorlan.service.DriveProvider
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.*

import java.time.Instant

object GoogleDriveSkillSpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ULayer[ZIORepositories] = InMemoryRepositories.live()

  private val ctx = InvocationContext(UserId(1L), None, None)

  private val fileId1 = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgVE2upms"
  private val fileId2 = "1C2xNVt1YSB6oGNLvCeDCajhnVVrquumct85PhWF3vnt"

  private val sampleFiles = List(
    DriveFile(
      id = DriveFileId(fileId1),
      name = "report.pdf",
      mimeType = "application/pdf",
      sizeBytes = Some(102400L),
      modifiedAt = Instant.parse("2026-06-01T10:00:00Z"),
      parents = List("folder-1"),
      webViewLink = Some(s"https://drive.google.com/file/d/$fileId1"),
    ),
    DriveFile(
      id = DriveFileId(fileId2),
      name = "notes.txt",
      mimeType = "text/plain",
      sizeBytes = Some(512L),
      modifiedAt = Instant.parse("2026-06-02T10:00:00Z"),
      parents = List("root"),
      webViewLink = None,
    ),
  )

  private val fileContent = Map(
    fileId2 -> "This is the content of notes.txt".getBytes("UTF-8"),
  )

  private def makeProvider(
    files:   List[DriveFile] = sampleFiles,
    content: Map[String, Array[Byte]] = fileContent,
  ): UIO[FakeDriveProvider] =
    for {
      fr <- Ref.make(files)
      cr <- Ref.make(content)
    } yield FakeDriveProvider(fr, cr)

  private def makeSkill(provider: DriveProvider[[A] =>> IO[JorlanError, A]]): URIO[ZIORepositories, GoogleDriveSkill] =
    ZIO.succeed(new GoogleDriveSkill(provider))

  private def intField(
    json: Json,
    key:  String,
  ): Option[Int] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Num(n)) => n.intValue }
      case _                => None
    }

  private def strField(
    json: Json,
    key:  String,
  ): Option[String] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Str(v)) => v }
      case _                => None
    }

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("GoogleDriveSkill")(
      test("drive.listFiles returns all files") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "drive.listFiles", Json.Obj())
        } yield assertTrue(intField(result, "count").contains(2))
      },
      test("drive.listFiles with folder filter") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(
            ctx,
            "drive.listFiles",
            Json.Obj("folderId" -> Json.Str("folder-1")),
          )
        } yield assertTrue(intField(result, "count").contains(1))
      },
      test("drive.listFiles with maxResults") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(
            ctx,
            "drive.listFiles",
            Json.Obj("maxResults" -> Json.Num(1)),
          )
        } yield assertTrue(intField(result, "count").contains(1))
      },
      test("drive.listFiles with query filters by name") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(
            ctx,
            "drive.listFiles",
            Json.Obj("query" -> Json.Str("report")),
          )
        } yield assertTrue(intField(result, "count").contains(1))
      },
      test("drive.readFile returns file content") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "drive.readFile", Json.Obj("fileId" -> Json.Str(fileId2)))
        } yield assertTrue(
          strField(result, "fileId").contains(fileId2),
          strField(result, "content").exists(_.contains("notes.txt")),
        )
      },
      test("drive.readFile fails for unknown file") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "drive.readFile", Json.Obj("fileId" -> Json.Str("no-such-file"))).either
        } yield assertTrue(result.isLeft)
      },
      test("drive.readFile fails without fileId") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "drive.readFile", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("drive.downloadFile returns base64 content") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "drive.downloadFile", Json.Obj("fileId" -> Json.Str(fileId2)))
        } yield assertTrue(
          strField(result, "fileId").contains(fileId2),
          strField(result, "content").isDefined,
          intField(result, "sizeBytes").isDefined,
        )
      },
      test("drive.downloadFile fails without fileId") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "drive.downloadFile", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("unknown tool returns JorlanError") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "drive.unknown", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
    )

}

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
