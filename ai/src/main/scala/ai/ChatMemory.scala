/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai
// $COVERAGE-OFF$

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
// $COVERAGE-ON$
