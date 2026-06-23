/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.init

import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

case class ServerStatus(
  initialized: Boolean,
  version:     String,
  buildTime:   Long,
  serverName:  String,
  uptimeMs:    Long,
) derives JsonCodec
