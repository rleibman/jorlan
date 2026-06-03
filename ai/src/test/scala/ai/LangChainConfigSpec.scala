/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package ai

import zio.*
import zio.test.*

/** Unit tests for the [[ai]] module. Runs without Ollama — safe locally and on CI.
  *
  * NOTE: [[streamedChat]] is marked `\$COVERAGE-OFF\$` and bridges LangChain4j's async callback model to ZStream. The
  * bridge terminates on `ZIO.fail(None)` (stream end) or `ZIO.fail(Some(error))` (stream error). Testing it correctly
  * requires a real `TokenStream` implementation, which needs Ollama; end-to-end streaming behaviour is covered instead
  * by [[jorlan.service.AgentRunnerSpec]] and [[jorlan.service.AgentSessionManagerSpec]] via
  * [[jorlan.service.FakeModelGateway]].
  */
object LangChainConfigSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("LangChainConfig defaults")(
      test("default ollamaBaseUrl is localhost:11434") {
        assertTrue(LangChainConfig().ollamaBaseUrl == "http://localhost:11434")
      },
      test("default ollamaModel is llama3.2:3b") {
        assertTrue(LangChainConfig().ollamaModel == "llama3.2:3b")
      },
      test("default maxMessages is 1000") {
        assertTrue(LangChainConfig().maxMessages == 1000)
      },
      test("temperature, topK, topP have sensible defaults") {
        val cfg = LangChainConfig()
        assertTrue(cfg.temperature > 0.0, cfg.topK > 0, cfg.topP > 0.0, cfg.topP <= 1.0)
      },
      test("config fields can be overridden") {
        val cfg = LangChainConfig(ollamaBaseUrl = "http://myserver:11434", ollamaModel = "phi3:mini")
        assertTrue(
          cfg.ollamaBaseUrl == "http://myserver:11434",
          cfg.ollamaModel == "phi3:mini",
        )
      },
    )

}
