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
import jorlan.web.AsyncCallbackRepositories
import jorlan.web.components.MuiButton
import net.leibman.jorlan.muiMaterial.components.*

import scala.language.unsafeNulls
import scala.scalajs.js

/** OAuth connection management page.
  *
  * Shows which external providers (e.g. Google) the authenticated user has linked, and allows linking or revoking
  * access via the `startOAuth` / `revokeOAuth` GraphQL mutations.
  */
object OAuthManagementPage {

  private val SupportedProviders: scala.List[String] = scala.List("google")

  case class State(
    connectedProviders: scala.List[String],
    loading:            Boolean,
    error:              Option[String],
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
            AsyncCallbackRepositories.extCredential
              .listOAuthProviders()
              .flatMap { result =>
                state
                  .setState(
                    state.value.copy(connectedProviders = result, loading = false),
                  ).asAsyncCallback
              }
              .completeWith {
                case scala.util.Failure(err) =>
                  state.setState(state.value.copy(loading = false, error = Some(err.getMessage)))
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
          def handleConnect(provider: String): Callback =
            Callback {
              AsyncCallbackRepositories.extCredential
                .startOAuth(provider)
                .completeWith {
                  case scala.util.Success(Some(authUrl)) =>
                    Callback { org.scalajs.dom.window.location.href = authUrl }
                  case scala.util.Success(None) =>
                    state.setState(state.value.copy(error = Some(s"Failed to start $provider OAuth")))
                  case scala.util.Failure(err) =>
                    state.setState(state.value.copy(error = Some(err.getMessage)))
                }
                .runNow()
            }

          def handleRevoke(provider: String): Callback =
            Callback {
              AsyncCallbackRepositories.extCredential
                .revokeOAuth(provider)
                .completeWith {
                  case scala.util.Success(_) =>
                    state.modState(s => s.copy(connectedProviders = s.connectedProviders.filterNot(_ == provider)))
                  case scala.util.Failure(err) =>
                    state.modState(_.copy(error = Some(err.getMessage)))
                }
                .runNow()
            }

          <.div(
            Box.set("sx", js.Dynamic.literal(display = "flex", alignItems = "center", mb = 2, gap = 2))(
              Typography.set("variant", "h5")("Connected Accounts"),
            ),
            state.value.error.fold(EmptyVdom)(msg => Alert.set("severity", "error")(msg)),
            if (state.value.loading) {
              CircularProgress()
            } else {
              TableContainer(
                Table(
                  TableHead(
                    TableRow(
                      TableCell("Provider"),
                      TableCell("Status"),
                      TableCell(""),
                    ),
                  ),
                  TableBody()(
                    SupportedProviders.flatMap { provider =>
                      val connected = state.value.connectedProviders.contains(provider)
                      scala.List[VdomElement](
                        TableRow.set("key", provider)(
                          TableCell(provider.capitalize),
                          TableCell(
                            Chip
                              .set("label", if (connected) "Connected" else "Not connected")
                              .set("color", if (connected) "success" else "default")(),
                          ),
                          TableCell(
                            if (connected) {
                              MuiButton
                                .variant("outlined")
                                .color("error")
                                .size("small")
                                .onClick(() => handleRevoke(provider).runNow())("Disconnect")
                            } else {
                              MuiButton
                                .variant("contained")
                                .size("small")
                                .onClick(() => handleConnect(provider).runNow())("Connect")
                            },
                          ),
                        ),
                      )
                    }*,
                  ),
                ),
              )
            },
          )
      }

  def apply(user: User): VdomElement = component(user)

}
