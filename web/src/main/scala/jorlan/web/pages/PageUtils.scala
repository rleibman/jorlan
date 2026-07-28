/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.pages

import japgolly.scalajs.react.Callback

import java.time.Instant
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

object PageUtils {

  def onError[A](handler: Option[String] => Callback): Try[A] => Callback = {
    case Failure(ex) => handler(Some(Option(ex.getMessage).getOrElse(ex.getClass.getName)))
    case Success(_)  => Callback.empty
  }

  /** Renders a UTC [[Instant]] in the browser's local timezone (`YYYY-MM-DD HH:MM:SS`). All timestamps are stored and
    * transported as UTC; the browser is the only place that knows the viewer's timezone.
    */
  def formatTimestamp(instant: Instant): String =
    formatIsoTimestamp(instant.toString)

  /** Renders an ISO 8601 timestamp string in the browser's local timezone. Falls back to the raw string (trimmed to
    * seconds) when unparseable.
    */
  def formatIsoTimestamp(iso: String): String = {
    val millis = js.Date.parse(iso)
    if (millis.isNaN) iso.take(19)
    else {
      val d = new js.Date(millis)
      def p2(n: Int): String = if (n < 10) s"0$n" else n.toString
      s"${d.getFullYear().toInt}-${p2(d.getMonth().toInt + 1)}-${p2(d.getDate().toInt)} " +
        s"${p2(d.getHours().toInt)}:${p2(d.getMinutes().toInt)}:${p2(d.getSeconds().toInt)}"
    }
  }

}
