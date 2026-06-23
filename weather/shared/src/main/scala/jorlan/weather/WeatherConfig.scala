/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.weather

import zio.json.JsonCodec

case class WeatherConfig(
  apiKey:          String = "",
  baseUrl:         String = "https://api.openweathermap.org/data/2.5",
  units:           String = "metric",
  defaultLocation: String = "New York",
) derives JsonCodec
