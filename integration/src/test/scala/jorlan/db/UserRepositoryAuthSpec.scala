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
import jorlan.db.TestFixtures.*
import jorlan.db.repository.*
import jorlan.domain.*
import zio.*
import zio.test.*

object UserRepositoryAuthSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("UserRepository — auth paths")(
      test("login returns user for correct credentials") {
        for {
          repo   <- ZIO.service[UserZIORepository]
          user   <- repo.upsert(User(UserId.empty, "LoginUser", "login@test.com", T0, T0))
          _      <- repo.changePassword(user.id, "correct-password")
          result <- repo.login("login@test.com", "correct-password")
        } yield assertTrue(
          result.isDefined,
          result.exists(_.id == user.id),
          result.exists(_.displayName == "LoginUser"),
        )
      },
      test("login returns None for wrong password") {
        for {
          repo   <- ZIO.service[UserZIORepository]
          user   <- repo.upsert(User(UserId.empty, "WrongPassUser", "wrongpass@test.com", T0, T0))
          _      <- repo.changePassword(user.id, "real-password")
          result <- repo.login("wrongpass@test.com", "not-the-password")
        } yield assertTrue(result.isEmpty)
      },
      test("login returns None for unknown email") {
        for {
          repo   <- ZIO.service[UserZIORepository]
          result <- repo.login("nobody@test.com", "any-password")
        } yield assertTrue(result.isEmpty)
      },
      test("changePassword allows login with new password and rejects old") {
        for {
          repo    <- ZIO.service[UserZIORepository]
          user    <- repo.upsert(User(UserId.empty, "ChangePwUser", "changepw@test.com", T0, T0))
          _       <- repo.changePassword(user.id, "old-password")
          _       <- repo.changePassword(user.id, "new-password")
          withNew <- repo.login("changepw@test.com", "new-password")
          withOld <- repo.login("changepw@test.com", "old-password")
        } yield assertTrue(
          withNew.isDefined,
          withOld.isEmpty,
        )
      },
      test("userByEmail finds user by exact email") {
        for {
          repo  <- ZIO.service[UserZIORepository]
          user  <- repo.upsert(User(UserId.empty, "EmailUser", "exact@test.com", T0, T0))
          found <- repo.userByEmail("exact@test.com")
          none  <- repo.userByEmail("exact")
        } yield assertTrue(
          found.isDefined,
          found.exists(_.id == user.id),
          none.isEmpty,
        )
      },
    ).provideLayerShared(JorlanContainer.repositoryLayer) @@ TestAspect.sequential

}
