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
import jorlan.domain.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ServerSettingsRepositorySpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ZLayer[Any, Any, ZIORepositories] = JorlanContainer.repositoryLayer

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("ServerSettingsRepository")(
      test("get returns None for an absent key") {
        for {
          repo <- ZIO.serviceWith[ZIORepositories](_.setting)
          v    <- repo.get("does-not-exist")
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
      test("set then get round-trips a JSON number") {
        for {
          repo <- ZIO.serviceWith[ZIORepositories](_.setting)
          _    <- repo.set("num-key", Json.Num(42))
          v    <- repo.get("num-key")
        } yield assertTrue(v.contains(Json.Num(42)))
      },
      test("set then get round-trips a JSON object") {
        val obj = Json.Obj("a" -> Json.Str("1"), "b" -> Json.Bool(false))
        for {
          repo <- ZIO.serviceWith[ZIORepositories](_.setting)
          _    <- repo.set("obj-key", obj)
          v    <- repo.get("obj-key")
        } yield assertTrue(v.contains(obj))
      },
      test("second set on the same key overwrites the first value") {
        for {
          repo <- ZIO.serviceWith[ZIORepositories](_.setting)
          _    <- repo.set("overwrite-key", Json.Str("first"))
          _    <- repo.set("overwrite-key", Json.Str("second"))
          v    <- repo.get("overwrite-key")
        } yield assertTrue(v.contains(Json.Str("second")))
      },
      test("isServerInitialized returns true when the flag is set to Json.Bool(true)") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.setting)
          _      <- repo.set(ZIOServerSettingsRepository.InitializedKey, Json.Bool(true))
          result <- repo.isServerInitialized
        } yield assertTrue(result)
      },
      test("isServerInitialized returns false when the key is absent") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.setting)
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
      test("getPersonality returns Personality.default when key is absent") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.setting)
          result <- repo.getPersonality
        } yield assertTrue(result == Personality.default)
      },
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
      test("getPersonality falls back to default when stored JSON is corrupt") {
        for {
          repo   <- ZIO.serviceWith[ZIORepositories](_.setting)
          _      <- repo.set(ZIOServerSettingsRepository.PersonalityKey, Json.Str("not-a-personality-object"))
          result <- repo.getPersonality
        } yield assertTrue(result == Personality.default)
      },
    ) @@ TestAspect.sequential

}
