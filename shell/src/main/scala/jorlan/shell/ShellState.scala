/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell

import jorlan.domain.AgentSessionId
import zio.*

/** Holds ephemeral shell session state. Currently tracks which [[AgentSessionId]] is active so that plain-text messages
  * are routed to the right session.
  */
class ShellState private (sessionIdRef: Ref[Option[AgentSessionId]]) {

  def getSessionId: UIO[Option[AgentSessionId]] = sessionIdRef.get

  def setSessionId(id: AgentSessionId): UIO[Unit] = sessionIdRef.set(Some(id))

  def clearSessionId: UIO[Unit] = sessionIdRef.set(None)

}

object ShellState {

  val live: ULayer[ShellState] =
    ZLayer.fromZIO(Ref.make(Option.empty[AgentSessionId]).map(new ShellState(_)))

}
