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

import jorlan.*
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
      test("/new with model argument") {
        assertTrue(ShellCommand.parse("/new llama3") == ShellCommand.NewSession(Some("llama3")))
      },
      test("/capabilities") {
        assertTrue(ShellCommand.parse("/capabilities") == ShellCommand.Capabilities)
      },
      test("/memory list without scope") {
        assertTrue(ShellCommand.parse("/memory list") == ShellCommand.MemoryList(None))
      },
      test("/memory list with scope") {
        assertTrue(ShellCommand.parse("/memory list User") == ShellCommand.MemoryList(Some(MemoryScope.User)))
      },
      test("/memory search with text") {
        assertTrue(ShellCommand.parse("/memory search my password") == ShellCommand.MemorySearch("my password"))
      },
      test("/memory search without text becomes Unknown") {
        assertTrue(ShellCommand.parse("/memory search") == ShellCommand.Unknown("/memory"))
      },
      test("/memory forget with valid id") {
        assertTrue(ShellCommand.parse("/memory forget 42") == ShellCommand.MemoryForget(MemoryRecordId(42L)))
      },
      test("/memory forget with non-numeric id becomes Unknown") {
        assertTrue(ShellCommand.parse("/memory forget abc") == ShellCommand.Unknown("/memory"))
      },
      test("/memory remember with key and text") {
        assertTrue(
          ShellCommand.parse("/memory remember user.lang I prefer Scala") == ShellCommand
            .MemoryRemember("user.lang", "I prefer Scala"),
        )
      },
      test("/memory remember without text becomes Unknown") {
        assertTrue(ShellCommand.parse("/memory remember key") == ShellCommand.Unknown("/memory"))
      },
      test("/skills") {
        assertTrue(ShellCommand.parse("/skills") == ShellCommand.Skills)
      },
      test("/skills with extra args") {
        assertTrue(ShellCommand.parse("/skills extra") == ShellCommand.Skills)
      },
      test("/contacts find with name") {
        assertTrue(ShellCommand.parse("/contacts find Alice") == ShellCommand.ContactsFind("Alice"))
      },
      test("/contacts find with multi-word name") {
        assertTrue(ShellCommand.parse("/contacts find Alice Smith") == ShellCommand.ContactsFind("Alice Smith"))
      },
      test("/contacts without find becomes Unknown") {
        assertTrue(ShellCommand.parse("/contacts") == ShellCommand.Unknown("/contacts"))
      },
    )

}
