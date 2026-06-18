/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.llm

// $COVERAGE-OFF$

import ai.{*, given}
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.{
  AiMessage,
  ChatMessage as LCChatMessage,
  SystemMessage,
  ToolExecutionResultMessage,
  UserMessage as LCUserMessage,
}
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.chat.response.{ChatResponse, StreamingChatResponseHandler}
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore
import jorlan.*
import jorlan.db.repository.{ZIOEventLogRepository, ZIORepositories}
import jorlan.service.*
import zio.*
import zio.json.JsonDecoder
import zio.stream.ZStream

import java.util as jutil
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

private case class OllamaModelDetails(
  parameter_size:     Option[String],
  quantization_level: Option[String],
) derives JsonDecoder

private case class OllamaTagModel(
  name:    String,
  details: Option[OllamaModelDetails] = None,
) derives JsonDecoder

private case class OllamaTagsResponse(
  models: List[OllamaTagModel],
) derives JsonDecoder

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
  eventLogRepo: ZIOEventLogRepository,
) extends ModelGateway {

  private def buildAssistant(
    sessionId:    AgentSessionId,
    systemPrompt: String,
  ): IO[JorlanError, StreamAssistant] =
    ZIO
      .attempt {
        val memory = ChatMemory.fromJava(
          MessageWindowChatMemory
            .builder()
            .id(sessionId.value.toString)
            .maxMessages(config.maxMessages)
            .chatMemoryStore(InMemoryChatMemoryStore())
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
      }.mapError(JorlanError.apply)

  private def getOrCreate(
    sessionId:    AgentSessionId,
    systemPrompt: String,
  ): IO[JorlanError, StreamAssistant] =
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
  ): ZStream[Any, JorlanError, String] = {
    def logEvent(eventType: EventType): UIO[Unit] =
      Clock.instant.flatMap { now =>
        eventLogRepo
          .append(
            EventLog(
              id = EventLogId.empty,
              eventType = eventType,
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

    ZStream.fromZIO(Ref.make(false)).flatMap { errored =>
      ZStream
        .fromZIO(logEvent(EventType.ModelCallStarted) *> getOrCreate(sessionId, systemPrompt))
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
            .tapError(e => errored.set(true) *> logEvent(EventType.ModelCallFailed))
            .mapError(e => ModelUnavailable(Option(e.getMessage).getOrElse(e.getClass.getName)))
        }
        .ensuring(errored.get.flatMap(failed => logEvent(EventType.ModelCallCompleted).unless(failed)))
    }
  }

  override def chatStep(
    sessionId: AgentSessionId,
    messages:  List[AgentMessage],
    tools:     List[ToolSpec],
  ): IO[JorlanError, ChatStep] = {
    val lcMessages: jutil.List[LCChatMessage] = messages.map {
      case SystemMsg(c)                => SystemMessage.from(c): LCChatMessage
      case UserMsg(c)                  => LCUserMessage.from(c): LCChatMessage
      case AssistantMsg(c)             => AiMessage.from(c):     LCChatMessage
      case ToolCallMsg(id, name, args) =>
        AiMessage.from(
          jutil.List.of(
            ToolExecutionRequest.builder().id(id).name(name).arguments(args).build(),
          ),
        ): LCChatMessage
      case ToolResultMsg(id, name, result) =>
        ToolExecutionResultMessage.from(id, name, result): LCChatMessage
    }.asJava

    val lcTools: jutil.List[dev.langchain4j.agent.tool.ToolSpecification] =
      tools
        .map(t => ToolSupport.buildToolSpecification(ScalaToolSpec(t.name, t.description, t.inputSchemaJson)))
        .asJava

    for {
      runtime    <- ZIO.runtime[Any]
      tokenQueue <- Queue.unbounded[Option[String]]
      done       <- Promise.make[JorlanError, ChatStep]
      _          <- ZIO
        .attempt {
          sharedModel.chat(
            lcMessages,
            lcTools,
            new StreamingChatResponseHandler {
              override def onPartialResponse(s: String): Unit =
                Unsafe.unsafe(implicit u => runtime.unsafe.run(tokenQueue.offer(Some(s)).unit))

              override def onCompleteResponse(response: ChatResponse): Unit = {
                val effect = ToolSupport.extractToolCall(response) match {
                  case Some(call) =>
                    tokenQueue.shutdown *>
                      done.succeed(ToolCallRequested(call.id, call.name, call.argsJson))
                  case None =>
                    // Offer a None sentinel to signal end-of-stream BEFORE resolving the promise.
                    // We must NOT call tokenQueue.shutdown here: ZIO's UnboundedQueue checks the
                    // shutdown flag before draining buffered items, so shutdown would silently
                    // discard all tokens that onPartialResponse already offered.
                    tokenQueue.offer(None).unit *>
                      done.succeed(
                        FinalAnswer(
                          ZStream
                            .fromQueue(tokenQueue)
                            .collectWhile { case Some(s) => s }
                            .ensuring(tokenQueue.shutdown),
                        ),
                      )
                }
                Unsafe.unsafe(implicit u => runtime.unsafe.run(effect))
              }

              override def onError(e: Throwable): Unit = {
                val msg = Option(e.getMessage).getOrElse(e.getClass.getName)
                val modelError: ModelError = e match {
                  case _: java.net.http.HttpTimeoutException => ModelTimeout(msg)
                  case e if e.getMessage != null && e.getMessage.toLowerCase.contains("timed") => ModelTimeout(msg)
                  case _                                                                       => ModelUnavailable(msg)
                }
                Unsafe.unsafe { implicit u =>
                  runtime.unsafe.run(tokenQueue.shutdown *> done.fail(modelError))
                }
              }
            },
          )
        }.mapError(e => ModelUnavailable(e.getMessage): JorlanError)
      step <- done.await
    } yield step
  }

  override def availableModels: IO[JorlanError, List[ModelInfo]] = {
    // OllamaClient is package-private in LangChain4j, so we call Ollama's REST API directly.
    // GET /api/tags returns all locally downloaded models (equivalent to `ollama list`).
    ZIO
      .attempt {
        val http = java.net.http.HttpClient.newHttpClient()
        val request = java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(s"${config.ollamaBaseUrl}/api/tags"))
          .timeout(java.time.Duration.ofSeconds(10))
          .GET()
          .build()
        val response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        response.body()
      }
      .flatMap { body =>
        ZIO
          .fromEither(
            zio.json.JsonDecoder[OllamaTagsResponse].decodeJson(body),
          ).mapError(new RuntimeException(_))
      }
      .map { resp =>
        resp.models.map { m =>
          val tag = List(m.details.flatMap(_.parameter_size), m.details.flatMap(_.quantization_level)).flatten
            .filter(_.nonEmpty).mkString("/")
          ModelInfo(
            id = ModelId(m.name),
            provider = if (tag.nonEmpty) s"ollama/$tag" else "ollama",
            contextWindow = 0,
            supportsStreaming = true,
          )
        }
      }
      .tapError(e => ZIO.logWarning(s"Could not list Ollama models: ${e.getMessage}"))
      .orElseSucceed(List(ModelInfo(ModelId(config.ollamaModel), "ollama", 0, supportsStreaming = true)))
  }

  override def seedHistory(
    sessionId:    AgentSessionId,
    messages:     List[Message],
    systemPrompt: String,
  ): IO[JorlanError, Unit] =
    sessions.get
      .flatMap { map =>
        ZIO
          .attempt {
            val store = InMemoryChatMemoryStore()
            val memory = MessageWindowChatMemory
              .builder()
              .id(sessionId.value.toString)
              .maxMessages(config.maxMessages)
              .chatMemoryStore(store)
              .build()
            messages.foreach { msg =>
              val lc4j: dev.langchain4j.data.message.ChatMessage = msg.role match {
                case MessageRole.Assistant => AiMessage.from(msg.content)
                case MessageRole.System    => SystemMessage.from(msg.content)
                case _                     => LCUserMessage.from(msg.content)
              }
              memory.add(lc4j)
            }
            val scalaMem = ChatMemory.fromJava(memory)
            val builder = dev.langchain4j.service.AiServices
              .builder(classOf[StreamAssistant])
              .streamingChatModel(sharedModel.toJava)
              .chatMemory(scalaMem)
            val withPrompt =
              if (systemPrompt.nonEmpty) builder.systemMessageProvider(_ => systemPrompt)
              else builder
            SessionEntry(withPrompt.build(): StreamAssistant, systemPrompt)
          }.flatMap { entry =>
            sessions.update(m => if (m.contains(sessionId)) m else m + (sessionId -> entry))
          }.unless(map.contains(sessionId) || messages.isEmpty).unit
      }.mapError(JorlanError.apply)

  override def invalidateSession(sessionId: AgentSessionId): IO[JorlanError, Unit] =
    sessions.update(_.removed(sessionId))

}

object OllamaModelGateway {

  val live: ZLayer[ConfigurationService & ZIORepositories, JorlanError, ModelGateway] =
    ZLayer.fromZIO(
      for {
        config       <- ZIO.serviceWithZIO[ConfigurationService](_.appConfig).map(_.jorlan.ai)
        eventLogRepo <- ZIO.serviceWith[ZIORepositories](_.eventLog)
        model        <- ZIO
          .attempt {
            StreamingChatLanguageModel.fromJava(
              dev.langchain4j.model.ollama.OllamaStreamingChatModel.builder
                .baseUrl(config.ollamaBaseUrl)
                .modelName(config.ollamaModel)
                .timeout(ai.timeout) // TODO add to config
                .temperature(config.temperature)
                .topK(config.topK)
                .topP(config.topP)
                .think(false) // TODO need to think this through
                .numCtx(4096) // TODO add to config, need to think this through as well
                .build,
            )
          }.mapError(JorlanError.apply)
        sessions <- Ref.make(Map.empty[AgentSessionId, SessionEntry])
      } yield OllamaModelGateway(config, model, sessions, eventLogRepo),
    )

}
// $COVERAGE-ON$
