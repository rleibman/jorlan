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

import jorlan.db.TestFixtures.{*, given}
import jorlan.db.repository.*
import jorlan.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ExternalCredentialRepositorySpec extends ZIOSpec[ZIORepositories] {

  override val bootstrap: ZLayer[Any, Any, ZIORepositories] = JorlanContainer.repositoryLayer

  override def spec: Spec[ZIORepositories & TestEnvironment & Scope, Any] =
    suite("ExternalCredentialRepository")(
      test("upsert and find a credential") {
        for {
          repo     <- ZIO.serviceWith[ZIORepositories](_.extCredential)
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          user     <- userRepo.upsert(User(UserId.empty, "CredUser", "creduser@test.local", T0, T0))
          payload = Json.Obj("access_token" -> Json.Str("tok123"), "token_type" -> Json.Str("Bearer"))
          _     <- repo.upsert(user.id, "google", payload, None, Some("gmail calendar"))
          found <- repo.find(user.id, "google")
        } yield assertTrue(
          found.isDefined,
          found.exists(_.provider == "google"),
          found.exists(_.userId == user.id),
        )
      },
      test("upsert updates existing credential") {
        for {
          repo     <- ZIO.serviceWith[ZIORepositories](_.extCredential)
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          user     <- userRepo.upsert(User(UserId.empty, "CredUser2", "creduser2@test.local", T0, T0))
          payload1 = Json.Obj("access_token" -> Json.Str("old-token"))
          payload2 = Json.Obj("access_token" -> Json.Str("new-token"))
          _     <- repo.upsert(user.id, "google", payload1, None, None)
          _     <- repo.upsert(user.id, "google", payload2, None, None)
          found <- repo.find(user.id, "google")
        } yield assertTrue(
          found.isDefined,
          found.exists(_.credentialData == payload2),
        )
      },
      test("delete removes credential") {
        for {
          repo     <- ZIO.serviceWith[ZIORepositories](_.extCredential)
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          user     <- userRepo.upsert(User(UserId.empty, "CredUser3", "creduser3@test.local", T0, T0))
          payload = Json.Obj("access_token" -> Json.Str("tok-to-delete"))
          _         <- repo.upsert(user.id, "google", payload, None, None)
          beforeDel <- repo.find(user.id, "google")
          _         <- repo.delete(user.id, "google")
          afterDel  <- repo.find(user.id, "google")
        } yield assertTrue(
          beforeDel.isDefined,
          afterDel.isEmpty,
        )
      },
      test("listByUser returns all credentials for a user") {
        for {
          repo     <- ZIO.serviceWith[ZIORepositories](_.extCredential)
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          user     <- userRepo.upsert(User(UserId.empty, "CredUser4", "creduser4@test.local", T0, T0))
          _        <- repo.upsert(user.id, "google", Json.Obj("t" -> Json.Str("g")), None, None)
          _        <- repo.upsert(user.id, "github", Json.Obj("t" -> Json.Str("gh")), None, None)
          list     <- repo.listByUser(user.id)
        } yield assertTrue(
          list.size == 2,
          list.map(_.provider).toSet == Set("google", "github"),
        )
      },
      test("listByUser returns empty list for user with no credentials") {
        for {
          repo     <- ZIO.serviceWith[ZIORepositories](_.extCredential)
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          user     <- userRepo.upsert(User(UserId.empty, "CredUser6", "creduser6@test.local", T0, T0))
          list     <- repo.listByUser(user.id)
        } yield assertTrue(list.isEmpty)
      },
      test("find returns None for non-existent credential") {
        for {
          repo     <- ZIO.serviceWith[ZIORepositories](_.extCredential)
          userRepo <- ZIO.serviceWith[ZIORepositories](_.user)
          user     <- userRepo.upsert(User(UserId.empty, "CredUser5", "creduser5@test.local", T0, T0))
          found    <- repo.find(user.id, "nonexistent")
        } yield assertTrue(found.isEmpty)
      },
    ) @@ TestAspect.sequential

}
