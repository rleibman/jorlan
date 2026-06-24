/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.email

import emil.*
import emil.builder.{Bcc as EmilBcc, Cc as EmilCc, From as EmilFrom, To as EmilTo, *}
import emil.javamail.JavaMailEmil
import jorlan.*
import jorlan.service.EmailProvider
import zio.*
import zio.interop.catz.{*, given}

import java.time.Instant
import scala.language.unsafeNulls

class ImapSmtpProvider(config: EmailConfig) extends EmailProvider[[A] =>> IO[JorlanError, A]] {

  private type F[A] = Task[A]

  private val emilClient: Emil[F] = JavaMailEmil[F]()

  private val imapConfig: MailConfig = MailConfig(
    url = s"${if (config.imapSsl) "imaps" else "imap"}://${config.imapHost}:${config.imapPort}",
    user = config.username,
    password = config.password,
    sslType = if (config.imapSsl) SSLType.SSL else SSLType.NoEncryption,
    disableCertificateCheck = config.sslTrustAll,
  )

  private val smtpConfig: MailConfig = MailConfig(
    url = s"smtp://${config.smtpHost}:${config.smtpPort}",
    user = config.username,
    password = config.password,
    sslType = if (config.smtpTls) SSLType.StartTLS else SSLType.NoEncryption,
    disableCertificateCheck = config.sslTrustAll,
  )

  private def runImap[A](op: MailOp[F, emilClient.C, A]): IO[JorlanError, A] =
    emilClient(imapConfig).run(op)
      .mapError(e => JorlanError(s"IMAP error: ${e.getMessage}"))

  private def runSmtp[A](op: MailOp[F, emilClient.C, A]): IO[JorlanError, A] =
    emilClient(smtpConfig).run(op)
      .mapError(e => JorlanError(s"SMTP error: ${e.getMessage}"))

  private def mailToEmailMessage(mail: Mail[F]): Task[EmailMessage] = {
    val mh = mail.header
    for {
      textOpt <- mail.body.textPart
      htmlOpt <- mail.body.htmlPart
    } yield EmailMessage(
      id = EmailMessageId(mh.id),
      threadId = mh.messageId.getOrElse(""),
      from = mh.from.map(_.displayString).getOrElse(""),
      to = mh.recipients.to.map(_.displayString),
      cc = mh.recipients.cc.map(_.displayString),
      bcc = mh.recipients.bcc.map(_.displayString),
      subject = mh.subject,
      body = textOpt.map(_.asString).getOrElse(""),
      bodyHtml = htmlOpt.map(_.asString),
      date = mh.date.getOrElse(Instant.EPOCH),
      attachments = mail.attachments.all.map { at =>
        EmailAttachment(
          name = at.filename.getOrElse(""),
          mimeType = at.mimeType.asString,
          sizeBytes = 0L,
          attachmentId = "",
        )
      }.toList,
      labels = mh.flags.map(_.toString).toList,
      pgpSigned = false,
      pgpSignatureValid = None,
    )
  }

  private def findMailHeader(
    messageId: EmailMessageId,
    folder:    MailFolder,
    max:       Int = 200,
  ): MailOp[F, emilClient.C, Option[MailHeader]] =
    emilClient.access
      .search(folder, max)(SearchQuery.All)
      .map(_.mails.find(_.id == messageId.value))

  private def buildOutgoingMail(draft: EmailDraft): Mail[F] = {
    val sender = MailAddress.unsafe(
      if (config.senderName.nonEmpty) Some(config.senderName) else None,
      config.username,
    )
    val parts: List[Trans[F]] = List(
      EmilFrom[F](sender),
      Subject[F](draft.subject),
      TextBody[F](draft.body),
    ) ++
      draft.to.map(addr => EmilTo[F](MailAddress.unsafe(None, addr))) ++
      draft.cc.map(addr => EmilCc[F](MailAddress.unsafe(None, addr))) ++
      draft.bcc.map(addr => EmilBcc[F](MailAddress.unsafe(None, addr)))
    MailBuilder.build[F](parts*)
  }

