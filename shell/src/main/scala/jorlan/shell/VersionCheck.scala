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

/** Thrown when the shell version is incompatible with the server. Distinct from unexpected runtime errors. */
final case class VersionIncompatibleError(msg: String) extends RuntimeException(msg)

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

  private def parse(v: String): Option[SemVer] = {
    import scala.language.unsafeNulls
    semver.unapplySeq(v).collect { case List(maj, min, pat) =>
      SemVer(maj.toInt, min.toInt, pat.toInt)
    }
  }

  /** Returns `Right(())` if the client is compatible with the server, or `Left(humanReadableReason)` if not.
    *
    * When both versions are semver, compatibility requires identical major and minor versions, and client patch ≥
    * server patch (a newer client patch is acceptable). When either version is non-semver, compatibility requires
    * `clientBuildTime >= serverBuildTime` (equal build times are compatible; a newer client build is acceptable).
    *
    * @param clientVersion
    *   Version string from the client's [[jorlan.BuildInfo]].
    * @param clientBuildTime
    *   Epoch-millis from the client's [[jorlan.BuildInfo]]. Must be ≥ `serverBuildTime` in the non-semver fallback.
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
