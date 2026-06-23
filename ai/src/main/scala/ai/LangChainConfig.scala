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
  * @param maxMessages
  *   Maximum number of messages to retain per session in the sliding chat memory window (default 1000).
  */
case class LangChainConfig(
  ollamaBaseUrl: String = "http://localhost:11434",
  ollamaModel:   String = "qwen3:8b", // TODO this should be required
  qdrantHost:    String = "localhost",
  qdrantRPCPort: Int = 6334,
  temperature:   Double = 0.2,
  topK:          Int = 20,
  topP:          Double = 0.8,
  maxMessages:   Int = 1000,
)
// $COVERAGE-ON$
