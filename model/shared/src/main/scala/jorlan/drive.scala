/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

case class DriveFile(
  id:          DriveFileId,
  name:        String,
  mimeType:    String,
  sizeBytes:   Option[Long],
  modifiedAt:  Instant,
  parents:     List[String],
  webViewLink: Option[String],
) derives JsonCodec
