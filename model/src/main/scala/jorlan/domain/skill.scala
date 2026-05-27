/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.domain

import jorlan.Codecs.given
import just.semver.SemVer
import zio.json.ast.Json
import zio.json.{JsonDecoder, JsonEncoder}

import java.time.Instant

/** Trust tier of a skill — lower level = more trusted, higher level = more restricted.
  *
  * Tiers determine the approval requirements and sandbox constraints when a skill is executed. Only an administrator
  * can promote a skill to a lower tier.
  */
enum SkillTier(
  val level:       Int,
  val description: String,
) derives JsonEncoder, JsonDecoder {

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
enum SkillStatus derives JsonEncoder, JsonDecoder {

  case Draft, Validated, PermissionReviewed, SandboxTested, AwaitingApproval, Active, Deprecated, Revoked

}

/** The external system a [[ConnectorInstance]] bridges to. */
enum ConnectorType derives JsonEncoder, JsonDecoder {

  case Shell, GraphQL, Telegram, Slack, Email, WhatsApp, Sms, Lyrion, MarketData, WebSearch

}

/** The canonical registry entry for a skill. Immutable versions are stored in [[SkillVersion]]; this record carries
  * only the identity and the pointer to the active version.
  *
  * @param currentVersion
  *   The semver string of the currently `Active` version, or `None` if no version is active yet.
  */
case class Skill(
  id:             SkillId,
  name:           String,
  currentVersion: Option[SemVer],
  tier:           SkillTier,
  createdAt:      Instant,
) derives JsonEncoder, JsonDecoder

/** An immutable snapshot of a [[Skill]] at a specific semver version.
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
) derives JsonEncoder, JsonDecoder

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
) derives JsonEncoder, JsonDecoder {

  override def toString: String = s"ConnectorInstance($id, $connectorType, $name, [redacted], $status, $createdAt)"

}
