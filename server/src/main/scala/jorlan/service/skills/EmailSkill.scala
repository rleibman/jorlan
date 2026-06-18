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
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.*
import jorlan.service.EmailProvider
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Built-in skill for reading, sending, and managing email messages.
  *
  * Tools:
  *   - `email.list` — list recent messages
  *   - `email.read` — read a specific message
  *   - `email.send` — send a message immediately
  *   - `email.draft` — create a draft
  *   - `email.archive` — archive a message
  *   - `email.delete` — delete a message
  *   - `email.reply` — reply to a message
  *   - `email.search` — search for messages
  */
class EmailSkill(
  emailProvider: EmailProvider[[A] =>> IO[JorlanError, A]],
) extends Skill with SkillEventLogger {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "email",
    tier = SkillTier.BuiltIn,
    tools = List(
      ToolDescriptor(
        name = "email.list",
        description = "List recent email messages from the inbox.",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"maxResults":{"type":"integer","description":"Maximum number of messages to return (default 10)"},"query":{"type":"string","description":"Optional search query to filter messages"}},"required":[]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.read")),
        examplePrompts = List(
          "Show me my recent emails",
          "What's in my inbox?",
          "List the last 5 emails from Alice",
        ),
      ),
      ToolDescriptor(
        name = "email.read",
        description = "Read a specific email message by ID.",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"messageId":{"type":"string","description":"The email message ID"}},"required":["messageId"]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.read")),
        examplePrompts = List(
          "Read the email from Bob about the meeting",
          "Show me the full content of message abc123",
        ),
      ),
      ToolDescriptor(
        name = "email.send",
        description = "Send an email message immediately.",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"to":{"type":"array","items":{"type":"string"},"description":"Recipient email addresses"},"cc":{"type":"array","items":{"type":"string"},"description":"CC recipients"},"bcc":{"type":"array","items":{"type":"string"},"description":"BCC recipients"},"subject":{"type":"string","description":"Email subject"},"body":{"type":"string","description":"Email body text"},"replyToMessageId":{"type":"string","description":"Message ID to reply to (optional)"},"signWithPgp":{"type":"boolean","description":"Sign with PGP (default false)"}},"required":["to","subject","body"]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.send")),
        examplePrompts = List(
          "Send an email to alice@example.com about the project update",
          "Email the team that the release is ready",
          "Reply to Bob's message saying I'll be there",
        ),
      ),
      ToolDescriptor(
        name = "email.draft",
        description = "Create an email draft without sending.",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"to":{"type":"array","items":{"type":"string"}},"cc":{"type":"array","items":{"type":"string"}},"bcc":{"type":"array","items":{"type":"string"}},"subject":{"type":"string"},"body":{"type":"string"},"replyToMessageId":{"type":"string"}},"required":["to","subject","body"]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.write")),
        examplePrompts = List(
          "Draft an email to the team about next week's sprint",
          "Save a draft reply to Alice without sending yet",
        ),
      ),
      ToolDescriptor(
        name = "email.archive",
        description = "Archive an email message.",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"messageId":{"type":"string","description":"The email message ID to archive"}},"required":["messageId"]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.write")),
        examplePrompts = List(
          "Archive the email from Bob about the old project",
          "Move this newsletter to archive",
        ),
      ),
      ToolDescriptor(
        name = "email.delete",
        description = "Delete an email message.",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"messageId":{"type":"string","description":"The email message ID to delete"}},"required":["messageId"]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.write")),
        examplePrompts = List(
          "Delete the spam email I just received",
          "Remove that old invoice email",
        ),
      ),
      ToolDescriptor(
        name = "email.reply",
        description = "Reply to an email message.",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"messageId":{"type":"string","description":"The message ID to reply to"},"body":{"type":"string","description":"Reply body text"},"replyAll":{"type":"boolean","description":"Reply to all recipients (default false)"}},"required":["messageId","body"]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.send")),
        examplePrompts = List(
          "Reply to Alice's email saying I'll join the meeting",
          "Reply to all on the project thread with my update",
        ),
      ),
      ToolDescriptor(
        name = "email.search",
        description = "Search for email messages using a query string.",
        inputSchema =
          parseSchema("""{"type":"object","properties":{"query":{"type":"string","description":"Search query (supports Gmail-style search operators)"},"maxResults":{"type":"integer","description":"Maximum number of results (default 10)"}},"required":["query"]}"""),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.read")),
        examplePrompts = List(
          "Find emails from Alice about the contract",
          "Search for any emails mentioning the budget report",
          "Look for emails with attachments from last week",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] =
    tool match {
      case "email.list"    => emailList(ctx, args)
      case "email.read"    => emailRead(ctx, args)
      case "email.send"    => emailSend(ctx, args)
      case "email.draft"   => emailDraft(ctx, args)
      case "email.archive" => emailArchive(ctx, args)
      case "email.delete"  => emailDelete(ctx, args)
      case "email.reply"   => emailReply(ctx, args)
      case "email.search"  => emailSearch(ctx, args)
      case other           => ZIO.fail(JorlanError(s"EmailSkill: unknown tool '$other'"))
    }

  private def buildDraft(
    args:      Json,
    replyToId: Option[String] = None,
  ): Option[EmailDraft] = {
    val to = strList(args, "to")
    val subject = str(args, "subject")
    val body = str(args, "body")
    (subject, body) match {
      case (Some(subj), Some(b)) =>
        Some(
          EmailDraft(
            to = to,
            cc = strList(args, "cc"),
            bcc = strList(args, "bcc"),
            subject = subj,
            body = b,
            replyToMessageId = replyToId.orElse(str(args, "replyToMessageId")),
            signWithPgp = bool(args, "signWithPgp").getOrElse(false),
          ),
        )
      case _ => None
    }
  }

  private def emailList(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] = {
    val maxResults = int(args, "maxResults").getOrElse(10)
    for {
      msgs <- emailProvider.listMessages(ctx.actorId, maxResults, None)
      _    <- logEvent(
        ctx,
        EventType.EmailMessageRead,
        Json.Obj("action" -> Json.Str("list"), "count" -> Json.Num(msgs.size)),
      )
    } yield Json.Obj(
      "messages" -> Json.Arr(
        msgs.map(m =>
          Json.Obj(
            "id"      -> Json.Str(m.id.value),
            "from"    -> Json.Str(m.from),
            "subject" -> Json.Str(m.subject),
            "date"    -> Json.Str(m.date.toString),
          ),
        )*,
      ),
      "count" -> Json.Num(msgs.size),
    )
  }

  private def emailRead(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    str(args, "messageId") match {
      case None     => ZIO.fail(JorlanError("email.read: messageId is required"))
      case Some(id) =>
        for {
          msg <- emailProvider.getMessage(ctx.actorId, EmailMessageId(id))
          _   <- logEvent(ctx, EventType.EmailMessageRead, Json.Obj("messageId" -> Json.Str(id)))
        } yield Json.Obj(
          "id"        -> Json.Str(msg.id.value),
          "from"      -> Json.Str(msg.from),
          "to"        -> Json.Arr(msg.to.map(Json.Str(_))*),
          "subject"   -> Json.Str(msg.subject),
          "body"      -> Json.Str(msg.body),
          "date"      -> Json.Str(msg.date.toString),
          "labels"    -> Json.Arr(msg.labels.map(Json.Str(_))*),
          "pgpSigned" -> Json.Bool(msg.pgpSigned),
        )
    }

  private def emailSend(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    buildDraft(args) match {
      case None        => ZIO.fail(JorlanError("email.send: to, subject and body are required"))
      case Some(draft) =>
        for {
          msgId <- emailProvider.sendDraft(ctx.actorId, draft)
          _     <- logEvent(
            ctx,
            EventType.EmailMessageSent,
            Json.Obj(
              "to"        -> Json.Arr(draft.to.map(Json.Str(_))*),
              "subject"   -> Json.Str(draft.subject),
              "messageId" -> Json.Str(msgId.value),
            ),
          )
        } yield Json.Obj("messageId" -> Json.Str(msgId.value), "sent" -> Json.Bool(true))
    }

  private def emailDraft(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    buildDraft(args) match {
      case None        => ZIO.fail(JorlanError("email.draft: to, subject and body are required"))
      case Some(draft) =>
        for {
          draftId <- emailProvider.createDraft(ctx.actorId, draft)
          _       <- logEvent(
            ctx,
            EventType.EmailDraftCreated,
            Json.Obj("to" -> Json.Arr(draft.to.map(Json.Str(_))*), "subject" -> Json.Str(draft.subject)),
          )
        } yield Json.Obj("draftId" -> Json.Str(draftId), "created" -> Json.Bool(true))
    }

  private def emailArchive(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    str(args, "messageId") match {
      case None     => ZIO.fail(JorlanError("email.archive: messageId is required"))
      case Some(id) =>
        for {
          _ <- emailProvider.archiveMessage(ctx.actorId, EmailMessageId(id))
          _ <- logEvent(ctx, EventType.EmailMessageArchived, Json.Obj("messageId" -> Json.Str(id)))
        } yield Json.Obj("messageId" -> Json.Str(id), "archived" -> Json.Bool(true))
    }

  private def emailDelete(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    str(args, "messageId") match {
      case None     => ZIO.fail(JorlanError("email.delete: messageId is required"))
      case Some(id) =>
        for {
          _ <- emailProvider.deleteMessage(ctx.actorId, EmailMessageId(id))
          _ <- logEvent(ctx, EventType.EmailMessageDeleted, Json.Obj("messageId" -> Json.Str(id)))
        } yield Json.Obj("messageId" -> Json.Str(id), "deleted" -> Json.Bool(true))
    }

  private def emailReply(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    for {
      id <- ZIO
        .fromOption(str(args, "messageId")).orElseFail(JorlanError("email.reply: messageId is required"))
      body     <- ZIO.fromOption(str(args, "body")).orElseFail(JorlanError("email.reply: body is required"))
      original <- emailProvider.getMessage(ctx.actorId, EmailMessageId(id))
      draft = EmailDraft(
        to = List(original.from),
        cc = Nil,
        bcc = Nil,
        subject = s"Re: ${original.subject}",
        body = body,
        replyToMessageId = Some(id),
        signWithPgp = false,
      )
      msgId <- emailProvider.sendDraft(ctx.actorId, draft)
      _     <- logEvent(
        ctx,
        EventType.EmailMessageSent,
        Json.Obj("replyTo" -> Json.Str(id), "messageId" -> Json.Str(msgId.value)),
      )
    } yield Json.Obj("messageId" -> Json.Str(msgId.value), "sent" -> Json.Bool(true))

  private def emailSearch(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    str(args, "query") match {
      case None        => ZIO.fail(JorlanError("email.search: query is required"))
      case Some(query) =>
        val maxResults = int(args, "maxResults").getOrElse(10)
        for {
          msgs <- emailProvider.listMessages(ctx.actorId, maxResults, Some(query))
          _    <- logEvent(
            ctx,
            EventType.EmailMessageRead,
            Json.Obj("action" -> Json.Str("search"), "query" -> Json.Str(query), "count" -> Json.Num(msgs.size)),
          )
        } yield Json.Obj(
          "messages" -> Json.Arr(
            msgs.map(m =>
              Json.Obj(
                "id"      -> Json.Str(m.id.value),
                "from"    -> Json.Str(m.from),
                "subject" -> Json.Str(m.subject),
                "date"    -> Json.Str(m.date.toString),
              ),
            )*,
          ),
          "count" -> Json.Num(msgs.size),
        )
    }

}

object EmailSkill
