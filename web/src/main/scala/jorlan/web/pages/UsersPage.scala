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
import jorlan.web.graphql.client.JorlanClient
import jorlan.web.graphql.client.JorlanClientDecoders._
import net.leibman.jorlan.muiMaterial.components.*

import scala.language.unsafeNulls
import scala.scalajs.js

object UsersPage {

  case class State(
    users:   List[JorlanClient.User.UserView],
    loading: Boolean,
    error:   Option[String],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(Nil, loading = true, error = None))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            JorlanWebApp
              .makeAdapter()
              .asyncCalibanCallWithAuth(
                JorlanClient.Queries.users()(JorlanClient.User.view),
              )
              .flatMap(users =>
                state.setState(State(users.getOrElse(Nil), loading = false, error = None)).asAsyncCallback,
              )
              .completeWith {
                case scala.util.Failure(ex) =>
                  state.setState(state.value.copy(loading = false, error = Some(ex.getMessage)))
                case _ => Callback.empty
              }
              .runNow()
          }
      }
      .render {
        (
          _,
          state,
        ) =>
          <.div(
            Box.set("sx", js.Dynamic.literal(display = "flex", alignItems = "center", mb = 2, gap = 2))(
              Typography.set("variant", "h5")("Users"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.set("severity", "error")(err)),
            if (state.value.loading) CircularProgress()
            else
              TableContainer()(
                Table()(
                  TableHead()(
                    TableRow()(
                      TableCell()("Display Name"),
                      TableCell()("Email"),
                      TableCell()("Active"),
                      TableCell()("Created"),
                    ),
                  ),
                  TableBody()(
                    state.value.users.map { user =>
                      TableRow.withKey(user.id.value.toString)(
                        TableCell()(user.displayName),
                        TableCell()(user.email.getOrElse("—")),
                        TableCell()(
                          Chip
                            .set("label", if (user.active) "Active" else "Inactive")
                            .set("color", if (user.active) "success" else "default")
                            .set("size", "small")(),
                        ),
                        TableCell()(user.createdAt.toString.take(19)),
                      )
                    }*,
                  ),
                ),
              ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
