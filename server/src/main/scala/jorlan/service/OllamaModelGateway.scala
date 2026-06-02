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
import jorlan.db.repository.EventLogZIORepository
import jorlan.domain.*
import zio.*
import zio.stream.ZStream

import scala.language.unsafeNulls

// Future: consider how much of this belongs in the ai module and how much of it should should be using it directly.

/** Holds one LangChain4j assistant and the system prompt it was built with.
  *
  * If the personality changes and `systemPrompt` differs from the stored one, the entry is rebuilt. This resets the
  * session's [[MessageWindowChatMemory]] and conversation history is lost for that session.
  */
private case class SessionEntry(
  assistant:    StreamAssistant,
  systemPrompt: String,
)

/** [[ModelGateway]] implementation backed by a local Ollama endpoint via LangChain4j.
  *
  * A single [[ai.StreamingChatLanguageModel]] (and its HTTP connection pool) is shared across all sessions. Each
  * session gets its own [[ai.StreamAssistant]] wrapping an isolated [[MessageWindowChatMemory]].
  *
  * The `sessions` map is kept in a [[Ref]]. The fast path (existing assistant, unchanged prompt) does a plain
  * [[Ref.get]]. The rebuild path builds a candidate assistant and commits it via [[Ref.modify]] so the write is atomic.
  * A concurrent fiber that wins the race is used; the losing candidate is discarded.
  *
  * **Note:** a personality update causes the next call for any active session to rebuild its assistant, discarding
  * conversation history for that session.
  */
private class OllamaModelGateway(
  config:       LangChainConfig,
  sharedModel:  StreamingChatLanguageModel,
  sessions:     Ref[Map[AgentSessionId, SessionEntry]],
  eventLogRepo: EventLogZIORepository,
) extends ModelGateway {

  private def buildAssistant(
    sessionId:    AgentSessionId,
    systemPrompt: String,
  ): UIO[StreamAssistant] =
    ZIO.attempt {
      val memory = ChatMemory.fromJava(
        MessageWindowChatMemory
          .builder()
          .id(sessionId.value.toString)
          .maxMessages(config.maxMessages)
          .chatMemoryStore(new InMemoryChatMemoryStore())
          .build(),
      )
      val builder = dev.langchain4j.service.AiServices
        .builder(classOf[StreamAssistant])
        .streamingChatModel(sharedModel.toJava)
        .chatMemory(memory)
      val withPrompt =
        if (systemPrompt.nonEmpty) builder.systemMessageProvider(_ => systemPrompt)
        else builder
      withPrompt.build(): StreamAssistant
    }.orDie

  private def getOrCreate(
    sessionId:    AgentSessionId,
    systemPrompt: String,
  ): UIO[StreamAssistant] =
    sessions.get.flatMap { map =>
      map.get(sessionId) match {
        case Some(SessionEntry(existing, storedPrompt)) if storedPrompt == systemPrompt =>
          ZIO.succeed(existing)
        case _ =>
          buildAssistant(sessionId, systemPrompt).flatMap { fresh =>
            val entry = SessionEntry(fresh, systemPrompt)
            // Atomic commit: if another fiber already installed a compatible entry, use theirs.
            sessions.modify { m =>
              m.get(sessionId) match {
                case Some(winner @ SessionEntry(_, sp)) if sp == systemPrompt => (winner.assistant, m)
                case _                                                        => (fresh, m + (sessionId -> entry))
              }
            }
          }
      }
    }

  override def streamedResponse(
    sessionId:    AgentSessionId,
    message:      String,
    systemPrompt: String = "",
  ): ZStream[Any, ModelError, String] = {
    val logStarted = Clock.instant.flatMap { now =>
      eventLogRepo
        .append(
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
      eventLogRepo
        .append(
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
        eventLogRepo
          .append(
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
          .fromZIO(logStarted *> getOrCreate(sessionId, systemPrompt))
          .flatMap { assistant =>
            ZStream
              .async[Any, Throwable, String] { cb =>
                assistant
                  .chat(message)
                  .onPartialResponse(str => cb(ZIO.succeed(Chunk(str))))
                  .onCompleteResponse(_ => cb(ZIO.fail(None)))
                  .onError(err => cb(ZIO.fail(Some(err))))
                  .start()
              }
              .tapError(e => errored.set(true) *> logFailed(e))
              .mapError(e => ModelUnavailable(Option(e.getMessage).getOrElse(e.getClass.getName)))
          }
          .ensuring(errored.get.flatMap(failed => logCompleted.unless(failed)))
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
    sessions.update(_.removed(sessionId))

}

object OllamaModelGateway {

  val live: URLayer[LangChainConfig & EventLogZIORepository, ModelGateway] =
    ZLayer.fromZIO(
      for {
        config       <- ZIO.service[LangChainConfig]
        eventLogRepo <- ZIO.service[EventLogZIORepository]
        model        <- ZIO.attempt {
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
        sessions <- Ref.make(Map.empty[AgentSessionId, SessionEntry])
      } yield new OllamaModelGateway(config, model, sessions, eventLogRepo),
    )

}
// $COVERAGE-ON$
