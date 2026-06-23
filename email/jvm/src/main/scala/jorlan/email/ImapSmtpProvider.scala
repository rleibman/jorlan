/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.email

import jorlan.*
import jorlan.service.EmailProvider
import zio.*

/** IMAP/SMTP-backed email provider — placeholder implementation, all methods return a descriptive not-yet-implemented
  * error. The real IMAP/SMTP wiring is deferred to a later phase.
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
) extends EmailProvider[[A] =>> IO[JorlanError, A]] {

  override def listMessages(
    userId:     UserId,
    maxResults: Int,
    query:      Option[String],
  ): IO[JorlanError, List[EmailMessage]] =
    ZIO.fail(JorlanError("IMAP listMessages not yet implemented"))

  override def getMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, EmailMessage] =
    ZIO.fail(JorlanError("IMAP getMessage not yet implemented"))

  override def sendDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): IO[JorlanError, EmailMessageId] =
    ZIO.fail(JorlanError("SMTP sendDraft not yet implemented"))

  override def createDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): IO[JorlanError, String] =
    ZIO.fail(JorlanError("IMAP createDraft not yet implemented"))

  override def archiveMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, Unit] =
    ZIO.fail(JorlanError("IMAP archiveMessage not yet implemented"))

  override def deleteMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, Unit] =
    ZIO.fail(JorlanError("IMAP deleteMessage not yet implemented"))

}
