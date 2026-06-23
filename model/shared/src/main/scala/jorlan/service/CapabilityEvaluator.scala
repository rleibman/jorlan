/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
import jorlan.*
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
