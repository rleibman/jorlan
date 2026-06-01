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
import jorlan.domain.*
import zio.*

/** Notifies a human that an approval request requires attention.
  *
  * Phase 8 provides only a stub implementation that writes an event log entry. Real delivery (Telegram, push
  * notifications) is wired in Phase 11.
  */
trait HumanApprovalNotifier {

  /** Notify that human approval is required for `request`.
    *
    * @param request
    *   The approval request that needs human attention.
    */
  def notifyApprovalRequired(request: ApprovalRequest): IO[JorlanError, Unit]

}

object HumanApprovalNotifier
