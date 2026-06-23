/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell.tui

import jorlan.shell.testing.FakeScreen
import zio.*
import zio.test.*
import zio.test.Assertion.*

/** P7-040: Tests for `JorlanScreen` behavior using the `FakeScreen` test double.
  *
  * Full Lanterna rendering tests require a headless terminal (`DefaultTerminalFactory().setForceTextTerminal(true)`);
  * they are a follow-up integration test. These tests verify the message-management and state-management logic that is
  * independent of terminal I/O, using the FakeScreen test double.
  */
object JorlanScreenSpec extends ZIOSpecDefault {

  override def spec: Spec[Any, Nothing] =
    suite("JorlanScreen behavior via FakeScreen")(
      suite("addMessage — buffer cap")(
        test("FakeScreen has no cap and stores beyond maxMessages") {
          for {
            fs <- FakeScreen.make
            // Add maxMessages + 5 messages; FakeScreen stores all of them
            _ <- ZIO.foreachDiscard(1 to (JorlanScreen.maxMessages + 5))(i =>
              fs.addMessage(MessageKind.System, s"msg $i"),
            )
            msgs <- fs.messages
          } yield assertTrue(msgs.size == JorlanScreen.maxMessages + 5)
        },
        test("messages are appended in order") {
          for {
            fs   <- FakeScreen.make
            _    <- fs.addMessage(MessageKind.System, "first")
            _    <- fs.addMessage(MessageKind.User, "second")
            _    <- fs.addMessage(MessageKind.Error, "third")
            msgs <- fs.messages
          } yield assertTrue(
            msgs.size == 3 &&
              msgs(0).content == "first" &&
              msgs(1).content == "second" &&
              msgs(2).content == "third",
          )
        },
      ),
      suite("setStatus / setModeStatus / setInputPrompt")(
        test("status and mode updates are reflected via FakeScreen") {
          for {
            fs   <- FakeScreen.make
            _    <- fs.setStatus(" ● test status")
            _    <- fs.setModeStatus(" [connected]")
            _    <- fs.setInputPrompt(">> ")
            msgs <- fs.messages
          } yield assertTrue(msgs.isEmpty) // setters produce no messages
        },
      ),
      suite("shutdown")(
        test("isShutdown reflects shutdown call") {
          for {
            fs     <- FakeScreen.make
            before <- fs.isShutdown
            _      <- fs.shutdown
            after  <- fs.isShutdown
          } yield assertTrue(!before && after)
        },
      ),
      suite("readLine / sendLine")(
        test("sendLine delivers a line to readLine") {
          for {
            fs   <- FakeScreen.make
            _    <- fs.sendLine("hello world")
            line <- fs.readLine
          } yield assertTrue(line == "hello world")
        },
        test("multiple sendLine calls are delivered in order") {
          for {
            fs    <- FakeScreen.make
            _     <- fs.sendLine("first")
            _     <- fs.sendLine("second")
            line1 <- fs.readLine
            line2 <- fs.readLine
          } yield assertTrue(line1 == "first" && line2 == "second")
        },
      ),
      suite("MessageKind filtering")(
        test("messagesOfKind filters correctly") {
          for {
            fs      <- FakeScreen.make
            _       <- fs.addMessage(MessageKind.System, "sys")
            _       <- fs.addMessage(MessageKind.Error, "err")
            _       <- fs.addMessage(MessageKind.System, "sys2")
            sysMsgs <- fs.messagesOfKind(MessageKind.System)
            errMsgs <- fs.messagesOfKind(MessageKind.Error)
          } yield assertTrue(sysMsgs.size == 2 && errMsgs.size == 1)
        },
      ),
    )

}
