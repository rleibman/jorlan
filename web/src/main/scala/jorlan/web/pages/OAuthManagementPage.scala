/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.pages

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.AsyncCallbackRepositories
import jorlan.web.components.MuiButton
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}

import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

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
      .useState(State(List.empty, loading = true, error = None))
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
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", alignItems = "center", mb = 2, gap = 2).asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography
                .withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])("Connected Accounts"),
            ),
            state.value.error.fold(EmptyVdom)(msg => Alert.severity("error")(msg)),
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
                        TableRow.withKey(provider)(
                          TableCell(provider.capitalize),
                          TableCell(
                            Chip.withProps(
                              ChipOwnProps()
                                .setLabel(if (connected) "Connected" else "Not connected")
                                .setColor(if (connected) "success" else "default")
                                .asInstanceOf[Chip.Props],
                            )(),
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
