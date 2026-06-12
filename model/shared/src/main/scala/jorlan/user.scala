/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import zio.json.ast.Json
import zio.json.{JsonDecoder, JsonEncoder}

import java.time.Instant

/** Identifies the external communication channel through which a user interacts with the system.
  *
  * OAuth provider values (`Google`, `GitHub`, `Discord`) double as channel types so that OAuth identities are stored in
  * [[ChannelIdentity]] alongside other channel links.
  */
enum ChannelType derives JsonEncoder, JsonDecoder {

  case Shell, Telegram, Slack, Email, WhatsApp, Sms, GraphQL, Google, GitHub, Discord

}

object ChannelType {

  def fromProvider(provider: String): Option[ChannelType] = {
    provider.toLowerCase match {
      case "google"  => Some(Google)
      case "github"  => Some(GitHub)
      case "discord" => Some(Discord)
      case _         => None
    }
  }

}

/** A canonical platform user. All inbound messages from any connector are resolved to a `User` before agent execution
  * begins.
  *
  * @param email
  *   Login email address. Required as of V022 — every persisted user has a non-empty email.
  */
case class User(
  id:          UserId,
  displayName: String,
  email:       String,
  createdAt:   Instant,
  updatedAt:   Instant,
  active:      Boolean = true,
) derives JsonEncoder, JsonDecoder

/** Links a [[User]] to their identity on a specific external channel (e.g. a Telegram chat ID or Slack member ID). One
  * user may have multiple identities across different channels. OAuth provider identities are stored here using
  * [[ChannelType.Google]] etc., with [[providerData]] holding the raw OAuth payload.
  *
  * @param channelUserId
  *   The channel-native user identifier (e.g. Telegram numeric ID, Slack `UXXXXXXX`, OAuth provider user ID).
  * @param verified
  *   `true` once the user has proved ownership of the channel identity.
  * @param providerData
  *   Raw JSON payload from the OAuth provider (only set for OAuth-backed channel identities).
  */
case class ChannelIdentity(
  id:            ChannelIdentityId,
  userId:        UserId,
  channelType:   ChannelType,
  channelUserId: String,
  verified:      Boolean = false,
  providerData:  Option[Json] = None,
  createdAt:     Instant,
) derives JsonEncoder, JsonDecoder
