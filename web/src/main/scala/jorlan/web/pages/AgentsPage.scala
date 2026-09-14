/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.pages

import jorlan.web.components.MuiExtensions.*
import net.leibman.jorlan.muiMaterial.muiMaterialStrings as MuiStrings
import net.leibman.jorlan.muiMaterial.components.{Button, TextField}
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.AsyncCallbackRepositories
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
                  state.value.error.fold(EmptyVdom)(err => Alert.severity(MuiStrings.error)(err)),
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", flexDirection = "column", gap = 2, pt = 1, minWidth = 600)
                          .asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Box.Props],
                  )(
                    Typography.withProps(
                      TypographyOwnProps().setVariant(MuiStrings.subtitle1).asInstanceOf[Typography.Props],
                    )("Invariants"),
                    Typography.withProps(
                      TypographyOwnProps().setVariant(MuiStrings.body2).asInstanceOf[Typography.Props],
                    )(
                      "Key-value facts injected into every pipeline step for this agent. These override memory and are never skipped.",
                    ),
                    if (agent.invariants.isEmpty)
                      <.span("No invariants defined yet.")
                    else
                      Table.withProps(TableOwnProps().setSize(MuiStrings.small).asInstanceOf[Table.Props])(
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
                                  TextField
                                    .value(v)
                                    .fullWidth(true)
                                    .size(MuiStrings.small)
                                    .onChange { e =>
                                      val nv = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                                      editInvariantValue(k, nv)
                                    }(),
                                ),
                                TableCell()(
                                  Button
                                    .size(MuiStrings.small)
                                    .color(MuiStrings.error)
                                    .onClick(_ => deleteInvariant(k))("×"),
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
                      TextField
                        .label("Key")
                        .value(state.value.newKey)
                        .size(MuiStrings.small)
                        .onChange { e =>
                          val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                          state.modState(_.copy(newKey = v))
                        }(),
                      TextField
                        .label("Value")
                        .value(state.value.newValue)
                        .size(MuiStrings.small)
                        .fullWidth(true)
                        .onChange { e =>
                          val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                          state.modState(_.copy(newValue = v))
                        }(),
                      Button
                        .variant(MuiStrings.outlined)
                        .size(MuiStrings.small)
                        .onClick(_ => addInvariant())("+ Add"),
                    ),
                  ),
                ),
                DialogActions()(
                  Button.onClick(_ => closeEdit())("Cancel"),
                  Button
                    .variant(MuiStrings.contained)
                    .disabled(state.value.saving)
                    .onClick(_ => saveAgent())("Save"),
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
              Typography
                .withProps(TypographyOwnProps().setVariant(MuiStrings.h5).asInstanceOf[Typography.Props])("Agents"),
              Button
                .variant(MuiStrings.outlined)
                .size(MuiStrings.small)
                .onClick(_ => loadAgents())("Refresh"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity(MuiStrings.error)(err)),
            if (state.value.loading)
              CircularProgress()
            else if (state.value.agents.isEmpty)
              Alert.severity(MuiStrings.info)("No agents found.")
            else
              TableContainer()(
                Table.withProps(TableOwnProps().setSize(MuiStrings.small).asInstanceOf[Table.Props])(
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
                                    .setSize(MuiStrings.small)
                                    .asInstanceOf[Chip.Props],
                                )(),
                          ),
                          TableCell()(
                            Button
                              .size(MuiStrings.small)
                              .variant(MuiStrings.outlined)
                              .onClick(_ => openEdit(agent))("Edit Invariants"),
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
