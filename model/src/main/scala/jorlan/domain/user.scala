/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.domain

import zio.json.{JsonDecoder, JsonEncoder}

import java.time.Instant

/** Identifies the external communication channel through which a user interacts with the system. */
enum ChannelType derives JsonEncoder, JsonDecoder {

  case Shell, Telegram, Slack, Email, WhatsApp, Sms, GraphQL

}

/** A canonical platform user. All inbound messages from any connector are resolved to a `User` before agent execution
  * begins.
  */
case class User(
  id:          UserId,
  displayName: String,
  createdAt:   Instant,
  updatedAt:   Instant,
  active:      Boolean = true,
) derives JsonEncoder, JsonDecoder

/** Links a [[User]] to their identity on a specific external channel (e.g. a Telegram chat ID or Slack member ID). One
  * user may have multiple identities across different channels.
  *
  * @param channelUserId
  *   The channel-native user identifier (e.g. Telegram numeric ID, Slack `UXXXXXXX`).
  * @param verified
  *   `true` once the user has proved ownership of the channel identity.
  */
case class ChannelIdentity(
  id:            ChannelIdentityId,
  userId:        UserId,
  channelType:   ChannelType,
  channelUserId: String,
  verified:      Boolean = false,
  createdAt:     Instant,
) derives JsonEncoder, JsonDecoder
