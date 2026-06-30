/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell.commands

import jorlan.{
  AgentSessionId,
  ApprovalRequestId,
  CapabilityGrantId,
  ChannelIdentityId,
  McpEnvVarInfo,
  MemoryRecordId,
  MemoryScope,
  RoleId,
  SchedulerJobId,
  SchedulerTriggerId,
  SkillId,
  UserId,
}

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
  case MemoryList(scope: Option[MemoryScope])
  case MemorySearch(text: String)
  case MemoryForget(id: MemoryRecordId)
  case MemoryShare(id: MemoryRecordId)
  case MemoryPrivatize(id: MemoryRecordId)
  case MemoryRemember(
    key:   String,
    text:  String,
    scope: Option[String] = None,
  )
  case MemoryCheckpoint
  case MemoryPolicyShow
  case MemoryPolicySetInterval(turns: Int)
  case MemoryPolicyToggle(
    trigger: String,
    enabled: Boolean,
  )
  case Skills
  case SkillsEnable(name: String)
  case SkillsDisable(name: String)
  case SkillsGetConfig(name: String)
  case SkillsSetConfig(
    name:       String,
    configJson: String,
  )
  case ContactsFind(name: String)
  case Capabilities
  case AgentsList
  case AgentsStop(sessionId: AgentSessionId)
  case ApprovalsList
  case ApprovalsApprove(id: ApprovalRequestId)
  case ApprovalsDeny(id: ApprovalRequestId)
  case UsersList(active: Option[Boolean])
  case UsersCreate(
    displayName: String,
    email:       String,
  )
  case UsersDeactivate(id: UserId)
  case UsersUpdate(
    id:    UserId,
    field: String,
    value: String,
  )
  case UsersCapabilities(userId: UserId)
  case UsersGrantCapability(
    userId:       UserId,
    capability:   String,
    approvalMode: String,
  )
  case UsersRevokeGrant(grantId: CapabilityGrantId)
  case UsersRoles(userId: UserId)
  case UsersAssignRole(
    userId: UserId,
    roleId: RoleId,
  )
  case UsersRevokeRole(
    userId: UserId,
    roleId: RoleId,
  )
  case UsersIdentities(userId: UserId)
  case UsersLinkIdentity(
    userId:        UserId,
    channelType:   String,
    channelUserId: String,
  )
  case UsersUnlinkIdentity(identityId: ChannelIdentityId)
  case RolesList
  case RolesCreate(
    name:        String,
    description: Option[String],
  )
  case RolesUpdate(
    id:    RoleId,
    field: String,
    value: String,
  )
  case RolesDelete(id: RoleId)
  case RolesCapabilities(id: RoleId)
  case RolesGrantCapability(
    id:           RoleId,
    capability:   String,
    approvalMode: String,
  )
  case RolesRevokeGrant(grantId: CapabilityGrantId)
  case UsersReactivate(id: UserId)
  case SchedulerList
  case SchedulerResult(id: SchedulerJobId)
  case SchedulerCreate(
    name:   String,
    prompt: String,
  )
  case SchedulerUpdate(
    id:    SchedulerJobId,
    field: String,
    value: String,
  )
  case SchedulerDelete(id: SchedulerJobId)
  case SchedulerPause(id: SchedulerJobId)
  case SchedulerResume(id: SchedulerJobId)
  case SchedulerCancel(id: SchedulerJobId)
  case SchedulerRun(id: SchedulerJobId)
  case SchedulerTriggers(id: SchedulerJobId)
  case SchedulerTriggerAdd(
    id:          SchedulerJobId,
    triggerType: String,
    expression:  String,
  )
  case SchedulerTriggerDelete(triggerId: SchedulerTriggerId)
  case McpList
  case McpAdd(
    name:      String,
    transport: String,
    command:   Option[String],
    args:      List[String],
    env:       List[McpEnvVarInfo],
    url:       Option[String],
    enabled:   Boolean,
    keywords:  List[String],
  )
  case McpEdit(
    name:      String,
    transport: Option[String],
    command:   Option[String],
    args:      Option[List[String]],
    env:       Option[List[McpEnvVarInfo]],
    url:       Option[String],
    enabled:   Option[Boolean],
    keywords:  Option[List[String]],
  )
  case McpDelete(name: String)
  case McpReload
  case McpEnable(name: String)
  case McpDisable(name: String)
  case SkillsDocs(name: String)
  case SkillsValidate(name: String)
  case SkillsListCustom
  case SkillsListPending
  case SkillsVersions(skillId: SkillId)
  case SkillsCreate(manifestFile: String)
  case SkillsAdvance(versionId: String)
  case SkillsApprove(versionId: String)
  case SkillsReject(
    versionId: String,
    reason:    String,
  )
  case EventsTail
  case EventsList(count: Int)
  case Dashboard
  case OAuthStatus(provider: String)
  case OAuthConnect(provider: String)
  case OAuthRevoke(provider: String)
  case OAuthList
  case EmailList(maxResults: Int)
  case EmailRead(messageId: String)
  case EmailSearch(query: String)
  case CalendarToday
  case CalendarList(date: Option[String])
  case Unknown(raw: String)

}

