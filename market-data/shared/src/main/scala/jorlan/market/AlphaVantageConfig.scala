/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.market

import zio.json.JsonCodec

case class AlphaVantageConfig(
  apiKey:          String = "",
  baseUrl:         String = "https://www.alphavantage.co/query",
  preferredStocks: List[String] = List.empty,
) derives JsonCodec
