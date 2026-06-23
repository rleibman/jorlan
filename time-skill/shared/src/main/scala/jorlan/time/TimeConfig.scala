/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.time

import zio.json.JsonCodec

case class TimeConfig(defaultTimezone: String = "UTC") derives JsonCodec
