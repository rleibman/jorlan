/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.google

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.js

object GoogleUI {

  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           js.Function1[String, Unit] = js.native

  }

  private val GoogleWidget = ScalaFnComponent[SkillProps] { _ =>
    <.div(
      ^.style := js.Dynamic.literal(color = "#666", fontStyle = "italic"),
      "The Google Services skill has no configurable settings.",
    )
  }

  def main(args: Array[String]): Unit = {
    val payload = js.Dynamic.literal(
      "component" -> GoogleWidget.raw,
      "onUnload"  -> (() => ()),
    )
    js.Dynamic.global.registerRemoteSkill("jorlan-google-services", payload)
  }

}
