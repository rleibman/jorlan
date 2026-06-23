/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.units

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.js

object UnitsUI {

  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           js.Function1[String, Unit] = js.native

  }

  private val UnitsWidget = ScalaFnComponent[SkillProps] { _ =>
    <.div(
      ^.style := js.Dynamic.literal(color = "#666", fontStyle = "italic"),
      "The Unit Conversion skill has no configurable settings.",
    )
  }

  def main(args: Array[String]): Unit = {
    val payload = js.Dynamic.literal(
      "component" -> UnitsWidget.raw,
      "onUnload"  -> (() => ()),
    )
    js.Dynamic.global.registerRemoteSkill("jorlan-unit-conversion", payload)
  }

}