object ShellCommand {

  private case class McpFlags(
    transport: Option[String] = None,
    command:   Option[String] = None,
    args:      List[String] = Nil,
    env:       List[McpEnvVarInfo] = Nil,
    url:       Option[String] = None,
    enabled:   Option[Boolean] = None,
    keywords:  List[String] = Nil,
  )

  @annotation.tailrec
  private def parseMcpFlags(tokens: List[String], acc: McpFlags = McpFlags()): McpFlags =
    tokens match {
      case "--transport" :: v :: rest => parseMcpFlags(rest, acc.copy(transport = Some(v)))
      case "--command" :: v :: rest   => parseMcpFlags(rest, acc.copy(command = Some(v)))
      case "--url" :: v :: rest       => parseMcpFlags(rest, acc.copy(url = Some(v)))
      case "--args" :: v :: rest      => parseMcpFlags(rest, acc.copy(args = acc.args :+ v))
      case "--env" :: kv :: rest      =>
        val newEnv = kv.split("=", 2).toList match {
          case k :: v :: Nil => acc.env :+ McpEnvVarInfo(k, v)
          case _             => acc.env
        }
        parseMcpFlags(rest, acc.copy(env = newEnv))
      case "--keywords" :: v :: rest  =>
        parseMcpFlags(rest, acc.copy(keywords = v.split(",").map(_.trim).filter(_.nonEmpty).toList))
      case "--disabled" :: rest       => parseMcpFlags(rest, acc.copy(enabled = Some(false)))
      case "--enabled" :: rest        => parseMcpFlags(rest, acc.copy(enabled = Some(true)))
      case _ :: rest                  => parseMcpFlags(rest, acc)
      case Nil                        => acc
    }

