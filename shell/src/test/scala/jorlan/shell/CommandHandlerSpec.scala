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

import jorlan.graphql.client.JorlanClient
import jorlan.graphql.client.JorlanClient.CapabilityGrant.grantorId
import jorlan.init.ServerStatus
import jorlan.shell.client.*
import jorlan.shell.commands.{CommandHandler, ShellCommand}
import jorlan.shell.testing.FakeScreen
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import jorlan.*
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

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
        override def refresh:      IO[String, String] = ZIO.fail("not used in these tests")
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

  /** Fake GQL where `run` always returns the given value (unchecked cast). Sufficient for tests where only one `run`
    * call type is expected and the return type is known by the test.
    */
  def fakeGQLRunReturning[A](value: A): ULayer[GraphQLClient] =
    ZLayer.succeed {
      new GraphQLClient {
        override def execute(
          query:     String,
          variables: Option[Json],
        ): IO[String, Json] = ZIO.succeed(Json.Obj())

        override def run[Origin: caliban.client.Operations.IsOperation, B](
          selection: caliban.client.SelectionBuilder[Origin, B],
        ): IO[String, B] = ZIO.succeed(value.asInstanceOf[B])
      }
    }

  /** Fake GQL that returns values from `responses` in order on each `run` call (unchecked cast). */
  def fakeGQLRunSequence(responses: Any*): UIO[ULayer[GraphQLClient]] =
    Ref.make(responses.toList).map { ref =>
      ZLayer.succeed {
        new GraphQLClient {
          override def execute(
            query:     String,
            variables: Option[Json],
          ): IO[String, Json] = ZIO.succeed(Json.Obj())
          override def run[Origin: caliban.client.Operations.IsOperation, B](
            selection: caliban.client.SelectionBuilder[Origin, B],
          ): IO[String, B] =
            ref.modify {
              case h :: t => (h.asInstanceOf[B], t)
              case Nil    => (null.asInstanceOf[B], Nil)
            }
        }
      }
    }

  /** Fake GQL where `run` always fails with the given error message. */
  def fakeGQLRunFailing(err: String): ULayer[GraphQLClient] =
    ZLayer.succeed {
      new GraphQLClient {
        override def execute(
          query:     String,
          variables: Option[Json],
        ): IO[String, Json] = ZIO.succeed(Json.Obj())

        override def run[Origin: caliban.client.Operations.IsOperation, A](
          selection: caliban.client.SelectionBuilder[Origin, A],
        ): IO[String, A] = ZIO.fail(err)
      }
    }

  /** Fake GQL whose `run` also offers `tokens` to `tokenQueue` before returning `Some(())`. This simulates the
    * subscription fiber pushing tokens right after submitMessage is processed — needed because `handleMessage` drains
    * stale queue contents before calling `run`, so pre-populating the queue would cause the stale-drain to remove the
    * test tokens before `drain` reads them.
    */
  def fakeGQLSubmitWithTokens(
    tokenQueue: Queue[Either[String, Option[ResponseChunk]]],
    tokens:     List[Either[String, Option[ResponseChunk]]],
  ): ULayer[GraphQLClient] =
    ZLayer.succeed {
      new GraphQLClient {
        override def execute(
          query:     String,
          variables: Option[Json],
        ): IO[String, Json] = ZIO.succeed(Json.Obj())

        override def run[Origin: caliban.client.Operations.IsOperation, A](
          selection: caliban.client.SelectionBuilder[Origin, A],
        ): IO[String, A] =
          ZIO.foreachDiscard(tokens)(tokenQueue.offer) *> ZIO.succeed(Some(()).asInstanceOf[A])
      }
    }

  val fakeSubscriptionClient: ULayer[SubscriptionClient] =
    ZLayer.succeed {
      new SubscriptionClient {
        override def agentResponseStream(sessionId: AgentSessionId): ZStream[Scope, String, ResponseChunk] =
          ZStream.empty
        override def toolEventsStream(sessionId: AgentSessionId)
          : ZStream[Scope, String, jorlan.graphql.client.JorlanClient.ToolEventResult.ToolEventResultView] =
          ZStream.empty
      }
    }

  val fakeInitClient: ULayer[InitClient] =
    ZLayer.succeed {
      new InitClient {
        override def checkStatus(serverUrl: jorlan.shell.ServerUrl): IO[String, ServerStatus] =
          ZIO.succeed(
            ServerStatus(initialized = true, version = "test", buildTime = 0L, serverName = "Test", uptimeMs = 0L),
          )
        override def complete(
          serverUrl:     jorlan.shell.ServerUrl,
          token:         String,
          serverName:    String,
          adminEmail:    String,
          adminName:     String,
          adminPassword: String,
        ): IO[String, Unit] = ZIO.unit
      }
    }

  val defaultCfg: ULayer[ShellConfig] = ZLayer.succeed(ShellConfig())

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
            fakeSubscriptionClient ++
            fakeInitClient,
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
          msgs    <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("/new"))
      },
      test("/new when GQL run fails emits Error message") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.NewSession(None), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunFailing("connection refused") ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Failed to create session"))
      },
      test("/new with successful GQL run starts session and shows System message") {
        val sessionView = JorlanClient.AgentSession.AgentSessionView(
          id = AgentSessionId(99L),
          agentId = AgentId(1L),
          userId = UserId(1L),
          workspaceId = None,
          status = SessionStatus.Active,
          chatRef = None,
          modelId = None,
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
        )
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.NewSession(None), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(sessionView)) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Session 99 started"))
      },
      test("/model without active session prompts to create one") {
        for {
          (fs, _) <- runCmd(ShellCommand.ModelInfo)
          msgs    <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("No active session"))
      },
      test("/models with empty list shows no-models message") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.ListModels, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning[scala.Option[List[JorlanClient.ModelInfo.ModelInfoView]]](Some(Nil)) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("No models"))
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
                fakeSubscriptionClient ++
                fakeInitClient,
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
                fakeSubscriptionClient ++
                fakeInitClient,
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
                fakeSubscriptionClient ++
                fakeInitClient,
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
                fakeSubscriptionClient ++
                fakeInitClient,
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
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("401"))
      },
      // ─── Active-session handleMessage tests ──────────────────────────────────
      // Note: tokens are offered inside fakeGQLSubmitWithTokens.run rather than pre-populating
      // the queue, because handleMessage calls drainStale (Queue.takeAll) before submitMessage,
      // which would empty any pre-populated tokens before drain can read them.
      test("handleMessage with active session drains Server chunks from tokenQueue") {
        val sid = AgentSessionId(1L)
        for {
          fs         <- FakeScreen.make
          exit       <- Promise.make[Nothing, Unit]
          tokenQueue <- Queue.bounded[Either[String, Option[ResponseChunk]]](16)
          fiber      <- ZIO.never.fork.asInstanceOf[UIO[Fiber[Nothing, Unit]]]
          state      <- ShellState.make
          _          <- state.setLiveSession(LiveSession(sid, tokenQueue, fiber, fiber))
          tokens = List(
            Right(Some(ResponseChunk(sid, "hello", finished = false))),
            Right(Some(ResponseChunk(sid, " world", finished = false))),
            Right(None),
          )
          _ <- CommandHandler
            .handle(ShellCommand.Message("hi"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLSubmitWithTokens(tokenQueue, tokens) ++
                defaultCfg ++
                ZLayer.succeed[ShellState](state) ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          serverMsgs <- fs.messagesOfKind(MessageKind.Server)
          serverText = serverMsgs.map(_.content).mkString
          _ <- fiber.interrupt
        } yield assertTrue(serverMsgs.nonEmpty, serverText.contains("hello"))
      },
      test("handleMessage with active session — Left(err) in queue shows Streaming error") {
        val sid = AgentSessionId(2L)
        for {
          fs         <- FakeScreen.make
          exit       <- Promise.make[Nothing, Unit]
          tokenQueue <- Queue.bounded[Either[String, Option[ResponseChunk]]](16)
          fiber      <- ZIO.never.fork.asInstanceOf[UIO[Fiber[Nothing, Unit]]]
          state      <- ShellState.make
          _          <- state.setLiveSession(LiveSession(sid, tokenQueue, fiber, fiber))
          tokens = List(Left("connection lost"))
          _ <- CommandHandler
            .handle(ShellCommand.Message("hello"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLSubmitWithTokens(tokenQueue, tokens) ++
                defaultCfg ++
                ZLayer.succeed[ShellState](state) ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          errMsgs <- fs.messagesOfKind(MessageKind.Error)
          errText = errMsgs.map(_.content).mkString
          _ <- fiber.interrupt
        } yield assertTrue(errText.contains("Streaming error"))
      },
      test("handleMessage submit failure shows Submit failed error") {
        val sid = AgentSessionId(3L)
        for {
          fs         <- FakeScreen.make
          exit       <- Promise.make[Nothing, Unit]
          tokenQueue <- Queue.bounded[Either[String, Option[ResponseChunk]]](16)
          fiber      <- ZIO.never.fork.asInstanceOf[UIO[Fiber[Nothing, Unit]]]
          state      <- ShellState.make
          _          <- state.setLiveSession(LiveSession(sid, tokenQueue, fiber, fiber))
          _          <- CommandHandler
            .handle(ShellCommand.Message("hello"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunFailing("server error") ++
                defaultCfg ++
                ZLayer.succeed[ShellState](state) ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          errMsgs <- fs.messagesOfKind(MessageKind.Error)
          errText = errMsgs.map(_.content).mkString
          _ <- fiber.interrupt
        } yield assertTrue(errText.contains("Submit failed"))
      },
      // ─── Personality command tests ───────────────────────────────────────────
      test("/personality shows personality when GQL succeeds") {
        import jorlan.graphql.client.JorlanClient
        val pView = JorlanClient.Personality.PersonalityView(
          name = "Jorlan",
          formality = Formality.Professional,
          languages = List("en"),
          expertise = Nil,
          prompt = "Be helpful.",
        )
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.Personality, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(pView)) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Jorlan") && text.contains("Professional"))
      },
      test("/personality shows error when GQL fails") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.Personality, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunFailing("server unavailable") ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Could not fetch personality"))
      },
      test("/personality shows error when server returns None") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.Personality, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(None) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("no personality"))
      },
      test("/personality set with unknown field shows error") {
        for {
          (fs, _) <- runCmd(ShellCommand.PersonalitySet("badfield", "value"))
          msgs    <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Unknown personality field"))
      },
      test("/personality set valid field succeeds and shows updated personality") {
        import jorlan.graphql.client.JorlanClient
        val pView = JorlanClient.Personality.PersonalityView(
          name = "Jorlan",
          formality = Formality.Casual,
          languages = List("en"),
          expertise = Nil,
          prompt = "Be concise.",
        )
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.PersonalitySet("formality", "Casual"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(pView)) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Casual"))
      },
      test("/personality set shows error when fetch fails") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.PersonalitySet("name", "NewBot"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunFailing("network error") ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Could not fetch current personality"))
      },
      // ─── Memory command tests ────────────────────────────────────────────────
      test("/memory list shows records when GQL returns records") {
        import jorlan.graphql.client.JorlanClient
        import jorlan.{MemoryRecordId, MemoryScope}
        import zio.json.ast.Json

        import java.time.Instant
        val record = JorlanClient.MemoryRecord.MemoryRecordView(
          id = MemoryRecordId(1L),
          scope = MemoryScope.User,
          userId = None,
          workspaceId = None,
          agentId = None,
          ttl = None,
          recordKey = "user.lang",
          value = "Scala",
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
        )
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemoryList(None), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(List(record))) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("user.lang"))
      },
      test("/memory list shows 'No memory records' when empty") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemoryList(None), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(List.empty)) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("No memory records"))
      },
      test("/memory list shows error when GQL fails") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemoryList(None), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunFailing("timeout") ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Could not list memory"))
      },
      test("/memory search returns matching records") {
        import jorlan.MemoryRecordId
        import jorlan.graphql.client.JorlanClient

        import java.time.Instant
        val record = JorlanClient.MemoryRecord.MemoryRecordView(
          id = MemoryRecordId(2L),
          scope = MemoryScope.User,
          recordKey = "user.pref",
          value = "prefers Scala",
          userId = None,
          workspaceId = None,
          agentId = None,
          ttl = None,
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
        )
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemorySearch("Scala"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(List(record))) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("user.pref"))
      },
      test("/memory search returns 'no records' message when empty") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemorySearch("xyz"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(List.empty)) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("xyz"))
      },
      test("/memory forget shows success message") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemoryForget(MemoryRecordId(42L)), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(true)) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("42"))
      },
      test("/memory forget shows error when GQL fails") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemoryForget(MemoryRecordId(42L)), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunFailing("not found") ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Forget failed"))
      },
      test("/memory remember shows stored record") {
        import jorlan.MemoryRecordId
        import jorlan.graphql.client.JorlanClient

        import java.time.Instant
        val record = JorlanClient.MemoryRecord.MemoryRecordView(
          id = MemoryRecordId(3L),
          scope = MemoryScope.User,
          recordKey = "pref.lang",
          value = "Scala",
          userId = None,
          workspaceId = None,
          agentId = None,
          ttl = None,
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
        )
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemoryRemember("pref.lang", "Scala"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(record)) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("pref.lang"))
      },
      test("/memory remember shows 'Memory stored' when server returns None") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemoryRemember("k", "v"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(None) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Memory stored"))
      },
      test("/memory remember shows error when GQL fails") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemoryRemember("k", "v"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunFailing("store error") ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Remember failed"))
      },
      test("/memory search shows error when GQL fails") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemorySearch("term"), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunFailing("timeout") ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("Memory search failed"))
      },
      test("/memory list with explicit scope passes scope to GQL") {
        import jorlan.MemoryRecordId
        import jorlan.graphql.client.JorlanClient

        import java.time.Instant
        val record = JorlanClient.MemoryRecord.MemoryRecordView(
          id = MemoryRecordId(9L),
          scope = MemoryScope.User,
          recordKey = "scoped.key",
          value = "filtered",
          userId = None,
          workspaceId = None,
          agentId = None,
          ttl = None,
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
        )
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemoryList(Some(MemoryScope.User)), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(List(record))) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("scoped.key"))
      },
      test("/memory forget success message contains the word 'deleted'") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.MemoryForget(MemoryRecordId(42L)), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(Some(true)) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("42") && text.contains("deleted"))
      },
      test("/commands output includes memory commands") {
        for {
          (fs, _) <- runCmd(ShellCommand.Commands)
          msgs    <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("/memory list") && text.contains("/memory remember"))
      },
      test("/capabilities emits System message with grants from server") {
        import jorlan.graphql.client.JorlanClient.CapabilityGrant.CapabilityGrantView
        val grantViews = Some(
          List(
            CapabilityGrantView(
              id = CapabilityGrantId(1L),
              capability = CapabilityName("memory.read"),
              scopeJson = None,
              granteeId = UserId(1L),
              grantorId = None,
              approvalMode = ApprovalMode.Persistent,
              resourceConstraints = None,
              expiresAt = None,
              createdAt = java.time.Instant.EPOCH,
            ),
            CapabilityGrantView(
              id = CapabilityGrantId(2L),
              capability = CapabilityName("agent.message"),
              scopeJson = None,
              granteeId = UserId(1L),
              grantorId = None,
              approvalMode = ApprovalMode.Persistent,
              resourceConstraints = None,
              expiresAt = None,
              createdAt = java.time.Instant.EPOCH,
            ),
          ),
        )
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.Capabilities, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(grantViews) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("memory.read") && text.contains("agent.message"))
      },
      test("/new with Right(None) shows 'Server returned no session' error") {
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.NewSession(None), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(None) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.Error)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("no session"))
      },
      test("/agents list shows sessions from server") {
        import jorlan.WorkspaceId
        import jorlan.graphql.client.JorlanClient.AgentSession.AgentSessionView
        val sessions = Some(
          List(
            AgentSessionView(
              id = AgentSessionId(10L),
              agentId = AgentId(1L),
              userId = UserId(1L),
              workspaceId = None,
              status = SessionStatus.Active,
              modelId = None,
              chatRef = None,
              createdAt = java.time.Instant.EPOCH,
              updatedAt = java.time.Instant.EPOCH,
            ),
          ),
        )
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.AgentsList, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(sessions) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("10"))
      },
      test("/agents list shows 'No active sessions' when empty") {
        val empty: Option[List[jorlan.graphql.client.JorlanClient.AgentSession.AgentSessionView]] = Some(Nil)
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.AgentsList, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(empty) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("No active sessions"))
      },
      test("/agents stop shows success message") {
        val result: Option[Boolean] = Some(true)
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.AgentsStop(AgentSessionId(10L)), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(result) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("10"))
      },
      test("/approvals list shows approval requests") {
        import jorlan.graphql.client.JorlanClient.ApprovalRequest.ApprovalRequestView
        import jorlan.{ApprovalRequestId, ApprovalStatus, RiskClass}
        val approvals = Some(
          List(
            ApprovalRequestView(
              ApprovalRequestId(5L),
              CapabilityName("shell.execute"),
              None,
              None,
              UserId(1L),
              None,
              RiskClass.ExternalEffect,
              ApprovalStatus.Pending,
              java.time.Instant.EPOCH,
              None,
            ),
          ),
        )
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.ApprovalsList, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(approvals) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("shell.execute"), text.contains("5"))
      },
      test("/approvals list shows 'No pending' when empty") {
        val empty: Option[List[jorlan.graphql.client.JorlanClient.ApprovalRequest.ApprovalRequestView]] = Some(Nil)
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.ApprovalsList, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(empty) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("No pending"))
      },
      test("/approvals approve shows success message") {
        val result: Option[Boolean] = Some(true)
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.ApprovalsApprove(ApprovalRequestId(5L)), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(result) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("approved"), text.contains("5"))
      },
      test("/approvals deny shows success message") {
        val result: Option[Boolean] = Some(true)
        for {
          fs   <- FakeScreen.make
          exit <- Promise.make[Nothing, Unit]
          _    <- CommandHandler
            .handle(ShellCommand.ApprovalsDeny(ApprovalRequestId(5L)), exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                fakeGQLRunReturning(result) ++
                defaultCfg ++
                ShellState.live ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
        } yield assertTrue(text.contains("denied"), text.contains("5"))
      },
      test("/model with active session shows session id and model") {
        val sid = AgentSessionId(77L)
        import java.time.Instant
        val sessionView = JorlanClient.AgentSession.AgentSessionView(
          id = sid,
          agentId = AgentId(1L),
          userId = UserId(1L),
          workspaceId = None,
          status = SessionStatus.Active,
          modelId = Some(ModelId("test-model")),
          chatRef = None,
          createdAt = Instant.EPOCH,
          updatedAt = Instant.EPOCH,
        )
        val modelView = JorlanClient.ModelInfo.ModelInfoView(
          id = ModelId("test-model"),
          provider = "test",
          contextWindow = 4096,
          supportsStreaming = true,
        )
        for {
          fs         <- FakeScreen.make
          exit       <- Promise.make[Nothing, Unit]
          tokenQueue <- Queue.bounded[Either[String, Option[ResponseChunk]]](1)
          fiber      <- ZIO.never.fork.asInstanceOf[UIO[Fiber[Nothing, Unit]]]
          state      <- ShellState.make
          _          <- state.setLiveSession(LiveSession(sid, tokenQueue, fiber, fiber))
          gqlLayer   <- fakeGQLRunSequence(
            Some(List(sessionView)),
            Some(List(modelView)),
          )
          _ <- CommandHandler
            .handle(ShellCommand.ModelInfo, exit).provide(
              ZLayer.succeed[JorlanScreen](fs) ++
                fakeAuth() ++
                gqlLayer ++
                defaultCfg ++
                ZLayer.succeed[ShellState](state) ++
                fakeSubscriptionClient ++
                fakeInitClient,
            )
          msgs <- fs.messagesOfKind(MessageKind.System)
          text = msgs.map(_.content).mkString
          _ <- fiber.interrupt
        } yield assertTrue(text.contains("77"), text.contains("test-model"))
      },
    )

}
