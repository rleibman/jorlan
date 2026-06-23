/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*

/** Abstract email backend. `F[_]` is the effect type — typically `IO[JorlanError, *]` in production and `Id` in tests.
  * Implementations include [[jorlan.google.GmailProvider]] (Google OAuth) and [[jorlan.email.ImapSmtpProvider]] (stub).
  */
trait EmailProvider[F[_]] {

  def listMessages(
    userId:     UserId,
    maxResults: Int,
    query:      Option[String],
  ): F[List[EmailMessage]]
  def getMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): F[EmailMessage]
  def sendDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): F[EmailMessageId]
  def createDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): F[String]
  def archiveMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): F[Unit]
  def deleteMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): F[Unit]

}
