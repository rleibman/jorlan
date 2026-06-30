/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.memory

import jorlan.*
import zio.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant

object MemoryAccessPolicySpec extends ZIOSpecDefault {

  private val now = Instant.parse("2026-01-01T00:00:00Z")
  private val uid1 = UserId(1L)
  private val uid2 = UserId(2L)
  private val aid1 = AgentId(10L)
  private val aid2 = AgentId(20L)
  private val policy = new MemoryAccessPolicyImpl()

  private def record(
    scope:  MemoryScope,
    userId: Option[UserId] = None,
    agent:  Option[AgentId] = None,
  ): MemoryRecord =
    MemoryRecord(
      id = MemoryRecordId(1L),
      scope = scope,
      userId = userId,
      workspaceId = None,
      agentId = agent,
      recordKey = "k",
      value = Json.Str("v"),
      ttl = None,
      createdAt = now,
      updatedAt = now,
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MemoryAccessPolicySpec")(
      test("User-scoped record is visible to matching userId") {
        val r = record(MemoryScope.User, userId = Some(uid1))
        policy.filter(uid1, aid1, List(r)).map(res => assert(res)(equalTo(List(r))))
      },
      test("User-scoped record is hidden for different userId") {
        val r = record(MemoryScope.User, userId = Some(uid1))
        policy.filter(uid2, aid1, List(r)).map(res => assert(res)(isEmpty))
      },
      test("Private-scoped record is visible when userId and agentId both match") {
        val r = record(MemoryScope.Private, userId = Some(uid1), agent = Some(aid1))
        policy.filter(uid1, aid1, List(r)).map(res => assert(res)(equalTo(List(r))))
      },
      test("Private-scoped record is hidden when agentId differs") {
        val r = record(MemoryScope.Private, userId = Some(uid1), agent = Some(aid1))
        policy.filter(uid1, aid2, List(r)).map(res => assert(res)(isEmpty))
      },
      test("Private-scoped record is hidden when userId differs") {
        val r = record(MemoryScope.Private, userId = Some(uid1), agent = Some(aid1))
        policy.filter(uid2, aid1, List(r)).map(res => assert(res)(isEmpty))
      },
      test("Shared-scoped record is visible to any user") {
        val r = record(MemoryScope.Shared)
        policy.filter(uid2, aid2, List(r)).map(res => assert(res)(equalTo(List(r))))
      },
      test("Workspace-scoped record is always denied") {
        val r = record(MemoryScope.Workspace)
        policy.filter(uid1, aid1, List(r)).map(res => assert(res)(isEmpty))
      },
      test("filter preserves order and handles mixed scopes") {
        val shared = record(MemoryScope.Shared)
        val user = record(MemoryScope.User, userId = Some(uid1))
        val hidden = record(MemoryScope.User, userId = Some(uid2))
        val private_ = record(MemoryScope.Private, userId = Some(uid1), agent = Some(aid1))
        policy
          .filter(uid1, aid1, List(shared, hidden, user, private_))
          .map(res => assert(res)(equalTo(List(shared, user, private_))))
      },
    )

}
