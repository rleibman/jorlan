/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import jorlan.web.JorlanWebApp

import scala.scalajs.js.annotation.JSExport

/** Entry point delegating to [[JorlanWebApp.main]]. */
object JorlanApp {

  @JSExport
  def main(args: Array[String]): Unit = JorlanWebApp.main(args)

}
