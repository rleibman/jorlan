/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.init

import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

case class InitRequest(
  token:         String,
  serverName:    String,
  adminEmail:    String,
  adminName:     String,
  adminPassword: String,
) derives JsonCodec
