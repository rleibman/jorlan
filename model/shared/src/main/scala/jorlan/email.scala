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
