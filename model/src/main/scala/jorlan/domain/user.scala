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

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.Instant

enum ChannelType {

  case Shell, Telegram, Slack, Email, WhatsApp, Sms, GraphQL

}
object ChannelType {

  given JsonEncoder[ChannelType] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[ChannelType] =
    JsonDecoder[String].mapOrFail { s =>
      ChannelType.values.find(_.toString == s).toRight(s"Unknown ChannelType: $s")
    }

}

case class User(
  id:          UserId,
  displayName: String,
  createdAt:   Instant,
  updatedAt:   Instant,
  active:      Boolean = true,
)
object User {

  given JsonEncoder[User] = DeriveJsonEncoder.gen[User]
  given JsonDecoder[User] = DeriveJsonDecoder.gen[User]

}

case class ChannelIdentity(
  id:            UserId, // reuse UserId as PK for this row
  userId:        UserId,
  channelType:   ChannelType,
  channelUserId: String,
  verified:      Boolean = false,
  createdAt:     Instant,
)
object ChannelIdentity {

  given JsonEncoder[ChannelIdentity] = DeriveJsonEncoder.gen[ChannelIdentity]
  given JsonDecoder[ChannelIdentity] = DeriveJsonDecoder.gen[ChannelIdentity]

}
