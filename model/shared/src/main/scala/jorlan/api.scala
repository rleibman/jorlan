/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
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

/** A timestamped count bucket for time-series dashboard charts. */
case class DashboardTimeSeriesPoint(
  timestampMs: Long,
  count:       Int,
) derives JsonCodec

/** A named count for dashboard distribution charts. */
case class DashboardNamedCount(
  name:  String,
  count: Int,
) derives JsonCodec

/** Aggregated system metrics for the dashboard page. */
case class DashboardStats(
  activeSessionCount:     Int,
  eventCountToday:        Int,
  skillInvocationCount:   Int,
  schedulerSuccessRate:   Double,
  eventVolumeSeries:      List[DashboardTimeSeriesPoint],
  skillInvocationsByName: List[DashboardNamedCount],
  sessionStatusCounts:    List[DashboardNamedCount],
  jobOutcomeCounts:       List[DashboardNamedCount],
) derives JsonCodec

/** A single environment variable for an MCP server. */
case class McpEnvVarInfo(
  key:   String,
  value: String,
) derives JsonCodec

/** An MCP server configuration entry visible via the API. */
case class McpServerInfo(
  name:      String,
  transport: String,
  command:   Option[String] = None,
  args:      List[String] = List.empty,
  env:       List[McpEnvVarInfo] = List.empty,
  url:       Option[String] = None,
  enabled:   Boolean = true,
) derives JsonCodec

/** Result of a skill configuration validation check. */
case class SkillValidationResult(
  ok:      Boolean,
  message: String,
) derives JsonCodec

/** A registered skill visible via the API. */
case class SkillInfo(
  name:              String,
  tier:              SkillTier,
  tools:             List[SkillToolInfo],
  enabled:           Boolean = true,
  keywords:          List[String] = List.empty,
  configKey:         Option[String] = None,
  configJsModule:    Option[String] = None,
  dashboardJsModule: Option[String] = None,
  hasDashboardData:  Boolean = false,
) derives JsonCodec
