/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.email

import zio.json.JsonCodec

case class EmailConfig() derives JsonCodec
