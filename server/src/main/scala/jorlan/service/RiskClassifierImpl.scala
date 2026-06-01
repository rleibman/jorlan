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

import jorlan.domain.{CapabilityName, RiskClass}
import zio.*

// TODO the RiskClassifierImpl does not need to be a ZIO type service, it has no dependencies and is pure,
//  so we could just make it a plain class with a method in the companion object. We can make it zio aware late if needed
/** Default [[RiskClassifier]] driven by capability-name prefix matching.
  *
  * Exact-name overrides are checked first. For all other names, the classifier walks from the most-specific dot-segment
  * prefix to the least-specific, returning the first match in `prefixMap`. This is O(d) where d is the dot-depth of the
  * name (at most 3–4 in practice) with O(1) Map lookups, compared to the O(n) list scan it replaces.
  *
  * Unknown capabilities that match no prefix default to `SecuritySensitive`, consistent with the deny-by-default model.
  */
class RiskClassifierImpl extends RiskClassifier {

  private val exactOverrides: Map[String, RiskClass] = Map(
    "shell.sudo.execute"      -> RiskClass.SecuritySensitive,
    "shell.interactive.start" -> RiskClass.Privileged,
    "capability.grant"        -> RiskClass.SecuritySensitive,
    "permission.grant"        -> RiskClass.Privileged,
    "permission.revoke"       -> RiskClass.Privileged,
  )

  private val prefixMap: Map[String, RiskClass] = Map(
    "shell.sudo"        -> RiskClass.SecuritySensitive,
    "shell.script"      -> RiskClass.ExternalEffect,
    "shell.binary"      -> RiskClass.ExternalEffect,
    "shell.interactive" -> RiskClass.Privileged,
    "shell"             -> RiskClass.ExternalEffect,
    "filesystem.delete" -> RiskClass.Destructive,
    "filesystem.remove" -> RiskClass.Destructive,
    "filesystem.write"  -> RiskClass.WorkspaceWrite,
    "filesystem.read"   -> RiskClass.ReadOnly,
    "filesystem.list"   -> RiskClass.ReadOnly,
    "filesystem"        -> RiskClass.WorkspaceWrite,
    "memory.forget"     -> RiskClass.Destructive,
    "memory.delete"     -> RiskClass.Destructive,
    "memory.search"     -> RiskClass.ReadOnly,
    "memory.read"       -> RiskClass.ReadOnly,
    "memory"            -> RiskClass.WorkspaceWrite,
    "network.post"      -> RiskClass.ExternalEffect,
    "network.send"      -> RiskClass.ExternalEffect,
    "network.external"  -> RiskClass.ExternalEffect,
    "network.read"      -> RiskClass.ExternalEffect,
    "network"           -> RiskClass.WorkspaceWrite,
    "role.assign"       -> RiskClass.Privileged,
    "role.remove"       -> RiskClass.Privileged,
    "role"              -> RiskClass.Privileged,
    "permission"        -> RiskClass.Privileged,
    "capability"        -> RiskClass.SecuritySensitive,
    "skill.install"     -> RiskClass.ExternalEffect,
    "skill.approve"     -> RiskClass.ExternalEffect,
    "skill"             -> RiskClass.WorkspaceWrite,
    "scheduler"         -> RiskClass.WorkspaceWrite,
    "agent"             -> RiskClass.WorkspaceWrite,
  )

  override def classify(capability: CapabilityName): RiskClass = {
    val name = capability.value
    exactOverrides.get(name) match {
      case Some(rc) => rc
      case None     =>
        val segments = name.split('.')
        Iterator
          .range(segments.length, 0, -1)
          .map(n => prefixMap.get(segments.take(n).mkString(".")))
          .collectFirst { case Some(rc) => rc }
          .getOrElse(RiskClass.SecuritySensitive)
    }
  }

}

object RiskClassifierImpl {

  val live: ULayer[RiskClassifier] = ZLayer.succeed(new RiskClassifierImpl)

}
