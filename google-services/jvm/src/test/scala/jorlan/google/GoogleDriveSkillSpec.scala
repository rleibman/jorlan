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
import jorlan.connector.InvocationContext
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object GoogleDriveSkillSpec extends ZIOSpecDefault {

  private val userId = UserId(1L)

  private val dummyCtx: InvocationContext = InvocationContext(
    actorId = userId,
    agentId = None,
    sessionId = None,
  )

  private val sampleFile: DriveFile = DriveFile(
    id = DriveFileId("file-1"),
    name = "My Document.pdf",
    mimeType = "application/pdf",
    sizeBytes = Some(1024L),
    modifiedAt = Instant.parse("2026-06-01T10:00:00Z"),
    parents = List("root"),
    webViewLink = Some("https://drive.google.com/file/d/file-1"),
  )

  private val sampleContent: Array[Byte] = "Hello, Drive!".getBytes("UTF-8")

  private def makeSkill(
    files:   List[DriveFile] = List(sampleFile),
    content: Map[String, Array[Byte]] = Map("file-1" -> sampleContent),
  ): ZIO[Any, Nothing, GoogleDriveSkill] =
    FakeDriveProvider.make(files, content).map(new GoogleDriveSkill(_))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("GoogleDriveSkillSpec")(
      test("drive.listFiles returns file list") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "drive.listFiles", Json.Obj())
        } yield result match {
          case Json.Obj(fields) =>
            val fileArr = fields.collectFirst { case ("files", Json.Arr(items)) => items }.getOrElse(Chunk.empty)
            val count = fields.collectFirst { case ("count", Json.Num(n)) => n.intValue() }.getOrElse(0)
            assert(fileArr.length)(equalTo(1)) &&
            assert(count)(equalTo(1))
          case _ => assert(false)(isTrue)
        }
      },
      test("drive.listFiles with query filters by name") {
        for {
          skill <- makeSkill(
            files = List(
              sampleFile,
              DriveFile(
                id = DriveFileId("file-2"),
                name = "README.txt",
                mimeType = "text/plain",
                sizeBytes = Some(100L),
                modifiedAt = Instant.now(),
                parents = List("root"),
                webViewLink = None,
              ),
            ),
          )
          result <- skill.invoke(
            dummyCtx,
            "drive.listFiles",
            Json.Obj("query" -> Json.Str("README")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val count = fields.collectFirst { case ("count", Json.Num(n)) => n.intValue() }.getOrElse(0)
            assert(count)(equalTo(1))
          case _ => assert(false)(isTrue)
        }
      },
      test("drive.listFiles with folderId filters by parent") {
        for {
          skill <- makeSkill(
            files = List(
              sampleFile.copy(id = DriveFileId("f1"), parents = List("folder-a")),
              sampleFile.copy(id = DriveFileId("f2"), parents = List("folder-b")),
            ),
          )
          result <- skill.invoke(
            dummyCtx,
            "drive.listFiles",
            Json.Obj("folderId" -> Json.Str("folder-a")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val count = fields.collectFirst { case ("count", Json.Num(n)) => n.intValue() }.getOrElse(0)
            assert(count)(equalTo(1))
          case _ => assert(false)(isTrue)
        }
      },
      test("drive.listFiles returns empty list when no files match") {
        for {
          skill  <- makeSkill(files = List.empty, content = Map.empty)
          result <- skill.invoke(dummyCtx, "drive.listFiles", Json.Obj())
        } yield result match {
          case Json.Obj(fields) =>
            val count = fields.collectFirst { case ("count", Json.Num(n)) => n.intValue() }.getOrElse(-1)
            assert(count)(equalTo(0))
          case _ => assert(false)(isTrue)
        }
      },
      test("drive.readFile returns text content for valid fileId") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "drive.readFile", Json.Obj("fileId" -> Json.Str("file-1")))
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            assert(fieldMap.get("fileId"))(isSome(equalTo(Json.Str("file-1")))) &&
            assert(fieldMap.get("content"))(isSome(equalTo(Json.Str("Hello, Drive!"))))
          case _ => assert(false)(isTrue)
        }
      },
      test("drive.readFile fails when fileId not found") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "drive.readFile", Json.Obj("fileId" -> Json.Str("nonexistent"))).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("drive.readFile fails when fileId missing") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "drive.readFile", Json.Obj()).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("drive.downloadFile returns base64-encoded content") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "drive.downloadFile", Json.Obj("fileId" -> Json.Str("file-1")))
        } yield result match {
          case Json.Obj(fields) =>
            val fieldMap = fields.toMap
            val encoded = java.util.Base64.getEncoder.encodeToString(sampleContent)
            assert(fieldMap.get("fileId"))(isSome(equalTo(Json.Str("file-1")))) &&
            assert(fieldMap.get("content"))(isSome(equalTo(Json.Str(encoded)))) &&
            assert(fieldMap.get("sizeBytes"))(isSome(equalTo(Json.Num(sampleContent.length))))
          case _ => assert(false)(isTrue)
        }
      },
      test("drive.downloadFile fails when fileId not found") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "drive.downloadFile", Json.Obj("fileId" -> Json.Str("nonexistent"))).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("drive.downloadFile fails when fileId missing") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "drive.downloadFile", Json.Obj()).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
      test("unknown tool fails with JorlanError") {
        for {
          skill  <- makeSkill()
          result <- skill.invoke(dummyCtx, "drive.unknown", Json.Obj()).exit
        } yield assert(result)(failsWithA[JorlanError])
      },
    )

}
