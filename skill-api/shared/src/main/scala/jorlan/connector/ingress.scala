/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.connector

import jorlan.ChannelType
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

/** A connector-normalized inbound message, before identity resolution.
  *
  * @param channelType
  *   The connector that received this message (e.g. [[ChannelType.Telegram]]).
  * @param channelUserId
  *   Channel-native sender identifier (e.g. Telegram numeric user id, as a String).
  * @param chatRef
  *   Channel-native chat identifier. Equals [[channelUserId]] for private chats; distinct for groups/channels.
  * @param chatKind
  *   The kind of chat this message arrived in.
  * @param content
  *   The raw text content of the message.
  * @param receivedAt
  *   Wall-clock time when the message was received by the connector.
  */
case class InboundMessage(
  channelType:   ChannelType,
  channelUserId: String,
  chatRef:       String,
  chatKind:      ChatKind,
  content:       String,
  receivedAt:    Instant,
) derives JsonCodec

/** The kind of chat a [[InboundMessage]] arrived in. */
enum ChatKind derives JsonCodec {

  case Private, Group, Channel, Supergroup

}

/** Policy applied when a connector receives a message from a sender that does not resolve to a known, verified user. */
enum UnrecognizedIdentityPolicy derives JsonCodec {

  /** Drop the message silently and write a rejection entry to the event log. */
  case Reject

  /** Store the raw message for later admin review; do not dispatch to the agent. */
  case Quarantine

}
