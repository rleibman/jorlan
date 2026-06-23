/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import jorlan.Codecs.given
import just.semver.SemVer
import zio.json.ast.Json
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

/** Trust tier of a skill — lower level = more trusted, higher level = more restricted.
  *
  * Tiers determine the approval requirements and sandbox constraints when a skill is executed. Only an administrator
  * can promote a skill to a lower tier.
  */
enum SkillTier(
  val level:       Int,
  val description: String,
) derives JsonCodec {

  case BuiltIn extends SkillTier(0, "Built-in native Scala skill")
  case Plugin extends SkillTier(1, "Installed native Scala plugin skill")
  case Declarative extends SkillTier(2, "Declarative JSON skill")
  case Scripted extends SkillTier(3, "Scripted sandbox skill")
  case Imported extends SkillTier(4, "Imported MCP or external skill")
  case AgentDraft extends SkillTier(5, "Agent-authored draft skill")

}

/** Lifecycle gate for a [[SkillVersion]]. Versions must advance through all intermediate states before becoming
  * `Active`. `Revoked` is terminal.
  */
enum SkillStatus derives JsonCodec {

  case Draft, Validated, PermissionReviewed, SandboxTested, AwaitingApproval, Active, Deprecated, Revoked

}

/** The external system a [[ConnectorInstance]] bridges to. */
enum ConnectorType derives JsonCodec {

  case Shell, GraphQL, Telegram, Slack, Email, WhatsApp, Sms, Lyrion, MarketData, WebSearch

}

/** The canonical registry entry for a skill. Immutable versions are stored in [[SkillVersion]]; this record carries
  * only the identity and the pointer to the active version.
  *
  * @param currentVersion
  *   The semver string of the currently `Active` version, or `None` if no version is active yet.
  */
case class SkillRecord(
  id:             SkillId,
  name:           String,
  currentVersion: Option[SemVer],
  tier:           SkillTier,
  createdAt:      Instant,
) derives JsonCodec

/** An immutable snapshot of a [[SkillRecord]] at a specific semver version.
  *
  * @param manifestJson
  *   Full JSON skill manifest: input/output JSON schema, required capabilities, description, and any connector-specific
  *   execution metadata.
  */
case class SkillVersion(
  id:           SkillVersionId,
  skillId:      SkillId,
  version:      String,
  manifestJson: Json,
  status:       SkillStatus,
  createdAt:    Instant,
) derives JsonCodec

/** A configured and named instance of a connector to an external system.
  *
  * @param configJson
  *   Connector-specific configuration (credentials, endpoints, timeouts). Must be treated as sensitive — never logged
  *   or returned to unprivileged callers.
  * @param status
  *   Free-form runtime status string (e.g. `"connected"`, `"error: auth failed"`).
  */
case class ConnectorInstance(
  id:            ConnectorInstanceId,
  connectorType: ConnectorType,
  name:          String,
  configJson:    Json,
  status:        String,
  createdAt:     Instant,
) derives JsonCodec {

  override def toString: String = s"ConnectorInstance($id, $connectorType, $name, [redacted], $status, $createdAt)"

}
