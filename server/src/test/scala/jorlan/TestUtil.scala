/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
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
