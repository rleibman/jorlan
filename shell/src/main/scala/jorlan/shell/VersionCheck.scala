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

import scala.util.matching.Regex

/** Pure version-compatibility logic for the shell ↔ server handshake.
  *
  * Two strategies are applied depending on whether both versions are proper semver:
  *
  *   - **Both semver** (`X.Y.Z`): major and minor must be identical; client patch must be ≥ server patch.
  *   - **Either is a non-semver string** (e.g. a git-commit hash or `…-SNAPSHOT`): fall back to comparing `buildTime`
  *     epoch-millis; client must be ≥ server.
  */
object VersionCheck {

  private val semver: Regex = """^(\d+)\.(\d+)\.(\d+)$""".r

  private case class SemVer(
    major: Int,
    minor: Int,
    patch: Int,
  )

  private def parse(v: String): Option[SemVer] =
    semver.unapplySeq(v).flatMap {
      case List(maj, min, pat) if maj != null && min != null && pat != null =>
        Some(SemVer(maj.toInt, min.toInt, pat.toInt))
      case _ => None
    }

  /** Returns `Right(())` if the client is compatible with the server, or `Left(humanReadableReason)` if not.
    *
    * @param clientVersion
    *   Version string from the client's [[jorlan.BuildInfo]].
    * @param clientBuildTime
    *   Epoch-millis from the client's [[jorlan.BuildInfo]].
    * @param serverVersion
    *   Version string returned by the server's `/api/status`.
    * @param serverBuildTime
    *   Epoch-millis returned by the server's `/api/status`.
    */
  def check(
    clientVersion:   String,
    clientBuildTime: Long,
    serverVersion:   String,
    serverBuildTime: Long,
  ): Either[String, Unit] =
    (parse(clientVersion), parse(serverVersion)) match {
      case (Some(c), Some(s)) =>
        if (c.major == s.major && c.minor == s.minor && c.patch >= s.patch) Right(())
        else
          Left(
            s"Incompatible server version. " +
              s"Client: $clientVersion, Server: $serverVersion. " +
              s"Major and minor versions must match; client patch must be ≥ server patch.",
          )
      case _ =>
        if (clientBuildTime >= serverBuildTime) Right(())
        else
          Left(
            s"Shell is older than the server. " +
              s"Please update the shell (client built: $clientBuildTime, server built: $serverBuildTime).",
          )
    }

}
