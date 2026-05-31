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

import jorlan.domain.AgentSessionId
import zio.*
import zio.test.*
import zio.test.Assertion.*

object ShellStateSpec extends ZIOSpecDefault {

  private val sessionId = AgentSessionId(42L)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ShellState")(
      test("getSessionId returns None initially") {
        for {
          state  <- ZIO.service[ShellState]
          result <- state.getSessionId
        } yield assertTrue(result.isEmpty)
      }.provide(ShellState.live),
      test("setSessionId sets the active session") {
        for {
          state  <- ZIO.service[ShellState]
          _      <- state.setSessionId(sessionId)
          result <- state.getSessionId
        } yield assertTrue(result.contains(sessionId))
      }.provide(ShellState.live),
      test("clearSessionId removes the active session") {
        for {
          state  <- ZIO.service[ShellState]
          _      <- state.setSessionId(sessionId)
          _      <- state.clearSessionId
          result <- state.getSessionId
        } yield assertTrue(result.isEmpty)
      }.provide(ShellState.live),
    )

}
