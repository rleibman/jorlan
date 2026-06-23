/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.httpfetch

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import zio.json.*

import scala.scalajs.js

object HttpFetchUI {

  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           js.Function1[String, Unit] = js.native

  }

  private def targetValue(e: Any): String =
    e.asInstanceOf[js.Dynamic].target.value.asInstanceOf[String]

  private val HttpFetchWidget = ScalaFnComponent
    .withHooks[SkillProps]
    .useStateBy(props => props.initialConfigStr.fromJson[HttpFetchConfig].getOrElse(HttpFetchConfig()))
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
            <.span("Allowed Hosts (one per line; use * to allow all)"),
            <.textarea(
              ^.value := cfg.allowedHosts.mkString("\n"),
              ^.rows  := 4,
              ^.onChange ==> { e =>
                val lines = targetValue(e).split("\n").map(_.trim).filter(_.nonEmpty).toList
                state.setState(cfg.copy(allowedHosts = lines))
              },
              ^.style := js.Dynamic
                .literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px", fontFamily = "monospace"),
            ),
          ),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Max Response Bytes"),
            <.input(
              ^.`type` := "number",
              ^.value  := cfg.maxResponseBytes.toString,
              ^.onChange ==> { e =>
                state.setState(cfg.copy(maxResponseBytes = targetValue(e).toIntOption.getOrElse(524288)))
              },
              ^.style := js.Dynamic
                .literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px", width = "120px"),
            ),
          ),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Timeout (seconds)"),
            <.input(
              ^.`type` := "number",
              ^.value  := cfg.timeoutSeconds.toString,
              ^.onChange ==> { e =>
                state.setState(cfg.copy(timeoutSeconds = targetValue(e).toIntOption.getOrElse(30)))
              },
              ^.style := js.Dynamic
                .literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px", width = "80px"),
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
              ^.onClick --> state
                .setState(props.initialConfigStr.fromJson[HttpFetchConfig].getOrElse(HttpFetchConfig())),
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
      "component" -> HttpFetchWidget.raw,
      "onUnload"  -> (() => ()),
    )
    js.Dynamic.global.registerRemoteSkill("jorlan-http-fetch", payload)
  }

}
