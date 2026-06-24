/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.email

import zio.json.JsonCodec

case class EmailConfig(
  provider:      String = "imap",
  imapHost:      String = "",
  imapPort:      Int = 993,
  imapSsl:       Boolean = true,
  smtpHost:      String = "",
  smtpPort:      Int = 587,
  smtpTls:       Boolean = true,
  username:      String = "",
  password:      String = "",
  senderName:    String = "",
  inboxFolder:   String = "INBOX",
  archiveFolder: String = "Archive",
  sslTrustAll:   Boolean = false,
) derives JsonCodec
