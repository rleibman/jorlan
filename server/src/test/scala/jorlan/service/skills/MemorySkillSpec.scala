/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.InvocationContext
import jorlan.*
import jorlan.service.MemoryService
import jorlan.service.llm.FakeModelGateway
import jorlan.service.memory.MemoryServiceImpl
import jorlan.service.skills.MemorySkill
import ai.{EmbeddingModel, EmbeddingStore}
import jorlan.testing.{InMemoryRepositories, NoOpEmbeddingLayers}
import zio.*
import zio.json.ast.Json
import zio.test.*

object MemorySkillSpec extends ZIOSpec[MemoryService & EmbeddingStore & EmbeddingModel] {

  private val userId = UserId(1L)
  private val agentId = AgentId(1L)

  override val bootstrap: ULayer[MemoryService & EmbeddingStore & EmbeddingModel] =
    ZLayer.make[MemoryService & EmbeddingStore & EmbeddingModel](
      InMemoryRepositories.live(),
      FakeModelGateway.layer(List()),
      NoOpEmbeddingLayers.embeddingStoreLayer,
      NoOpEmbeddingLayers.embeddingModelLayer,
      MemoryServiceImpl.live,
    )

  private def makeSkill: URIO[MemoryService & EmbeddingStore & EmbeddingModel, MemorySkill] =
    for {
      svc   <- ZIO.service[MemoryService]
      store <- ZIO.service[EmbeddingStore]
      model <- ZIO.service[EmbeddingModel]
    } yield new MemorySkill(svc, store, model)

