/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web

import japgolly.scalajs.react.vdom.VdomElement
import zio.json.ast.Json

trait SkillConfiguration {

  def component:     VdomElement
  def configuration: Json

}
