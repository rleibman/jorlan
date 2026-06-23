/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.connector

import jorlan.*
import jorlan.connector.InboundMessage
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
    * resolve-or-create [[AgentSession]] for `(user, chatRef, channelType)` → dispatch to [[AgentRunner.processMessage]]
    * → event log.
    *
    * @param msg
    *   the normalized inbound message from any connector
    * @param unrecognizedPolicy
    *   how to handle a sender that does not resolve to a known Jorlan user (`Reject` drops and logs; `Quarantine` is
    *   log-only in Phase 11)
    * @param onResponse
    *   optional callback invoked with the fully-accumulated agent response text once the response is complete.
    *   Connectors that need to reply (e.g. Telegram) should supply this; it is called from a forked fiber.
    */
  def receive(
    msg:                InboundMessage,
    unrecognizedPolicy: UnrecognizedIdentityPolicy = UnrecognizedIdentityPolicy.Reject,
    onResponse:         Option[String => UIO[Unit]] = None,
  ): IO[JorlanError, Unit]

}

object MessageIngress {

  def receive(
    msg:                InboundMessage,
    unrecognizedPolicy: UnrecognizedIdentityPolicy = UnrecognizedIdentityPolicy.Reject,
    onResponse:         Option[String => UIO[Unit]] = None,
  ): ZIO[MessageIngress, JorlanError, Unit] =
    ZIO.serviceWithZIO[MessageIngress](_.receive(msg, unrecognizedPolicy, onResponse))

}
