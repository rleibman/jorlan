/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell

import zio.json.{JsonDecoder, JsonEncoder}

/** Opaque type wrapping a server base URL string (e.g. `"http://localhost:8080"`).
  *
  * Use [[ServerUrl.apply]] to construct, `.value` to get the underlying string for HTTP libraries.
  */
opaque type ServerUrl = String

object ServerUrl {

  def apply(value: String): ServerUrl = value

  extension (url: ServerUrl) {

    def value: String = url
    def toUri: java.net.URI = java.net.URI(url)

  }

  given JsonEncoder[ServerUrl] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[ServerUrl] = JsonDecoder[String].map(ServerUrl(_))

}
