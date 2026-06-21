/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web

import japgolly.scalajs.react.vdom.VdomElement
import zio.json.ast.Json

trait SkillConfiguration {

  def component:     VdomElement
  def configuration: Json

}
