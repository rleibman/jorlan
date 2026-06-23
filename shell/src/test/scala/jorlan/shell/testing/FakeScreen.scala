/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell.testing

import jorlan.shell.tui.{JorlanScreen, MessageEntry, MessageKind}
import zio.*

/** In-memory [[JorlanScreen]] test double. All writes are captured in Refs so tests can assert on them. `readLine`
  * blocks until `sendLine` is called, allowing tests to simulate user input.
  */
class FakeScreen(
  capturedMessages: Ref[Vector[MessageEntry]],
  statusRef:        Ref[String],
  modeRef:          Ref[String],
  promptRef:        Ref[String],
  inputQueue:       Queue[String],
  runningRef:       Ref[Boolean],
) extends JorlanScreen {

  override def addMessage(
    kind:    MessageKind,
    content: String,
  ): UIO[Unit] =
    capturedMessages.update(_ :+ MessageEntry(kind, content, "00:00:00"))

  override def appendToLastMessage(
    kind:  MessageKind,
    extra: String,
  ): UIO[Unit] =
    capturedMessages.update { msgs =>
      msgs.lastOption match {
        case Some(last) if last.kind == kind => msgs.init :+ last.copy(content = last.content + extra)
        case _                               => msgs :+ MessageEntry(kind, extra, "00:00:00")
      }
    }

  override def commitInProgress(): UIO[Unit] = ZIO.unit

  override def setStatus(text:       String): UIO[Unit] = statusRef.set(text)
  override def setModeStatus(text:   String): UIO[Unit] = modeRef.set(text)
  override def setInputPrompt(label: String): UIO[Unit] = promptRef.set(label)
  override def readLine:                      UIO[String] = inputQueue.take
  override def shutdown:                      UIO[Unit] = runningRef.set(false)
  override def startRendering:                UIO[Unit] = ZIO.unit

  /** Retrieve all messages captured so far. */
  def messages: UIO[Vector[MessageEntry]] = capturedMessages.get

  /** Retrieve all messages of a specific kind. */
  def messagesOfKind(kind: MessageKind): UIO[Vector[MessageEntry]] =
    capturedMessages.get.map(_.filter(_.kind == kind))

  /** Simulate user typing a line in the input area. */
  def sendLine(line: String): UIO[Unit] = inputQueue.offer(line).unit

  /** True if `shutdown` has been called. */
  def isShutdown: UIO[Boolean] = runningRef.get.map(!_)

}

object FakeScreen {

  val make: UIO[FakeScreen] =
    for {
      messages <- Ref.make(Vector.empty[MessageEntry])
      status   <- Ref.make("")
      mode     <- Ref.make("")
      prompt   <- Ref.make("❯ ")
      queue    <- Queue.unbounded[String]
      running  <- Ref.make(true)
    } yield FakeScreen(messages, status, mode, prompt, queue, running)

  val layer: ULayer[JorlanScreen] = ZLayer.fromZIO(make)

}
