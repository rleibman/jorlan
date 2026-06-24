/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.telegram

import jorlan.connector.UnrecognizedIdentityPolicy
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

/** Runtime configuration for a single Telegram bot connector instance.
  *
  * @param botToken
  *   Telegram Bot API token. Never logged or returned to unprivileged callers.
  * @param allowedChatIds
  *   When non-empty, messages from chats not in this set are dropped before identity resolution.
  * @param allowedUserIds
  *   When non-empty, messages from users not in this set are dropped before identity resolution.
  * @param unrecognizedPolicy
  *   What to do when the sender does not resolve to a known Jorlan user.
  * @param useWebhook
  *   When `true`, the connector logs a startup warning and falls back to long-polling. Webhook ingress requires a
  *   publicly reachable HTTPS endpoint wired into the server's route table, which is not yet implemented.
  */
case class TelegramConfig(
  botToken:               String = "",
  allowedChatIds:         Set[String] = Set.empty,
  allowedUserIds:         Set[String] = Set.empty,
  unrecognizedPolicy:     UnrecognizedIdentityPolicy = UnrecognizedIdentityPolicy.Reject,
  useWebhook:             Boolean = false,
  apiBaseUrl:             String = "https://api.telegram.org",
  longPollTimeoutSeconds: Int = 5,
) derives JsonCodec
