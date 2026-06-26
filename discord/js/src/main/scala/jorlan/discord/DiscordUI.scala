/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.discord

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.connector.UnrecognizedIdentityPolicy
import zio.json.*

import scala.scalajs.js

object DiscordUI {

  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           js.Function1[String, Unit] = js.native

  }

  private def targetValue(e: Any): String =
    e.asInstanceOf[js.Dynamic].target.value.asInstanceOf[String]

  private def targetChecked(e: Any): Boolean =
    e.asInstanceOf[js.Dynamic].target.checked.asInstanceOf[Boolean]

  private val DiscordWidget = ScalaFnComponent
    .withHooks[SkillProps]
    .useStateBy(props => props.initialConfigStr.fromJson[DiscordConfig].getOrElse(DiscordConfig()))
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
              ^.placeholder := "Discord bot token",
              ^.onChange ==> { e => state.setState(cfg.copy(botToken = targetValue(e))) },
              ^.style := js.Dynamic.literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px"),
            ),
          ),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Allowed Guild IDs (comma-separated; leave empty to allow all guilds)"),
            <.input(
              ^.`type` := "text",
              ^.value  := cfg.allowedGuildIds.mkString(", "),
              ^.onChange ==> { e =>
                val ids = targetValue(e).split(",").map(_.trim).filter(_.nonEmpty).toSet
                state.setState(cfg.copy(allowedGuildIds = ids))
              },
              ^.style := js.Dynamic.literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px"),
            ),
          ),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Allowed User IDs (comma-separated; leave empty to allow all users)"),
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
              ^.checked := cfg.mentionOnly,
              ^.onChange ==> { e => state.setState(cfg.copy(mentionOnly = targetChecked(e))) },
            ),
            <.span("Mention Only (guild channels: only process @mentions of the bot)"),
          ),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Unrecognized User Policy"),
            <.select(
              ^.value := cfg.unrecognizedPolicy.toString,
              ^.onChange ==> { e =>
                val policy = targetValue(e) match {
                  case "CreateGuest" => UnrecognizedIdentityPolicy.CreateGuest
                  case "AllowAll"    => UnrecognizedIdentityPolicy.AllowAll
                  case _             => UnrecognizedIdentityPolicy.Reject
                }
                state.setState(cfg.copy(unrecognizedPolicy = policy))
              },
              ^.style := js.Dynamic.literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px"),
              <.option(^.value := "Reject", "Reject"),
              <.option(^.value := "CreateGuest", "Create Guest"),
              <.option(^.value := "AllowAll", "Allow All"),
            ),
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
              ^.onClick --> state.setState(props.initialConfigStr.fromJson[DiscordConfig].getOrElse(DiscordConfig())),
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
      "component" -> DiscordWidget.raw,
      "onUnload"  -> (() => ()),
    )
    js.Dynamic.global.registerRemoteSkill("jorlan-discord", payload)
  }

}
