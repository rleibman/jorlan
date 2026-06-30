/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Minimal facade for the `marked` npm library. */
@js.native
@JSImport("marked", "marked")
object Marked extends js.Object {

  def parse(src: String): String = js.native

}
