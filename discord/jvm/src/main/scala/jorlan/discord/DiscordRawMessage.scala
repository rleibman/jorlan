/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.discord

/** A connector-neutral snapshot of a Discord message event.
  *
  * Deliberately free of JDA types so that [[DiscordMessageNormalizer]] and tests can operate without a live JDA
  * instance. The [[DiscordApiClientLive]] translates JDA events into this form before enqueuing them.
  *
  * @param messageId
  *   Snowflake ID of the Discord message.
  * @param authorId
  *   Snowflake ID of the message author.
  * @param authorName
  *   Display name of the message author at the time the event was received.
  * @param channelId
  *   Snowflake ID of the channel in which the message was posted.
  * @param guildId
  *   Snowflake ID of the guild, or `None` for DM messages.
  * @param isBot
  *   `true` when the author is a bot account (including the bot itself).
  * @param content
  *   Raw message text content.
  * @param isMention
  *   `true` when the bot was mentioned in the message.
  * @param receivedAt
  *   Wall-clock instant at which the JDA listener received the event.
  */
case class DiscordRawMessage(
  messageId:  String,
  authorId:   String,
  authorName: String,
  channelId:  String,
  guildId:    Option[String],
  isBot:      Boolean,
  content:    String,
  isMention:  Boolean,
  receivedAt: java.time.Instant,
)
