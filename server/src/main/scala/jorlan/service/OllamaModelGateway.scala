/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

// $COVERAGE-OFF$

import ai.*
import ai.given_Conversion_ChatMemory_ChatMemory
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore
import jorlan.*
import jorlan.domain.*
import zio.*
import zio.stream.ZStream

import scala.language.unsafeNulls

private class OllamaModelGateway(
  config:   LangChainConfig,
  sessions: Ref[Map[AgentSessionId, StreamAssistant]],
  eventLog: EventLogService,
) extends ModelGateway {

  private def getOrCreate(sessionId: AgentSessionId): UIO[StreamAssistant] =
    sessions.get.flatMap { map =>
      map.get(sessionId) match {
        case Some(a) => ZIO.succeed(a)
        case None    =>
          ZIO
            .attempt {
              val memory = ChatMemory.fromJava(
                MessageWindowChatMemory
                  .builder()
                  .id(sessionId.value.toString)
                  .maxMessages(1000)
                  .chatMemoryStore(new InMemoryChatMemoryStore())
                  .build(),
              )
              val streamModel = StreamingChatLanguageModel.fromJava(
                dev.langchain4j.model.ollama.OllamaStreamingChatModel.builder
                  .baseUrl(config.ollamaBaseUrl)
                  .modelName(config.ollamaModel)
                  .timeout(ai.timeout)
                  .temperature(1.1)
                  .topK(40)
                  .topP(0.9)
                  .build,
              )
              dev.langchain4j.service.AiServices
                .builder(classOf[StreamAssistant])
                .streamingChatModel(streamModel.toJava)
                .chatMemory(memory)
                .build(): StreamAssistant
            }
            .orDie
            .flatMap(a => sessions.update(_ + (sessionId -> a)).as(a))
      }
    }

  override def streamedResponse(
    sessionId: AgentSessionId,
    message:   String,
  ): ZStream[Any, ModelError, String] =
    ZStream
      .fromZIO(
        for {
          now <- Clock.instant
          _   <- eventLog
            .log(
              EventLog(
                id = EventLogId.empty,
                eventType = EventType.ModelCallStarted,
                actorId = None,
                agentId = None,
                sessionId = Some(sessionId),
                resource = Some(sessionId),
                payloadJson = None,
                occurredAt = now,
              ),
            )
            .ignore
          assistant <- getOrCreate(sessionId)
        } yield assistant,
      )
      .flatMap { assistant =>
        ZStream
          .async[Any, Throwable, String] { cb =>
            assistant
              .chat(message)
              .onPartialResponse(str => cb(ZIO.succeed(Chunk(str))))
              .onCompleteResponse(_ => cb(ZIO.succeed(Chunk.empty)))
              .onError(err => cb(ZIO.fail(Some(err))))
              .start()
          }
          .mapError(e => ModelUnavailable(Option(e.getMessage).getOrElse(e.getClass.getName)))
      }
      .ensuring(
        Clock.instant.flatMap { now =>
          eventLog
            .log(
              EventLog(
                id = EventLogId.empty,
                eventType = EventType.ModelCallCompleted,
                actorId = None,
                agentId = None,
                sessionId = Some(sessionId),
                resource = Some(sessionId),
                payloadJson = None,
                occurredAt = now,
              ),
            )
            .ignore
        },
      )

  override def availableModels: UIO[List[ModelInfo]] =
    ZIO.succeed(
      List(
        ModelInfo(
          id = ModelId(config.ollamaModel),
          provider = "ollama",
          contextWindow = 4096,
          supportsStreaming = true,
        ),
      ),
    )

}

object OllamaModelGateway {

  val live: URLayer[LangChainConfig & EventLogService, ModelGateway] =
    ZLayer.fromZIO(
      for {
        config   <- ZIO.service[LangChainConfig]
        eventLog <- ZIO.service[EventLogService]
        sessions <- Ref.make(Map.empty[AgentSessionId, StreamAssistant])
      } yield new OllamaModelGateway(config, sessions, eventLog),
    )

}
// $COVERAGE-ON$
