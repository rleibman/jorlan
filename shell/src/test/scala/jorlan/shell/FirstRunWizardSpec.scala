/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell

import jorlan.init.ServerStatus
import jorlan.shell.ServerUrl
import jorlan.shell.client.InitClient
import jorlan.shell.testing.FakeScreen
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect.*

import java.io.File
import java.nio.file.Files

/** P8.1-C6: Unit tests for [[FirstRunWizard]] using a [[FakeScreen]] and a stub [[InitClient]]. */
object FirstRunWizardSpec extends ZIOSpecDefault {

  // ─── Fake InitClient ──────────────────────────────────────────────────────────

  private class FakeInitClient(
    statusResult:   IO[String, ServerStatus],
    completeResult: IO[String, Unit],
  ) extends InitClient {

    override def checkStatus(serverUrl: ServerUrl): IO[String, ServerStatus] = statusResult
    override def complete(
      serverUrl:     ServerUrl,
      token:         String,
      serverName:    String,
      adminEmail:    String,
      adminName:     String,
      adminPassword: String,
    ): IO[String, Unit] = completeResult

  }

  private def stubClient(
    status:   IO[String, ServerStatus] = ZIO.fail("not connected"),
    complete: IO[String, Unit] = ZIO.unit,
  ): ULayer[InitClient] = ZLayer.succeed(FakeInitClient(status, complete))

  private val initializedStatus =
    ServerStatus(initialized = true, version = "1.0", buildTime = 0L, serverName = "Jorlan", uptimeMs = 100)
  private val uninitializedStatus =
    ServerStatus(initialized = false, version = "1.0", buildTime = 0L, serverName = "Jorlan", uptimeMs = 100)

  // ─── Test helpers ─────────────────────────────────────────────────────────────

  private def tmpFile: UIO[File] =
    ZIO.succeed(Files.createTempFile("jorlan-test-", ".json").toFile).map { f => f.delete(); f }

