/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.discord

import jorlan.connector.UnrecognizedIdentityPolicy
import zio.json.JsonCodec

/** Runtime configuration for a single Discord bot connector instance.
  *
  * @param botToken
  *   Discord bot token. Never logged or returned to unprivileged callers.
  * @param allowedGuildIds
  *   When non-empty, messages from guilds not in this set are dropped before identity resolution.
  * @param allowedUserIds
  *   When non-empty, messages from users not in this set are dropped before identity resolution.
  * @param mentionOnly
  *   When `true`, only process messages in guild channels that mention the bot directly.
  * @param unrecognizedPolicy
  *   What to do when the sender does not resolve to a known Jorlan user.
  */
case class DiscordConfig(
  botToken:           String = "",
  allowedGuildIds:    Set[String] = Set.empty,
  allowedUserIds:     Set[String] = Set.empty,
  mentionOnly:        Boolean = true,
  unrecognizedPolicy: UnrecognizedIdentityPolicy = UnrecognizedIdentityPolicy.Reject,
) derives JsonCodec
