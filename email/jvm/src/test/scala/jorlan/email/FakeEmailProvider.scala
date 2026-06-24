/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.email

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

  override def moveMessage(
    userId:    UserId,
    messageId: EmailMessageId,
    toFolder:  String,
  ): IO[JorlanError, Unit] =
    archivedRef.update(_ :+ messageId)

  override def flagMessage(
    userId:    UserId,
    messageId: EmailMessageId,
    flagged:   Option[Boolean],
    read:      Option[Boolean],
  ): IO[JorlanError, Unit] = ZIO.unit

  override def listFolders(userId: UserId): IO[JorlanError, List[EmailFolderInfo]] =
    ZIO.succeed(List(EmailFolderInfo("INBOX", "INBOX"), EmailFolderInfo("Archive", "Archive")))

  override def forwardMessage(
    userId:    UserId,
    messageId: EmailMessageId,
    to:        List[String],
    note:      Option[String],
  ): IO[JorlanError, EmailMessageId] =
    sentRef.update(_ :+ EmailDraft(to, Nil, Nil, "Fwd:", "", None, false)) *>
      ZIO.succeed(EmailMessageId("fake-forward-id"))

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
