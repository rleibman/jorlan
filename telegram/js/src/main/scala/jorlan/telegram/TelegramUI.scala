/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.telegram

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import zio.json.*

import scala.scalajs.js

object TelegramUI {

  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           js.Function1[String, Unit] = js.native

  }

  private def targetValue(e: Any): String =
    e.asInstanceOf[js.Dynamic].target.value.asInstanceOf[String]

  private val TelegramWidget = ScalaFnComponent
    .withHooks[SkillProps]
    .useStateBy(props => props.initialConfigStr.fromJson[TelegramConfig].getOrElse(TelegramConfig()))
    .render {
      (
        props,
        state,
      ) =>
        val cfg = state.value
        <.div(
          ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "12px", maxWidth = "480px"),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Bot Token"),
            <.input(
              ^.`type`      := "password",
              ^.value       := cfg.botToken,
              ^.placeholder := "Telegram bot token",
              ^.onChange ==> { e => state.setState(cfg.copy(botToken = targetValue(e))) },
              ^.style := js.Dynamic.literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px"),
            ),
          ),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Allowed Chat IDs (comma-separated; leave empty to allow all)"),
            <.input(
              ^.`type` := "text",
              ^.value  := cfg.allowedChatIds.mkString(", "),
              ^.onChange ==> { e =>
                val ids = targetValue(e).split(",").map(_.trim).filter(_.nonEmpty).toSet
                state.setState(cfg.copy(allowedChatIds = ids))
              },
              ^.style := js.Dynamic.literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px"),
            ),
          ),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Allowed User IDs (comma-separated; leave empty to allow all)"),
            <.input(
              ^.`type` := "text",
              ^.value  := cfg.allowedUserIds.mkString(", "),
              ^.onChange ==> { e =>
                val ids = targetValue(e).split(",").map(_.trim).filter(_.nonEmpty).toSet
                state.setState(cfg.copy(allowedUserIds = ids))
              },
              ^.style := js.Dynamic.literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px"),
            ),
          ),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", alignItems = "center", gap = "8px"),
            <.input(
              ^.`type`  := "checkbox",
              ^.checked := cfg.useWebhook,
              ^.onChange ==> { _ => state.setState(cfg.copy(useWebhook = !cfg.useWebhook)) },
            ),
            <.span("Use Webhook (requires public HTTPS endpoint)"),
          ),
          <.div(
            ^.style := js.Dynamic.literal(display = "flex", gap = "8px"),
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
              ^.onClick --> state.setState(props.initialConfigStr.fromJson[TelegramConfig].getOrElse(TelegramConfig())),
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
      "component" -> TelegramWidget.raw,
      "onUnload"  -> (() => ()),
    )
    js.Dynamic.global.registerRemoteSkill("jorlan-telegram", payload)
  }

}
