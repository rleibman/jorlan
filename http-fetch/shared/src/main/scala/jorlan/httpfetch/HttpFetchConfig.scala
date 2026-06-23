/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
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
