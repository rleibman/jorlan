/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell

import jorlan.shell.commands.ShellCommand
import zio.test.*

object ShellCommandSpec extends ZIOSpecDefault {

  override def spec: Spec[Any, Nothing] =
    suite("ShellCommand.parse")(
      test("plain text becomes Message") {
        assertTrue(ShellCommand.parse("hello world") == ShellCommand.Message("hello world"))
      },
      test("/help") {
        assertTrue(ShellCommand.parse("/help") == ShellCommand.Help)
      },
      test("/commands") {
        assertTrue(ShellCommand.parse("/commands") == ShellCommand.Commands)
      },
      test("/status") {
        assertTrue(ShellCommand.parse("/status") == ShellCommand.Status)
      },
      test("/about") {
        assertTrue(ShellCommand.parse("/about") == ShellCommand.About)
      },
      test("/whoami") {
        assertTrue(ShellCommand.parse("/whoami") == ShellCommand.WhoAmI)
      },
      test("/quit") {
        assertTrue(ShellCommand.parse("/quit") == ShellCommand.Quit)
      },
      test("/exit aliases /quit") {
        assertTrue(ShellCommand.parse("/exit") == ShellCommand.Quit)
      },
      test("/new") {
        assertTrue(ShellCommand.parse("/new") == ShellCommand.NewSession(None))
      },
      test("/model") {
        assertTrue(ShellCommand.parse("/model gpt-4") == ShellCommand.ModelInfo)
      },
      test("/models") {
        assertTrue(ShellCommand.parse("/models") == ShellCommand.ListModels)
      },
      test("/trace with level") {
        assertTrue(ShellCommand.parse("/trace debug") == ShellCommand.Trace("debug"))
      },
      test("/trace without level defaults to info") {
        assertTrue(ShellCommand.parse("/trace") == ShellCommand.Trace("info"))
      },
      test("unknown command") {
        assertTrue(ShellCommand.parse("/foobar") == ShellCommand.Unknown("/foobar"))
      },
      test("leading slash without name") {
        assertTrue(ShellCommand.parse("/") == ShellCommand.Unknown("/"))
      },
      test("/personality without args") {
        assertTrue(ShellCommand.parse("/personality") == ShellCommand.Personality)
      },
      test("/personality set with field and single-word value") {
        assertTrue(
          ShellCommand.parse("/personality set formality Casual") == ShellCommand.PersonalitySet("formality", "Casual"),
        )
      },
      test("/personality set with field and multi-word value") {
        assertTrue(
          ShellCommand.parse("/personality set name Jorlan The Bot") == ShellCommand
            .PersonalitySet("name", "Jorlan The Bot"),
        )
      },
      test("/personality set without value falls back to Personality") {
        assertTrue(ShellCommand.parse("/personality set formality") == ShellCommand.Personality)
      },
      // P7-041: Edge cases documented by tests to pin current parser behaviour.
      test("/trace with extra words — only the first word is the level") {
        assertTrue(ShellCommand.parse("/trace info extra") == ShellCommand.Trace("info"))
      },
      test("//comment — double slash produces Unknown(\"//comment\")") {
        assertTrue(ShellCommand.parse("//comment") == ShellCommand.Unknown("//comment"))
      },
      test("empty string becomes Message(\"\")") {
        assertTrue(ShellCommand.parse("") == ShellCommand.Message(""))
      },
      test("whitespace-only becomes Message(\"   \")") {
        assertTrue(ShellCommand.parse("   ") == ShellCommand.Message("   "))
      },
    )

}
