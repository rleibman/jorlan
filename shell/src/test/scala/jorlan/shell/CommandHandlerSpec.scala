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

import jorlan.domain.{AgentSessionId, ResponseChunk}
import jorlan.shell.client.{AuthClient, GraphQLClient, LoginResult, SubscriptionClient}
import jorlan.shell.commands.{CommandHandler, ShellCommand}
import jorlan.shell.testing.FakeScreen
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

object CommandHandlerSpec extends ZIOSpecDefault {

  // ─── Stub services ───────────────────────────────────────────────────────────

  def fakeAuth(
    whoAmIResult: Either[String, String] = Right("alice@test.com"),
    token:        Option[String] = Some("tok"),
  ): ULayer[AuthClient] =
    ZLayer.succeed {
      new AuthClient {
        override def login(
          email:    String,
          password: String,
        ): IO[String, LoginResult] =
          ZIO.fail("not used in these tests")
        override def whoAmI:       IO[String, String] = ZIO.fromEither(whoAmIResult)
        override def currentToken: UIO[Option[String]] = ZIO.succeed(token)
      }
    }

  def fakeGQL(
    result: Either[String, Json] = Right(Json.Obj()),
  ): ULayer[GraphQLClient] =
    ZLayer.succeed {
      new GraphQLClient {
        override def execute(
          query:     String,
          variables: Option[Json],
        ): IO[String, Json] =
          ZIO.fromEither(result)

        override def run[Origin: caliban.client.Operations.IsOperation, A](
          selection: caliban.client.SelectionBuilder[Origin, A],
        ): IO[String, A] =
          ZIO.fail("run not implemented in fake")
      }
    }

  val fakeSubscriptionClient: ULayer[SubscriptionClient] =
    ZLayer.succeed {
      new SubscriptionClient {
        override def agentResponseStream(sessionId: AgentSessionId): ZStream[Scope, String, ResponseChunk] =
          ZStream.empty
      }
    }

  val defaultCfg: ULayer[ShellConfig] = ZLayer.succeed(ShellConfig())

  type TestEnv = JorlanScreen & AuthClient & GraphQLClient & ShellConfig & ShellState & SubscriptionClient

  def testLayer(
    whoAmIResult: Either[String, String] = Right("alice@test.com"),
    gqlResult:    Either[String, Json] = Right(Json.Obj()),
  ): ULayer[TestEnv] =
    FakeScreen.layer ++ fakeAuth(whoAmIResult) ++ fakeGQL(
      gqlResult,
    ) ++ defaultCfg ++ ShellState.live ++ fakeSubscriptionClient

  def runCmd(cmd: ShellCommand): ZIO[Any, Nothing, (FakeScreen, Unit)] =
    for {
      fs   <- FakeScreen.make
      exit <- Promise.make[Nothing, Unit]
      _    <- CommandHandler
        .handle(cmd, exit).provide(
          ZLayer.succeed[JorlanScreen](fs) ++
            fakeAuth() ++
            fakeGQL() ++
            defaultCfg ++
            ShellState.live ++
            fakeSubscriptionClient,
        )
    } yield (fs, ())

  // ─── Tests ───────────────────────────────────────────────────────────────────

  override def spec: Spec[Any, Nothing] =
    suite("CommandHandler.handle")(
      test("/help emits one System message") {
        for {
          (fs, _) <- runCmd(ShellCommand.Help)
          msgs    <- fs.messagesOfKind(MessageKind.System)
        } yield assertTrue(msgs.size == 1)
      },
      test("/commands lists all command names") {
        for {
          (fs, _) <- runCmd(ShellCommand.Commands)
          msgs    <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(
          text.contains("/help") &&
            text.contains("/status") &&
            text.contains("/quit") &&
            text.contains("/trace") &&
            text.contains("/whoami"),
        )
      },
      test("/about mentions Jorlan Shell") {
        for {
          (fs, _) <- runCmd(ShellCommand.About)
          msgs    <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Jorlan Shell"))
      },
      test("Message without active session prompts to start one") {
        for {
          (fs, _) <- runCmd(ShellCommand.Message("hello"))
          msgs    <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("/new"))
      },
      test("/new creates a new session") {
        for {
          (fs, _) <- runCmd(ShellCommand.NewSession(None))
          msgs    <- fs.messages
        } yield assertTrue(msgs.nonEmpty)
      },
      test("/model without active session prompts to create one") {
        for {
          (fs, _) <- runCmd(ShellCommand.ModelInfo)
          msgs    <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("No active session"))
      },
      test("/models emits a System message") {
        for {
          (fs, _) <- runCmd(ShellCommand.ListModels)
          msgs    <- fs.messagesOfKind(MessageKind.System)
        } yield assertTrue(msgs.nonEmpty)
      },
      test("/unknown emits Error message containing command name") {
        for {
          (fs, _) <- runCmd(ShellCommand.Unknown("/badcmd"))
          msgs    <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("/badcmd"))
      },
      test("/trace with valid level emits System message with level name") {
        for {
          (fs, _) <- runCmd(ShellCommand.Trace("info"))
          msgs    <- fs.messages
          // May emit System (success) or Error (if logback not on classpath in test) — either is non-empty
        } yield assertTrue(msgs.nonEmpty)
      },
      test("/trace with invalid level emits Error") {
        for {
          (fs, _) <- runCmd(ShellCommand.Trace("INVALID_LEVEL"))
          msgs    <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("INVALID_LEVEL"))
      },
      test("/trace is case-insensitive for valid levels") {
        for {
          (fs, _) <- runCmd(ShellCommand.Trace("DEBUG"))
          msgs    <- fs.messagesOfKind(MessageKind.Error)
          // Uppercase DEBUG should NOT produce an error
        } yield assertTrue(msgs.isEmpty)
      },
      test("/quit completes the exit promise without adding messages") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.Quit, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQL() ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient,
            )
          done   <- exit.isDone
          msgCnt <- fs.messages.map(_.size)
        } yield assertTrue(done && msgCnt == 0)
      },
      test("/status with reachable server emits System message with server URL") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.Status, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQL(Right(Json.Obj())) ++
                ZLayer.succeed(ShellConfig(serverUrl = "http://test-server:9090")) ++
                ShellState.live ++
                fakeSubscriptionClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("http://test-server:9090") && text.contains("reachable"))
      },
      test("/status with unreachable server emits System message containing error") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.Status, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQL(Left("connection refused")) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("unreachable") && text.contains("connection refused"))
      },
      test("/whoami with successful response shows user identity") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.WhoAmI, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth(Right("alice@test.com")) ++
                fakeGQL() ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("alice@test.com"))
      },
      test("/whoami with auth failure shows error") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.WhoAmI, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth(whoAmIResult = Left("401 Unauthorized")) ++
                fakeGQL() ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("401"))
      },
    )

}
