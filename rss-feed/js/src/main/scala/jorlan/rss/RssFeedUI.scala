/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.rss

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.js

object RssFeedUI {

  @js.native
  private trait SkillProps extends js.Object {

    val initialConfigStr: String = js.native
    val onSave:           js.Function1[String, Unit] = js.native

  }

  private val RssFeedWidget = ScalaFnComponent[SkillProps] { _ =>
    <.div(
      ^.style := js.Dynamic.literal(maxWidth = "480px", padding = "8px"),
      <.p("No configuration required."),
      <.p("Use the ", <.code("rss.save_feed"), ", ", <.code("rss.list_saved"), ", and ", <.code("rss.fetch"), " tools to manage and read your RSS/Atom feeds."),
    )
  }

  def main(args: Array[String]): Unit = {
    val payload = js.Dynamic.literal(
      "component" -> RssFeedWidget.raw,
      "onUnload"  -> (() => ()),
    )
    js.Dynamic.global.registerRemoteSkill("jorlan-rss-feed", payload)
  }

}
