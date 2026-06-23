/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.market

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import zio.json.*

import scala.scalajs.js

object MarketUI {

  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           js.Function1[String, Unit] = js.native

  }

  private def targetValue(e: Any): String =
    e.asInstanceOf[js.Dynamic].target.value.asInstanceOf[String]

  private val MarketWidget = ScalaFnComponent
    .withHooks[SkillProps]
    .useStateBy(props => props.initialConfigStr.fromJson[AlphaVantageConfig].getOrElse(AlphaVantageConfig()))
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
            <.span("Alpha Vantage API Key"),
            <.input(
              ^.`type`      := "password",
              ^.value       := cfg.apiKey,
              ^.placeholder := "Alpha Vantage API key",
              ^.onChange ==> { e => state.setState(cfg.copy(apiKey = targetValue(e))) },
              ^.style := js.Dynamic.literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px"),
            ),
          ),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Base URL"),
            <.input(
              ^.`type` := "text",
              ^.value  := cfg.baseUrl,
              ^.onChange ==> { e => state.setState(cfg.copy(baseUrl = targetValue(e))) },
              ^.style := js.Dynamic.literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px"),
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
                .setState(props.initialConfigStr.fromJson[AlphaVantageConfig].getOrElse(AlphaVantageConfig())),
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
      "component" -> MarketWidget.raw,
      "onUnload"  -> (() => ()),
    )
    js.Dynamic.global.registerRemoteSkill("jorlan-market-data", payload)
  }

}
