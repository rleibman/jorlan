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
import jorlan.db.repository.PermissionZIORepository
import jorlan.domain.*
import zio.*

/** Evaluates a [[CapabilityRequest]] by querying the permission store in the prescribed priority order:
  *
  *   1. Explicit deny grant
  *   2. Direct user-scoped [[Permission]] row
  *   3. Role-derived [[Permission]] row
  *   4. Active [[CapabilityGrant]] (non-denied)
  *   5. Connector policy (stub — always falls through)
  *   6. Skill policy (stub — always falls through)
  *   7. Default deny
  *
  * The capability name is split on the first `.` to derive `resource` and `action` for permission lookups (e.g.
  * `shell.sudo.execute` → resource = `"shell"`, action = `"sudo.execute"`). If the name contains no `.`, the full name
  * is used as the resource with action `"use"`. Permission rows must be written with the same convention.
  */
private class CapabilityEvaluatorImpl(repo: PermissionZIORepository) extends CapabilityEvaluator {

  override def evaluate(request: CapabilityRequest): IO[JorlanError, EvaluationResult] = {
    val (resource, action) = splitCapability(request.capability)
    for {
      // Fetch all three DB queries in parallel to minimise latency on the hot authorization path.
      grantsFib <- repo.getGrantsForCapability(request.requestorId, request.capability).fork
      directFib <- repo.hasDirectPermission(request.requestorId, resource, action).fork
      roleFib   <- repo.hasRolePermission(request.requestorId, resource, action).fork
      grants    <- grantsFib.join
      direct    <- directFib.join
      role      <- roleFib.join
    } yield {
      // Priority order: explicit deny first, default deny last.
      if (grants.exists(_.approvalMode == ApprovalMode.Denied)) {
        EvaluationResult.ExplicitDeny
      } else if (direct) {
        EvaluationResult.ResourcePermissionAllows
      } else if (role) {
        EvaluationResult.RolePermissionAllows
      } else {
        grants.find(_.approvalMode != ApprovalMode.Denied) match {
          case Some(grant) => EvaluationResult.CapabilityGrantAllows(grant)
          // Steps 5 & 6: connector / skill policy stubs — fall through.
          // Step 7: default deny.
          case None => EvaluationResult.DefaultDeny
        }
      }
    }
  }

  // Splits on the first '.' only; the tail (e.g. "sudo.execute") is the full action string.
  private def splitCapability(capability: CapabilityName): (String, String) = {
    val s = capability.value
    val dot = s.indexOf('.')
    if (dot < 0) (s, "use") else (s.substring(0, dot), s.substring(dot + 1))
  }

}

object CapabilityEvaluatorImpl {

  val live: URLayer[PermissionZIORepository, CapabilityEvaluator] =
    ZLayer.fromFunction((repo: PermissionZIORepository) => new CapabilityEvaluatorImpl(repo))

}
