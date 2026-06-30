/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.rss

import zio.json.JsonCodec

case class RssEntry(
  title:       String,
  link:        String,
  description: String,
  pubDate:     String,
  feedUrl:     String,
) derives JsonCodec
