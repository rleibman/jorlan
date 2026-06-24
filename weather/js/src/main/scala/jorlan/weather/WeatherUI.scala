/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.weather

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import zio.json.*

import scala.scalajs.js

object WeatherUI {

  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           js.Function1[String, Unit] = js.native

  }

  private def targetValue(e: Any): String =
    e.asInstanceOf[js.Dynamic].target.value.asInstanceOf[String]

  private val WeatherWidget = ScalaFnComponent
    .withHooks[SkillProps]
    .useStateBy(props => props.initialConfigStr.fromJson[WeatherConfig].getOrElse(WeatherConfig()))
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
            <.span("API Key"),
            <.input(
              ^.`type`      := "password",
              ^.value       := cfg.apiKey,
              ^.placeholder := "OpenWeatherMap API key",
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
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Units (metric / imperial / standard)"),
            <.input(
              ^.`type` := "text",
              ^.value  := cfg.units,
              ^.onChange ==> { e => state.setState(cfg.copy(units = targetValue(e))) },
              ^.style := js.Dynamic
                .literal(padding = "6px", border = "1px solid #ccc", borderRadius = "4px", width = "140px"),
            ),
          ),
          <.label(
            ^.style := js.Dynamic.literal(display = "flex", flexDirection = "column", gap = "4px"),
            <.span("Default Location"),
            <.input(
              ^.`type`      := "text",
              ^.value       := cfg.defaultLocation,
              ^.placeholder := "e.g. New York or London,UK or Las Vegas,NV,US",
              ^.onChange ==> { e => state.setState(cfg.copy(defaultLocation = targetValue(e))) },
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
              ^.onClick --> state.setState(props.initialConfigStr.fromJson[WeatherConfig].getOrElse(WeatherConfig())),
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
      "component" -> WeatherWidget.raw,
      "onUnload"  -> (() => ()),
    )
    js.Dynamic.global.registerRemoteSkill("jorlan-weather", payload)
  }

}
