/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.{HasDashboardData, HasValidation, InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.service.EmailProvider
import just.semver.SemVer
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.literal.*

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
  *   - `email.move` — move message to a folder
  *   - `email.flag` — flag or mark a message as read/unread
  *   - `email.folders` — list available folders
  *   - `email.forward` — forward a message
  */
class EmailSkill(
  emailProvider: EmailProvider[[A] =>> IO[JorlanError, A]],
) extends Skill with HasDashboardData with HasValidation {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "email",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "email",
      "mail",
      "message",
      "send",
      "inbox",
      "Gmail",
      "SMTP",
      "IMAP",
      "compose",
      "reply",
      "forward",
      "attachment",
      "cc",
      "bcc",
      "subject",
      "unread",
      "archive",
      "delete",
      "draft",
    ),
    configKey = Some("skill.email"),
    configJsModule = Some("jorlan-email"),
    tools = List(
      ToolDescriptor(
        name = "email.list",
        description = "List recent email messages from the inbox.",
        inputSchema = json"""{"type":"object","properties":{"maxResults":{"type":"integer","description":"Maximum number of messages to return (default 10)"},"query":{"type":"string","description":"Optional search query to filter messages"}},"required":[]}""",
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
        inputSchema = json"""{"type":"object","properties":{"messageId":{"type":"string","description":"The email message ID"}},"required":["messageId"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"to":{"type":"array","items":{"type":"string"},"description":"Recipient email addresses"},"cc":{"type":"array","items":{"type":"string"},"description":"CC recipients"},"bcc":{"type":"array","items":{"type":"string"},"description":"BCC recipients"},"subject":{"type":"string","description":"Email subject"},"body":{"type":"string","description":"Email body text"},"replyToMessageId":{"type":"string","description":"Message ID to reply to (optional)"},"signWithPgp":{"type":"boolean","description":"Sign with PGP (default false)"}},"required":["to","subject","body"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"to":{"type":"array","items":{"type":"string"}},"cc":{"type":"array","items":{"type":"string"}},"bcc":{"type":"array","items":{"type":"string"}},"subject":{"type":"string"},"body":{"type":"string"},"replyToMessageId":{"type":"string"}},"required":["to","subject","body"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"messageId":{"type":"string","description":"The email message ID to archive"}},"required":["messageId"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"messageId":{"type":"string","description":"The email message ID to delete"}},"required":["messageId"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"messageId":{"type":"string","description":"The message ID to reply to"},"body":{"type":"string","description":"Reply body text"},"replyAll":{"type":"boolean","description":"Reply to all recipients (default false)"}},"required":["messageId","body"]}""",
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
        inputSchema = json"""{"type":"object","properties":{"query":{"type":"string","description":"Search query (supports Gmail-style search operators)"},"maxResults":{"type":"integer","description":"Maximum number of results (default 10)"}},"required":["query"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.read")),
        examplePrompts = List(
          "Find emails from Alice about the contract",
          "Search for any emails mentioning the budget report",
          "Look for emails with attachments from last week",
        ),
      ),
      ToolDescriptor(
        name = "email.move",
        description = "Move an email message to a specified folder.",
        inputSchema = json"""{"type":"object","properties":{"messageId":{"type":"string","description":"The email message ID to move"},"folder":{"type":"string","description":"The target folder name (e.g. Archive, Sent, Trash)"}},"required":["messageId","folder"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.write")),
        examplePrompts = List(
          "Move the email from Bob to the Archive folder",
          "Put this newsletter in the Newsletters folder",
        ),
      ),
      ToolDescriptor(
        name = "email.flag",
        description = "Flag or unflag an email, or mark it as read or unread.",
        inputSchema = json"""{"type":"object","properties":{"messageId":{"type":"string","description":"The email message ID"},"flagged":{"type":"boolean","description":"true to flag/star the message, false to unflag"},"read":{"type":"boolean","description":"true to mark as read, false to mark as unread"}},"required":["messageId"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.write")),
        examplePrompts = List(
          "Flag the email from Alice as important",
          "Mark the newsletter as unread",
          "Star the meeting invite email",
        ),
      ),
      ToolDescriptor(
        name = "email.folders",
        description = "List all available mailbox folders.",
        inputSchema = json"""{"type":"object","properties":{},"required":[]}""",
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("email.read")),
        examplePrompts = List(
          "What email folders do I have?",
          "List my mailbox folders",
          "Show me all my email labels",
        ),
      ),
      ToolDescriptor(
        name = "email.forward",
        description = "Forward an email message to one or more recipients.",
        inputSchema = json"""{"type":"object","properties":{"messageId":{"type":"string","description":"The email message ID to forward"},"to":{"type":"array","items":{"type":"string"},"description":"Recipient email addresses"},"note":{"type":"string","description":"Optional note to prepend before the forwarded content"}},"required":["messageId","to"]}""",
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("email.send")),
        examplePrompts = List(
          "Forward Bob's email to Alice",
          "Send this meeting invite to the whole team",
          "Forward the report email to my manager with a note",
        ),
      ),
    ),
    oauthProvider = Some(OAuthProvider.Google),
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
      case "email.move"    => emailMove(ctx, args)
      case "email.flag"    => emailFlag(ctx, args)
      case "email.folders" => emailFolders(ctx, args)
      case "email.forward" => emailForward(ctx, args)
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
        cc = List.empty,
        bcc = List.empty,
        subject = s"Re: ${original.subject}",
        body = body,
        replyToMessageId = Some(id),
        signWithPgp = false,
      )
      msgId <- emailProvider.sendDraft(ctx.actorId, draft)
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

  private def emailMove(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    for {
      id     <- ZIO.fromOption(str(args, "messageId")).orElseFail(JorlanError("email.move: messageId is required"))
      folder <- ZIO.fromOption(str(args, "folder")).orElseFail(JorlanError("email.move: folder is required"))
      _      <- emailProvider.moveMessage(ctx.actorId, EmailMessageId(id), folder)
    } yield Json.Obj("messageId" -> Json.Str(id), "movedTo" -> Json.Str(folder))

  private def emailFlag(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    for {
      id <- ZIO.fromOption(str(args, "messageId")).orElseFail(JorlanError("email.flag: messageId is required"))
      flagged = bool(args, "flagged")
      read = bool(args, "read")
      _ <- emailProvider.flagMessage(ctx.actorId, EmailMessageId(id), flagged, read)
    } yield Json.Obj("messageId" -> Json.Str(id), "updated" -> Json.Bool(true))

  private def emailFolders(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    for {
      folders <- emailProvider.listFolders(ctx.actorId)
    } yield Json.Obj(
      "folders" -> Json.Arr(
        folders.map(f => Json.Obj("id" -> Json.Str(f.id), "name" -> Json.Str(f.name)))*,
      ),
      "count" -> Json.Num(folders.size),
    )

  private def emailForward(
    ctx:  InvocationContext,
    args: Json,
  ): IO[JorlanError, Json] =
    for {
      id <- ZIO.fromOption(str(args, "messageId")).orElseFail(JorlanError("email.forward: messageId is required"))
      to <- ZIO
        .fromOption(
          Some(strList(args, "to")).filter(_.nonEmpty),
        ).orElseFail(JorlanError("email.forward: to is required"))
      note = str(args, "note")
      msgId <- emailProvider.forwardMessage(ctx.actorId, EmailMessageId(id), to, note)
    } yield Json.Obj("messageId" -> Json.Str(msgId.value), "forwarded" -> Json.Bool(true))

  override def dashboardData(ctx: InvocationContext): IO[JorlanError, Json] =
    emailProvider
      .listMessages(ctx.actorId, 5, Some("is:unread"))
      .map { msgs =>
        Json.Obj(
          "unreadCount"    -> Json.Num(msgs.size),
          "recentMessages" -> Json.Arr(
            msgs.map(m =>
              Json.Obj(
                "from"    -> Json.Str(m.from),
                "subject" -> Json.Str(m.subject),
                "date"    -> Json.Str(m.date.toString),
              ),
            )*,
          ),
        )
      }
      .catchAll(e =>
        ZIO.succeed(Json.Obj("error" -> Json.Str(e.msg), "unreadCount" -> Json.Num(0), "recentMessages" -> Json.Arr())),
      )

  override def validate(): IO[JorlanError, SkillValidationResult] =
    emailProvider
      .listMessages(UserId.empty, 1, None)
      .as(SkillValidationResult(ok = true, message = "OK — email provider connection successful"))
      .catchAll(e => ZIO.succeed(SkillValidationResult(ok = false, message = s"Email provider error: ${e.msg}")))

}

object EmailSkill