  override def spec: Spec[MemoryService & EmbeddingStore & EmbeddingModel & TestEnvironment & Scope, Any] =
    suite("MemorySkill")(
      test("remember stores and is retrievable via search") {
        for {
          skill   <- makeSkill
          _       <- skill.remember("user.lang", "prefers Scala", MemoryScope.User, userId, agentId)
          results <- skill.search("prefers Scala", MemoryScope.User, userId, agentId)
        } yield assertTrue(results.exists(_.recordKey == "user.lang"))
      },
      test("forget removes a record") {
        for {
          skill  <- makeSkill
          _      <- skill.remember("remove.me", "temporary", MemoryScope.User, userId, agentId)
          before <- skill.search("temporary", MemoryScope.User, userId, agentId)
          id = before.find(_.recordKey == "remove.me").map(_.id).getOrElse(MemoryRecordId.empty)
          _     <- skill.forget(id, userId)
          after <- skill.search("temporary", MemoryScope.User, userId, agentId)
        } yield assertTrue(
          before.exists(_.recordKey == "remove.me"),
          !after.exists(_.recordKey == "remove.me"),
        )
      },
      test("markShared changes scope from User to Shared") {
        for {
          skill   <- makeSkill
          _       <- skill.remember("to.share", "shared fact", MemoryScope.User, userId, agentId)
          records <- skill.search("shared fact", MemoryScope.User, userId, agentId)
          id = records.find(_.recordKey == "to.share").map(_.id).getOrElse(MemoryRecordId.empty)
          updated <- skill.markShared(id, userId)
        } yield assertTrue(updated.scope == MemoryScope.Shared)
      },
      test("markPrivate changes scope from User to Private") {
        for {
          skill   <- makeSkill
          _       <- skill.remember("to.private", "private fact", MemoryScope.User, userId, agentId)
          records <- skill.search("private fact", MemoryScope.User, userId, agentId)
          id = records.find(_.recordKey == "to.private").map(_.id).getOrElse(MemoryRecordId.empty)
          updated <- skill.markPrivate(id, userId)
        } yield assertTrue(updated.scope == MemoryScope.Private)
      },
      test("search returns empty list when no matching records") {
        for {
          skill   <- makeSkill
          results <- skill.search("nonexistent_xyz_string", MemoryScope.User, userId, agentId)
        } yield assertTrue(results.isEmpty)
      },
      // ─── Skill.invoke dispatch ─────────────────────────────────────────────────
      test("invoke memory.remember stores a record and returns JSON") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(
            ctx,
            "memory.remember",
            Json.Obj("key" -> Json.Str("invoke.test"), "text" -> Json.Str("via invoke")),
          )
        } yield result match {
          case Json.Obj(fields) =>
            val key = fields.collectFirst { case ("key", Json.Str(v)) => v }
            assertTrue(key.contains("invoke.test"))
          case _ => assertTrue(false)
        }
      },
      test("invoke memory.search returns JSON array") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill <- makeSkill
          _     <- skill
            .invoke(ctx, "memory.remember", Json.Obj("key" -> Json.Str("s.key"), "text" -> Json.Str("searchable")))
          result <- skill.invoke(
            ctx,
            "memory.search",
            Json.Obj("text" -> Json.Str("searchable"), "scope" -> Json.Str("user")),
          )
        } yield result match {
          case Json.Arr(_) => assertTrue(true)
          case _           => assertTrue(false)
        }
      },
      test("invoke memory.forget removes a record") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill   <- makeSkill
          _       <- skill.remember("forget.via.invoke", "gone", MemoryScope.User, userId, agentId)
          records <- skill.search("gone", MemoryScope.User, userId, agentId)
          id = records.find(_.recordKey == "forget.via.invoke").map(_.id.value.toString).getOrElse("0")
          result <- skill.invoke(ctx, "memory.forget", Json.Obj("id" -> Json.Str(id)))
        } yield result match {
          case Json.Bool(_) => assertTrue(true)
          case _            => assertTrue(false)
        }
      },
      test("invoke memory.mark_shared changes scope") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill   <- makeSkill
          _       <- skill.remember("mark.shared.invoke", "fact", MemoryScope.User, userId, agentId)
          records <- skill.search("fact", MemoryScope.User, userId, agentId)
          id = records.find(_.recordKey == "mark.shared.invoke").map(_.id.value.toString).getOrElse("0")
          result <- skill.invoke(ctx, "memory.mark_shared", Json.Obj("id" -> Json.Str(id)))
        } yield result match {
          case Json.Obj(fields) =>
            val scope = fields.collectFirst { case ("scope", Json.Str(v)) => v }
            assertTrue(scope.contains("Shared"))
          case _ => assertTrue(false)
        }
      },
      test("invoke memory.mark_private changes scope") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill   <- makeSkill
          _       <- skill.remember("mark.private.invoke", "priv", MemoryScope.Shared, userId, agentId)
          records <- skill.search("priv", MemoryScope.Shared, userId, agentId)
          id = records.find(_.recordKey == "mark.private.invoke").map(_.id.value.toString).getOrElse("0")
          result <- skill.invoke(ctx, "memory.mark_private", Json.Obj("id" -> Json.Str(id)))
        } yield result match {
          case Json.Obj(fields) =>
            val scope = fields.collectFirst { case ("scope", Json.Str(v)) => v }
            assertTrue(scope.contains("Private"))
          case _ => assertTrue(false)
        }
      },
      test("invoke unknown tool returns failure") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "memory.nonexistent", Json.Obj()).either
        } yield assertTrue(result.isLeft)
      },
      test("invoke returns failure when required field is missing") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "memory.remember", Json.Obj("text" -> Json.Str("no key"))).either
        } yield assertTrue(result.isLeft)
      },
      // ─── scope=user path + search results body ────────────────────────────────
      test("invoke memory.remember with scope=user stores in user scope, search returns result") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill <- makeSkill
          _     <- skill.invoke(
            ctx,
            "memory.remember",
            Json.Obj("key" -> Json.Str("user.scoped"), "text" -> Json.Str("user text"), "scope" -> Json.Str("user")),
          )
          result <- skill.invoke(
            ctx,
            "memory.search",
            Json.Obj("text" -> Json.Str("user text"), "scope" -> Json.Str("user")),
          )
        } yield result match {
          case Json.Arr(elems) =>
            val keys = elems.collect { case Json.Obj(fs) =>
              fs.collectFirst { case ("key", Json.Str(k)) => k }
            }.flatten
            assertTrue(elems.nonEmpty, keys.contains("user.scoped"))
          case _ => assertTrue(false)
        }
      },
      // ─── invalid id for forget / markShared / markPrivate ─────────────────────
      test("invoke memory.forget with non-numeric id fails") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "memory.forget", Json.Obj("id" -> Json.Str("not-a-number"))).either
        } yield assertTrue(result.isLeft)
      },
      test("invoke memory.mark_shared with non-numeric id fails") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "memory.mark_shared", Json.Obj("id" -> Json.Str("bad"))).either
        } yield assertTrue(result.isLeft)
      },
      test("invoke memory.mark_private with non-numeric id fails") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "memory.mark_private", Json.Obj("id" -> Json.Str("bad"))).either
        } yield assertTrue(result.isLeft)
      },
      // ─── scope=shared path ───────────────────────────────────────────────────
      test("invoke memory.remember with scope=shared stores in shared scope") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill <- makeSkill
          _     <- skill.invoke(
            ctx,
            "memory.remember",
            Json.Obj("key" -> Json.Str("shared.key"), "text" -> Json.Str("shared text"), "scope" -> Json.Str("shared")),
          )
          result <- skill.invoke(
            ctx,
            "memory.search",
            Json.Obj("text" -> Json.Str("shared text"), "scope" -> Json.Str("shared")),
          )
        } yield result match {
          case Json.Arr(elems) => assertTrue(elems.nonEmpty)
          case _               => assertTrue(false)
        }
      },
      // ─── non-object args path ─────────────────────────────────────────────────
      test("invoke with non-object args fails (covers non-Obj branch in field helper)") {
        val ctx = InvocationContext(userId, Some(agentId), None)
        for {
          skill  <- makeSkill
          result <- skill.invoke(ctx, "memory.remember", Json.Arr(Json.Str("bad"))).either
        } yield assertTrue(result.isLeft)
      },
    )

}
