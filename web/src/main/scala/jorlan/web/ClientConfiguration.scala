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

import org.scalajs.dom.window

import scala.language.unsafeNulls

case class ClientConfiguration() {

  val host: String =
    s"${window.location.hostname}${
        if (window.location.port != "" && window.location.port != "80")
          s":${window.location.port}"
        else ""
      }"

}

object ClientConfiguration {

  val live: ClientConfiguration = ClientConfiguration()

}
