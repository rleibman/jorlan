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
import jorlan.web.components.*
import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}
import net.leibman.jorlan.muiMaterial.internalSwitchBaseMod.SwitchBaseProps
import net.leibman.jorlan.muiMaterial.switchSwitchMod.SwitchProps
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

object McpServersPage {

  case class EnvVarDraft(
    key:   String,
    value: String,
  )

  case class ServerForm(
    name:      String = "",
    transport: String = "Stdio",
    command:   String = "",
    args:      String = "",
    url:       String = "",
    env:       List[EnvVarDraft] = List.empty,
    enabled:   Boolean = true,
    keywords:  String = "",
  )

  case class State(
    servers:      List[McpServerInfo],
    loading:      Boolean,
    error:        Option[String],
    showDialog:   Boolean,
    editingName:  Option[String],
    form:         ServerForm,
    saving:       Boolean,
    deleteTarget: Option[String],
    deleting:     Boolean,
    reloading:    Boolean,
    toast:        Option[ToastMessage],
  )

  private def formFromServer(s: McpServerInfo): ServerForm =
    ServerForm(
      name = s.name,
      transport = s.transport,
      command = s.command.getOrElse(""),
      args = s.args.mkString("\n"),
      url = s.url.getOrElse(""),
      env = s.env.map(e => EnvVarDraft(e.key, e.value)),
      enabled = s.enabled,
      keywords = s.keywords.mkString(", "),
    )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(
          servers = List.empty,
          loading = true,
          error = None,
          showDialog = false,
          editingName = None,
          form = ServerForm(),
          saving = false,
          deleteTarget = None,
          deleting = false,
          reloading = false,
          toast = None,
        ),
      )
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories.mcp
              .listMcpServers()
              .flatMap { servers =>
                state.modState(_.copy(servers = servers, loading = false)).asAsyncCallback
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
          def reloadList(): Callback =
            Callback {
              AsyncCallbackRepositories.mcp
                .listMcpServers()
                .flatMap(servers => state.modState(_.copy(servers = servers)).asAsyncCallback)
                .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                .runNow()
            }

          def openAdd(): Callback =
            state.setState(state.value.copy(showDialog = true, editingName = None, form = ServerForm()))

          def openEdit(server: McpServerInfo): Callback =
            state.setState(
              state.value.copy(showDialog = true, editingName = Some(server.name), form = formFromServer(server)),
            )

          def closeDialog(): Callback =
            state.setState(state.value.copy(showDialog = false, editingName = None, form = ServerForm()))

          def saveServer(): Callback =
            Callback {
              val f = state.value.form
              state.modState(_.copy(saving = true)).runNow()
              AsyncCallbackRepositories.mcp
                .upsertMcpServer(
                  name = f.name.trim,
                  transport = f.transport,
                  command =
                    if (f.transport == "Stdio" && f.command.trim.nonEmpty) Some(f.command.trim) else None,
                  args =
                    if (f.transport == "Stdio") f.args.split("\n").map(_.trim).filter(_.nonEmpty).toList
                    else List.empty,
                  url =
                    if ((f.transport == "Http" || f.transport == "HttpSse") && f.url.trim.nonEmpty)
                      Some(f.url.trim)
                    else None,
                  env = f.env.filter(e => e.key.trim.nonEmpty).map(e => McpEnvVarInfo(e.key.trim, e.value)),
                  enabled = f.enabled,
                  keywords = f.keywords.split(",").map(_.trim).filter(_.nonEmpty).toList,
                )
                .flatMap { _ =>
                  val msg =
                    if (state.value.editingName.isDefined) s"Server '${f.name.trim}' updated."
                    else s"Server '${f.name.trim}' added."
                  state
                    .modState(
                      _.copy(
                        saving = false,
                        showDialog = false,
                        editingName = None,
                        form = ServerForm(),
                        toast = Some(ToastMessage(msg, ToastSeverity.Success)),
                      ),
                    )
                    .asAsyncCallback
                    .flatMap(_ => reloadList().asAsyncCallback)
                }
                .completeWith(
                  PageUtils.onError(err =>
                    state.modState(
                      _.copy(
                        saving = false,
                        toast = Some(ToastMessage(err.getOrElse("Save failed"), ToastSeverity.Error)),
                      ),
                    ),
                  ),
                )
                .runNow()
            }

          def confirmDelete(name: String): Callback =
            state.setState(state.value.copy(deleteTarget = Some(name)))

          def cancelDelete(): Callback =
            state.setState(state.value.copy(deleteTarget = None))

          def doDelete(name: String): Callback =
            Callback {
              state.modState(_.copy(deleting = true)).runNow()
              AsyncCallbackRepositories.mcp
                .deleteMcpServer(name)
                .flatMap { _ =>
                  state
                    .modState(
                      _.copy(
                        deleting = false,
                        deleteTarget = None,
                        toast = Some(ToastMessage(s"Server '$name' deleted.", ToastSeverity.Success)),
                      ),
                    )
                    .asAsyncCallback
                    .flatMap(_ => reloadList().asAsyncCallback)
                }
                .completeWith(
                  PageUtils.onError(err =>
                    state.modState(
                      _.copy(
                        deleting = false,
                        deleteTarget = None,
                        toast = Some(ToastMessage(err.getOrElse("Delete failed"), ToastSeverity.Error)),
                      ),
                    ),
                  ),
                )
                .runNow()
            }

          def doReload(): Callback =
            Callback {
              state.modState(_.copy(reloading = true)).runNow()
              AsyncCallbackRepositories.mcp
                .reloadMcpServers()
                .flatMap { _ =>
                  state
                    .modState(
                      _.copy(
                        reloading = false,
                        toast = Some(ToastMessage("MCP servers reloaded.", ToastSeverity.Success)),
                      ),
                    )
                    .asAsyncCallback
                    .flatMap(_ => reloadList().asAsyncCallback)
                }
                .completeWith(
                  PageUtils.onError(err =>
                    state.modState(
                      _.copy(
                        reloading = false,
                        toast = Some(ToastMessage(err.getOrElse("Reload failed"), ToastSeverity.Error)),
                      ),
                    ),
                  ),
                )
                .runNow()
            }

          def updateForm(f: ServerForm): Callback =
            state.modState(_.copy(form = f))

          def addEnvVar(): Callback =
            state.modState(s => s.copy(form = s.form.copy(env = s.form.env :+ EnvVarDraft("", ""))))

          def removeEnvVar(idx: Int): Callback =
            state
              .modState(s => s.copy(form = s.form.copy(env = s.form.env.zipWithIndex.filterNot(_._2 == idx).map(_._1))))

          def updateEnvVar(
            idx:   Int,
            key:   String,
            value: String,
          ): Callback =
            state.modState(s =>
              s.copy(form = s.form.copy(env = s.form.env.zipWithIndex.map { case (e, i) =>
                if (i == idx) EnvVarDraft(key, value) else e
              })),
            )

          val f = state.value.form

          <.div(
            Box.withProps(
              BoxOwnProps()
                .setSx(
                  js.Dynamic
                    .literal(
                      display = "flex",
                      justifyContent = "space-between",
                      alignItems = "center",
                      mb = 2,
                    ).asInstanceOf[SxProps[Any]],
                )
                .asInstanceOf[Box.Props],
            )(
              Typography.withProps(
                TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props],
              )("MCP Servers"),
              Box.withProps(
                BoxOwnProps()
                  .setSx(js.Dynamic.literal(display = "flex", gap = 1).asInstanceOf[SxProps[Any]])
                  .asInstanceOf[Box.Props],
              )(
                MuiButton
                  .variant("outlined")
                  .disabled(state.value.reloading)
                  .onClick(() => doReload().runNow())(
                    if (state.value.reloading) "Reloading…" else "Reload",
                  ),
                MuiButton
                  .variant("contained")
                  .onClick(() => openAdd().runNow())("+ Add Server"),
              ),
            ),
            if (state.value.loading)
              CircularProgress()
            else if (state.value.servers.isEmpty)
              Typography.withProps(
                TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props],
              )("No MCP servers configured.")
            else
              TableContainer()(
                Table()(
                  TableHead()(
                    TableRow()(
                      TableCell()("Name"),
                      TableCell()("Transport"),
                      TableCell()("Command / URL"),
                      TableCell()("Status"),
                      TableCell()("Actions"),
                    ),
                  ),
                  TableBody()(
                    state.value.servers.toVdomArray { server =>
                      TableRow
                        .withKey(server.name)(
                          TableCell()(server.name),
                          TableCell()(server.transport),
                          TableCell()(
                            server.transport match {
                              case "Http" | "HttpSse" => server.url.getOrElse("—")
                              case _                  =>
                                server.command.map(c => (c :: server.args).mkString(" ")).getOrElse("—")
                            },
                          ),
                          TableCell()(
                            Chip.withProps(
                              ChipOwnProps()
                                .setLabel(if (server.enabled) "Enabled" else "Disabled")
                                .setColor(if (server.enabled) "success" else "default")
                                .setSize("small")
                                .asInstanceOf[Chip.Props],
                            )(),
                          ),
                          TableCell()(
                            MuiButton
                              .size("small")
                              .onClick(() => openEdit(server).runNow())("Edit"),
                            MuiButton
                              .size("small")
                              .color("error")
                              .onClick(() => confirmDelete(server.name).runNow())("Delete"),
                          ),
                        )
                    },
                  ),
                ),
              ),
            // Add/Edit dialog
            Dialog(state.value.showDialog)(
              DialogTitle()(if (state.value.editingName.isDefined) "Edit MCP Server" else "Add MCP Server"),
              DialogContent()(
                MuiTextField
                  .label("Name")
                  .value(f.name)
                  .fullWidth(true)
                  .variant("outlined")
                  .size("small")
                  .disabled(state.value.editingName.isDefined)
                  .sx(js.Dynamic.literal(mt = 1, mb = 2))
                  .onChange(e => updateForm(f.copy(name = e.target.value.asInstanceOf[String])).runNow()),
                Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                  "Transport",
                ),
                MuiSelect
                  .value(f.transport)
                  .fullWidth(true)
                  .sx(js.Dynamic.literal(mb = 2))
                  .onChange { e =>
                    val v = e.target.value.asInstanceOf[String]
                    updateForm(f.copy(transport = v)).runNow()
                  }(
                    MuiMenuItem.value("Stdio")("Stdio — local subprocess via stdin/stdout"):            VdomNode,
                    MuiMenuItem.value("Http")("Http — Streamable HTTP (MCP 2025-03-26)"):               VdomNode,
                    MuiMenuItem.value("HttpSse")("HttpSse — HTTP+SSE (MCP 2024-11-05, legacy servers"): VdomNode,
                  ),
                if (f.transport == "Stdio")
                  <.div(
                    MuiTextField
                      .label("Command")
                      .value(f.command)
                      .fullWidth(true)
                      .variant("outlined")
                      .size("small")
                      .sx(js.Dynamic.literal(mb = 2))
                      .onChange(e => updateForm(f.copy(command = e.target.value.asInstanceOf[String])).runNow()),
                    MuiTextField
                      .label("Arguments (one per line)")
                      .value(f.args)
                      .fullWidth(true)
                      .multiline(true)
                      .rows(3)
                      .variant("outlined")
                      .size("small")
                      .sx(js.Dynamic.literal(mb = 2))
                      .onChange(e => updateForm(f.copy(args = e.target.value.asInstanceOf[String])).runNow()),
                  )
                else
                  MuiTextField
                    .label("URL")
                    .value(f.url)
                    .fullWidth(true)
                    .variant("outlined")
                    .size("small")
                    .sx(js.Dynamic.literal(mb = 2))
                    .onChange(e => updateForm(f.copy(url = e.target.value.asInstanceOf[String])).runNow()),
                Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                  "Environment Variables",
                ),
                f.env.zipWithIndex.toVdomArray { case (ev, idx) =>
                  <.div(
                    ^.key := idx.toString,
                    Box.withProps(
                      BoxOwnProps()
                        .setSx(
                          js.Dynamic.literal(display = "flex", gap = 1, mb = 1).asInstanceOf[SxProps[Any]],
                        )
                        .asInstanceOf[Box.Props],
                    )(
                      MuiTextField
                        .label("Key")
                        .value(ev.key)
                        .size("small")
                        .variant("outlined")
                        .onChange(e => updateEnvVar(idx, e.target.value.asInstanceOf[String], ev.value).runNow()),
                      MuiTextField
                        .label("Value")
                        .value(ev.value)
                        .size("small")
                        .variant("outlined")
                        .onChange(e => updateEnvVar(idx, ev.key, e.target.value.asInstanceOf[String]).runNow()),
                      MuiButton
                        .color("error")
                        .size("small")
                        .onClick(() => removeEnvVar(idx).runNow())("✕"),
                    ),
                  )
                },
                MuiButton
                  .size("small")
                  .onClick(() => addEnvVar().runNow())("+ Add Env Var"),
                Box.withProps(
                  BoxOwnProps()
                    .setSx(
                      js.Dynamic.literal(display = "flex", alignItems = "center", mt = 1).asInstanceOf[SxProps[Any]],
                    )
                    .asInstanceOf[Box.Props],
                )(
                  Switch.withProps(
                    SwitchProps()
                      .asInstanceOf[SwitchBaseProps]
                      .setChecked(f.enabled)
                      .setOnChange(
                        (
                          _,
                          checked,
                        ) => updateForm(f.copy(enabled = checked)),
                      )
                      .asInstanceOf[SwitchProps],
                  )(),
                  Typography.withProps(TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props])(
                    if (f.enabled) "Enabled" else "Disabled",
                  ),
                ),
                MuiTextField
                  .label("Keywords (comma-separated)")
                  .value(f.keywords)
                  .fullWidth(true)
                  .variant("outlined")
                  .size("small")
                  .sx(js.Dynamic.literal(mt = 2))
                  .helperText(
                    "Optional tags to help the AI find this server's tools (e.g. \"pubmed, research, medical\")",
                  )
                  .onChange(e => updateForm(f.copy(keywords = e.target.value.asInstanceOf[String])).runNow()),
              ),
              DialogActions()(
                MuiButton.onClick(() => closeDialog().runNow())("Cancel"),
                MuiButton
                  .variant("contained")
                  .disabled(state.value.saving || f.name.trim.isEmpty)
                  .onClick(() => saveServer().runNow())(
                    if (state.value.saving) "Saving…" else "Save",
                  ),
              ),
            ),
            // Delete confirmation dialog
            Dialog(state.value.deleteTarget.isDefined)(
              DialogTitle()("Delete MCP Server"),
              DialogContent()(
                state.value.deleteTarget match {
                  case Some(name) =>
                    Typography.withProps(TypographyOwnProps().setVariant("body1").asInstanceOf[Typography.Props])(
                      s"Are you sure you want to delete '$name'?",
                    )
                  case None => EmptyVdom
                },
              ),
              DialogActions()(
                MuiButton.onClick(() => cancelDelete().runNow())("Cancel"),
                MuiButton
                  .variant("contained")
                  .color("error")
                  .disabled(state.value.deleting)
                  .onClick(() => state.value.deleteTarget.foreach(doDelete(_).runNow()))(
                    if (state.value.deleting) "Deleting…" else "Delete",
                  ),
              ),
            ),
            Toast(
              message = state.value.toast,
              onClose = state.modState(_.copy(toast = None)),
            ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
