/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai

// $COVERAGE-OFF$
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.response.ChatResponse

import java.util

object ChatLanguageModel {

  def fromJava(j: dev.langchain4j.model.chat.ChatModel): ChatLanguageModel =
    new ChatLanguageModel() {
      override def toJava: dev.langchain4j.model.chat.ChatModel = j

      override def chat(messages: util.List[ChatMessage]): ChatResponse = j.chat(messages)
    }

}

given Conversion[ChatLanguageModel, dev.langchain4j.model.chat.ChatModel] = _.toJava

trait ChatLanguageModel {

  def toJava: dev.langchain4j.model.chat.ChatModel

  def chat(messages: util.List[ChatMessage]): ChatResponse

}
// $COVERAGE-ON$
