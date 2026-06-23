/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.search

import zio.json.JsonCodec

case class SearchConfig(
  apiKey:     String = "",
  baseUrl:    String = "https://api.tavily.com",
  maxResults: Int = 5,
) derives JsonCodec
