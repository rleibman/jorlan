/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

import jorlan.db.repository.*
import jorlan.testing.InMemoryRepositories.InMemoryServerSettingsRepo
import zio.json.ast.Json
import zio.{URIO, ZIO}

object TestUtil {

  def withSettings(m: Map[String, Json]): URIO[ZIORepositories, ZIORepositories] = {
    case class Delegate(
      repos:            ZIORepositories,
      overrideSettings: ZIOServerSettingsRepository,
    ) extends ZIORepositories {
      export repos.{setting as _, *}
      def setting: ZIOServerSettingsRepository = overrideSettings
    }

    for {
      settingRepo <- InMemoryServerSettingsRepo.make(m)
      newRepos    <- ZIO.serviceWith[ZIORepositories](repos => Delegate(repos, settingRepo))
    } yield newRepos
  }

}
