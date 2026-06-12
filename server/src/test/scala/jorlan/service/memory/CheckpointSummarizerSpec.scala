/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.memory

import jorlan.*
import jorlan
.*
import jorlan.service.llm.FakeModelGateway
import jorlan.service.memory.CheckpointSummarizerImpl
import jorlan.service.{CheckpointSummarizer, ModelUnavailable}
import zio.*
import zio.test.*

import java.time.Instant

object CheckpointSummarizerSpec extends ZIOSpec[CheckpointSummarizer] {

  private val userId = UserId(1L)
  private val agentId = AgentId(1L)
  private val now = Instant.now()

  private def msg(
    role:    MessageRole,
    content: String,
  ) = Message(MessageId.empty, ConversationId.empty, role, content, None, now)

  override val bootstrap: ULayer[CheckpointSummarizer] =
    ZLayer.make[CheckpointSummarizer](
      FakeModelGateway.layer(List("- User prefers Python\n", "- Project is Jorlan\n")),
      ZLayer.fromFunction(CheckpointSummarizerImpl(_)),
    )

  override def spec: Spec[CheckpointSummarizer & TestEnvironment & Scope, Any] =
    suite("CheckpointSummarizer")(
      test("empty messages produce no records") {
        for {
          summarizer <- ZIO.service[CheckpointSummarizer]
          records    <- summarizer.summarize(Nil, userId, agentId)
        } yield assertTrue(records.isEmpty)
      },
      test("FakeModelGateway with bullet response produces records") {
        for {
          summarizer <- ZIO.service[CheckpointSummarizer]
          messages = List(
            msg(MessageRole.User, "I prefer Python"),
            msg(MessageRole.Assistant, "Noted."),
          )
          records <- summarizer.summarize(messages, userId, agentId)
        } yield
          // FakeModelGateway emits "- User prefers Python\n- Project is Jorlan"
          assertTrue(records.nonEmpty, records.forall(_.recordKey == "episodic.checkpoint"))
      },
    ) +
      suite("degenerate LLM responses")(
        test("blank LLM response produces empty record list") {
          for {
            summarizer <- ZIO.service[CheckpointSummarizer]
            records    <- summarizer.summarize(List(msg(MessageRole.User, "hello")), userId, agentId)
          } yield assertTrue(records.isEmpty)
        },
        test("LLM response without bullet prefix produces empty record list") {
          for {
            summarizer <- ZIO.service[CheckpointSummarizer]
            records    <- summarizer.summarize(List(msg(MessageRole.User, "hello")), userId, agentId)
          } yield assertTrue(records.isEmpty)
        },
      ).provide(
        ZLayer.make[CheckpointSummarizer](
          FakeModelGateway.layer(List("Here is a summary without any bullets.")),
          ZLayer.fromFunction(CheckpointSummarizerImpl(_)),
        ),
      ) +
      suite("LLM stream failure")(
        test("LLM failure is propagated as JorlanError") {
          for {
            summarizer <- ZIO.service[CheckpointSummarizer]
            result     <- summarizer.summarize(List(msg(MessageRole.User, "hello")), userId, agentId).exit
          } yield assertTrue(result.isFailure)
        },
      ).provide(
        ZLayer.make[CheckpointSummarizer](
          FakeModelGateway.failingLayer(ModelUnavailable("simulated failure")),
          ZLayer.fromFunction(CheckpointSummarizerImpl(_)),
        ),
      )

}
