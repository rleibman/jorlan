/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.google

import jorlan.*
import jorlan.service.EmailProvider
import zio.*

class FakeGmailProvider(
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
    sentRef.update(_ :+ draft) *> ZIO.succeed(EmailMessageId("fake-gmail-sent-id"))

  override def createDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): IO[JorlanError, String] =
    draftsRef.update(_ :+ draft) *> ZIO.succeed("fake-gmail-draft-id")

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

object FakeGmailProvider {

  def make(messages: List[EmailMessage] = List.empty): UIO[FakeGmailProvider] =
    for {
      mr <- Ref.make(messages)
      sr <- Ref.make[List[EmailDraft]](List.empty)
      dr <- Ref.make[List[EmailDraft]](List.empty)
      ar <- Ref.make[List[EmailMessageId]](List.empty)
    } yield FakeGmailProvider(mr, sr, dr, ar)

}
