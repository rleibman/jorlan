/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai
// $COVERAGE-OFF$

/** Configuration for the LangChain4j / Ollama integration.
  *
  * @param temperature
  *   Sampling temperature. Higher values produce more varied output (default 1.1).
  * @param topK
  *   Top-K sampling parameter (default 40).
  * @param topP
  *   Top-P (nucleus) sampling parameter (default 0.9).
  * @param numCtx
  *   Context window size passed to Ollama (num_ctx). Should be at most the model's native context length. Must be large
  *   enough for a pipeline step's full prompt (invariants + previous step output + tool specs + ReAct tool-result
  *   history) — when the prompt exceeds num_ctx, Ollama silently drops the OLDEST tokens, i.e. the system prompt and
  *   instructions, which manifests as the model ignoring its rules and fabricating results. Do NOT raise this without
  *   benchmarking on the target machine: on CPU-only hosts a too-large value can make generation pathologically slow
  *   (16384 on an AMD Phoenix box produced zero tokens in 26 minutes; 8192 was healthy).
  * @param maxMessages
  *   Maximum number of messages to retain per session in the sliding chat memory window (default 1000).
  */
case class LangChainConfig(
  // Which chat-model backend to use: "ollama" (local) or "openai" (any OpenAI-compatible HTTP API:
  // OpenAI, DeepSeek, Groq, OpenRouter, Google's Gemini OpenAI-compat endpoint, ...). Embeddings
  // always stay on Ollama regardless — the vector index is local and its dimensions must not change.
  provider:            String = "ollama",
  ollamaBaseUrl:       String = "http://localhost:11434",
  ollamaModel:         String = "llama3.2:3b", // TODO this should be required
  openAiBaseUrl:       String = "https://api.openai.com/v1",
  openAiApiKey:        String = "",
  openAiModel:         String = "gpt-4o-mini",
  embeddingModel:      String = "nomic-embed-text",
  embeddingDimensions: Int = 768,
  qdrantHost:          String = "localhost",
  qdrantRPCPort:       Int = 6334,
  temperature:         Double = 0.2,
  topK:                Int = 20,
  topP:                Double = 0.8,
  numCtx:              Int = 8192,
  maxMessages:         Int = 1000,
)
// $COVERAGE-ON$
