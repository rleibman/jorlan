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

/** Metadata about a language model available to the runtime. */
case class ModelInfo(
  id:                ModelId,
  provider:          String,
  contextWindow:     Int,
  supportsStreaming: Boolean,
) derives JsonCodec

/** Current OAuth connection state for a provider (e.g. Google). */
case class OAuthStatus(
  connected: Boolean,
  expiresAt: Option[Instant],
) derives JsonCodec

/** A single tool exposed by a skill. */
case class SkillToolInfo(
  name:                 String,
  description:          String,
  requiredCapabilities: List[String],
  examplePrompts:       List[String],
) derives JsonCodec

/** A registered skill visible via the API. */
case class SkillInfo(
  name:           String,
  tier:           SkillTier,
  tools:          List[SkillToolInfo],
  enabled:        Boolean = true,
  keywords:       List[String] = List.empty,
  configKey:      Option[String] = None,
  configJsModule: Option[String] = None,
) derives JsonCodec
