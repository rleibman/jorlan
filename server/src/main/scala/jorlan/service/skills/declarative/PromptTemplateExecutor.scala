/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import jorlan.*
import jorlan.service.*
import zio.*
import zio.json.ast.Json

/** Executes a [[PromptTemplateExecutorConfig]] tool by calling the LLM with substituted prompts. */
object PromptTemplateExecutor {

  def execute(
    config:    PromptTemplateExecutorConfig,
    args:      Json,
    sessionId: AgentSessionId,
    gateway:   ModelGateway,
  ): IO[JorlanError, Json] = {
    val systemPrompt = DeclarativeArgSubstitution.substitute(config.systemPrompt, args)
    val userPrompt = DeclarativeArgSubstitution.substitute(config.userPromptTemplate, args)
    val messages = List(SystemMsg(systemPrompt), UserMsg(userPrompt))
    gateway
      .chatStep(sessionId, messages, List.empty)
      .flatMap {
        case FinalAnswer(stream) =>
          stream.runCollect.mapBoth(JorlanError(_), chunks => Json.Str(chunks.mkString))
        case ToolCallRequested(_, name, _) =>
          ZIO.fail(JorlanError(s"Prompt template tool triggered unexpected tool call: $name"))
      }
  }

}
