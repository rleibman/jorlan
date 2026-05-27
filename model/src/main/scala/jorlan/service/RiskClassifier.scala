/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.domain.{CapabilityName, RiskClass}
import zio.*

/** Classifies a capability name into a [[RiskClass]] (0–5).
  *
  * Classification is pure and synchronous. Implementations use a combination of prefix matching and explicit name
  * overrides. The default implementation in [[jorlan.service.RiskClassifierImpl]] embeds the risk table from the design
  * document; alternative implementations can extend it via configuration.
  */
trait RiskClassifier {

  def classify(capability: CapabilityName): RiskClass

}

object RiskClassifier {

  def classify(capability: CapabilityName): URIO[RiskClassifier, RiskClass] =
    ZIO.serviceWith[RiskClassifier](_.classify(capability))

}
