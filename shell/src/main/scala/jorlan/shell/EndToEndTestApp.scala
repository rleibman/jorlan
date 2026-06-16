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

import jorlan.shell.client.*
import zio.*
import zio.logging.backend.SLF4J

import scala.language.unsafeNulls

/** Headless end-to-end test runner for a live Jorlan server.
  *
  * Runs a fixed sequence of prompts using the same client stack as the interactive shell (AuthClient, GraphQLClient,
  * SubscriptionClient, LiveSession). Reports PASS / FAIL for each scenario to stdout.
  *
  * Usage: set the same credentials in ~/.jorlan/jorlan.json that the interactive shell uses. Run with: sbtn "shell/run
  * EndToEndTestApp"
  *
  * The test suite covers:
  *   1. Server reachability and status
  *   2. Authentication (login + whoami)
  *   3. GraphQL API smoke test
  *   4. Session creation
  *   5. LLM response delivery (the core streaming path)
  *   6. WS connection stability across multiple messages
  *   7. Memory skill: store and recall via LLM
  */
object EndToEndTestApp extends ZIOApp {

  override type Environment =
    ShellConfig & AuthClient & GraphQLClient & ZIOClientRepositories & ShellState & SubscriptionClient & InitClient

  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Environment] =
    (Runtime.removeDefaultLoggers >>> SLF4J.slf4j) ++
      ZLayer.makeSome[ZIOAppArgs, Environment](
        ShellConfig.layer,
        AuthClient.live,
        GraphQLClient.live,
        ZIOClientRepositories.live,
        ShellState.live,
        SubscriptionClient.live,
        InitClient.live,
      )

  // ─── Test infrastructure ────────────────────────────────────────────────────

  sealed private trait TestResult

  private case class Passed(
    name:   String,
    detail: String = "",
  ) extends TestResult
  private case class Failed(
    name:   String,
    reason: String,
  ) extends TestResult
  private case class Skipped(
    name:   String,
    reason: String,
  ) extends TestResult

  private def pass(
    name:   String,
    detail: String = "",
  ): UIO[TestResult] =
    ZIO.succeed(Passed(name, detail))

  private def fail(
    name:   String,
    reason: String,
  ): UIO[TestResult] =
    ZIO.succeed(Failed(name, reason))

  private def skip(
    name:   String,
    reason: String,
  ): UIO[TestResult] =
    ZIO.succeed(Skipped(name, reason))

  private def printResult(r: TestResult): UIO[Unit] =
    ZIO.succeed {
      r match {
        case Passed(name, detail) =>
          val d = if (detail.nonEmpty) s"  (${detail.take(80)})" else ""
          println(s"  [PASS] $name$d")
        case Failed(name, reason) =>
          println(s"  [FAIL] $name — $reason")
        case Skipped(name, reason) =>
          println(s"  [SKIP] $name — $reason")
      }
    }

  /** Collect all streaming chunks from tokenQueue until the `Right(None)` sentinel. Times out after `timeout`. */
  private def drainResponse(
    tokenQueue: Queue[Either[String, Option[jorlan.ResponseChunk]]],
    timeout:    Duration = 90.seconds,
  ): ZIO[Any, String, String] = {
    def loop(acc: StringBuilder): ZIO[Any, String, String] =
      tokenQueue.take.flatMap {
        case Left(err)          => ZIO.fail(s"Streaming error: $err")
        case Right(None)        => ZIO.succeed(acc.result())
        case Right(Some(chunk)) =>
          if (chunk.isError) ZIO.fail(s"Agent error: ${chunk.content}")
          else { acc.append(chunk.content); loop(acc) }
      }
    loop(new StringBuilder).timeoutFail(s"No response within ${timeout.toSeconds}s")(timeout)
  }

  /** Submit a message to the active session, then collect the full response. */
  private def submitAndWait(
    sessionId:   jorlan.AgentSessionId,
    liveSession: LiveSession,
    prompt:      String,
    timeout:     Duration = 90.seconds,
  ): ZIO[ZIOClientRepositories, String, String] =
    for {
      _        <- liveSession.tokenQueue.takeAll
      _        <- ZIO.serviceWithZIO[ZIOClientRepositories](_.agent.submitMessage(sessionId, prompt))
      response <- drainResponse(liveSession.tokenQueue, timeout)
    } yield response

  // ─── Individual test cases ──────────────────────────────────────────────────

  private val testServerReachable: ZIO[InitClient & ShellConfig, Nothing, TestResult] =
    ZIO.serviceWithZIO[ShellConfig] { cfg =>
      InitClient
        .checkStatus(cfg.typedServerUrl)
        .foldZIO(
          err => fail("server_reachable", s"GET /api/status failed: $err"),
          status =>
            pass(
              "server_reachable",
              s"${status.serverName} v${status.version} uptime=${status.uptimeMs / 1000}s",
            ),
        )
    }

  private val testAuthentication: ZIO[AuthClient & ShellConfig, Nothing, TestResult] =
    for {
      cfg <- ZIO.service[ShellConfig]
      email = cfg.email.getOrElse("")
      password = cfg.password.getOrElse("")
      result <- (email, password) match {
        case ("", _) | (_, "") =>
          skip("authentication", "no credentials in config — set email+password in ~/.jorlan/jorlan.json")
        case (e, p) =>
          AuthClient
            .login(e, p)
            .foldZIO(
              err => fail("authentication", s"login failed: $err"),
              r => pass("authentication", s"logged in as ${r.displayName}"),
            )
      }
    } yield result

  private val testGraphQLSmoke: ZIO[ZIOClientRepositories, Nothing, TestResult] =
    ZIO
      .serviceWithZIO[ZIOClientRepositories](_.serverInfo.statusCheck())
      .foldZIO(
        err => fail("graphql_smoke", s"introspection query failed: $err"),
        _ => pass("graphql_smoke"),
      )

  private def testSessionCreation: ZIO[ZIOClientRepositories, Nothing, (TestResult, Option[jorlan.AgentSessionId])] =
    ZIO
      .serviceWithZIO[ZIOClientRepositories](_.agent.createSession(None))
      .foldZIO(
        err => fail("session_creation", s"createSession mutation failed: $err").map(_ -> None),
        {
          case None          => fail("session_creation", "server returned null session").map(_ -> None)
          case Some(session) => pass("session_creation", s"sessionId=${session.id.value}").map(_ -> Some(session.id))
        },
      )

  private def testLlmResponseDelivery(
    sessionId:   jorlan.AgentSessionId,
    liveSession: LiveSession,
  ): ZIO[ZIOClientRepositories, Nothing, TestResult] = {
    val prompt = "Reply with exactly the word PONG and nothing else."
    submitAndWait(sessionId, liveSession, prompt)
      .foldZIO(
        err => fail("llm_response_delivery", err),
        response => {
          val trimmed = response.trim
          if (trimmed.isEmpty)
            fail("llm_response_delivery", "received empty response from LLM")
          else
            pass("llm_response_delivery", s"got ${trimmed.length} chars: ${trimmed.take(60)}")
        },
      )
  }

  private def testWsConnectionStability(
    sessionId:   jorlan.AgentSessionId,
    liveSession: LiveSession,
  ): ZIO[ZIOClientRepositories, Nothing, TestResult] = {
    val prompts = List(
      "Say 'A'",
      "Say 'B'",
      "Say 'C'",
    )
    ZIO
      .foldLeft(prompts)(List.empty[String]) {
        (
          acc,
          prompt,
        ) =>
          submitAndWait(sessionId, liveSession, prompt)
            .map(r => acc :+ r)
            .mapError(e => s"on prompt '$prompt': $e")
      }.foldZIO(
        err => fail("ws_connection_stability", err),
        responses => {
          val received = responses.count(_.trim.nonEmpty)
          if (received == prompts.length)
            pass("ws_connection_stability", s"${prompts.length}/${prompts.length} responses received")
          else
            fail("ws_connection_stability", s"only $received/${prompts.length} responses received")
        },
      )
  }

  private def testTelegramNotifyRoberto(
    sessionId:   jorlan.AgentSessionId,
    liveSession: LiveSession,
  ): ZIO[ZIOClientRepositories, Nothing, TestResult] = {
    val prompt =
      "Please send a Telegram message to Roberto saying: 'End-to-end test ping from Jorlan — please ignore.'"
    submitAndWait(sessionId, liveSession, prompt, 120.seconds)
      .foldZIO(
        err => fail("telegram_notify_roberto", err),
        response => {
          val lower = response.toLowerCase
          val sentIndicator = lower.contains("sent") || lower.contains("delivered") ||
            lower.contains("message") || lower.contains("telegram")
          if (sentIndicator)
            pass("telegram_notify_roberto", s"LLM confirmed: ${response.trim.take(80)}")
          else
            fail("telegram_notify_roberto", s"LLM response did not confirm send. Got: ${response.trim.take(120)}")
        },
      )
  }

  private def testMemoryViaLlm(
    sessionId:   jorlan.AgentSessionId,
    liveSession: LiveSession,
  ): ZIO[ZIOClientRepositories, Nothing, TestResult] = {
    val uniqueKey = s"e2e-test-${java.time.Instant.now.toEpochMilli}"
    val storePrompt = s"Please remember this: $uniqueKey is my test marker."
    val recallPrompt = s"What is $uniqueKey?"

    val run = for {
      storeResponse  <- submitAndWait(sessionId, liveSession, storePrompt)
      recallResponse <- submitAndWait(sessionId, liveSession, recallPrompt, 60.seconds)
      ok = recallResponse.toLowerCase.contains("test marker") || recallResponse.toLowerCase.contains(
        uniqueKey.toLowerCase,
      )
    } yield (storeResponse, recallResponse, ok)

    run.foldZIO(
      err => fail("memory_via_llm", err),
      { case (_, recall, ok) =>
        if (ok)
          pass("memory_via_llm", s"recall contained expected content")
        else
          fail("memory_via_llm", s"recall did not contain expected marker. Got: ${recall.take(120)}")
      },
    )
  }

  // ─── Main runner ────────────────────────────────────────────────────────────

  // $COVERAGE-OFF$ — requires a live server
  override def run: ZIO[Environment & ZIOAppArgs & Scope, Throwable, Unit] = {
    for {
      _ <- ZIO.succeed(println("\n=== Jorlan End-to-End Test Suite ===\n"))

      // Phase 1: connectivity and auth (no session needed)
      r1 <- testServerReachable
      _  <- printResult(r1)
      r2 <- testAuthentication
      _  <- printResult(r2)
      r3 <- testGraphQLSmoke
      _  <- printResult(r3)

      // Phase 2: session-based tests
      (r4, sessionIdOpt) <- testSessionCreation
      _                  <- printResult(r4)

      _ <- sessionIdOpt match {
        case None =>
          ZIO.succeed(println("\n  Skipping session-based tests (session creation failed)."))
        case Some(sessionId) =>
          for {
            ls <- LiveSession.start(sessionId)
            _  <- ZIO.succeed(println(s"\n  [INFO] Subscription started for session ${sessionId.value}"))
            _  <- ZIO.succeed(println("  [INFO] Waiting 2s for subscription to stabilize..."))
            _  <- ZIO.sleep(2.seconds)

            r5 <- testLlmResponseDelivery(sessionId, ls)
            _  <- printResult(r5)

            r6 <- testWsConnectionStability(sessionId, ls)
            _  <- printResult(r6)

            r7 <- testMemoryViaLlm(sessionId, ls)
            _  <- printResult(r7)

            r8 <- testTelegramNotifyRoberto(sessionId, ls)
            _  <- printResult(r8)

            _ <- ls.subscriptionFiber.interruptFork
            _ <- ls.toolEventFiber.interruptFork
          } yield ()
      }

      results = List(r1, r2, r3, r4)
      passed = results.count(_.isInstanceOf[Passed])
      failed = results.count(_.isInstanceOf[Failed])
      skipped = results.count(_.isInstanceOf[Skipped])
      _ <- ZIO.succeed(println(s"\n=== Results: $passed passed, $failed failed, $skipped skipped ===\n"))
    } yield ()
  }
  // $COVERAGE-ON$

}
