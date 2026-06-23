/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service

import jorlan.*
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
