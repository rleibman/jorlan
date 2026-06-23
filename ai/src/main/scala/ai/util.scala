/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai
// $COVERAGE-OFF$

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.chat.request.{ChatRequest, ChatRequestParameters, ResponseFormat}
import dev.langchain4j.model.ollama.{OllamaChatModel, OllamaStreamingChatModel}
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever
import dev.langchain4j.service.{AiServices, TokenStream}
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore
import zio.*
import zio.stream.ZStream

val timeout = 5.minutes

trait StreamAssistant {

  def chat(message: String): TokenStream

}

trait ChatAssistant {

  def chat(message: String): String

}
type StreamingLangChainEnvironment = ChatMemory & StreamingChatLanguageModel & StreamAssistant & LangChainConfig
type LangChainEnvironment = ChatMemory & ChatLanguageModel & ChatAssistant & LangChainConfig

object LangChainServiceBuilder {

  // Use configuration
  def messageWindowChatMemoryLayer(): URLayer[LangChainConfig, ChatMemory] =
    ZLayer.succeed(
      ChatMemory.fromJava(
        MessageWindowChatMemory
          .builder()
          .id("12345")
          .maxMessages(1000)
          .chatMemoryStore(InMemoryChatMemoryStore())
          .build(),
      ),
    )

  def ollamaChatModelLayer: URLayer[LangChainConfig, ChatLanguageModel] =
    ZLayer.fromZIO(
      for {
        config <- ZIO.service[LangChainConfig]
      } yield ChatLanguageModel.fromJava(
        OllamaChatModel.builder
          .baseUrl(config.ollamaBaseUrl)
          .modelName(config.ollamaModel)
          .timeout(timeout)
          .temperature(1.1)
          .topK(40)
          .topP(0.9)
          .build,
      ),
    )

  def ollamaStreamingChatModelLayer: URLayer[LangChainConfig, StreamingChatLanguageModel] =
    ZLayer.fromZIO(
      for {
        config <- ZIO.service[LangChainConfig]
      } yield StreamingChatLanguageModel.fromJava(
        OllamaStreamingChatModel.builder
          .baseUrl(config.ollamaBaseUrl)
          .modelName(config.ollamaModel)
          .timeout(timeout)
          .temperature(1.1)
          .topK(40)
          .topP(0.9)
          .build,
      ),
    )

  def streamingAssistantLayerWithStore: URLayer[
    StreamingChatLanguageModel & ChatMemory & EmbeddingStore & LangChainConfig,
    StreamAssistant,
  ] =
    ZLayer.fromZIO(
      for {
        store       <- ZIO.service[EmbeddingStore]
        chatMemory  <- ZIO.service[ChatMemory]
        streamModel <- ZIO.service[StreamingChatLanguageModel]
      } yield {
        val base: AiServices[StreamAssistant] = AiServices
          .builder(classOf[StreamAssistant]).streamingChatModel(streamModel)
          .chatMemory(chatMemory)

        val ret: StreamAssistant = base.contentRetriever(EmbeddingStoreContentRetriever.from(store)).build()
        ret
      },
    )

  private def chatAssistant(storeOpt: Option[EmbeddingStore] = None)
    : ZIO[ChatLanguageModel & ChatMemory, Nothing, ChatAssistant] =
    for {
      chatMemory <- ZIO.service[ChatMemory] // Do we really want memory? Should it be "by user"?
      model      <- ZIO.service[ChatLanguageModel]
    } yield {
      val base: AiServices[ChatAssistant] = AiServices
        .builder(classOf[ChatAssistant])
        .chatModel(model)
        .chatMemory(chatMemory)

      storeOpt.fold(base)(store => base.contentRetriever(EmbeddingStoreContentRetriever.from(store))).build
    }

  val chatAssistantLayerWithStore
    : URLayer[EmbeddingStore & ChatLanguageModel & ChatMemory & LangChainConfig, ChatAssistant] =
    ZLayer.fromZIO(
      for {
        store      <- ZIO.service[EmbeddingStore]
        chatMemory <- ZIO.service[ChatMemory]
        model      <- ZIO.service[ChatLanguageModel]
      } yield {
        val base: AiServices[ChatAssistant] = AiServices
          .builder(classOf[ChatAssistant])
          .chatModel(model)
          .chatMemory(chatMemory)

        val ret: ChatAssistant = base.contentRetriever(EmbeddingStoreContentRetriever.from(store)).build()
        ret
      },
    )

  def chatAssistantLayer(storeOpt: Option[EmbeddingStore] = None)
    : URLayer[ChatLanguageModel & ChatMemory & LangChainConfig, ChatAssistant] = ZLayer.fromZIO(chatAssistant(storeOpt))

  def streamingAssistantLayer(storeOpt: Option[EmbeddingStore] = None)
    : URLayer[StreamingChatLanguageModel & ChatMemory & LangChainConfig, StreamAssistant] =
    ZLayer.fromZIO(
      for {
        chatMemory  <- ZIO.service[ChatMemory]
        streamModel <- ZIO.service[StreamingChatLanguageModel]
      } yield {
        val base: AiServices[StreamAssistant] = AiServices
          .builder(classOf[StreamAssistant])
          .streamingChatModel(streamModel)
          .chatMemory(chatMemory)

        val ret: StreamAssistant =
          storeOpt.fold(base)(store => base.contentRetriever(EmbeddingStoreContentRetriever.from(store))).build
        ret
      },
    )

}

def streamedChat(message: String): ZStream[StreamAssistant, Throwable, String] =
  ZStream.unwrap(for {
    aiServices <- ZIO.service[StreamAssistant]
  } yield ZStream.async[Any, Throwable, String] { callback =>
    aiServices
      .chat(message)
      .onPartialResponse(str => callback(ZIO.succeed(Chunk(str))))
      .onCompleteResponse(_ => callback(ZIO.succeed(Chunk.empty)))
      .onError(error => callback(ZIO.fail(Some(error))))
      .start()
  })

def chat(
  question:       String,
  responseFormat: Option[ResponseFormat] = None,
): ZIO[ChatLanguageModel, Throwable, String] = {
  val request = responseFormat
    .fold(ChatRequest.builder())(fmt =>
      ChatRequest.builder().parameters(ChatRequestParameters.builder().responseFormat(fmt).build()),
    )
    .messages(UserMessage.from(question))
    .build()
  for {
    model <- ZIO.service[ChatLanguageModel]
    res   <- ZIO.attemptBlocking(model.chat(request).aiMessage().text())
  } yield res
}
// $COVERAGE-ON$
