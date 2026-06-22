/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.db.repository.ZIORepositories
import jorlan.*
import just.semver.SemVer
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
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "contacts",
      "people",
      "person",
      "find user",
      "lookup user",
      "identity",
      "alias",
      "name search",
      "address book",
      "channel identity",
      "Telegram user",
      "link identity",
    ),
    tools = List(
      ToolDescriptor(
        name = "contacts.find",
        description = "Search users by display name with fuzzy/phonetic matching. 'Roberto' matches 'Robert Leibman'; 'Sara' matches 'Sarah Smith'. Omit 'name' to list all users.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"name":{"type":"string","description":"Name to search — supports partial names, phonetic variants, and minor spelling differences"}},"required":[]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("contacts.read")),
        examplePrompts = List(
          "Who is Alice?",
          "Find a user named Roberto",
          "Show me all users in the system",
        ),
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
        examplePrompts = List(
          "Who has Telegram ID 123456?",
          "Which user is associated with this Telegram handle?",
        ),
      ),
      ToolDescriptor(
        name = "identity.link",
        description = "Create or update a channel identity for a user. If userId is omitted, links to the currently authenticated user.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"userId":{"type":"string","description":"Numeric user ID — omit to use the current user"},"channelType":{"type":"string","description":"Channel type: Telegram, Slack, Email, etc."},"channelUserId":{"type":"string","description":"Channel-native user identifier (e.g. Telegram numeric user ID)"}},"required":["channelType","channelUserId"]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("identity.manage")),
        examplePrompts = List(
          "Link my Telegram account to user 7",
          "Associate Telegram ID 987654 with my account",
        ),
      ),
      ToolDescriptor(
        name = "identity.listAliases",
        description =
          "List all registered channel identities for a user. If userId is omitted, lists for the current user.",
        inputSchema = Json.decoder
          .decodeJson(
            """|{"type":"object","properties":{"userId":{"type":"string","description":"Numeric user ID — omit to use the current user"}},"required":[]}""",
          )
          .getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("contacts.read")),
        examplePrompts = List(
          "What channels am I connected to?",
          "List all identities for user 3",
          "Show me my linked accounts",
        ),
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
      case "identity.link"        => identityLink(ctx, args)
      case "identity.listAliases" => identityListAliases(ctx, args)
      case other                  => ZIO.fail(JorlanError(s"ContactsSkill: unknown tool '$other'"))
    }

  private def contactFind(args: Json): IO[JorlanError, Json] = {
    val nameOpt = str(args, "name")
    for {
      users <- repo.user.search(UserSearch(fuzzyName = nameOpt, active = Some(true))).mapError(JorlanError(_))
      ranked = nameOpt.fold(users)(q => jorlan.service.FuzzyNameMatch.rank(users, q)(_.displayName))
      results <- ZIO.foreachPar(ranked) { u =>
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

  private def identityResolve(args: Json): IO[JorlanError, Json] = {
    val channelTypeStr = str(args, "channelType")
    val channelUserId = str(args, "channelUserId")
    (channelTypeStr, channelUserId) match {
      case (None, _)              => ZIO.fail(JorlanError("identity.resolve: channelType is required"))
      case (_, None)              => ZIO.fail(JorlanError("identity.resolve: channelUserId is required"))
      case (Some(ct), Some(cuid)) =>
        parseChannelType(ct) match {
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

  private def identityLink(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val userIdRaw = str(args, "userId")
    val channelTypeStr = str(args, "channelType")
    val channelUserId = str(args, "channelUserId")
    // userId is optional — defaults to the acting user
    val resolvedUserId: Either[String, UserId] = userIdRaw match {
      case None      => Right(ctx.actorId)
      case Some(uid) => uid.toLongOption.map(UserId(_)).toRight(s"identity.link: userId must be numeric, got '$uid'")
    }
    (resolvedUserId, channelTypeStr, channelUserId) match {
      case (_, None, _)                       => ZIO.fail(JorlanError("identity.link: channelType is required"))
      case (_, _, None)                       => ZIO.fail(JorlanError("identity.link: channelUserId is required"))
      case (Left(err), _, _)                  => ZIO.fail(JorlanError(err))
      case (Right(uid), Some(ct), Some(cuid)) =>
        parseChannelType(ct) match {
          case None         => ZIO.fail(JorlanError(s"identity.link: unknown channelType '$ct'"))
          case Some(chType) =>
            Clock.instant.flatMap { now =>
              val ci = ChannelIdentity(
                id = ChannelIdentityId.empty,
                userId = uid,
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

  private def identityListAliases(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val resolvedId = str(args, "userId") match {
      case None      => Right(ctx.actorId)
      case Some(uid) =>
        uid.toLongOption.map(UserId(_)).toRight(s"identity.listAliases: userId must be numeric, got '$uid'")
    }
    resolvedId match {
      case Left(err) => ZIO.fail(JorlanError(err))
      case Right(id) =>
        repo.user
          .getChannelIdentities(id)
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

object ContactsSkill
