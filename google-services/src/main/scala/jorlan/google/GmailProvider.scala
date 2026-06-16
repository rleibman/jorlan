/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.google

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.{Draft, Message, MessagePartHeader}
import jorlan.JorlanError
import jorlan.*
import jorlan.service.{EmailProvider, OAuthCredentialService}
import zio.IO
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.{Base64, Date}
import scala.jdk.CollectionConverters.*
import scala.language.unsafeNulls

/** Gmail-backed [[jorlan.service.EmailProvider]] using the Google Java API client library.
  *
  * On every API call, delegates to [[OAuthCredentialService.refreshAccessToken]] which checks the stored expiry and
  * only calls Google's token endpoint when the access token is within 60 seconds of expiry.
  */
class GmailProvider private (
  credentials: OAuthCredentialService,
  transport:   com.google.api.client.http.HttpTransport,
  jsonFactory: com.google.api.client.json.JsonFactory,
  clientCache: Ref[Map[UserId, (String, Gmail)]],
) extends GoogleApiProvider[Gmail](credentials, transport, jsonFactory, clientCache)
    with EmailProvider[[A] =>> IO[JorlanError, A]] {

  override protected val apiName: String = "Gmail"

  override protected def makeClient(accessToken: String): Gmail =
    new Gmail.Builder(transport, jsonFactory, buildAdapter(accessToken))
      .setApplicationName("Jorlan")
      .build()
      .nn

  private def withGmail[A](userId: UserId)(f: Gmail => A): IO[JorlanError, A] = withClient(userId)(f)

  private def headerValue(
    headers: java.util.List[MessagePartHeader] | Null,
    name:    String,
  ): String =
    Option(headers)
      .map(_.asScala.find(h => h.getName.nn.equalsIgnoreCase(name)).map(_.getValue.nn).getOrElse(""))
      .getOrElse("")

  private def decodePart(data: String | Null): String =
    Option(data).map(d => new String(Base64.getUrlDecoder.decode(d.replaceAll("\\s", "")), "UTF-8")).getOrElse("")

  private def extractTextBody(payload: com.google.api.services.gmail.model.MessagePart | Null)
    : (String, Option[String]) =
    Option(payload) match {
      case None    => ("", None)
      case Some(p) =>
        val mime = Option(p.getMimeType).getOrElse("").nn
        val parts = Option(p.getParts).map(_.asScala.toList).getOrElse(Nil)
        if (mime.startsWith("text/plain")) {
          (decodePart(Option(p.getBody).flatMap(b => Option(b.getData)).orNull), None)
        } else if (mime.startsWith("text/html")) {
          ("", Some(decodePart(Option(p.getBody).flatMap(b => Option(b.getData)).orNull)))
        } else if (mime.startsWith("multipart/")) {
          val plain = parts
            .find(pt => Option(pt.getMimeType).exists(_.startsWith("text/plain")))
            .flatMap(pt => Option(pt.getBody).flatMap(b => Option(b.getData)))
            .map(decodePart)
            .getOrElse("")
          val html = parts
            .find(pt => Option(pt.getMimeType).exists(_.startsWith("text/html")))
            .flatMap(pt => Option(pt.getBody).flatMap(b => Option(b.getData)))
            .map(decodePart)
          (plain, html)
        } else {
          ("", None)
        }
    }

  private def messageToEmail(m: Message): EmailMessage = {
    val payload = Option(m.getPayload)
    val headers = payload.flatMap(p => Option(p.getHeaders)).map(_.asScala.toList).getOrElse(Nil)
    def hdr(n: String) =
      headers.find(_.getName.nn.equalsIgnoreCase(n)).flatMap(h => Option(h.getValue)).getOrElse("").nn
    val (plain, html) = extractTextBody(payload.orNull)
    val labels = Option(m.getLabelIds).map(_.asScala.toList).getOrElse(Nil)
    val date = Option(m.getInternalDate).map(d => Instant.ofEpochMilli(d.toLong)).getOrElse(Instant.EPOCH)
    val attachments = payload
      .flatMap(p => Option(p.getParts)).map(_.asScala.toList).getOrElse(Nil)
      .filter(pt => Option(pt.getFilename).exists(_.nonEmpty))
      .map { pt =>
        EmailAttachment(
          name = Option(pt.getFilename).getOrElse("").nn,
          mimeType = Option(pt.getMimeType).getOrElse("").nn,
          sizeBytes = Option(pt.getBody).flatMap(b => Option(b.getSize)).map(_.toLong).getOrElse(0L),
          attachmentId = Option(pt.getBody).flatMap(b => Option(b.getAttachmentId)).getOrElse("").nn,
        )
      }
    EmailMessage(
      id = EmailMessageId(Option(m.getId).getOrElse("").nn),
      threadId = Option(m.getThreadId).getOrElse("").nn,
      from = hdr("From"),
      to = hdr("To").split(",").map(_.trim).filter(_.nonEmpty).toList,
      cc = hdr("Cc").split(",").map(_.trim).filter(_.nonEmpty).toList,
      bcc = hdr("Bcc").split(",").map(_.trim).filter(_.nonEmpty).toList,
      subject = hdr("Subject"),
      body = plain,
      bodyHtml = html,
      date = date,
      attachments = attachments,
      labels = labels,
      pgpSigned = false,
      pgpSignatureValid = None,
    )
  }

  override def listMessages(
    userId:     UserId,
    maxResults: Int,
    query:      Option[String],
  ): IO[JorlanError, List[EmailMessage]] =
    withGmail(userId) { gmail =>
      val req = gmail
        .users().messages().list("me").nn
        .setMaxResults(maxResults.toLong)
      query.foreach(q => req.setQ(q))
      val msgRefs = Option(req.execute().nn.getMessages).map(_.asScala.toList).getOrElse(Nil)
      msgRefs.map { ref =>
        messageToEmail(
          gmail
            .users().messages().get("me", ref.getId).nn
            .setFormat("full")
            .execute().nn,
        )
      }
    }

  override def getMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, EmailMessage] =
    withGmail(userId) { gmail =>
      gmail
        .users().messages().get("me", messageId.value).nn
        .setFormat("full")
        .execute().nn
    }.map(messageToEmail)

  override def sendDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): IO[JorlanError, EmailMessageId] =
    withGmail(userId) { gmail =>
      val fromEmail = Option(gmail.users().getProfile("me").nn.execute().nn.getEmailAddress).getOrElse("").nn
      val raw = buildRfc822(draft, fromEmail)
      val encoded = Base64.getUrlEncoder.encodeToString(raw.getBytes("UTF-8"))
      val message = new Message().setRaw(encoded)
      val result = gmail.users().messages().send("me", message).nn.execute().nn
      EmailMessageId(Option(result.getId).getOrElse("").nn)
    }

  override def createDraft(
    userId: UserId,
    draft:  EmailDraft,
  ): IO[JorlanError, String] =
    withGmail(userId) { gmail =>
      val fromEmail = Option(gmail.users().getProfile("me").nn.execute().nn.getEmailAddress).getOrElse("").nn
      val raw = buildRfc822(draft, fromEmail)
      val encoded = Base64.getUrlEncoder.encodeToString(raw.getBytes("UTF-8"))
      val message = new Message().setRaw(encoded)
      val result = gmail.users().drafts().create("me", new Draft().setMessage(message)).nn.execute().nn
      Option(result.getId).getOrElse("").nn
    }

  override def archiveMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, Unit] =
    withGmail(userId) { gmail =>
      val req = new com.google.api.services.gmail.model.ModifyMessageRequest()
        .setRemoveLabelIds(List("INBOX").asJava)
      gmail.users().messages().modify("me", messageId.value, req).nn.execute()
    }.unit

  override def deleteMessage(
    userId:    UserId,
    messageId: EmailMessageId,
  ): IO[JorlanError, Unit] =
    withGmail(userId) { gmail =>
      val req = new com.google.api.services.gmail.model.ModifyMessageRequest()
        .setAddLabelIds(List("TRASH").asJava)
      gmail.users().messages().modify("me", messageId.value, req).nn.execute()
    }.unit

  private def buildRfc822(
    draft:     EmailDraft,
    fromEmail: String,
  ): String = {
    val to = draft.to.mkString(", ")
    val from = if (fromEmail.nonEmpty) s"\r\nFrom: $fromEmail" else ""
    val cc = if (draft.cc.nonEmpty) s"\r\nCc: ${draft.cc.mkString(", ")}" else ""
    val bcc = if (draft.bcc.nonEmpty) s"\r\nBcc: ${draft.bcc.mkString(", ")}" else ""
    val replyTo = draft.replyToMessageId.map(id => s"\r\nIn-Reply-To: $id").getOrElse("")
    s"To: $to$from$cc$bcc\r\nSubject: ${draft.subject}$replyTo\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n${draft.body}"
  }

}

object GmailProvider {

  def apply(credentials: OAuthCredentialService): IO[JorlanError, GmailProvider] =
    for {
      transport <- ZIO
        .attemptBlocking(GoogleNetHttpTransport.newTrustedTransport().nn)
        .mapError(e => JorlanError(s"Failed to initialize Gmail transport: ${e.getMessage}", Some(e)))
      jsonFactory = GsonFactory.getDefaultInstance.nn
      cache <- Ref.make(Map.empty[UserId, (String, Gmail)])
    } yield new GmailProvider(credentials, transport, jsonFactory, cache)

}
