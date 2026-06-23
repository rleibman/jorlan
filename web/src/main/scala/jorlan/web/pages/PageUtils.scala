/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.pages

import japgolly.scalajs.react.Callback

import scala.util.{Failure, Success, Try}

object PageUtils {

  def onError[A](handler: Option[String] => Callback): Try[A] => Callback = {
    case Failure(ex) => handler(Some(Option(ex.getMessage).getOrElse(ex.getClass.getName)))
    case Success(_)  => Callback.empty
  }

}
