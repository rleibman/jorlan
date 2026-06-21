/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.lyrion

import zio.json.*

import scala.scalajs.js

object LyrionUI {

  // Match the string-based JS facade from the Host
  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           String => Unit = js.native

  }

  import japgolly.scalajs.react.*
  import japgolly.scalajs.react.vdom.html_<^.*

  import scala.scalajs.js

  private val LyrionWidget = ScalaFnComponent
    .withHooks[SkillProps]
    .useStateBy { props =>
      val parsed = props.initialConfigStr
        .fromJson[LyrionConfig]
        .getOrElse(LyrionConfig())
      parsed
    }
    .render(
      (
        props,
        s,
      ) => <.div("Lyrion Skill Configuration"),
    )

  ScalaComponent
    .builder[Unit]("LyrionWidget")
    .renderStatic(<.div("Lyrion Skill v1")) // TODO configuration goes here
    .build

  def main(args: Array[String]): Unit = {

    // Start background operation
    val pollingIntervalId = js.timers.setInterval(5000) {
      println("Lyrion plugin fetching latest temperatures...")
    }
    // Package component and send to host
    val payload = js.Dynamic.literal(
      "component" -> LyrionWidget.raw,
      // CRITICAL: The cleanup callback executed by the host on removal
      "onUnload" -> (() => {
        js.timers.clearInterval(pollingIntervalId)
        println("Lyrion plugin cleaned up background intervals successfully.")
      }),
    )
    js.Dynamic.global.registerRemoteSkill("lyrion-skill", payload)
  }

}
