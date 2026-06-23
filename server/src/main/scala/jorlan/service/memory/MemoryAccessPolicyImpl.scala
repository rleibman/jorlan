/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.memory

import jorlan.{AgentId, MemoryRecord, MemoryScope, UserId}
import jorlan.*
import jorlan.service.MemoryAccessPolicy
import zio.*

/** Default [[MemoryAccessPolicy]].
  *
  * Visibility rules:
  *   - `User` — record's `userId` must match the requesting user.
  *   - `Private` — record's `userId` AND `agentId` must both match.
  *   - `Shared` — visible to all users (no restriction).
  *   - `Workspace` — visible to all users in the workspace (no user restriction enforced here; workspace membership is
  *     a higher-level concern).
  */
class MemoryAccessPolicyImpl extends MemoryAccessPolicy {

  override def filter(
    requestingUserId: UserId,
    agentId:          AgentId,
    records:          List[MemoryRecord],
  ): UIO[List[MemoryRecord]] =
    ZIO.succeed {
      records.filter { r =>
        r.scope match {
          case MemoryScope.User      => r.userId.contains(requestingUserId)
          case MemoryScope.Private   => r.userId.contains(requestingUserId) && r.agentId.contains(agentId)
          case MemoryScope.Shared    => true
          case MemoryScope.Workspace => false // deny-by-default until workspace membership is enforced (P9-022)
        }
      }
    }

}

object MemoryAccessPolicyImpl {

  val live: ULayer[MemoryAccessPolicy] = ZLayer.succeed(MemoryAccessPolicyImpl())

}
