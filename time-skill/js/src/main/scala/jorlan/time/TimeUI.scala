/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.time

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import zio.json.*

import scala.scalajs.js

object TimeUI {

  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           js.Function1[String, Unit] = js.native

  }

  private def targetValue(e: Any): String =
    e.asInstanceOf[js.Dynamic].target.value.asInstanceOf[String]

  private val TimeWidget = ScalaFnComponent
    .withHooks[SkillProps]
    .useStateBy(props => props.initialConfigStr.fromJson[TimeConfig].getOrElse(TimeConfig()))
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
            <.span("Default Timezone"),
            <.input(
              ^.`type`      := "text",
              ^.value       := cfg.defaultTimezone,
              ^.placeholder := "IANA timezone, e.g. America/New_York",
              ^.onChange ==> { e => state.setState(cfg.copy(defaultTimezone = targetValue(e))) },
              ^.style := js.Dynamic.literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px"),
            ),
            <.span(
              ^.style := js.Dynamic.literal(fontSize = "0.8em", color = "#666"),
              "Used when a tool call does not specify a timezone. Use IANA format, e.g. America/Chicago or Europe/Berlin.",
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
              ^.onClick --> state.setState(props.initialConfigStr.fromJson[TimeConfig].getOrElse(TimeConfig())),
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
      "component" -> TimeWidget.raw,
      "onUnload"  -> (() => ()),
    )
    js.Dynamic.global.registerRemoteSkill("jorlan-time", payload)
  }

}
