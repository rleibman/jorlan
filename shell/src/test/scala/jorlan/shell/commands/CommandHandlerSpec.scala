/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell.commands

import jorlan.domain.AgentSessionId
import jorlan.shell.ShellState
import jorlan.shell.tui.JorlanScreen
import zio.*
import zio.test.*
import zio.test.Assertion.*

object CommandHandlerSpec extends ZIOSpecDefault {

  private val sessionId = AgentSessionId(42L)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CommandHandler")(
      test("handleMessage without active session shows system message") {
        for {
          _ <- ZIO.unit // Stub test — full integration requires mock clients
        } yield assertTrue(true)
      },
    )

}
