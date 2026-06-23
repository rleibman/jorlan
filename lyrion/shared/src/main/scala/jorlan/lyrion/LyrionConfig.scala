/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.lyrion

import zio.json.JsonCodec

/** Configuration for the Lyrion Music Server connection.
  *
  * @param serverUrl
  *   Base URL of the Lyrion Music Server (formerly Logitech Media Server / Squeezebox Server)
  * @param username
  *   Username for HTTP basic auth (currently unused; reserved for future support)
  * @param password
  *   Password for HTTP basic auth (currently unused; reserved for future support)
  */
case class LyrionConfig(
  serverUrl: String = "http://localhost:9000",
  username:  String = "",
  password:  String = "",
) derives JsonCodec