  override def spec: Spec[Any, Any] =
    suite("FirstRunWizard")(
      test("server already initialized — prompts for email and password, writes config") {
        for {
          screen <- FakeScreen.make
          path   <- tmpFile
          // Inputs: serverUrl (enter), email, password
          _ <- screen.sendLine("")
          _ <- screen.sendLine("alice@example.com")
          _ <- screen.sendLine("secret12345!")
          client = stubClient(status = ZIO.succeed(initializedStatus))
          cfg <- FirstRunWizard
            .run(ServerUrl("http://localhost:8080"), path)
            .provide(ZLayer.succeed(screen: JorlanScreen), client)
        } yield {
          assertTrue(
            cfg.serverUrl == "http://localhost:8080",
            cfg.email.contains("alice@example.com"),
            cfg.password.contains("secret12345!"),
            path.exists(),
          )
        }
      },
      test("server uninitialized — setup path with bad token then success") {
        for {
          screen <- FakeScreen.make
          path   <- tmpFile
          calls  <- Ref.make(0)
          // First complete call fails (bad token), second succeeds
          completeEffect = calls.updateAndGet(_ + 1).flatMap {
            case 1 => ZIO.fail("(403) Invalid setup token")
            case _ => ZIO.unit
          }
          client = stubClient(
            status = ZIO.succeed(uninitializedStatus),
            complete = completeEffect,
          )
          // First round of inputs (bad token attempt):
          // serverUrl, token, serverName, adminName, adminEmail, password, confirmPassword
          _ <- screen.sendLine("") // serverUrl: use default
          _ <- screen.sendLine("badtoken") // token (fails)
          _ <- screen.sendLine("") // serverName
          _ <- screen.sendLine("Alice") // adminName
          _ <- screen.sendLine("alice@example.com") // adminEmail
          _ <- screen.sendLine("password123!") // password
          _ <- screen.sendLine("password123!") // confirm
          // Second round (good token):
          _   <- screen.sendLine("goodtoken") // token
          _   <- screen.sendLine("") // serverName
          _   <- screen.sendLine("Alice") // adminName
          _   <- screen.sendLine("alice@example.com")
          _   <- screen.sendLine("password123!")
          _   <- screen.sendLine("password123!")
          cfg <- FirstRunWizard
            .run(ServerUrl("http://localhost:8080"), path)
            .provide(ZLayer.succeed(screen: JorlanScreen), client)
        } yield {
          assertTrue(
            cfg.serverUrl == "http://localhost:8080",
            cfg.email.contains("alice@example.com"),
            path.exists(),
          )
        }
      } @@ withLiveClock,
      test("password mismatch retry") {
        for {
          screen <- FakeScreen.make
          path   <- tmpFile
          client = stubClient(status = ZIO.succeed(uninitializedStatus))
          // First attempt: passwords don't match; second attempt: they do
          _ <- screen.sendLine("") // serverUrl
          _ <- screen.sendLine("token123") // token
          _ <- screen.sendLine("") // serverName
          _ <- screen.sendLine("Bob") // adminName
          _ <- screen.sendLine("bob@example.com")
          _ <- screen.sendLine("hunter2!!!!!")
          _ <- screen.sendLine("hunter2different!") // mismatch
          // Retry:
          _   <- screen.sendLine("token123")
          _   <- screen.sendLine("")
          _   <- screen.sendLine("Bob")
          _   <- screen.sendLine("bob@example.com")
          _   <- screen.sendLine("hunter2!!!!!")
          _   <- screen.sendLine("hunter2!!!!!") // match
          cfg <- FirstRunWizard
            .run(ServerUrl("http://localhost:8080"), path)
            .provide(ZLayer.succeed(screen: JorlanScreen), client)
          msgs <- screen.messagesOfKind(MessageKind.Error)
        } yield {
          assertTrue(
            cfg.email.contains("bob@example.com"),
            msgs.exists(_.content.contains("Passwords do not match")),
          )
        }
      } @@ withLiveClock,
      test("connection error — retries after user presses enter") {
        for {
          screen <- FakeScreen.make
          path   <- tmpFile
          calls  <- Ref.make(0)
          statusEffect = calls.updateAndGet(_ + 1).flatMap {
            case 1 => ZIO.fail("connection refused")
            case _ => ZIO.succeed(initializedStatus)
          }
          client = stubClient(status = statusEffect)
          // First attempt fails (error message), press enter to retry,
          // second attempt succeeds (initialized), then email + password
          _   <- screen.sendLine("") // serverUrl
          _   <- screen.sendLine("") // press enter to retry after error
          _   <- screen.sendLine("") // serverUrl again (setupLoop re-prompts)
          _   <- screen.sendLine("carol@example.com")
          _   <- screen.sendLine("password123!")
          cfg <- FirstRunWizard
            .run(ServerUrl("http://localhost:8080"), path)
            .provide(ZLayer.succeed(screen: JorlanScreen), client)
          msgs <- screen.messagesOfKind(MessageKind.Error)
        } yield {
          assertTrue(
            cfg.email.contains("carol@example.com"),
            msgs.exists(_.content.contains("connection refused")),
          )
        }
      },
      // P8.1-027: custom server URL overrides the default
      test("custom server URL is used when user types one") {
        for {
          screen <- FakeScreen.make
          path   <- tmpFile
          client = stubClient(status = ZIO.succeed(initializedStatus))
          _   <- screen.sendLine("http://myserver:9090") // custom URL
          _   <- screen.sendLine("dave@example.com")
          _   <- screen.sendLine("password123!")
          cfg <- FirstRunWizard
            .run(ServerUrl("http://localhost:8080"), path)
            .provide(ZLayer.succeed(screen: JorlanScreen), client)
        } yield assertTrue(
          cfg.serverUrl == "http://myserver:9090",
          cfg.email.contains("dave@example.com"),
        )
      },
    )

}
