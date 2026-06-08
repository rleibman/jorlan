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

/** The connector-agnostic path every inbound message traverses.
  *
  * Implementations resolve identity, apply the [[UnrecognizedIdentityPolicy]], gate on capabilities, and dispatch to
  * [[AgentRunner]]. Every concrete [[ConnectorSkill]] hands its normalized [[InboundMessage]]s to this service.
  */
trait MessageIngress {

  /** Receive a normalized inbound message from a connector and route it to the agent.
    *
    * The implementation performs: identity resolution → unrecognized policy → capability gate (`agent.message`) →
    * resolve-or-create [[AgentSession]] for `(user, chatRef)` → dispatch to [[AgentRunner.processMessage]] → event log.
    */
  def receive(msg: InboundMessage): IO[JorlanError, Unit]

}

object MessageIngress {

  def receive(msg: InboundMessage): ZIO[MessageIngress, JorlanError, Unit] =
    ZIO.serviceWithZIO[MessageIngress](_.receive(msg))

}
