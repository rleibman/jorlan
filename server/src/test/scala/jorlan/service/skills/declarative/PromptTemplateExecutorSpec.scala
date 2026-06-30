/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import jorlan.*
import jorlan.service.*
import jorlan.service.llm.FakeModelGateway
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object PromptTemplateExecutorSpec extends ZIOSpecDefault {

  private val dummySessionId = AgentSessionId(1L)

  private val baseConfig = PromptTemplateExecutorConfig(
    systemPrompt = "You are a helpful assistant.",
    userPromptTemplate = "Answer this: {{question}}",
  )

  override def spec =
    suite("PromptTemplateExecutorSpec")(
      suite("template substitution")(
        test("substitutes {{param}} in userPromptTemplate") {
          val gateway = FakeModelGateway(List("The answer is 42"))
          val args = Json.Obj("question" -> Json.Str("What is the answer?"))
          for {
            result <- PromptTemplateExecutor.execute(baseConfig, args, dummySessionId, gateway)
          } yield assertTrue(result == Json.Str("The answer is 42"))
        },
        test("substitutes {{param}} in systemPrompt") {
          val config = PromptTemplateExecutorConfig(
            systemPrompt = "You are an expert in {{domain}}.",
            userPromptTemplate = "Tell me about it.",
          )
          val captured = scala.collection.mutable.ListBuffer.empty[String]
          val gateway = new FakeModelGateway(List("ok")) {
            override def chatStep(
              sessionId: AgentSessionId,
              messages:  List[AgentMessage],
              tools:     List[ToolSpec],
            ): IO[ModelError, ChatStep] = {
              messages.foreach {
                case SystemMsg(c) => captured += c
                case _            => ()
              }
              ZIO.succeed(FinalAnswer(ZStream.fromIterable(List("ok"))))
            }
          }
          val args = Json.Obj("domain" -> Json.Str("Scala"))
          for {
            _ <- PromptTemplateExecutor.execute(config, args, dummySessionId, gateway)
          } yield assertTrue(captured.headOption.contains("You are an expert in Scala."))
        },
        test("concatenates multiple chunks from gateway") {
          val gateway = FakeModelGateway(List("Hello", " ", "world"))
          val args = Json.Obj("question" -> Json.Str("hi"))
          for {
            result <- PromptTemplateExecutor.execute(baseConfig, args, dummySessionId, gateway)
          } yield assertTrue(result == Json.Str("Hello world"))
        },
      ),
      suite("FinalAnswer path")(
        test("returns streamed answer as Json.Str") {
          val gateway = FakeModelGateway(List("42"))
          val args = Json.Obj("question" -> Json.Str("Q"))
          for {
            result <- PromptTemplateExecutor.execute(baseConfig, args, dummySessionId, gateway)
          } yield assertTrue(result == Json.Str("42"))
        },
        test("returns empty string when gateway emits no chunks") {
          val gateway = FakeModelGateway(List.empty)
          val args = Json.Obj("question" -> Json.Str("Q"))
          for {
            result <- PromptTemplateExecutor.execute(baseConfig, args, dummySessionId, gateway)
          } yield assertTrue(result == Json.Str(""))
        },
      ),
      suite("ToolCallRequested path")(
        test("fails with JorlanError when LLM requests a tool call") {
          val toolCall = ToolCallRequested("call-1", "some_tool", "{}")
          val gateway = new FakeModelGateway(List.empty) {
            override def chatStep(
              sessionId: AgentSessionId,
              messages:  List[AgentMessage],
              tools:     List[ToolSpec],
            ): IO[ModelError, ChatStep] =
              ZIO.succeed(toolCall)
          }
          val args = Json.Obj("question" -> Json.Str("Q"))
          for {
            result <- PromptTemplateExecutor.execute(baseConfig, args, dummySessionId, gateway).either
          } yield assertTrue(result.isLeft, result.left.exists(_.msg.contains("some_tool")))
        },
      ),
    )

}
