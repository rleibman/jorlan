/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package ai
// $COVERAGE-OFF$

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.{AiMessage, ChatMessage, UserMessage}
import dev.langchain4j.model.StreamingResponseHandler
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler

import java.util
import java.util.Collections.singletonList

object StreamingChatLanguageModel {

  def fromJava(j: dev.langchain4j.model.chat.StreamingChatModel): StreamingChatLanguageModel =
    new StreamingChatLanguageModel() {
      def toJava: dev.langchain4j.model.chat.StreamingChatModel = j

      def chat(
        messages: util.List[ChatMessage],
        handler:  StreamingChatResponseHandler,
      ): Unit = j.chat(messages, handler)
    }

}

given Conversion[StreamingChatLanguageModel, dev.langchain4j.model.chat.StreamingChatModel] = _.toJava

trait StreamingChatLanguageModel {

  def toJava: dev.langchain4j.model.chat.StreamingChatModel

  /** Generates a response from the model based on a message from a user.
    *
    * @param userMessage
    *   The message from the user.
    * @param handler
    *   The handler for streaming the response.
    */
  def chat(
    userMessage: String,
    handler:     StreamingChatResponseHandler,
  ): Unit = {
    chat(singletonList(UserMessage.from(userMessage): ChatMessage), handler)
  }

  /** Generates a response from the model based on a message from a user.
    *
    * @param userMessage
    *   The message from the user.
    * @param handler
    *   The handler for streaming the response.
    */
  def chat(
    userMessage: UserMessage,
    handler:     StreamingChatResponseHandler,
  ): Unit = {
    chat(singletonList(userMessage), handler)
  }

  /** Generates a response from the model based on a sequence of messages. Typically, the sequence contains messages in
    * the following order: System (optional) - User - AI - User - AI - User ...
    *
    * @param messages
    *   A list of messages.
    * @param handler
    *   The handler for streaming the response.
    */
  def chat(
    messages: util.List[ChatMessage],
    handler:  StreamingChatResponseHandler,
  ): Unit

  /** Generates a response from the model based on a list of messages and a list of tool specifications. The response
    * may either be a text message or a request to execute one of the specified tools. Typically, the list contains
    * messages in the following order: System (optional) - User - AI - User - AI - User ...
    *
    * @param messages
    *   A list of messages.
    * @param toolSpecifications
    *   A list of tools that the model is allowed to execute. The model autonomously decides whether to use any of these
    *   tools.
    * @param handler
    *   The handler for streaming the response. {@link AiMessage} can contain either a textual response or a request to
    *   execute one of the tools.
    */
  def chat(
    messages:           util.List[ChatMessage],
    toolSpecifications: util.List[ToolSpecification],
    handler:            StreamingChatResponseHandler,
  ): Unit = {
    throw new IllegalArgumentException("Tools are currently not supported by this model")
  }

  /** Generates a response from the model based on a list of messages and a tool specification.
    *
    * @param messages
    *   A list of messages.
    * @param toolSpecification
    *   A tool that the model is allowed to execute.
    * @param handler
    *   The handler for streaming the response.
    */
  def chat(
    messages:          util.List[ChatMessage],
    toolSpecification: ToolSpecification,
    handler:           StreamingChatResponseHandler,
  ): Unit = {
    throw new IllegalArgumentException("Tools are currently not supported by this model")
  }

}
// $COVERAGE-ON$