  /** Parse a raw input line into a [[ShellCommand]]. Starts with `/` → command; otherwise → message. */
  def parse(line: String): ShellCommand = {
    if (!line.startsWith("/")) {
      Message(line)
    } else {
      val parts = line.stripPrefix("/").split("\\s+").toList
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
        case "memory" :: "list" :: scope :: _ => MemoryList(MemoryScope.values.find(_.toString.equalsIgnoreCase(scope)))
        case "memory" :: "list" :: Nil        => MemoryList(None)
        case "memory" :: "search" :: rest if rest.nonEmpty                      => MemorySearch(rest.mkString(" "))
        case "memory" :: "forget" :: idStr :: _ if idStr.toLongOption.isDefined =>
          MemoryForget(MemoryRecordId(idStr.toLong))
        case "memory" :: "share" :: idStr :: _ if idStr.toLongOption.isDefined =>
          MemoryShare(MemoryRecordId(idStr.toLong))
        case "memory" :: "privatize" :: idStr :: _ if idStr.toLongOption.isDefined =>
          MemoryPrivatize(MemoryRecordId(idStr.toLong))
        case "memory" :: "remember" :: key :: "--scope" :: scope :: rest if rest.nonEmpty =>
          MemoryRemember(key, rest.mkString(" "), Some(scope))
        case "memory" :: "remember" :: key :: "--scope" :: scope :: Nil => Unknown(s"/memory remember")
        case "memory" :: "remember" :: key :: rest if rest.nonEmpty     => MemoryRemember(key, rest.mkString(" "))
        case "memory" :: "checkpoint" :: _                              => MemoryCheckpoint
        case "memory" :: "policy" :: Nil                                => MemoryPolicyShow
        case "memory" :: "policy" :: "interval" :: n :: _ if n.toIntOption.isDefined =>
          MemoryPolicySetInterval(n.toInt)
        case "memory" :: "policy" :: toggle :: _
            if Set(
              "on-session-end",
              "off-session-end",
              "on-user-request",
              "off-user-request",
              "on-before-effect",
              "off-before-effect",
            ).contains(toggle) =>
          val enabled = toggle.startsWith("on-")
          val trigger = toggle.stripPrefix("on-").stripPrefix("off-")
          MemoryPolicyToggle(trigger, enabled)
        case "skills" :: "enable" :: name :: _                              => SkillsEnable(name)
        case "skills" :: "disable" :: name :: _                             => SkillsDisable(name)
        case "skills" :: "config" :: "get" :: name :: _                     => SkillsGetConfig(name)
        case "skills" :: "config" :: "set" :: name :: rest if rest.nonEmpty => SkillsSetConfig(name, rest.mkString(" "))
        case "skills" :: "docs" :: name :: _                                => SkillsDocs(name)
        case "skills" :: "validate" :: name :: _                            => SkillsValidate(name)
        case "skills" :: "list-custom" :: _                                 => SkillsListCustom
        case "skills" :: "list-pending" :: _                                => SkillsListPending
        case "skills" :: "versions" :: idStr :: _ if idStr.toLongOption.isDefined =>
          SkillsVersions(SkillId(idStr.toLong))
        case "skills" :: "create" :: manifestFile :: _  => SkillsCreate(manifestFile)
        case "skills" :: "advance" :: versionId :: _    => SkillsAdvance(versionId)
        case "skills" :: "approve" :: versionId :: _    => SkillsApprove(versionId)
        case "skills" :: "reject" :: versionId :: rest if rest.nonEmpty =>
          SkillsReject(versionId, rest.mkString(" "))
        case "skills" :: _                                                  => Skills
        case "contacts" :: "find" :: rest if rest.nonEmpty                  => ContactsFind(rest.mkString(" "))
        case "contacts" :: _                                                => Unknown("/contacts")
        case "capabilities" :: _                                            => Capabilities
        case "users" :: "list" :: "all" :: _                                => UsersList(None)
        case "users" :: "list" :: "inactive" :: _                           => UsersList(Some(false))
        case "users" :: "list" :: _                                         => UsersList(Some(true))
        case "users" :: "create" :: displayName :: email :: _               =>
          UsersCreate(displayName, email)
        case "users" :: "deactivate" :: idStr :: _ if idStr.toLongOption.isDefined =>
          UsersDeactivate(UserId(idStr.toLong))
        case "users" :: "reactivate" :: idStr :: _ if idStr.toLongOption.isDefined =>
          UsersReactivate(UserId(idStr.toLong))
        case "users" :: "update" :: idStr :: field :: rest if idStr.toLongOption.isDefined && rest.nonEmpty =>
          UsersUpdate(UserId(idStr.toLong), field, rest.mkString(" "))
        case "users" :: "capabilities" :: idStr :: _ if idStr.toLongOption.isDefined =>
          UsersCapabilities(UserId(idStr.toLong))
        case "users" :: "grant" :: idStr :: cap :: mode :: _ if idStr.toLongOption.isDefined =>
          UsersGrantCapability(UserId(idStr.toLong), cap, mode)
        case "users" :: "revoke-grant" :: grantIdStr :: _ if grantIdStr.toLongOption.isDefined =>
          UsersRevokeGrant(CapabilityGrantId(grantIdStr.toLong))
        case "users" :: "roles" :: idStr :: _ if idStr.toLongOption.isDefined =>
          UsersRoles(UserId(idStr.toLong))
        case "users" :: "assign-role" :: idStr :: roleIdStr :: _
            if idStr.toLongOption.isDefined && roleIdStr.toLongOption.isDefined =>
          UsersAssignRole(UserId(idStr.toLong), RoleId(roleIdStr.toLong))
        case "users" :: "revoke-role" :: idStr :: roleIdStr :: _
            if idStr.toLongOption.isDefined && roleIdStr.toLongOption.isDefined =>
          UsersRevokeRole(UserId(idStr.toLong), RoleId(roleIdStr.toLong))
        case "users" :: "identities" :: idStr :: _ if idStr.toLongOption.isDefined =>
          UsersIdentities(UserId(idStr.toLong))
        case "users" :: "link-identity" :: idStr :: chType :: chUserId :: _ if idStr.toLongOption.isDefined =>
          UsersLinkIdentity(UserId(idStr.toLong), chType, chUserId)
        case "users" :: "unlink-identity" :: idStr :: _ if idStr.toLongOption.isDefined =>
          UsersUnlinkIdentity(ChannelIdentityId(idStr.toLong))
        case "users" :: _                        => Unknown("/users")
        case "roles" :: "list" :: _              => RolesList
        case "roles" :: "create" :: name :: rest =>
          RolesCreate(name, if (rest.nonEmpty) Some(rest.mkString(" ")) else None)
        case "roles" :: "update" :: idStr :: field :: rest if idStr.toLongOption.isDefined && rest.nonEmpty =>
          RolesUpdate(RoleId(idStr.toLong), field, rest.mkString(" "))
        case "roles" :: "delete" :: idStr :: _ if idStr.toLongOption.isDefined =>
          RolesDelete(RoleId(idStr.toLong))
        case "roles" :: "capabilities" :: idStr :: _ if idStr.toLongOption.isDefined =>
          RolesCapabilities(RoleId(idStr.toLong))
        case "roles" :: "grant" :: idStr :: cap :: mode :: _ if idStr.toLongOption.isDefined =>
          RolesGrantCapability(RoleId(idStr.toLong), cap, mode)
        case "roles" :: "revoke-grant" :: grantIdStr :: _ if grantIdStr.toLongOption.isDefined =>
          RolesRevokeGrant(CapabilityGrantId(grantIdStr.toLong))
        case "roles" :: _                                                     => Unknown("/roles")
        case "agents" :: "list" :: _                                          => AgentsList
        case "agents" :: "stop" :: idStr :: _ if idStr.toLongOption.isDefined =>
          AgentsStop(AgentSessionId(idStr.toLong))
        case "agents" :: _                                                          => Unknown("/agents")
        case "approvals" :: "list" :: _                                             => ApprovalsList
        case "approvals" :: "approve" :: idStr :: _ if idStr.toLongOption.isDefined =>
          ApprovalsApprove(ApprovalRequestId(idStr.toLong))
        case "approvals" :: "deny" :: idStr :: _ if idStr.toLongOption.isDefined =>
          ApprovalsDeny(ApprovalRequestId(idStr.toLong))
        case "approvals" :: _                                                      => Unknown("/approvals")
        case "scheduler" :: "list" :: _                                            => SchedulerList
        case "scheduler" :: "result" :: idStr :: _ if idStr.toLongOption.isDefined =>
          SchedulerResult(SchedulerJobId(idStr.toLong))
        case "scheduler" :: "create" :: name :: rest if rest.nonEmpty => SchedulerCreate(name, rest.mkString(" "))
        case "scheduler" :: "update" :: idStr :: field :: rest if idStr.toLongOption.isDefined && rest.nonEmpty =>
          SchedulerUpdate(SchedulerJobId(idStr.toLong), field, rest.mkString(" "))
        case "scheduler" :: "delete" :: idStr :: _ if idStr.toLongOption.isDefined =>
          SchedulerDelete(SchedulerJobId(idStr.toLong))
        case "scheduler" :: "pause" :: idStr :: _ if idStr.toLongOption.isDefined =>
          SchedulerPause(SchedulerJobId(idStr.toLong))
        case "scheduler" :: "resume" :: idStr :: _ if idStr.toLongOption.isDefined =>
          SchedulerResume(SchedulerJobId(idStr.toLong))
        case "scheduler" :: "cancel" :: idStr :: _ if idStr.toLongOption.isDefined =>
          SchedulerCancel(SchedulerJobId(idStr.toLong))
        case "scheduler" :: "run" :: idStr :: _ if idStr.toLongOption.isDefined =>
          SchedulerRun(SchedulerJobId(idStr.toLong))
        case "scheduler" :: "triggers" :: idStr :: _ if idStr.toLongOption.isDefined =>
          SchedulerTriggers(SchedulerJobId(idStr.toLong))
        case "scheduler" :: "trigger" :: "add" :: idStr :: trigType :: rest
            if idStr.toLongOption.isDefined && rest.nonEmpty =>
          SchedulerTriggerAdd(SchedulerJobId(idStr.toLong), trigType, rest.mkString(" "))
        case "scheduler" :: "trigger" :: "delete" :: idStr :: _ if idStr.toLongOption.isDefined =>
          SchedulerTriggerDelete(SchedulerTriggerId(idStr.toLong))
        case "scheduler" :: _                                       => Unknown("/scheduler")
        case "oauth" :: "status" :: provider :: _                   => OAuthStatus(provider)
        case "oauth" :: "connect" :: provider :: _                  => OAuthConnect(provider)
        case "oauth" :: "revoke" :: provider :: _                   => OAuthRevoke(provider)
        case "oauth" :: _                                           => OAuthList
        case "email" :: "list" :: n :: _ if n.toIntOption.isDefined => EmailList(n.toInt)
        case "email" :: "list" :: _                                 => EmailList(10)
        case "email" :: "read" :: id :: _                           => EmailRead(id)
        case "email" :: "search" :: rest if rest.nonEmpty           => EmailSearch(rest.mkString(" "))
        case "email" :: _                                           => Unknown("/email")
        case "calendar" :: "today" :: _                             => CalendarToday
        case "calendar" :: "list" :: date :: _                      => CalendarList(Some(date))
        case "calendar" :: "list" :: Nil                            => CalendarList(None)
        case "calendar" :: _                                        => Unknown("/calendar")
        case "mcp" :: "list" :: _                        => McpList
        case "mcp" :: "add" :: name :: transport :: rest =>
          val f = parseMcpFlags(rest)
          McpAdd(name, transport, f.command, f.args, f.env, f.url, f.enabled.getOrElse(true), f.keywords)
        case "mcp" :: "edit" :: name :: rest             =>
          val f = parseMcpFlags(rest)
          McpEdit(name, f.transport, f.command, Some(f.args).filter(_.nonEmpty), Some(f.env).filter(_.nonEmpty), f.url, f.enabled, Some(f.keywords).filter(_.nonEmpty))
        case "mcp" :: "delete" :: name :: _              => McpDelete(name)
        case "mcp" :: "reload" :: _                      => McpReload
        case "mcp" :: "enable" :: name :: _              => McpEnable(name)
        case "mcp" :: "disable" :: name :: _             => McpDisable(name)
        case "mcp" :: _                                  => McpList
        case "events" :: "tail" :: _                                => EventsTail
        case "events" :: "list" :: n :: _ if n.toIntOption.isDefined => EventsList(n.toInt)
        case "events" :: "list" :: _                                => EventsList(50)
        case "events" :: _                                          => EventsTail
        case "dashboard" :: _                                       => Dashboard
        case other :: _                                             => Unknown(s"/$other")
        case Nil                                                    => Unknown("/")
      }
    }
  }

}
