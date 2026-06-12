/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.email

import jorlan.JorlanError
import jorlan.domain.*
import jorlan.service.EmailProvider
import zio.*

/** IMAP/SMTP-backed email provider.
  *
  * Uses the `emil` library (via `zio-interop-cats`) to connect to an IMAP server for reading and an SMTP server for
  * sending. PGP signing/verification is delegated to [[PgpService]].
  */
class ImapSmtpProvider(
  imapHost: String,
  imapPort: Int,
  imapSsl:  Boolean,
  smtpHost: String,
  smtpPort: Int,
  smtpTls:  Boolean,
  username: String,
  password: String,
  pgp:      PgpService,
) extends EmailProvider[[A] =>> IO[JorlanError, A]] {

  override def listMessages(
    userId:     UserId,
    maxResults: Int,
    query:      Option[String],
  ): IO[JorlanError, List[EmailMessage]] =
    ZIO.fail(JorlanError("IMAP listMessages not yet implemented"))

  override def getMessage(userId: UserId, messageId: EmailMessageId): IO[JorlanError, EmailMessage] =
    ZIO.fail(JorlanError("IMAP getMessage not yet implemented"))

  override def sendDraft(userId: UserId, draft: EmailDraft): IO[JorlanError, EmailMessageId] =
    ZIO.fail(JorlanError("SMTP sendDraft not yet implemented"))

  override def createDraft(userId: UserId, draft: EmailDraft): IO[JorlanError, String] =
    ZIO.fail(JorlanError("IMAP createDraft not yet implemented"))

  override def archiveMessage(userId: UserId, messageId: EmailMessageId): IO[JorlanError, Unit] =
    ZIO.fail(JorlanError("IMAP archiveMessage not yet implemented"))

  override def deleteMessage(userId: UserId, messageId: EmailMessageId): IO[JorlanError, Unit] =
    ZIO.fail(JorlanError("IMAP deleteMessage not yet implemented"))

}
