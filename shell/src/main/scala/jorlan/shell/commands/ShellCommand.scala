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

import jorlan.domain.{AgentSessionId, ApprovalRequestId, MemoryRecordId}

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
  case MemoryList(scope: Option[String])
  case MemorySearch(text: String)
  case MemoryForget(id: MemoryRecordId)
  case MemoryShare(id: MemoryRecordId)
  case MemoryPrivatize(id: MemoryRecordId)
  case MemoryRemember(
    key:   String,
    text:  String,
    scope: Option[String] = None,
  )
  case Skills
  case ContactsFind(name: String)
  case Capabilities
  case AgentsList
  case AgentsStop(sessionId: AgentSessionId)
  case ApprovalsList
  case ApprovalsApprove(id: ApprovalRequestId)
  case ApprovalsDeny(id: ApprovalRequestId)
  case Unknown(raw: String)

}

object ShellCommand {

  /** Parse a raw input line into a [[ShellCommand]]. Starts with `/` → command; otherwise → message. */
  def parse(line: String): ShellCommand = {
    if (!line.startsWith("/")) {
      Message(line)
    } else {
      val parts = line.stripPrefix("/").split("\\s+", 4).toList
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
        case "memory" :: "list" :: scope :: _                         => MemoryList(Some(scope))
        case "memory" :: "list" :: Nil                                => MemoryList(None)
        case "memory" :: "search" :: rest if rest.nonEmpty            => MemorySearch(rest.mkString(" "))
        case "memory" :: "forget" :: idStr :: _ if idStr.toLongOption.isDefined =>
          MemoryForget(MemoryRecordId(idStr.toLong))
        case "memory" :: "share" :: idStr :: _ if idStr.toLongOption.isDefined =>
          MemoryShare(MemoryRecordId(idStr.toLong))
        case "memory" :: "privatize" :: idStr :: _ if idStr.toLongOption.isDefined =>
          MemoryPrivatize(MemoryRecordId(idStr.toLong))
        case "memory" :: "remember" :: key :: "--scope" :: scope :: rest if rest.nonEmpty =>
          MemoryRemember(key, rest.mkString(" "), Some(scope))
        case "memory" :: "remember" :: key :: "--scope" :: scope :: Nil       => Unknown(s"/memory remember")
        case "memory" :: "remember" :: key :: rest if rest.nonEmpty           => MemoryRemember(key, rest.mkString(" "))
        case "skills" :: _                                                    => Skills
        case "contacts" :: "find" :: rest if rest.nonEmpty                    => ContactsFind(rest.mkString(" "))
        case "contacts" :: _                                                  => Unknown("/contacts")
        case "capabilities" :: _                                              => Capabilities
        case "agents" :: "list" :: _                                          => AgentsList
        case "agents" :: "stop" :: idStr :: _ if idStr.toLongOption.isDefined =>
          AgentsStop(AgentSessionId(idStr.toLong))
        case "agents" :: _                                                          => Unknown("/agents")
        case "approvals" :: "list" :: _                                             => ApprovalsList
        case "approvals" :: "approve" :: idStr :: _ if idStr.toLongOption.isDefined =>
          ApprovalsApprove(ApprovalRequestId(idStr.toLong))
        case "approvals" :: "deny" :: idStr :: _ if idStr.toLongOption.isDefined =>
          ApprovalsDeny(ApprovalRequestId(idStr.toLong))
        case "approvals" :: _ => Unknown("/approvals")
        case other :: _       => Unknown(s"/$other")
        case Nil              => Unknown("/")
      }
    }
  }

}
