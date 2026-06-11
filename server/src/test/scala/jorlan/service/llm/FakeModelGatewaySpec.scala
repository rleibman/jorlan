/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.llm

import jorlan.domain.*
import jorlan.service.llm.FakeModelGateway
import jorlan.service.{ChatStep, FinalAnswer, ModelGateway, ModelUnavailable}
import zio.*
import zio.test.*
import zio.test.TestAspect.*

object FakeModelGatewaySpec extends ZIOSpecDefault {

  private val sessionId = AgentSessionId(1L)

  /** Build a layer and extract the single service value. */
  private def buildGw(layer: ULayer[ModelGateway]): ZIO[Scope, Nothing, ModelGateway] =
    layer.build.map(_.get[ModelGateway])

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("FakeModelGateway")(
      // ─── chunkDelay branch ─────────────────────────────────────────────────────
      test("streamedResponse with chunkDelay emits all chunks in order") {
        val gw = FakeModelGateway(List("a", "b", "c"), chunkDelay = Some(10.millis))
        for {
          result <- gw.streamedResponse(sessionId, "msg", "system").runCollect
        } yield assertTrue(result.toList == List("a", "b", "c"))
      } @@ withLiveClock,
      // ─── stepsRef exhausted ────────────────────────────────────────────────────
      test("chatStep with empty stepsRef returns FinalAnswer fallback") {
        for {
          ref <- Ref.make(List.empty[ChatStep])
          gw = FakeModelGateway(chunks = List("fallback"), stepsRef = Some(ref))
          step <- gw.chatStep(sessionId, Nil, Nil)
        } yield step match {
          case FinalAnswer(_) => assertTrue(true)
          case _              => assertTrue(false)
        }
      },
      // ─── CapturingFakeModelGateway.streamedResponse ────────────────────────────
      test("capturingLayer.streamedResponse records system prompt and returns chunks") {
        for {
          captured <- Ref.make(List.empty[String])
          gw       <- buildGw(FakeModelGateway.capturingLayer(List("tok1", "tok2"), captured))
          result   <- gw.streamedResponse(sessionId, "msg", "my-system").runCollect
          prompts  <- captured.get
        } yield assertTrue(
          result.toList == List("tok1", "tok2"),
          prompts.contains("my-system"),
        )
      },
      // ─── CapturingFakeModelGateway.availableModels ────────────────────────────
      test("capturingLayer.availableModels returns at least one model") {
        for {
          captured <- Ref.make(List.empty[String])
          gw       <- buildGw(FakeModelGateway.capturingLayer(Nil, captured))
          models   <- gw.availableModels
        } yield assertTrue(models.nonEmpty, models.exists(_.id == ModelId("fake-model")))
      },
      // ─── CapturingFakeModelGateway.invalidateSession ──────────────────────────
      test("capturingLayer.invalidateSession completes without error") {
        for {
          captured <- Ref.make(List.empty[String])
          gw       <- buildGw(FakeModelGateway.capturingLayer(Nil, captured))
          _        <- gw.invalidateSession(sessionId)
        } yield assertTrue(true)
      },
      // ─── FailingFakeModelGateway.streamedResponse ──────────────────────────────
      test("failingLayer.streamedResponse fails with the given error") {
        for {
          gw     <- buildGw(FakeModelGateway.failingLayer(ModelUnavailable("boom")))
          result <- gw.streamedResponse(sessionId, "msg", "sys").runCollect.either
        } yield assertTrue(result.isLeft)
      },
    )

}
