/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell.commands

/** All recognised shell commands. Plain text (no leading `/`) is represented as [[Message]].
  *
  * P7-031: Converted from sealed trait hierarchy to Scala 3 enum, consistent with [[MessageKind]] in the same module.
  * Pattern matching and companion references (`ShellCommand.Help`, etc.) are unchanged.
  */
enum ShellCommand {

  case Message(text: String)
  case Help
  case Commands
  case Status
  case About
  case WhoAmI
  case Quit
  case NewSession(model: Option[String])
  case ModelInfo
  case ListModels
  case Trace(level: String)
  case Personality
  case PersonalitySet(
    field: String,
    value: String,
  )
  case Unknown(raw: String)

}

object ShellCommand {

  /** Parse a raw input line into a [[ShellCommand]]. Starts with `/` → command; otherwise → message. */
  def parse(line: String): ShellCommand = {
    // P7-006: Use if-else expression — non-idiomatic `return` removed.
    if (!line.startsWith("/")) {
      Message(line)
    } else {
      val parts = line.stripPrefix("/").split("\\s+", 2).toList
      parts match {
        case "help" :: _                                              => Help
        case "commands" :: _                                          => Commands
        case "status" :: _                                            => Status
        case "about" :: _                                             => About
        case "whoami" :: _                                            => WhoAmI
        case "quit" :: _                                              => Quit
        case "exit" :: _                                              => Quit
        case "new" :: model :: _                                      => NewSession(Some(model))
        case "new" :: Nil                                             => NewSession(None)
        case "model" :: _                                             => ModelInfo
        case "models" :: _                                            => ListModels
        case "trace" :: level :: _                                    => Trace(level)
        case "trace" :: Nil                                           => Trace("info")
        case "personality" :: "set" :: field :: rest if rest.nonEmpty => PersonalitySet(field, rest.mkString(" "))
        case "personality" :: _                                       => Personality
        case other :: _                                               => Unknown(s"/$other")
        case Nil                                                      => Unknown("/")
      }
    }
  }

}
