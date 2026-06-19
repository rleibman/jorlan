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
import jorlan.*
import jorlan.service.EmailProvider
import zio.*

class FakeEmailProvider(
  messagesRef: Ref[List[EmailMessage]],
  sentRef:     Ref[List[EmailDraft]],
  draftsRef:   Ref[List[EmailDraft]],
  archivedRef: Ref[List[EmailMessageId]],
) extends EmailProvider[[A] =>> IO[JorlanError, A]] {

  override def listMessages(
    userId:     UserId,
    maxResults: Int,
    query:      Option[String],
  ): IO[JorlanError, List[EmailMessage]] =
    messagesRef.get.map { msgs =>
      val filtered = query.fold(msgs)(q => msgs.filter(m => m.subject.contains(q) || m.body.contains(q)))
      filtered.take(maxResults)
    }

  override def getMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, EmailMessage] =
    messagesRef.get.flatMap { msgs =>
      ZIO
        .fromOption(msgs.find(_.id == messageId))
        .orElseFail(JorlanError(s"Message not found: ${messageId.value}"))
    }

  override def sendDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): IO[JorlanError, EmailMessageId] =
    sentRef.update(_ :+ draft) *> ZIO.succeed(EmailMessageId("fake-sent-id"))

  override def createDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): IO[JorlanError, String] =
    draftsRef.update(_ :+ draft) *> ZIO.succeed("fake-draft-id")

  override def archiveMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, Unit] =
    archivedRef.update(_ :+ messageId)

  override def deleteMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, Unit] =
    archivedRef.update(_ :+ messageId)

  def sentMessages:  UIO[List[EmailDraft]] = sentRef.get
  def draftMessages: UIO[List[EmailDraft]] = draftsRef.get
  def archivedIds:   UIO[List[EmailMessageId]] = archivedRef.get

}

object FakeEmailProvider {

  def make(messages: List[EmailMessage] = List.empty): UIO[FakeEmailProvider] =
    for {
      mr <- Ref.make(messages)
      sr <- Ref.make[List[EmailDraft]](List.empty)
      dr <- Ref.make[List[EmailDraft]](List.empty)
      ar <- Ref.make[List[EmailMessageId]](List.empty)
    } yield FakeEmailProvider(mr, sr, dr, ar)

}
