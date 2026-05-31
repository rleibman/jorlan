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

// Future: consider how much of this belongs in the ai module and how much of it should should be using it directly.
/** [[ModelGateway]] implementation backed by a local Ollama endpoint via LangChain4j.
  *
  * A single [[ai.StreamingChatLanguageModel]] (and its HTTP connection pool) is shared across all sessions. Each
  * session gets its own [[ai.StreamAssistant]] wrapping an isolated [[MessageWindowChatMemory]].
  *
  * The `sessions` map is kept in a [[Ref]] and all reads/writes use [[Ref.modify]] to avoid TOCTOU races under
  * concurrent session creation.
  */
private class OllamaModelGateway(
  config:      LangChainConfig,
  sharedModel: StreamingChatLanguageModel,
  sessions:    Ref[Map[AgentSessionId, StreamAssistant]],
  eventLog:    EventLogService,
) extends ModelGateway {

  private def buildAssistant(sessionId: AgentSessionId): UIO[StreamAssistant] =
    ZIO.attempt {
      val memory = ChatMemory.fromJava(
        MessageWindowChatMemory
          .builder()
          .id(sessionId.value.toString)
          .maxMessages(config.maxMessages)
          .chatMemoryStore(new InMemoryChatMemoryStore())
          .build(),
      )
      dev.langchain4j.service.AiServices
        .builder(classOf[StreamAssistant])
        .streamingChatModel(sharedModel.toJava)
        .chatMemory(memory)
        .build(): StreamAssistant
    }.orDie

  private def getOrCreate(sessionId: AgentSessionId): UIO[StreamAssistant] =
    buildAssistant(sessionId).flatMap { fresh =>
      sessions.modify { map =>
        map.get(sessionId) match {
          case Some(existing) => (existing, map)
          case None           => (fresh, map + (sessionId -> fresh))
        }
      }
    }

  override def streamedResponse(
    sessionId: AgentSessionId,
    message:   String,
  ): ZStream[Any, ModelError, String] = {
    val logStarted = Clock.instant.flatMap { now =>
      eventLog
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
    }

    val logCompleted = Clock.instant.flatMap { now =>
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
    }

    def logFailed(err: Throwable) =
      Clock.instant.flatMap { now =>
        eventLog
          .log(
            EventLog(
              id = EventLogId.empty,
              eventType = EventType.ModelCallFailed,
              actorId = None,
              agentId = None,
              sessionId = Some(sessionId),
              resource = Some(sessionId),
              payloadJson = None,
              occurredAt = now,
            ),
          )
          .ignore
      }

    ZStream.unwrap(
      Ref.make(false).map { errored =>
        ZStream
          .fromZIO(logStarted *> getOrCreate(sessionId))
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
              .tapError(e => errored.set(true) *> logFailed(e))
              .mapError(e => ModelUnavailable(Option(e.getMessage).getOrElse(e.getClass.getName)))
          }
          .ensuring(errored.get.flatMap(failed => if (failed) ZIO.unit else logCompleted))
      },
    )
  }

  override def availableModels: UIO[List[ModelInfo]] =
    ZIO.succeed(
      List(
        ModelInfo(
          id = ModelId(config.ollamaModel),
          provider = "ollama",
          contextWindow = 4096, // placeholder — actual context window depends on the loaded model
          supportsStreaming = true,
        ),
      ),
    )

  override def invalidateSession(sessionId: AgentSessionId): UIO[Unit] =
    sessions.update(_ - sessionId)

}

object OllamaModelGateway {

  val live: URLayer[LangChainConfig & EventLogService, ModelGateway] =
    ZLayer.fromZIO(
      for {
        config   <- ZIO.service[LangChainConfig]
        eventLog <- ZIO.service[EventLogService]
        model    <- ZIO.attempt {
          StreamingChatLanguageModel.fromJava(
            dev.langchain4j.model.ollama.OllamaStreamingChatModel.builder
              .baseUrl(config.ollamaBaseUrl)
              .modelName(config.ollamaModel)
              .timeout(ai.timeout)
              .temperature(config.temperature)
              .topK(config.topK)
              .topP(config.topP)
              .build,
          )
        }.orDie
        sessions <- Ref.make(Map.empty[AgentSessionId, StreamAssistant])
      } yield new OllamaModelGateway(config, model, sessions, eventLog),
    )

}
// $COVERAGE-ON$
