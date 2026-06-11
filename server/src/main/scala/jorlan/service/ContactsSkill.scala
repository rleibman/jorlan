/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.db.repository.ZIORepositories
import jorlan.domain.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Built-in skill for user discovery and channel-identity management.
  *
  * Tools:
  *   - `contacts.find` — search users by display name
  *   - `identity.resolve` — look up user by channel type + channel user ID
  *   - `identity.link` — create/update a channel identity for a user
  *   - `identity.listAliases` — list all channel identities for a user
  */
class ContactsSkill(repo: ZIORepositories) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "contacts",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "contacts.find",
        description = "Case-insensitive substring search on user display names. Returns matching users with their channel identities.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"name":{"type":"string","description":"Substring to search in displayName (case-insensitive)"}},"required":["name"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("contacts.read")),
      ),
      ToolDescriptor(
        name = "identity.resolve",
        description = "Find the user linked to a specific channel identity (e.g. a Telegram chat ID).",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"channelType":{"type":"string","description":"Channel type: Telegram, Slack, Email, etc."},"channelUserId":{"type":"string","description":"Channel-native user identifier"}},"required":["channelType","channelUserId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("contacts.read")),
      ),
      ToolDescriptor(
        name = "identity.link",
        description = "Create or update a channel identity for a user.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"userId":{"type":"string","description":"Numeric user ID"},"channelType":{"type":"string","description":"Channel type: Telegram, Slack, Email, etc."},"channelUserId":{"type":"string","description":"Channel-native user identifier"}},"required":["userId","channelType","channelUserId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("identity.manage")),
      ),
      ToolDescriptor(
        name = "identity.listAliases",
        description = "List all registered channel identities for a user.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"userId":{"type":"string","description":"Numeric user ID"}},"required":["userId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("contacts.read")),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "contacts.find"        => contactFind(args)
      case "identity.resolve"     => identityResolve(args)
      case "identity.link"        => identityLink(args)
      case "identity.listAliases" => identityListAliases(args)
      case other                  => ZIO.fail(JorlanError(s"ContactsSkill: unknown tool '$other'"))
    }

  private def contactFind(args: Json): IO[JorlanError, Json] = { // contacts.find
    val nameOpt = SkillArgs.str(args, "name")
    nameOpt match {
      case None       => ZIO.fail(JorlanError("contacts.find: name is required"))
      case Some(name) =>
        for {
          users   <- repo.user.search(UserSearch(nameContains = Some(name))).mapError(JorlanError(_))
          results <- ZIO.foreachPar(users) { u =>
            repo.user
              .getChannelIdentities(u.id)
              .mapError(JorlanError(_))
              .map { identities =>
                val idJson = identities.map { ci =>
                  Json.Obj(
                    "channelType"   -> Json.Str(ci.channelType.toString),
                    "channelUserId" -> Json.Str(ci.channelUserId),
                  )
                }
                Json.Obj(
                  "userId"      -> Json.Str(u.id.value.toString),
                  "displayName" -> Json.Str(u.displayName),
                  "identities"  -> Json.Arr(idJson*),
                )
              }
          }
        } yield Json.Arr(results*)
    }
  }

  private def identityResolve(args: Json): IO[JorlanError, Json] = {
    val channelTypeStr = SkillArgs.str(args, "channelType")
    val channelUserId = SkillArgs.str(args, "channelUserId")
    (channelTypeStr, channelUserId) match {
      case (None, _)              => ZIO.fail(JorlanError("identity.resolve: channelType is required"))
      case (_, None)              => ZIO.fail(JorlanError("identity.resolve: channelUserId is required"))
      case (Some(ct), Some(cuid)) =>
        SkillArgs.parseChannelType(ct) match {
          case None         => ZIO.fail(JorlanError(s"identity.resolve: unknown channelType '$ct'"))
          case Some(chType) =>
            repo.user
              .userByChannelIdentity(chType, cuid)
              .mapError(JorlanError(_))
              .map {
                case None    => Json.Obj("found" -> Json.Bool(false))
                case Some(u) =>
                  Json.Obj(
                    "found"       -> Json.Bool(true),
                    "userId"      -> Json.Str(u.id.value.toString),
                    "displayName" -> Json.Str(u.displayName),
                    "email"       -> Json.Str(u.email),
                  )
              }
        }
    }
  }

  private def identityLink(args: Json): IO[JorlanError, Json] = {
    val userIdRaw = SkillArgs.str(args, "userId")
    val channelTypeStr = SkillArgs.str(args, "channelType")
    val channelUserId = SkillArgs.str(args, "channelUserId")
    (userIdRaw, channelTypeStr, channelUserId) match {
      case (None, _, _)                      => ZIO.fail(JorlanError("identity.link: userId is required"))
      case (_, None, _)                      => ZIO.fail(JorlanError("identity.link: channelType is required"))
      case (_, _, None)                      => ZIO.fail(JorlanError("identity.link: channelUserId is required"))
      case (Some(uid), Some(ct), Some(cuid)) =>
        uid.toLongOption match {
          case None     => ZIO.fail(JorlanError(s"identity.link: userId must be numeric, got '$uid'"))
          case Some(id) =>
            SkillArgs.parseChannelType(ct) match {
              case None         => ZIO.fail(JorlanError(s"identity.link: unknown channelType '$ct'"))
              case Some(chType) =>
                Clock.instant.flatMap { now =>
                  val ci = ChannelIdentity(
                    id = ChannelIdentityId.empty,
                    userId = UserId(id),
                    channelType = chType,
                    channelUserId = cuid,
                    verified = false,
                    providerData = None,
                    createdAt = now,
                  )
                  repo.user
                    .upsertChannelIdentity(ci)
                    .mapError(JorlanError(_))
                    .map { saved =>
                      Json.Obj(
                        "channelIdentityId" -> Json.Str(saved.id.value.toString),
                        "channelType"       -> Json.Str(saved.channelType.toString),
                        "channelUserId"     -> Json.Str(saved.channelUserId),
                      )
                    }
                }
            }
        }
    }
  }

  private def identityListAliases(args: Json): IO[JorlanError, Json] = {
    val userIdRaw = SkillArgs.str(args, "userId")
    userIdRaw match {
      case None      => ZIO.fail(JorlanError("identity.listAliases: userId is required"))
      case Some(uid) =>
        uid.toLongOption match {
          case None     => ZIO.fail(JorlanError(s"identity.listAliases: userId must be numeric, got '$uid'"))
          case Some(id) =>
            repo.user
              .getChannelIdentities(UserId(id))
              .mapError(JorlanError(_))
              .map { identities =>
                Json.Arr(
                  identities.map { ci =>
                    Json.Obj(
                      "channelType"   -> Json.Str(ci.channelType.toString),
                      "channelUserId" -> Json.Str(ci.channelUserId),
                      "verified"      -> Json.Bool(ci.verified),
                    )
                  }*,
                )
              }
        }
    }
  }

}

object ContactsSkill
