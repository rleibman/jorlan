/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
