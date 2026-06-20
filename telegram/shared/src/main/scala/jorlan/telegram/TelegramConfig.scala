/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
  botToken:           String = "",
  allowedChatIds:     Set[String] = Set.empty,
  allowedUserIds:     Set[String] = Set.empty,
  unrecognizedPolicy: UnrecognizedIdentityPolicy = UnrecognizedIdentityPolicy.Reject,
  useWebhook:         Boolean = false,
  apiBaseUrl:         String = "https://api.telegram.org",
) derives JsonCodec
