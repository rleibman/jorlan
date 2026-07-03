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
import jorlan.web.components.{MuiButton, MuiTextField}
import jorlan.web.pages.PageUtils
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}

import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.tableTableMod.TableOwnProps
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

object AgentsPage {

  case class EditInvariantRow(
    key:   String,
    value: String,
  )

  case class State(
    agents:    scala.List[Agent],
    loading:   Boolean,
    error:     Option[String],
    editAgent: Option[Agent],
    newKey:    String,
    newValue:  String,
    saving:    Boolean,
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(
          agents = scala.List.empty,
          loading = true,
          error = None,
          editAgent = None,
          newKey = "",
          newValue = "",
          saving = false,
        ),
      )
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories
              .listAgents()
              .flatMap { agents =>
                state.modState(_.copy(agents = agents, loading = false)).asAsyncCallback
              }
              .completeWith(PageUtils.onError(err => state.modState(_.copy(loading = false, error = err))))
              .runNow()
          }
      }
      .render {
        (
          _,
          state,
        ) =>
          def loadAgents(): Callback =
            Callback {
              AsyncCallbackRepositories
                .listAgents()
                .flatMap { agents =>
                  state.modState(_.copy(agents = agents, loading = false)).asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                .runNow()
            }

          def openEdit(agent: Agent): Callback =
            state.modState(_.copy(editAgent = Some(agent), newKey = "", newValue = "", error = None))

          def closeEdit(): Callback =
            state.modState(_.copy(editAgent = None, newKey = "", newValue = "", error = None))

          def saveAgent(): Callback =
            state.value.editAgent.fold(Callback.empty) { agent =>
              state.modState(_.copy(saving = true)) >> Callback {
                AsyncCallbackRepositories
                  .upsertAgent(agent)
                  .flatMap { saved =>
                    state
                      .modState(s =>
                        s.copy(
                          agents = s.agents.map(a => if (a.id == saved.id) saved else a),
                          editAgent = None,
                          saving = false,
                        ),
                      )
                      .asAsyncCallback
                  }
                  .completeWith(
                    PageUtils.onError(err => state.modState(_.copy(saving = false, error = err))),
                  )
                  .runNow()
              }
            }

          def deleteInvariant(key: String): Callback =
            state.value.editAgent.fold(Callback.empty) { agent =>
              val updated = agent.copy(invariants = agent.invariants - key)
              state.modState(_.copy(editAgent = Some(updated)))
            }

          def addInvariant(): Callback =
            state.value.editAgent.fold(Callback.empty) { agent =>
              val k = state.value.newKey.trim
              val v = state.value.newValue.trim
              if (k.isEmpty) Callback.empty
              else {
                val updated = agent.copy(invariants = agent.invariants + (k -> v))
                state.modState(_.copy(editAgent = Some(updated), newKey = "", newValue = ""))
              }
            }

          def editInvariantValue(
            key:      String,
            newValue: String,
          ): Callback =
            state.value.editAgent.fold(Callback.empty) { agent =>
              val updated = agent.copy(invariants = agent.invariants + (key -> newValue))
              state.modState(_.copy(editAgent = Some(updated)))
            }

          <.div(
            state.value.editAgent.fold(EmptyVdom) { agent =>
              Dialog(true)(
                DialogTitle()(s"Edit Agent: ${agent.name}"),
                DialogContent()(
                  state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", flexDirection = "column", gap = 2, pt = 1, minWidth = 600)
                          .asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Box.Props],
                  )(
                    Typography.withProps(
                      TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props],
                    )("Invariants"),
                    Typography.withProps(
                      TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props],
                    )(
                      "Key-value facts injected into every pipeline step for this agent. These override memory and are never skipped.",
                    ),
                    if (agent.invariants.isEmpty)
                      <.span("No invariants defined yet.")
                    else
                      Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
                        TableHead()(
                          TableRow()(
                            TableCell()("Key"),
                            TableCell()("Value"),
                            TableCell()(""),
                          ),
                        ),
                        TableBody()(
                          agent.invariants.toList.sortBy(_._1).map { case (k, v) =>
                            TableRow
                              .withKey(k)(
                                TableCell()(<.code(k)),
                                TableCell()(
                                  MuiTextField
                                    .value(v)
                                    .fullWidth(true)
                                    .size("small")
                                    .onChange { e =>
                                      val nv = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                                      editInvariantValue(k, nv).runNow()
                                    }(),
                                ),
                                TableCell()(
                                  MuiButton
                                    .size("small")
                                    .color("error")
                                    .onClick(() => deleteInvariant(k).runNow())("×"),
                                ),
                              ).build
                          }*,
                        ),
                      ),
                    Box.withProps(
                      BoxOwnProps[Theme]()
                        .setSx(
                          js.Dynamic
                            .literal(display = "flex", gap = 1, alignItems = "center")
                            .asInstanceOf[SxProps[Theme]],
                        ).asInstanceOf[Box.Props],
                    )(
                      MuiTextField
                        .label("Key")
                        .value(state.value.newKey)
                        .size("small")
                        .onChange { e =>
                          val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                          state.modState(_.copy(newKey = v)).runNow()
                        }(),
                      MuiTextField
                        .label("Value")
                        .value(state.value.newValue)
                        .size("small")
                        .fullWidth(true)
                        .onChange { e =>
                          val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                          state.modState(_.copy(newValue = v)).runNow()
                        }(),
                      MuiButton
                        .variant("outlined")
                        .size("small")
                        .onClick(() => addInvariant().runNow())("+ Add"),
                    ),
                  ),
                ),
                DialogActions()(
                  MuiButton.onClick(() => closeEdit().runNow())("Cancel"),
                  MuiButton
                    .variant("contained")
                    .disabled(state.value.saving)
                    .onClick(() => saveAgent().runNow())("Save"),
                ),
              )
            },
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", alignItems = "center", mb = 2, gap = 2)
                    .asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography.withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])("Agents"),
              MuiButton
                .variant("outlined")
                .size("small")
                .onClick(() => loadAgents().runNow())("Refresh"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            if (state.value.loading)
              CircularProgress()
            else if (state.value.agents.isEmpty)
              Alert.severity("info")("No agents found.")
            else
              TableContainer()(
                Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
                  TableHead()(
                    TableRow()(
                      TableCell()("Name"),
                      TableCell()("Description"),
                      TableCell()("Model"),
                      TableCell()("Invariants"),
                      TableCell()(""),
                    ),
                  ),
                  TableBody()(
                    state.value.agents.map { agent =>
                      TableRow
                        .withKey(agent.id.value.toString)(
                          TableCell()(agent.name),
                          TableCell()(agent.description.getOrElse("-")),
                          TableCell()(agent.defaultModel.fold("-")(_.value)),
                          TableCell()(
                            if (agent.invariants.isEmpty) <.span("none")
                            else
                              Chip
                                .withProps(
                                  ChipOwnProps()
                                    .setLabel(s"${agent.invariants.size} key(s)")
                                    .setSize("small")
                                    .asInstanceOf[Chip.Props],
                                )(),
                          ),
                          TableCell()(
                            MuiButton
                              .size("small")
                              .variant("outlined")
                              .onClick(() => openEdit(agent).runNow())("Edit Invariants"),
                          ),
                        ).build
                    }*,
                  ),
                ),
              ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
