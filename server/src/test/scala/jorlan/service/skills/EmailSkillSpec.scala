/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.db.repository.ZIORepositories
import jorlan.domain.*
import jorlan.service.EmailProvider
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.*

import java.time.Instant

object EmailSkillSpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ULayer[ZIORepositories] = InMemoryRepositories.live()

  private val ctx = InvocationContext(UserId(1L), None, None)

  private val sampleMessages = List(
    EmailMessage(
      id = EmailMessageId("msg-1"),
      threadId = "thread-1",
      from = "alice@example.com",
      to = List("bob@example.com"),
      cc = Nil,
      bcc = Nil,
      subject = "Hello",
      body = "Hello Bob",
      bodyHtml = None,
      date = Instant.parse("2026-01-01T10:00:00Z"),
      attachments = Nil,
      labels = List("INBOX"),
      pgpSigned = false,
      pgpSignatureValid = None,
    ),
    EmailMessage(
      id = EmailMessageId("msg-2"),
      threadId = "thread-2",
      from = "charlie@example.com",
      to = List("bob@example.com"),
      cc = Nil,
      bcc = Nil,
      subject = "Meeting tomorrow",
      body = "Let's meet at 10am",
      bodyHtml = None,
      date = Instant.parse("2026-01-02T09:00:00Z"),
      attachments = Nil,
      labels = List("INBOX"),
      pgpSigned = false,
      pgpSignatureValid = None,
    ),
  )

  private def makeProvider(messages: List[EmailMessage] = sampleMessages): UIO[FakeEmailProvider] =
    for {
      messagesRef <- Ref.make(messages)
      sentRef     <- Ref.make[List[EmailDraft]](Nil)
      draftsRef   <- Ref.make[List[EmailDraft]](Nil)
      archivedRef <- Ref.make[List[EmailMessageId]](Nil)
    } yield FakeEmailProvider(messagesRef, sentRef, draftsRef, archivedRef)

  private def makeSkill(provider: EmailProvider[[A] =>> IO[JorlanError, A]]): URIO[ZIORepositories, EmailSkill] =
    ZIO.serviceWith[ZIORepositories](new EmailSkill(provider, _))

  private def strField(json: Json, key: String): Option[String] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Str(v)) => v }
      case _                => None
    }

  private def intField(json: Json, key: String): Option[Int] =
    json match {
      case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Num(n)) => n.intValue }
      case _                => None
    }

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("EmailSkill")(
      test("email.list returns all messages") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "email.list", Json.Obj())
        } yield assertTrue(intField(result, "count").contains(2))
      },
      test("email.list with maxResults limits output") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "email.list", Json.Obj("maxResults" -> Json.Num(1)))
        } yield assertTrue(intField(result, "count").contains(1))
      },
      test("email.read returns message details") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "email.read", Json.Obj("messageId" -> Json.Str("msg-1")))
        } yield assertTrue(
          strField(result, "id").contains("msg-1"),
          strField(result, "subject").contains("Hello"),
          strField(result, "from").contains("alice@example.com"),
        )
      },
      test("email.read fails for unknown message ID") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "email.read", Json.Obj("messageId" -> Json.Str("no-such-id"))).either
        } yield assertTrue(result.isLeft)
      },
      test("email.read fails without messageId") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "email.read", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("email.send sends a message and returns messageId") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          args = Json.Obj(
            "to"      -> Json.Arr(Json.Str("alice@example.com")),
            "subject" -> Json.Str("Test"),
            "body"    -> Json.Str("Test body"),
          )
          result <- skill.invoke(ctx, "email.send", args)
          sent   <- provider.sentMessages
        } yield assertTrue(
          strField(result, "messageId").isDefined,
          sent.length == 1,
          sent.head.subject == "Test",
        )
      },
      test("email.send fails without required fields") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "email.send", Json.Obj("to" -> Json.Arr(Json.Str("alice@example.com")))).either
        } yield assertTrue(result.isLeft)
      },
      test("email.draft creates a draft") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          args = Json.Obj(
            "to"      -> Json.Arr(Json.Str("alice@example.com")),
            "subject" -> Json.Str("Draft subject"),
            "body"    -> Json.Str("Draft body"),
          )
          result <- skill.invoke(ctx, "email.draft", args)
          drafts <- provider.draftMessages
        } yield assertTrue(
          strField(result, "draftId").isDefined,
          drafts.length == 1,
          drafts.head.subject == "Draft subject",
        )
      },
      test("email.archive archives a message") {
        for {
          provider  <- makeProvider()
          skill     <- makeSkill(provider)
          result    <- skill.invoke(ctx, "email.archive", Json.Obj("messageId" -> Json.Str("msg-1")))
          archived  <- provider.archivedIds
        } yield assertTrue(
          strField(result, "messageId").contains("msg-1"),
          archived.contains(EmailMessageId("msg-1")),
        )
      },
      test("email.delete deletes a message") {
        for {
          provider  <- makeProvider()
          skill     <- makeSkill(provider)
          result    <- skill.invoke(ctx, "email.delete", Json.Obj("messageId" -> Json.Str("msg-2")))
          archived  <- provider.archivedIds
        } yield assertTrue(
          strField(result, "messageId").contains("msg-2"),
          archived.contains(EmailMessageId("msg-2")),
        )
      },
      test("email.reply replies to a message") {
        for {
          provider  <- makeProvider()
          skill     <- makeSkill(provider)
          args = Json.Obj(
            "messageId" -> Json.Str("msg-1"),
            "body"      -> Json.Str("Thanks for the message!"),
          )
          result <- skill.invoke(ctx, "email.reply", args)
          sent   <- provider.sentMessages
        } yield assertTrue(
          strField(result, "messageId").isDefined,
          sent.length == 1,
          sent.head.subject == "Re: Hello",
        )
      },
      test("email.search filters by query") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "email.search", Json.Obj("query" -> Json.Str("Meeting")))
        } yield assertTrue(intField(result, "count").contains(1))
      },
      test("email.search fails without query") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "email.search", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("unknown tool returns JorlanError") {
        for {
          provider <- makeProvider()
          skill    <- makeSkill(provider)
          result   <- skill.invoke(ctx, "email.unknown", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
    )

}

class FakeEmailProvider(
  messagesRef: Ref[List[EmailMessage]],
  sentRef:     Ref[List[EmailDraft]],
  draftsRef:   Ref[List[EmailDraft]],
  archivedRef: Ref[List[EmailMessageId]],
) extends EmailProvider[[A] =>> IO[JorlanError, A]] {

  override def listMessages(userId: UserId, maxResults: Int, query: Option[String]): IO[JorlanError, List[EmailMessage]] =
    messagesRef.get.map { msgs =>
      val filtered = query.fold(msgs)(q => msgs.filter(m => m.subject.contains(q) || m.body.contains(q)))
      filtered.take(maxResults)
    }

  override def getMessage(userId: UserId, messageId: EmailMessageId): IO[JorlanError, EmailMessage] =
    messagesRef.get.flatMap { msgs =>
      ZIO.fromOption(msgs.find(_.id == messageId))
        .orElseFail(JorlanError(s"Message not found: ${messageId.value}"))
    }

  override def sendDraft(userId: UserId, draft: EmailDraft): IO[JorlanError, EmailMessageId] =
    sentRef.update(_ :+ draft) *> ZIO.succeed(EmailMessageId("fake-sent-id"))

  override def createDraft(userId: UserId, draft: EmailDraft): IO[JorlanError, String] =
    draftsRef.update(_ :+ draft) *> ZIO.succeed("fake-draft-id")

  override def archiveMessage(userId: UserId, messageId: EmailMessageId): IO[JorlanError, Unit] =
    archivedRef.update(_ :+ messageId)

  override def deleteMessage(userId: UserId, messageId: EmailMessageId): IO[JorlanError, Unit] =
    archivedRef.update(_ :+ messageId)

  def sentMessages: UIO[List[EmailDraft]]    = sentRef.get
  def draftMessages: UIO[List[EmailDraft]]   = draftsRef.get
  def archivedIds: UIO[List[EmailMessageId]] = archivedRef.get

}
