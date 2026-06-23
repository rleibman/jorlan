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
import jorlan.web.components.{MuiButton, MuiTablePagination}
import jorlan.web.pages.PageUtils
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}

import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

object SessionsPage {

  case class State(
    sessions:        List[AgentSession],
    models:          List[ModelInfo],
    loading:         Boolean,
    error:           Option[String],
    showCreate:      Boolean,
    selectedModelId: Option[ModelId],
    page:            Int,
    rowsPerPage:     Int,
  )

  private val DefaultRowsPerPage = 10

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(
          List.empty,
          List.empty,
          loading = true,
          error = None,
          showCreate = false,
          selectedModelId = None,
          page = 0,
          rowsPerPage = DefaultRowsPerPage,
        ),
      )
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories.agent
              .searchSessions(AgentSessionSearch())
              .flatMap(sessions =>
                state.setState(state.value.copy(sessions = sessions, loading = false)).asAsyncCallback,
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
          def reload(): Callback =
            Callback {
              AsyncCallbackRepositories.agent
                .searchSessions(AgentSessionSearch())
                .flatMap(sessions =>
                  state.setState(state.value.copy(sessions = sessions, loading = false)).asAsyncCallback,
                )
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(loading = false, error = err))))
                .runNow()
            }

          def terminate(sessionId: AgentSessionId): Callback =
            Callback {
              AsyncCallbackRepositories.agent
                .terminateSession(sessionId)
                .flatMap { _ =>
                  val newSessions = state.value.sessions.filterNot(_.id == sessionId)
                  val maxPage = math.max(0, (newSessions.size - 1) / state.value.rowsPerPage)
                  state
                    .setState(
                      state.value.copy(sessions = newSessions, page = math.min(state.value.page, maxPage)),
                    )
                    .asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }

          def openCreateDialog(): Callback =
            Callback {
              AsyncCallbackRepositories.agent
                .availableModels()
                .flatMap { models =>
                  state.setState(state.value.copy(models = models, showCreate = true)).asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }

          def createSession(): Callback =
            Callback {
              AsyncCallbackRepositories.agent
                .createSession(state.value.selectedModelId)
                .flatMap { sessionOpt =>
                  sessionOpt.fold(AsyncCallback.unit) { session =>
                    state
                      .setState(
                        state.value.copy(
                          sessions = state.value.sessions :+ session,
                          showCreate = false,
                          selectedModelId = None,
                        ),
                      )
                      .asAsyncCallback
                  }
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }

          val pageItems = state.value.sessions
            .slice(state.value.page * state.value.rowsPerPage, (state.value.page + 1) * state.value.rowsPerPage)

          <.div(
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", alignItems = "center", mb = 2, gap = 2).asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography.withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])("Sessions"),
              MuiButton
                .variant("contained")
                .size("small")
                .onClick(() => openCreateDialog().runNow())("+ New Session"),
              MuiButton
                .variant("outlined")
                .size("small")
                .onClick(() => reload().runNow())("Refresh"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            if (state.value.loading)
              CircularProgress()
            else
              <.div(
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
                      pageItems.map { session =>
                        TableRow.withKey(session.id.value.toString)(
                          TableCell()(
                            <.span(^.title := session.id.value.toString)(
                              session.id.value.toString.take(8) + "…",
                            ),
                          ),
                          TableCell()(
                            Chip.withProps(
                              ChipOwnProps().setLabel(session.status.toString).setSize("small").asInstanceOf[Chip.Props],
                            )(),
                          ),
                          TableCell()(session.modelId.map(_.value).getOrElse("—")),
                          TableCell()(session.createdAt.toString.take(19)),
                          TableCell()(
                            if (session.status == SessionStatus.Active)
                              MuiButton
                                .variant("outlined")
                                .color("error")
                                .size("small")
                                .onClick(() => terminate(session.id).runNow())("Terminate")
                            else EmptyVdom,
                          ),
                        )
                      }*,
                    ),
                  ),
                ),
                MuiTablePagination
                  .component("div")
                  .count(state.value.sessions.size)
                  .page(state.value.page)
                  .rowsPerPage(state.value.rowsPerPage)
                  .rowsPerPageOptions(js.Array(5, 10, 25, 50))
                  .onPageChange(
                    (
                      _,
                      p,
                    ) => state.setState(state.value.copy(page = p)).runNow(),
                  )
                  .onRowsPerPageChange(e =>
                    state
                      .setState(state.value.copy(rowsPerPage = e.target.value.asInstanceOf[String].toInt, page = 0))
                      .runNow(),
                  )(),
              ),
            // Create Session dialog
            Dialog(state.value.showCreate)(
              DialogTitle()("New Session"),
              DialogContent()(
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("body2").setSx(js.Dynamic.literal(mb = 1).asInstanceOf[SxProps[Theme]]).asInstanceOf[
                      Typography.Props,
                    ],
                )("Model (optional):"),
                <.select(
                  ^.style := js.Dynamic.literal(width = "100%", padding = "8px", fontSize = "1rem"),
                  ^.value := state.value.selectedModelId.map(_.value).getOrElse(""),
                  ^.onChange ==> { e =>
                    val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                    val sel: Option[ModelId] = if (v.isEmpty) None else Some(ModelId(v))
                    state.setState(state.value.copy(selectedModelId = sel))
                  },
                  React.Fragment(
                    (<.option(^.value := "")("(default)") +: state.value.models.map { m =>
                      <.option(^.key := m.id.value, ^.value := m.id.value)(m.id.value)
                    })*,
                  ),
                ),
              ),
              DialogActions()(
                MuiButton.onClick(() => state.setState(state.value.copy(showCreate = false)).runNow())("Cancel"),
                MuiButton
                  .variant("contained")
                  .onClick(() => createSession().runNow())("Create"),
              ),
            ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
