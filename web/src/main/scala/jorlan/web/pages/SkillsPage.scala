/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web.pages

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import net.leibman.jorlan.muiMaterial.components.*

import scala.language.unsafeNulls
import scala.scalajs.js
import jorlan.graphql.client.JorlanClient
import jorlan.graphql.client.JorlanClientDecoders.given

/** Skill registry browser.
  *
  * TODO: implement fully once `listSkillVersions` (and related mutations) are added to the GraphQL API
  * (`JorlanAPI.scala` `Queries` / `Mutations`) and the generated client (`JorlanClient.scala`). Planned fields per row:
  *   - name, tier badge (Built-in / User / Agent), status (Active / Deprecated / Sandboxed), version string,
  *     `manifestJson` expandable detail
  * Planned mutations: promote, deprecate, sandbox (admin-only).
  */
object SkillsPage {

  val component =
    ScalaFnComponent
      .withHooks[User]
      .render { _ =>
        <.div(
          Box.set("sx", js.Dynamic.literal(display = "flex", alignItems = "center", mb = 2, gap = 2))(
            Typography.set("variant", "h5")("Skill Registry"),
          ),
          Alert.set("severity", "info")(
            // TODO: remove this alert and render the table below once listSkillVersions is added to the API
            "Skill registry is not yet queryable via GraphQL. Add `listSkillVersions` to Queries in JorlanAPI.scala, regenerate JorlanClient, then implement this page.",
          ),
          // TODO: render table once listSkillVersions query is available:
          //   TableContainer > Table > TableHead (Name | Tier | Status | Version | "") +
          //   TableBody rows from JorlanClient.Queries.listSkillVersions(...)
          //   Each row: name, Chip(tier), Chip(status), version, expand button for manifestJson
        )
      }

  def apply(user: User): VdomElement = component(user)

}
