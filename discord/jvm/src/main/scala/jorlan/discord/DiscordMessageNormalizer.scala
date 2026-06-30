/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.discord

import jorlan.*
import jorlan.connector.*

/** Converts a [[DiscordRawMessage]] into a connector-neutral [[InboundMessage]].
  *
  * Bot messages are dropped by returning `None`. Guild messages map to [[ChatKind.Group]]; DMs map to
  * [[ChatKind.Private]].
  */
object DiscordMessageNormalizer {

  def normalize(msg: DiscordRawMessage): Option[InboundMessage] = {
    if (msg.isBot) {
      None
    } else {
      val chatKind =
        if (msg.guildId.isDefined) ChatKind.Group
        else ChatKind.Private
      Some(
        InboundMessage(
          channelType = ChannelType.Discord,
          channelUserId = msg.authorId,
          chatRef = msg.channelId,
          chatKind = chatKind,
          content = msg.content,
          receivedAt = msg.receivedAt,
        ),
      )
    }
  }

}
