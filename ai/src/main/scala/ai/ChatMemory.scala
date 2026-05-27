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
// $COVERAGE-OFF

import dev.langchain4j.data.message.ChatMessage

import java.util
import java.util.List

object ChatMemory {

  def fromJava(j: dev.langchain4j.memory.ChatMemory): ChatMemory =
    new ChatMemory() {
      def toJava: dev.langchain4j.memory.ChatMemory = j

      /** The ID of the {@link ChatMemory} .
        *
        * @return
        *   The ID of the {@link ChatMemory} .
        */
      override def id: AnyRef = j.id

      /** Adds a message to the chat memory.
        *
        * @param message
        *   The {@link ChatMessage} to add.
        */
      override def add(message: ChatMessage): Unit = j.add(message)

      /** Retrieves messages from the chat memory. Depending on the implementation, it may not return all previously
        * added messages, but rather a subset, a summary, or a combination thereof.
        *
        * @return
        *   A list of {@link ChatMessage} objects that represent the current state of the chat memory.
        */
      override def messages: util.List[ChatMessage] = j.messages()

      /** Clears the chat memory.
        */
      override def clear(): Unit = j.clear()
    }

}

given Conversion[ChatMemory, dev.langchain4j.memory.ChatMemory] = _.toJava

trait ChatMemory {

  def toJava: dev.langchain4j.memory.ChatMemory

  /** The ID of the {@link ChatMemory} .
    *
    * @return
    *   The ID of the {@link ChatMemory} .
    */
  def id: AnyRef

  /** Adds a message to the chat memory.
    *
    * @param message
    *   The {@link ChatMessage} to add.
    */
  def add(message: ChatMessage): Unit

  /** Retrieves messages from the chat memory. Depending on the implementation, it may not return all previously added
    * messages, but rather a subset, a summary, or a combination thereof.
    *
    * @return
    *   A list of {@link ChatMessage} objects that represent the current state of the chat memory.
    */
  def messages: util.List[ChatMessage]

  /** Clears the chat memory.
    */
  def clear(): Unit

}
// $COVERAGE-ON
