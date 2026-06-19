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
import jorlan.connector.ConnectorSkill
import jorlan.*
import jorlan.service.skills.SkillRegistry
import zio.*

/** Minimal connector lifecycle manager — Phase 11 predecessor to the full [[SkillRegistry]] (Phase 12).
  *
  * Holds the registered [[ConnectorSkill]]s and provides `startAll`/`stopAll` to drive their ingress lifecycles. Boot
  * wiring in [[jorlan.Jorlan]] forks `startAll` as a daemon, mirroring the `TriggerEngine` pattern.
  */
trait ConnectorManager {

  /** Start ingress for all registered connectors in parallel. Intended to be forked as a daemon. */
  def startAll: UIO[Unit]

  /** Stop ingress for all registered connectors in parallel and release resources. */
  def stopAll: UIO[Unit]

  /** All registered connector skills (for wiring into [[SkillRegistry]]). */
  def connectors: List[ConnectorSkill]

  /** Connector skills indexed by [[ConnectorType]] for O(1) lookup. */
  def connectorMap: Map[ConnectorType, ConnectorSkill]

}

/** [[ConnectorManager]] backed by a fixed set of [[ConnectorSkill]]s. */
class ConnectorManagerImpl(val connectors: List[ConnectorSkill]) extends ConnectorManager {

  override val connectorMap: Map[ConnectorType, ConnectorSkill] =
    connectors.map(c => c.connectorType -> c).toMap

  override def startAll: UIO[Unit] =
    ZIO.foreachParDiscard(connectors) { c =>
      c.start
        .tapError(e => ZIO.logError(s"[connector:${c.connectorType}] Failed to start: ${e.msg}"))
        .ignore
    }

  override def stopAll: UIO[Unit] =
    ZIO.foreachParDiscard(connectors) { c =>
      c.stop
        .tapError(e => ZIO.logWarning(s"[connector:${c.connectorType}] Error during stop: ${e.msg}"))
        .ignore
    }

}

object ConnectorManager {

  /** Build a [[ConnectorManager]] from an explicit list of [[ConnectorSkill]] instances.
    *
    * Each connector's `start` will be called by `startAll`; Phase 12 will replace this with registry-driven wiring.
    */
  def fromSkills(connectors: List[ConnectorSkill]): ConnectorManager =
    ConnectorManagerImpl(connectors)

  /** A no-op [[ConnectorManager]] with no registered connectors.
    *
    * Use in tests and in non-connector configurations where no connectors are wired.
    */
  val empty: ConnectorManager = ConnectorManagerImpl(List.empty)

}
