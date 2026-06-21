/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.httpfetch

import zio.json.JsonCodec

/** Configuration for the HTTP Fetch skill.
  *
  * @param allowedHosts
  *   List of allowed host patterns. Exact match or glob (`*.example.com`). Use `["*"]` to allow all. Empty = deny all.
  * @param maxResponseBytes
  *   Maximum response body size in bytes. Responses larger than this are truncated (default 512 KB).
  * @param timeoutSeconds
  *   Request timeout in seconds (default 30).
  */
case class HttpFetchConfig(
  allowedHosts:     List[String] = List.empty,
  maxResponseBytes: Int = 524288,
  timeoutSeconds:   Int = 30,
) derives JsonCodec
