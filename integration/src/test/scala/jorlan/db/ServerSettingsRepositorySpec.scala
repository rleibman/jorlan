/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db

import jorlan.*
import jorlan.db.repository.*
import jorlan.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Integration tests for [[QuillServerSettingsRepository]].
  *
  * All tests run sequentially and share one container. Every test that calls `isServerInitialized` or
  * `getPersonality` explicitly sets the underlying key first so the assertion is never dependent on Flyway seed data or
  * the result of a previous test.
  *
  * "Key absent" behaviour (returns false / returns default) is already exercised by the unit tests in
  * InitServiceSpec; those use InMemoryRepositories and do not need a real DB.
  */
object ServerSettingsRepositorySpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ZLayer[Any, Any, ZIORepositories] = JorlanContainer.repositoryLayer

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("ServerSettingsRepository")(
      // ─── get / set primitives ────────────────────────────────────────────────
      test("get returns None for a key that has never been written") {
        for {
          repo <- ZIO.serviceWith[ZIORepositories](_.setting)
          v    <- repo.get("absolutely-does-not-exist-xyz")
        } yield assertTrue(v.isEmpty)
      },
      test("set then get round-trips a JSON boolean") {
        for {
          repo <- ZIO.serviceWith[ZIORepositories](_.setting)
          _    <- repo.set("bool-key", Json.Bool(true))
          v    <- repo.get("bool-key")
        } yield assertTrue(v.contains(Json.Bool(true)))
      },
      test("set then get round-trips a JSON string") {
        for {
          repo <- ZIO.serviceWith[ZIORepositories](_.setting)
          _    <- repo.set("str-key", Json.Str("hello"))
          v    <- repo.get("str-key")
        } yield assertTrue(v.contains(Json.Str("hello")))
      },
      test("set then get round-trips a JSON object") {
        val obj = Json.Obj("a" -> Json.Str("1"), "b" -> Json.Bool(false))
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.setting)
          _      <- repo.set("obj-key", obj)
          raw    <- repo.get("obj-key")
        } yield assertTrue(raw.isDefined) && {
          val decoded = raw.get
          assertTrue(
            decoded == Json.Obj("a" -> Json.Str("1"), "b" -> Json.Bool(false)),
          )
        }
      },
      test("second set on the same key overwrites the first value") {
        for {
          repo <- ZIO.serviceWith[ZIORepositories](_.setting)
          _    <- repo.set("overwrite-key", Json.Str("first"))
          _    <- repo.set("overwrite-key", Json.Str("second"))
          v    <- repo.get("overwrite-key")
        } yield assertTrue(v.contains(Json.Str("second")))
      },
      // ─── isServerInitialized ─────────────────────────────────────────────────
      test("isServerInitialized returns false when the stored value is Json.Bool(false)") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.setting)
          _      <- repo.set(ZIOServerSettingsRepository.InitializedKey, Json.Bool(false))
          result <- repo.isServerInitialized
        } yield assertTrue(!result)
      },
      test("isServerInitialized returns false when the value is not a JSON boolean") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.setting)
          _      <- repo.set(ZIOServerSettingsRepository.InitializedKey, Json.Str("yes"))
          result <- repo.isServerInitialized
        } yield assertTrue(!result)
      },
      test("isServerInitialized returns true when the flag is set to Json.Bool(true)") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.setting)
          _      <- repo.set(ZIOServerSettingsRepository.InitializedKey, Json.Bool(true))
          result <- repo.isServerInitialized
        } yield assertTrue(result)
      },
      // ─── getPersonality / setPersonality ─────────────────────────────────────
      test("setPersonality then getPersonality round-trips a custom personality") {
        val custom = Personality(
          name = "TestBot",
          formality = Formality.Casual,
          languages = List("en", "fr"),
          expertise = List("testing"),
          prompt = "You are a test assistant.",
        )
        for {
          repo      <- ZIO.serviceWith[ZIORepositories](_.setting)
          _         <- repo.setPersonality(custom)
          retrieved <- repo.getPersonality
        } yield assertTrue(retrieved == custom)
      },
      test("getPersonality returns Personality.default when setPersonality stores the default") {
        for {
          repo      <- ZIO.serviceWith[ZIORepositories](_.setting)
          _         <- repo.setPersonality(Personality.default)
          retrieved <- repo.getPersonality
        } yield assertTrue(retrieved == Personality.default)
      },
      test("getPersonality falls back to Personality.default when stored JSON is corrupt") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.setting)
          _      <- repo.set(ZIOServerSettingsRepository.PersonalityKey, Json.Str("not-a-personality-object"))
          result <- repo.getPersonality
        } yield assertTrue(result == Personality.default)
      },
    ) @@ TestAspect.sequential

}
