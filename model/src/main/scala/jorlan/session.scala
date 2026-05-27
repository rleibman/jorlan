/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import auth.{AuthenticatedSession, Session}
import jorlan.domain.{ConnectionId, User, UserId}

import java.time.Instant

/** ZIO environment type carrying the currently authenticated (or unauthenticated) user for a single request. */
type JorlanSession = Session[User, ConnectionId]

object JorlanSession {

  /** Hardcoded system/server user — seeded by migration V011 with id=1.
    *
    * Used as the session identity for background operations (scheduler, internal services) that execute outside the
    * context of an HTTP request. Never expose this to external callers.
    */
  val serverUser: User = User(
    id = UserId(1L),
    displayName = "server",
    email = Some("server@jorlan.internal"),
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
    active = true,
  )

  val serverSession: JorlanSession = AuthenticatedSession(Some(serverUser), None)

}
