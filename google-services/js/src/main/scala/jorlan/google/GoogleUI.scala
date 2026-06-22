/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
