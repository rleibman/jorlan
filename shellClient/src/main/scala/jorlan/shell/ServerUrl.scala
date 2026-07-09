/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
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
