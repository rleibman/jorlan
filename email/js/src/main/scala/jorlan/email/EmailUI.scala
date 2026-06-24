/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.email

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import zio.json.*

import scala.scalajs.js

object EmailUI {

  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           js.Function1[String, Unit] = js.native

  }

  private def targetValue(e: Any): String =
    e.asInstanceOf[js.Dynamic].target.value.asInstanceOf[String]

  private def targetChecked(e: Any): Boolean =
    e.asInstanceOf[js.Dynamic].target.checked.asInstanceOf[Boolean]

  private val fieldStyle = js.Dynamic.literal(
    padding = "6px",
    border = "1px solid #ccc",
    borderRadius = "4px",
    fontFamily = "inherit",
  )

  private val labelStyle = js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px")

  private val sectionStyle = js.Dynamic.literal(
    borderTop = "1px solid #e0e0e0",
    paddingTop = "12px",
    display = "flex",
    flexDirection = "column",
    gap = "10px",
  )

  private val EmailWidget = ScalaFnComponent
    .withHooks[SkillProps]
    .useStateBy(props => props.initialConfigStr.fromJson[EmailConfig].getOrElse(EmailConfig()))
    .render {
      (
        props,
        state,
      ) =>
        val cfg = state.value
        val isGmail = cfg.provider.toLowerCase == "gmail"
        <.div(
          ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "12px", maxWidth = "520px"),
          <.h4(^.style := js.Dynamic.literal(margin = "0 0 4px 0", color = "#444"), "Email Provider"),
          <.div(
            ^.style := sectionStyle,
            <.label(
              ^.style := labelStyle,
              <.span("Provider"),
              <.select(
                ^.value := cfg.provider,
                ^.onChange ==> { e => state.setState(cfg.copy(provider = targetValue(e))) },
                ^.style := fieldStyle,
                <.option(^.value := "imap", "IMAP / SMTP"),
                <.option(^.value := "gmail", "Gmail (Google OAuth)"),
              ),
            ),
          ),
          <.h4(^.style := js.Dynamic.literal(margin = "8px 0 4px 0", color = "#444"), "Credentials"),
          <.div(
            ^.style := sectionStyle,
            <.label(
              ^.style := labelStyle,
              <.span("Username / Email Address"),
              <.input(
                ^.`type`      := "email",
                ^.value       := cfg.username,
                ^.placeholder := "you@example.com",
                ^.onChange ==> { e => state.setState(cfg.copy(username = targetValue(e))) },
                ^.style := fieldStyle,
              ),
            ),
            <.label(
              ^.style := labelStyle,
              <.span("Password / App Password"),
              <.input(
                ^.`type`      := "password",
                ^.value       := cfg.password,
                ^.placeholder := "App password or account password",
                ^.onChange ==> { e => state.setState(cfg.copy(password = targetValue(e))) },
                ^.style := fieldStyle,
              ),
            ),
            <.label(
              ^.style := labelStyle,
              <.span("Sender Name (optional display name)"),
              <.input(
                ^.`type`      := "text",
                ^.value       := cfg.senderName,
                ^.placeholder := "Your Name",
                ^.onChange ==> { e => state.setState(cfg.copy(senderName = targetValue(e))) },
                ^.style := fieldStyle,
              ),
            ),
          ),
          if (isGmail)
            <.p(
              ^.style := js.Dynamic.literal(color = "#666", fontSize = "0.9em", margin = "4px 0"),
              "Gmail uses Google OAuth — connect your Google account in Settings → OAuth, then save.",
            )
          else
            <.div(
              <.h4(^.style := js.Dynamic.literal(margin = "8px 0 4px 0", color = "#444"), "IMAP (Incoming Mail)"),
              <.div(
                ^.style := sectionStyle,
                <.label(
                  ^.style := labelStyle,
                  <.span("IMAP Host"),
                  <.input(
                    ^.`type`      := "text",
                    ^.value       := cfg.imapHost,
                    ^.placeholder := "imap.example.com",
                    ^.onChange ==> { e => state.setState(cfg.copy(imapHost = targetValue(e))) },
                    ^.style := fieldStyle,
                  ),
                ),
                <.div(
                  ^.style := js.Dynamic.literal(display = "flex", gap = "12px", alignItems = "flex-end"),
                  <.label(
                    ^.style := labelStyle,
                    <.span("IMAP Port"),
                    <.input(
                      ^.`type` := "number",
                      ^.value  := cfg.imapPort.toString,
                      ^.onChange ==> { e =>
                        targetValue(e).toIntOption
                          .fold(Callback.empty)(p => state.setState(cfg.copy(imapPort = p)))
                      },
                      ^.style := js.Dynamic.literal(
                        padding = "6px",
                        border = "1px solid #ccc",
                        borderRadius = "4px",
                        width = "80px",
                      ),
                    ),
                  ),
                  <.label(
                    ^.style := js.Dynamic
                      .literal(display = "flex", alignItems = "center", gap = "6px", paddingBottom = "2px"),
                    <.input(
                      ^.`type`  := "checkbox",
                      ^.checked := cfg.imapSsl,
                      ^.onChange ==> { e => state.setState(cfg.copy(imapSsl = targetChecked(e))) },
                    ),
                    <.span("Use SSL/TLS"),
                  ),
                ),
                <.label(
                  ^.style := labelStyle,
                  <.span("Inbox Folder Name"),
                  <.input(
                    ^.`type`      := "text",
                    ^.value       := cfg.inboxFolder,
                    ^.placeholder := "INBOX",
                    ^.onChange ==> { e => state.setState(cfg.copy(inboxFolder = targetValue(e))) },
                    ^.style := js.Dynamic
                      .literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px", width = "160px"),
                  ),
                ),
                <.label(
                  ^.style := labelStyle,
                  <.span("Archive Folder Name"),
                  <.input(
                    ^.`type`      := "text",
                    ^.value       := cfg.archiveFolder,
                    ^.placeholder := "Archive",
                    ^.onChange ==> { e => state.setState(cfg.copy(archiveFolder = targetValue(e))) },
                    ^.style := js.Dynamic
                      .literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px", width = "160px"),
                  ),
                ),
              ),
              <.h4(^.style := js.Dynamic.literal(margin = "8px 0 4px 0", color = "#444"), "SMTP (Outgoing Mail)"),
              <.div(
                ^.style := sectionStyle,
                <.label(
                  ^.style := js.Dynamic.literal(display = "flex", alignItems = "center", gap = "6px"),
                  <.input(
                    ^.`type`  := "checkbox",
                    ^.checked := cfg.sslTrustAll,
                    ^.onChange ==> { e => state.setState(cfg.copy(sslTrustAll = targetChecked(e))) },
                  ),
                  <.span("Accept all SSL certificates (insecure — use only for self-signed certs)"),
                ),
                <.label(
                  ^.style := labelStyle,
                  <.span("SMTP Host"),
                  <.input(
                    ^.`type`      := "text",
                    ^.value       := cfg.smtpHost,
                    ^.placeholder := "smtp.example.com",
                    ^.onChange ==> { e => state.setState(cfg.copy(smtpHost = targetValue(e))) },
                    ^.style := fieldStyle,
                  ),
                ),
                <.div(
                  ^.style := js.Dynamic.literal(display = "flex", gap = "12px", alignItems = "flex-end"),
                  <.label(
                    ^.style := labelStyle,
                    <.span("SMTP Port"),
                    <.input(
                      ^.`type` := "number",
                      ^.value  := cfg.smtpPort.toString,
                      ^.onChange ==> { e =>
                        targetValue(e).toIntOption
                          .fold(Callback.empty)(p => state.setState(cfg.copy(smtpPort = p)))
                      },
                      ^.style := js.Dynamic.literal(
                        padding = "6px",
                        border = "1px solid #ccc",
                        borderRadius = "4px",
                        width = "80px",
                      ),
                    ),
                  ),
                  <.label(
                    ^.style := js.Dynamic
                      .literal(display = "flex", alignItems = "center", gap = "6px", paddingBottom = "2px"),
                    <.input(
                      ^.`type`  := "checkbox",
                      ^.checked := cfg.smtpTls,
                      ^.onChange ==> { e => state.setState(cfg.copy(smtpTls = targetChecked(e))) },
                    ),
                    <.span("Use STARTTLS"),
                  ),
                ),
              ),
            ),
          <.div(
            ^.style := js.Dynamic.literal(display = "flex", gap = "8px", marginTop = "8px"),
            <.button(
              ^.onClick --> Callback(props.onSave(state.value.toJson)),
              ^.style := js.Dynamic.literal(
                padding = "8px 16px",
                background = "#1976d2",
                color = "white",
                border = "none",
                borderRadius = "4px",
                cursor = "pointer",
              ),
              "Save",
            ),
            <.button(
              ^.onClick --> state.setState(props.initialConfigStr.fromJson[EmailConfig].getOrElse(EmailConfig())),
              ^.style := js.Dynamic.literal(
                padding = "8px 16px",
                background = "#e0e0e0",
                border = "none",
                borderRadius = "4px",
                cursor = "pointer",
              ),
              "Reset",
            ),
          ),
        )
    }

  def main(args: Array[String]): Unit = {
    val payload = js.Dynamic.literal(
      "component" -> EmailWidget.raw,
      "onUnload"  -> (() => ()),
    )
    js.Dynamic.global.registerRemoteSkill("jorlan-email", payload)
  }

}
