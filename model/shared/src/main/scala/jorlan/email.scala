/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

case class EmailAttachment(
  name:         String,
  mimeType:     String,
  sizeBytes:    Long,
  attachmentId: String,
) derives JsonCodec

case class EmailMessage(
  id:                EmailMessageId,
  threadId:          String,
  from:              String,
  to:                List[String],
  cc:                List[String],
  bcc:               List[String],
  subject:           String,
  body:              String,
  bodyHtml:          Option[String],
  date:              Instant,
  attachments:       List[EmailAttachment],
  labels:            List[String],
  pgpSigned:         Boolean,
  pgpSignatureValid: Option[Boolean],
) derives JsonCodec

case class EmailDraft(
  to:               List[String],
  cc:               List[String],
  bcc:              List[String],
  subject:          String,
  body:             String,
  replyToMessageId: Option[String],
  signWithPgp:      Boolean,
) derives JsonCodec

case class EmailFolderInfo(
  id:   String,
  name: String,
) derives JsonCodec
