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

import jorlan.*
import jorlan
.*
import zio.*

/** Evaluates a [[CapabilityRequest]] against the policy store and returns the first matching [[EvaluationResult]].
  *
  * Evaluation follows the priority order mandated by the design document:
  *   1. Explicit deny grant
  *   2. Direct (user-scoped) resource permission
  *   3. Role-derived resource permission
  *   4. Capability grant (approval mode governs whether human approval is required)
  *   5. Connector policy (stub)
  *   6. Skill policy (stub)
  *   7. Default deny
  */
trait CapabilityEvaluator {

  def evaluate(request: CapabilityRequest): IO[JorlanError, EvaluationResult]

}

object CapabilityEvaluator
