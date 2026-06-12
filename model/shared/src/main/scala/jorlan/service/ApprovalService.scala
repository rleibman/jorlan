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
import jorlan.*
import zio.*

/** Orchestrates the full capability authorization pipeline and manages the approval request lifecycle.
  *
  * Typical call flow:
  *   1. Agent calls `authorize(request)` before every tool invocation.
  *   2. If `Allowed` → proceed.
  *   3. If `PendingApproval` → persist the request, notify the user, block the agent until a decision arrives.
  *   4. If `Denied` → reject the tool call and log the denial.
  *
  * The service also owns expiry enforcement: a scheduled job calls `expireStaleRequests()` periodically to mark
  * timed-out `Pending` requests as `Expired`.
  */
trait ApprovalService {

  /** Run the full classify → evaluate → policy pipeline and return an [[AuthorizationResult]].
    *
    * If the result is [[AuthorizationResult.PendingApproval]], a new [[ApprovalRequest]] has been persisted with status
    * `Pending` and the approving user has been notified (stub). Every outcome writes an audit event.
    */
  def authorize(request: CapabilityRequest): IO[JorlanError, AuthorizationResult]

  /** Record a human's decision on a pending [[ApprovalRequest]]. Writes an audit event. */
  def recordDecision(decision: ApprovalDecision): IO[JorlanError, ApprovalDecision]

  /** Mark all `Pending` [[ApprovalRequest]] rows whose `expiresAt` has passed as `Expired`. Should be called on a
    * periodic schedule.
    */
  def expireStaleRequests(): IO[JorlanError, Long]

}

object ApprovalService
