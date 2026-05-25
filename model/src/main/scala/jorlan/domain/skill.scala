/*
 * Copyright (c) 2025 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.domain

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.Instant

enum SkillTier(
  val level:       Int,
  val description: String,
) {

  case BuiltIn extends SkillTier(0, "Built-in native Scala skill")
  case Plugin extends SkillTier(1, "Installed native Scala plugin skill")
  case Declarative extends SkillTier(2, "Declarative JSON skill")
  case Scripted extends SkillTier(3, "Scripted sandbox skill")
  case Imported extends SkillTier(4, "Imported MCP or external skill")
  case AgentDraft extends SkillTier(5, "Agent-authored draft skill")

}
object SkillTier {

  given JsonEncoder[SkillTier] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[SkillTier] =
    JsonDecoder[String].mapOrFail { s =>
      SkillTier.values.find(_.toString == s).toRight(s"Unknown SkillTier: $s")
    }

}

enum SkillStatus {

  case Draft, Validated, PermissionReviewed, SandboxTested, AwaitingApproval, Active, Deprecated, Revoked

}
object SkillStatus {

  given JsonEncoder[SkillStatus] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[SkillStatus] =
    JsonDecoder[String].mapOrFail { s =>
      SkillStatus.values.find(_.toString == s).toRight(s"Unknown SkillStatus: $s")
    }

}

enum ConnectorType {

  case Shell, GraphQL, Telegram, Slack, Email, WhatsApp, Sms, Lyrion, MarketData, WebSearch

}
object ConnectorType {

  given JsonEncoder[ConnectorType] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[ConnectorType] =
    JsonDecoder[String].mapOrFail { s =>
      ConnectorType.values.find(_.toString == s).toRight(s"Unknown ConnectorType: $s")
    }

}

// Raw JSON string containing the full skill manifest (input schema, output schema, capabilities, etc.)
case class Skill(
  id:             SkillId,
  name:           String,
  currentVersion: Option[String],
  tier:           SkillTier,
  createdAt:      Instant,
)
object Skill {

  given JsonEncoder[Skill] = DeriveJsonEncoder.gen[Skill]
  given JsonDecoder[Skill] = DeriveJsonDecoder.gen[Skill]

}

case class SkillVersion(
  id:           SkillVersionId,
  skillId:      SkillId,
  version:      String,
  manifestJson: String, // Full JSON manifest (input/output schema, required capabilities, etc.)
  status:       SkillStatus,
  createdAt:    Instant,
)
object SkillVersion {

  given JsonEncoder[SkillVersion] = DeriveJsonEncoder.gen[SkillVersion]
  given JsonDecoder[SkillVersion] = DeriveJsonDecoder.gen[SkillVersion]

}

case class ConnectorInstance(
  id:            ConnectorInstanceId,
  connectorType: ConnectorType,
  name:          String,
  configJson:    String,
  status:        String,
  createdAt:     Instant,
)
object ConnectorInstance {

  given JsonEncoder[ConnectorInstance] = DeriveJsonEncoder.gen[ConnectorInstance]
  given JsonDecoder[ConnectorInstance] = DeriveJsonDecoder.gen[ConnectorInstance]

}
