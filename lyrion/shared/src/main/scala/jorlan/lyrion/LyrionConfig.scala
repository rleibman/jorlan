/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
