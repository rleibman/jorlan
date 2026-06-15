/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import zio.json.{JsonDecoder, JsonEncoder}
import java.time.Instant

case class DriveFile(
  id:          DriveFileId,
  name:        String,
  mimeType:    String,
  sizeBytes:   Option[Long],
  modifiedAt:  Instant,
  parents:     List[String],
  webViewLink: Option[String],
) derives JsonEncoder, JsonDecoder
