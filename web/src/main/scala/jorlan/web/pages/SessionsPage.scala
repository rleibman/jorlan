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
import jorlan.domain.*
import jorlan.web.JorlanWebApp
import jorlan.web.components.MuiButton
import jorlan.web.graphql.ScalaJSClientAdapter
import jorlan.web.graphql.client.JorlanClient
import jorlan.web.graphql.client.JorlanClientDecoders._
import net.leibman.jorlan.muiMaterial.components.*
import sttp.model.Uri

import scala.language.unsafeNulls
import scala.scalajs.js

object SessionsPage {

  case class State(
    sessions: List[JorlanClient.AgentSession.AgentSessionView],
    loading:  Boolean,
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(Nil, loading = true))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            val adapter = ScalaJSClientAdapter(
              Uri
                .parse(
                  s"${if (org.scalajs.dom.window.location.protocol == "https:") "https" else "http"}://${org.scalajs.dom.window.location.host}/api/jorlan",
                )
                .fold(_ => throw new Exception("bad uri"), identity),
              JorlanWebApp.connectionId,
            )
            adapter
              .asyncCalibanCallWithAuth(
                JorlanClient.Queries.listSessions()(JorlanClient.AgentSession.view),
              )
              .flatMap(sessions => state.setState(State(sessions.getOrElse(Nil), loading = false)).asAsyncCallback)
              .completeWith(_ => Callback.empty)
              .runNow()
          }
      }
      .render {
        (
          _,
          state,
        ) =>
          <.div(
            Typography.set("variant", "h5").set("sx", js.Dynamic.literal(mb = 2))("Sessions"),
            if (state.value.loading)
              CircularProgress()
            else
              TableContainer()(
                Table()(
                  TableHead()(
                    TableRow()(
                      TableCell()("ID"),
                      TableCell()("Status"),
                      TableCell()("Model"),
                      TableCell()("Created"),
                      TableCell()("Actions"),
                    ),
                  ),
                  TableBody()(
                    state.value.sessions.map { session =>
                      TableRow.withKey(session.id.value.toString)(
                        TableCell()(session.id.value.toString),
                        TableCell()(
                          Chip.set("label", session.status.toString).set("size", "small")(),
                        ),
                        TableCell()(session.modelId.map(_.value).getOrElse("—")),
                        TableCell()(session.createdAt.toString.take(19)),
                        TableCell()(
                          if (session.status == SessionStatus.Active)
                            MuiButton
                              .variant("outlined")
                              .color("error")
                              .size("small")
                              .onClick(() => Callback.empty.runNow())("Terminate")
                          else EmptyVdom,
                        ),
                      )
                    }*,
                  ),
                ),
              ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
