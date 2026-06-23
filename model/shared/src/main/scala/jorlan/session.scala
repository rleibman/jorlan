/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan

import auth.{AuthenticatedSession, Session}

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
    id = UserId.server,
    displayName = "server",
    email = "server@jorlan.internal",
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
  )

  def guestSession(connectionId: ConnectionId): JorlanSession =
    AuthenticatedSession(
      Some(
        User(
          id = UserId.guest,
          email = "",
          displayName = "guest",
          createdAt = Instant.EPOCH,
          updatedAt = Instant.EPOCH,
        ),
      ),
      Some(connectionId),
    )

  val websocketSession: JorlanSession = AuthenticatedSession(
    Some(
      User(
        id = UserId.websocketUser,
        displayName = "websocket",
        email = "websocket@jorlan.internal",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
      ),
    ),
    Some(ConnectionId.unsafeRandom),
  )

  val serverSession: JorlanSession = AuthenticatedSession(Some(serverUser), None)

}