  override def listMessages(
    userId:     UserId,
    maxResults: Int,
    query:      Option[String],
  ): IO[JorlanError, List[EmailMessage]] = {
    val searchQuery = query match {
      case Some(q) if q.startsWith("from:") => SearchQuery.From(q.stripPrefix("from:").trim)
      case Some(q) if q.startsWith("subject:") =>
        SearchQuery.Subject(q.stripPrefix("subject:").trim)
      case Some(q) if !q.startsWith("is:") =>
        SearchQuery.Subject(q) || SearchQuery.From(q)
      case _ => SearchQuery.All
    }
    runImap {
      for {
        inbox  <- emilClient.access.getInbox
        result <- emilClient.access.searchAndLoad(inbox, maxResults)(searchQuery)
      } yield result.mails.toList
    }.flatMap { mails =>
      ZIO.foreach(mails)(mailToEmailMessage).mapError(e => JorlanError(e.getMessage))
    }
  }

  override def getMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, EmailMessage] =
    runImap {
      for {
        inbox  <- emilClient.access.getInbox
        result <- emilClient.access.searchAndLoad(inbox, Int.MaxValue)(SearchQuery.All)
        found = result.mails.find(_.header.id == messageId.value)
      } yield found
    }.flatMap {
      case None =>
        ZIO.fail(JorlanError(s"Message not found: ${messageId.value}"))
      case Some(mail) =>
        mailToEmailMessage(mail).mapError(e => JorlanError(e.getMessage))
    }

  override def sendDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): IO[JorlanError, EmailMessageId] = {
    val mail = buildOutgoingMail(draft)
    runSmtp {
      emilClient.sender.send(mail)
    }.map(ids => EmailMessageId(ids.head))
  }

  override def createDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): IO[JorlanError, String] =
    ZIO.fail(JorlanError("IMAP draft storage is not supported — use email.send to send directly"))

  override def archiveMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, Unit] =
    runImap {
      for {
        inbox   <- emilClient.access.getInbox
        archive <- emilClient.access.getOrCreateFolder(None, config.archiveFolder)
        mhOpt   <- findMailHeader(messageId, inbox)
        _ <- mhOpt match {
          case Some(mh) => emilClient.access.moveMail(mh, archive)
          case None     => MailOp.pure[F, emilClient.C, Unit](())
        }
      } yield ()
    }

  override def deleteMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, Unit] =
    runImap {
      for {
        inbox <- emilClient.access.getInbox
        mhOpt <- findMailHeader(messageId, inbox)
        _ <- mhOpt match {
          case Some(mh) => emilClient.access.deleteMail(mh)
          case None     => MailOp.pure[F, emilClient.C, Unit](())
        }
      } yield ()
    }

  override def moveMessage(
    userId:    UserId,
    messageId: EmailMessageId,
    toFolder:  String,
  ): IO[JorlanError, Unit] =
    runImap {
      for {
        inbox  <- emilClient.access.getInbox
        target <- emilClient.access.getOrCreateFolder(None, toFolder)
        mhOpt  <- findMailHeader(messageId, inbox)
        _ <- mhOpt match {
          case Some(mh) => emilClient.access.moveMail(mh, target)
          case None     => MailOp.pure[F, emilClient.C, Unit](())
        }
      } yield ()
    }

  override def flagMessage(
    userId:    UserId,
    messageId: EmailMessageId,
    flagged:   Option[Boolean],
    read:      Option[Boolean],
  ): IO[JorlanError, Unit] =
    ZIO.fail(JorlanError("Flag/read status operations are not supported by the IMAP provider"))

  override def listFolders(userId: UserId): IO[JorlanError, List[EmailFolderInfo]] =
    runImap {
      emilClient.access.listFolders(None).map { folders =>
        folders.toList.map(f => EmailFolderInfo(id = f.id, name = f.name))
      }
    }

  override def forwardMessage(
    userId:    UserId,
    messageId: EmailMessageId,
    to:        List[String],
    note:      Option[String],
  ): IO[JorlanError, EmailMessageId] =
    for {
      original <- getMessage(userId, messageId)
      prefix = note.map(_ + "\n\n---------- Forwarded message ----------\n").getOrElse(
        "---------- Forwarded message ----------\n",
      )
      fwdBody =
        s"${prefix}From: ${original.from}\nDate: ${original.date}\nSubject: ${original.subject}\n\n${original.body}"
      draft = EmailDraft(
        to = to,
        cc = Nil,
        bcc = Nil,
        subject = s"Fwd: ${original.subject}",
        body = fwdBody,
        replyToMessageId = None,
        signWithPgp = false,
      )
      msgId <- sendDraft(userId, draft)
    } yield msgId

}
