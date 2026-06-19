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
import jorlan.connector.*
import jorlan.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ConnectorManagerSpec extends ZIOSpecDefault {

  private class TrackingConnectorSkill(
    val started: Ref[Boolean],
    val stopped: Ref[Boolean],
  ) extends ConnectorSkill {

    override val connectorType:       ConnectorType = ConnectorType.Telegram
    override val instanceId:          ConnectorInstanceId = ConnectorInstanceId(99L)
    override val descriptor:          SkillDescriptor = SkillDescriptor("tracking", SkillTier.BuiltIn, List.empty)
    override val sendMessageToolName: Option[String] = Some("tracking.send_message")

    override def invoke(
      ctx:  InvocationContext,
      tool: String,
      args: Json,
    ): IO[JorlanError, Json] = ZIO.fail(JorlanError("no tools"))

    override def start: IO[JorlanError, Unit] = started.set(true)
    override def stop:  IO[JorlanError, Unit] = stopped.set(true)

  }

  private class FailingConnectorSkill extends ConnectorSkill {

    override val connectorType:       ConnectorType = ConnectorType.Telegram
    override val instanceId:          ConnectorInstanceId = ConnectorInstanceId(98L)
    override val descriptor:          SkillDescriptor = SkillDescriptor("failing", SkillTier.BuiltIn, List.empty)
    override val sendMessageToolName: Option[String] = None

    override def invoke(
      ctx:  InvocationContext,
      tool: String,
      args: Json,
    ): IO[JorlanError, Json] = ZIO.fail(JorlanError("no tools"))

    override def start: IO[JorlanError, Unit] = ZIO.fail(JorlanError("start failed"))
    override def stop:  IO[JorlanError, Unit] = ZIO.fail(JorlanError("stop failed"))

  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ConnectorManager")(
      test("startAll calls start on all registered connectors") {
        for {
          s1 <- (Ref.make(false) <*> Ref.make(false)).map { case (a, b) => TrackingConnectorSkill(a, b) }
          s2 <- (Ref.make(false) <*> Ref.make(false)).map { case (a, b) => TrackingConnectorSkill(a, b) }
          mgr = ConnectorManager.fromSkills(List(s1, s2))
          _        <- mgr.startAll
          started1 <- s1.started.get
          started2 <- s2.started.get
        } yield assertTrue(started1, started2)
      },
      test("stopAll calls stop on all registered connectors") {
        for {
          s1 <- (Ref.make(false) <*> Ref.make(false)).map { case (a, b) => TrackingConnectorSkill(a, b) }
          s2 <- (Ref.make(false) <*> Ref.make(false)).map { case (a, b) => TrackingConnectorSkill(a, b) }
          mgr = ConnectorManager.fromSkills(List(s1, s2))
          _        <- mgr.stopAll
          stopped1 <- s1.stopped.get
          stopped2 <- s2.stopped.get
        } yield assertTrue(stopped1, stopped2)
      },
      test("startAll ignores individual connector start failures") {
        val mgr = ConnectorManager.fromSkills(List(new FailingConnectorSkill))
        mgr.startAll.as(assertCompletes)
      },
      test("stopAll ignores individual connector stop failures") {
        val mgr = ConnectorManager.fromSkills(List(new FailingConnectorSkill))
        mgr.stopAll.as(assertCompletes)
      },
      test("empty manager startAll is a no-op") {
        ConnectorManager.empty.startAll.as(assertCompletes)
      },
      test("empty manager stopAll is a no-op") {
        ConnectorManager.empty.stopAll.as(assertCompletes)
      },
    )

}
