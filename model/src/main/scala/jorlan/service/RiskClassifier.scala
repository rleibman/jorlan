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

/** Classifies a capability name into a [[RiskClass]] (0–5).
  *
  * Classification is pure and synchronous. Uses a combination of prefix matching and explicit name overrides. The risk
  * table is derived from the design document; exact overrides are checked first, then the longest matching dot-prefix.
  * Unknown capabilities default to `SecuritySensitive` (deny-by-default model).
  */
object RiskClassifier {

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

  def classify(capability: CapabilityName): RiskClass = {
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
