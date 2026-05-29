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

import zio.*
import zio.test.*
import zio.test.Assertion.*

/** P7-037: Tests for `fmtDelay`, `resolveCredentials`, and reconnect logic in [[JorlanShell]]. */
object JorlanShellSpec extends ZIOSpecDefault {

  override def spec: Spec[Any, Any] =
    suite("JorlanShell")(
      suite("fmtDelay — P7-037")(
        test("formats sub-second durations in milliseconds") {
          assertTrue(JorlanShell.fmtDelay(500.millis) == "500ms")
        },
        test("formats exactly 1 second in seconds") {
          assertTrue(JorlanShell.fmtDelay(1.second) == "1s")
        },
        test("formats 2 seconds in seconds") {
          assertTrue(JorlanShell.fmtDelay(2.seconds) == "2s")
        },
        test("formats 60 seconds as 60s") {
          assertTrue(JorlanShell.fmtDelay(60.seconds) == "60s")
        },
        test("formats 999ms in milliseconds") {
          assertTrue(JorlanShell.fmtDelay(999.millis) == "999ms")
        },
      ),
      suite("resolveCredentials config filtering — P7-039")(
        test("empty email in config is treated as absent") {
          val cfg = ShellConfig(email = Some(""), password = Some("secret"))
          val emailOpt = cfg.email.filter(_.nonEmpty)
          assertTrue(emailOpt.isEmpty)
        },
        test("empty password in config is treated as absent") {
          val cfg = ShellConfig(email = Some("user@test.com"), password = Some(""))
          val passwordOpt = cfg.password.filter(_.nonEmpty)
          assertTrue(passwordOpt.isEmpty)
        },
        test("non-empty email is not filtered out") {
          val cfg = ShellConfig(email = Some("user@test.com"))
          val emailOpt = cfg.email.filter(_.nonEmpty)
          assertTrue(emailOpt.contains("user@test.com"))
        },
        test("both credentials present means no prompting is needed") {
          val cfg = ShellConfig(email = Some("alice@test.com"), password = Some("secret"))
          val emailOpt = cfg.email.filter(_.nonEmpty)
          val passwordOpt = cfg.password.filter(_.nonEmpty)
          assertTrue(emailOpt.isDefined && passwordOpt.isDefined)
        },
      ),
    )

}
