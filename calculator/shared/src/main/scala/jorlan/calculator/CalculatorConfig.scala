/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.calculator

import zio.json.JsonCodec

case class CalculatorConfig() derives JsonCodec
