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
