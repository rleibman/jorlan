/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.llm

// $COVERAGE-OFF$

import ai.*
import jorlan.*
import jorlan.db.repository.ZIORepositories
import jorlan.service.ModelGateway
import zio.*
import zio.json.JsonCodec

import scala.language.unsafeNulls

private case class OpenAiModelEntry(id: String) derives JsonCodec
private case class OpenAiModelsResponse(data: List[OpenAiModelEntry]) derives JsonCodec

/** [[ModelGateway]] backed by any OpenAI-compatible chat-completions API (OpenAI, DeepSeek, Groq, OpenRouter, Google's
  * Gemini OpenAI-compat endpoint, ...) via LangChain4j's OpenAI client. Reuses the provider-agnostic
  * [[LangChainModelGateway]]; only model construction and model listing differ from the Ollama backend.
  *
  * Selected with `jorlan.ai.provider = "openai"`; endpoint, key, and model come from `openAiBaseUrl`, `openAiApiKey`,
  * and `openAiModel`. Embeddings are unaffected — they always run on local Ollama so the vector index keeps its
  * dimensions.
  */
object OpenAiModelGateway {

  /** Lists models via `GET {baseUrl}/models` (supported by every major OpenAI-compatible provider). Falls back to the
    * configured model on error.
    */
  private def listOpenAiModels(config: LangChainConfig): IO[JorlanError, List[ModelInfo]] =
    ZIO
      .attempt {
        val http = java.net.http.HttpClient.newHttpClient()
        val request = java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(s"${config.openAiBaseUrl.stripSuffix("/")}/models"))
          .timeout(java.time.Duration.ofSeconds(10))
          .header("Authorization", s"Bearer ${config.openAiApiKey}")
          .GET()
          .build()
        val response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        response.body()
      }
      .flatMap { body =>
        ZIO
          .fromEither(zio.json.JsonDecoder[OpenAiModelsResponse].decodeJson(body))
          .mapError(new RuntimeException(_))
      }
      .map(_.data.map(m => ModelInfo(ModelId(m.id), "openai", 0, supportsStreaming = true)))
      .tapError(e => ZIO.logWarning(s"Could not list OpenAI-compatible models: ${e.getMessage}"))
      .orElseSucceed(List(ModelInfo(ModelId(config.openAiModel), "openai", 0, supportsStreaming = true)))

  val live: ZLayer[ConfigurationService & ZIORepositories, JorlanError, ModelGateway] =
    ZLayer.fromZIO(
      for {
        config <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.ai)
        _      <- ZIO.when(config.openAiApiKey.isEmpty)(
          ZIO.fail(
            JorlanError(
              "ai.provider is 'openai' but ai.openAiApiKey is empty — set JORLAN_AI_OPENAI_API_KEY or openAiApiKey in application.conf",
            ),
          ),
        )
        eventLogRepo <- ZIO.serviceWith[ZIORepositories](_.eventLog)
        model        <- ZIO
          .attempt {
            StreamingChatLanguageModel.fromJava(
              dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder
                .baseUrl(config.openAiBaseUrl)
                .apiKey(config.openAiApiKey)
                .modelName(config.openAiModel)
                // Cloud endpoints respond in seconds; a short timeout surfaces provider outages
                // quickly instead of letting a pipeline step burn its whole step budget.
                .timeout(java.time.Duration.ofMinutes(2))
                .temperature(config.temperature)
                .topP(config.topP)
                .build,
            )
          }.mapError(JorlanError.apply)
        sessions <- Ref.make(Map.empty[AgentSessionId, SessionEntry])
      } yield LangChainModelGateway(config.maxMessages, model, sessions, eventLogRepo, listOpenAiModels(config)),
    )

}

/** Selects the [[ModelGateway]] backend from `jorlan.ai.provider`. */
object ModelGateways {

  val live: ZLayer[ConfigurationService & ZIORepositories, JorlanError, ModelGateway] =
    ZLayer
      .fromZIO(ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.ai.provider))
      .flatMap { env =>
        env.get match {
          case "openai" => OpenAiModelGateway.live
          case "ollama" => OllamaModelGateway.live
          case other    =>
            ZLayer.fail(JorlanError(s"Unknown ai.provider '$other' — expected 'ollama' or 'openai'"))
        }
      }

}
// $COVERAGE-ON$
