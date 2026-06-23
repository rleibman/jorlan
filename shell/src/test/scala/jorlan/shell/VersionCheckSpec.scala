/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell

import zio.test.*

object VersionCheckSpec extends ZIOSpecDefault {

  private val t0 = 1_700_000_000_000L
  private val t1 = 1_700_000_001_000L // 1 second later

  override def spec: Spec[Any, Nothing] =
    suite("VersionCheck")(
      suite("semver — both versions are X.Y.Z")(
        test("identical versions are compatible") {
          assertTrue(VersionCheck.check("1.2.3", t0, "1.2.3", t0).isRight)
        },
        test("client patch newer than server is compatible") {
          assertTrue(VersionCheck.check("1.2.5", t0, "1.2.3", t0).isRight)
        },
        test("client patch equal to server is compatible") {
          assertTrue(VersionCheck.check("1.2.3", t0, "1.2.3", t1).isRight)
        },
        test("client patch older than server is incompatible") {
          assertTrue(VersionCheck.check("1.2.2", t0, "1.2.3", t0).isLeft)
        },
        test("minor mismatch is incompatible even when client minor is newer") {
          assertTrue(VersionCheck.check("1.3.0", t0, "1.2.9", t0).isLeft)
        },
        test("minor mismatch is incompatible when client minor is older") {
          assertTrue(VersionCheck.check("1.1.9", t0, "1.2.0", t0).isLeft)
        },
        test("major mismatch is incompatible") {
          assertTrue(VersionCheck.check("2.0.0", t0, "1.0.0", t0).isLeft)
        },
        test("incompatible result contains a human-readable message") {
          val result = VersionCheck.check("1.2.2", t0, "1.2.3", t0)
          assertTrue(result.left.toOption.exists(_.nonEmpty))
        },
      ),
      suite("non-semver — at least one version is a hash or snapshot")(
        test("client newer build time is compatible") {
          assertTrue(VersionCheck.check("abc123-SNAPSHOT", t1, "def456-SNAPSHOT", t0).isRight)
        },
        test("client equal build time is compatible") {
          assertTrue(VersionCheck.check("abc123-SNAPSHOT", t0, "def456-SNAPSHOT", t0).isRight)
        },
        test("client older build time is incompatible") {
          assertTrue(VersionCheck.check("abc123-SNAPSHOT", t0, "def456-SNAPSHOT", t1).isLeft)
        },
        test("semver client vs hash server falls back to buildTime — client newer") {
          assertTrue(VersionCheck.check("1.2.3", t1, "abc123", t0).isRight)
        },
        test("semver client vs hash server falls back to buildTime — client older") {
          assertTrue(VersionCheck.check("1.2.3", t0, "abc123", t1).isLeft)
        },
        test("hash client vs semver server falls back to buildTime — client newer") {
          assertTrue(VersionCheck.check("abc123", t1, "1.2.3", t0).isRight)
        },
        test("incompatible result contains a human-readable message") {
          val result = VersionCheck.check("old-SNAPSHOT", t0, "new-SNAPSHOT", t1)
          assertTrue(result.left.toOption.exists(_.nonEmpty))
        },
      ),
    )

}
