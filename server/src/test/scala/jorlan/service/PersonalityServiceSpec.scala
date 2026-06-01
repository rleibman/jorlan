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
import jorlan.db.repository.ServerSettingsRepository
import jorlan.domain.*
import jorlan.testing.InMemoryRepositories
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PersonalityServiceSpec extends ZIOSpecDefault {

  private val layer: ULayer[PersonalityService & ServerSettingsRepository] = {
    val settingsLayer = InMemoryRepositories.InMemoryServerSettingsRepo.layer
    settingsLayer >+> PersonalityServiceImpl.live
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("PersonalityService")(
      suite("get and update round-trip")(
        test("get returns Personality.default when no personality key is stored") {
          for {
            p <- PersonalityService.get()
          } yield assertTrue(p == Personality.default)
        }.provide(layer),
        test("get returns stored personality after update") {
          val updated = Personality(
            name = "CustomBot",
            formality = Formality.Casual,
            languages = List("en", "es"),
            expertise = List("Scala"),
            prompt = "Be helpful.",
          )
          for {
            _     <- PersonalityService.update(updated)
            saved <- PersonalityService.get()
          } yield assertTrue(saved == updated)
        }.provide(layer),
        test("update persists to ServerSettingsRepository") {
          val p = Personality(
            name = "PersistBot",
            formality = Formality.Technical,
            languages = List("en"),
            expertise = Nil,
            prompt = "Technical assistant.",
          )
          for {
            _         <- PersonalityService.update(p)
            rawStored <- ServerSettingsRepository.get(ServerSettingsRepository.PersonalityKey)
          } yield assertTrue(rawStored.isDefined)
        }.provide(layer),
        test("update returns the saved personality") {
          val p = Personality.default.copy(name = "ReturnBot")
          for {
            returned <- PersonalityService.update(p)
          } yield assertTrue(returned == p)
        }.provide(layer),
        test("companion accessor get() delegates to implementation") {
          for {
            p <- PersonalityService.get()
          } yield assertTrue(p.prompt.nonEmpty)
        }.provide(layer),
        test("companion accessor update() delegates to implementation") {
          for {
            updated <- PersonalityService.update(Personality.default.copy(name = "Delegated"))
          } yield assertTrue(updated.name == "Delegated")
        }.provide(layer),
      ),
      suite("Personality.buildSystemPrompt")(
        test("professional formality produces formal instruction") {
          val p = Personality.default.copy(formality = Formality.Professional)
          assertTrue(Personality.buildSystemPrompt(p).contains("professional"))
        },
        test("casual formality produces casual instruction") {
          val p = Personality.default.copy(formality = Formality.Casual)
          assertTrue(Personality.buildSystemPrompt(p).contains("casual"))
        },
        test("academic formality produces academic instruction") {
          val p = Personality.default.copy(formality = Formality.Academic)
          assertTrue(Personality.buildSystemPrompt(p).contains("scholarly"))
        },
        test("technical formality produces technical instruction") {
          val p = Personality.default.copy(formality = Formality.Technical)
          assertTrue(Personality.buildSystemPrompt(p).contains("technical"))
        },
        test("language hint omitted for single 'en' language") {
          val p = Personality.default.copy(languages = List("en"))
          val prompt = Personality.buildSystemPrompt(p)
          assertTrue(!prompt.contains("languages:"))
        },
        test("language hint included when multiple languages set") {
          val p = Personality.default.copy(languages = List("en", "es", "fr"))
          val prompt = Personality.buildSystemPrompt(p)
          assertTrue(prompt.contains("en, es, fr"))
        },
        test("expertise hint included when expertise is non-empty") {
          val p = Personality.default.copy(expertise = List("Scala", "ZIO"))
          val prompt = Personality.buildSystemPrompt(p)
          assertTrue(prompt.contains("Scala, ZIO"))
        },
        test("expertise hint omitted when expertise is empty") {
          val p = Personality.default.copy(expertise = Nil)
          val prompt = Personality.buildSystemPrompt(p)
          assertTrue(!prompt.contains("expertise"))
        },
        test("free-form prompt appended at the end") {
          val p = Personality.default.copy(prompt = "CUSTOM_PROMPT_MARKER")
          assertTrue(Personality.buildSystemPrompt(p).endsWith("CUSTOM_PROMPT_MARKER"))
        },
      ),
      suite("PersonalityServiceImpl.live layer initialization")(
        test("loads pre-seeded valid personality from repo on startup") {
          val seeded = Personality(
            name = "Loaded",
            formality = Formality.Academic,
            languages = List("en"),
            expertise = Nil,
            prompt = "Scholarly assistant.",
          )
          ZIO.scoped {
            for {
              repo <- InMemoryRepositories.InMemoryServerSettingsRepo.make
              _    <- repo.set(
                ServerSettingsRepository.PersonalityKey,
                seeded.toJsonAST.getOrElse(Json.Null),
              )
              repoLayer = ZLayer.succeed(repo: ServerSettingsRepository)
              loaded <- PersonalityServiceImpl.live.build
                .map(_.get[PersonalityService])
                .provide(repoLayer ++ Scope.default)
              p <- loaded.get()
            } yield assertTrue(p.name == "Loaded", p.formality == Formality.Academic)
          }
        },
        test("falls back to Personality.default and logs warning on corrupt stored JSON") {
          ZIO.scoped {
            for {
              repo <- InMemoryRepositories.InMemoryServerSettingsRepo.make
              _    <- repo.set(ServerSettingsRepository.PersonalityKey, Json.Str("NOT_VALID_PERSONALITY"))
              repoLayer = ZLayer.succeed(repo: ServerSettingsRepository)
              loaded <- PersonalityServiceImpl.live.build
                .map(_.get[PersonalityService])
                .provide(repoLayer ++ Scope.default)
              p <- loaded.get()
            } yield assertTrue(p == Personality.default)
          }
        },
        test("Formality decoder accepts lowercase (case-insensitive for seed compatibility)") {
          assertTrue(
            summon[zio.json.JsonDecoder[Formality]].decodeJson("\"professional\"") == Right(Formality.Professional),
            summon[zio.json.JsonDecoder[Formality]].decodeJson("\"Professional\"") == Right(Formality.Professional),
            summon[zio.json.JsonDecoder[Formality]].decodeJson("\"CASUAL\"") == Right(Formality.Casual),
          )
        },
      ),
    )

}
