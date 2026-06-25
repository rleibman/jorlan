/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import zio.json.*
import zio.json.ast.Json

/** Config for an HTTP/API executor tool. `{{paramName}}` tokens in `url` and `bodyTemplate` are substituted from the
  * tool's invocation arguments at runtime.
  */
case class HttpApiExecutorConfig(
  method:           String,
  url:              String,
  headers:          Map[String, String] = Map.empty,
  bodyTemplate:     Option[String] = None,
  responseJsonPath: Option[String] = None,
) derives JsonCodec

/** Config for a prompt-template executor tool. `{{paramName}}` tokens in both prompts are substituted from the tool's
  * invocation arguments at runtime.
  */
case class PromptTemplateExecutorConfig(
  systemPrompt:       String,
  userPromptTemplate: String,
) derives JsonCodec

/** Discriminated union of executor configurations. zio-json encodes as `{"HttpApiExecutorConfig": {...}}` etc. */
sealed trait ExecutorConfig derives JsonCodec
object ExecutorConfig {

  case class HttpApi(config: HttpApiExecutorConfig) extends ExecutorConfig
  case class PromptTemplate(config: PromptTemplateExecutorConfig) extends ExecutorConfig

}

/** One tool within a declarative skill manifest. */
case class DeclarativeToolDef(
  name:                 String,
  description:          String,
  requiredCapabilities: List[String] = List.empty,
  examplePrompts:       List[String] = List.empty,
  inputSchema:          Json,
  outputSchema:         Json,
  executor:             ExecutorConfig,
) derives JsonCodec

/** The full manifest for a user-created declarative skill. Stored in [[jorlan.SkillVersion.manifestJson]]. */
case class DeclarativeSkillManifest(
  name:        String,
  version:     String,
  description: String,
  keywords:    List[String] = List.empty,
  tools:       List[DeclarativeToolDef],
) derives JsonCodec
