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
import jorlan.web.components.{MuiButton, MuiMenuItem, MuiSelect, MuiTextField, *}
import jorlan.web.pages.PageUtils
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}
import zio.json.ast.Json

import java.time.Instant
import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

object MemoryPage {

  case class StoreForm(
    key:   String,
    text:  String,
    scope: MemoryScope = MemoryScope.User,
  )

  case class State(
    memories:    List[MemoryRecord],
    search:      String,
    scopeFilter: MemoryScope,
    loading:     Boolean,
    error:       Option[String],
    showStore:   Boolean,
    storeForm:   StoreForm,
    page:        Int,
    rowsPerPage: Int,
  )

  private def valueString(v: Json): String =
    v match {
      case Json.Str(s) => s
      case other       => other.toString
    }

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(
          List.empty,
          "",
          scopeFilter = MemoryScope.User,
          loading = true,
          error = None,
          showStore = false,
          StoreForm("", "", MemoryScope.User),
          page = 0,
          rowsPerPage = 10,
        ),
      )
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories.memory
              .search(MemorySearch(MemoryScope.User))
              .flatMap { memories =>
                state.modState(_.copy(memories = memories, loading = false, page = 0)).asAsyncCallback
              }
              .completeWith {
                case scala.util.Failure(ex) =>
                  state.modState(_.copy(loading = false, error = Some(ex.getMessage)))
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
          def runSearch(q: String): Callback =
            Callback {
              val search = if (q.trim.isEmpty) None else Some(q.trim)
              AsyncCallbackRepositories.memory
                .search(MemorySearch(MemoryScope.User, textSearch = search))
                .flatMap { memories =>
                  state.modState(_.copy(memories = memories, loading = false, page = 0)).asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) =>
                    state.modState(_.copy(loading = false, error = Some(ex.getMessage)))
                  case _ => Callback.empty
                }
                .runNow()
            }

          def forget(id: MemoryRecordId): Callback =
            Callback {
              AsyncCallbackRepositories.memory
                .delete(id)
                .flatMap { _ =>
                  state.modState { s =>
                    val newMems = s.memories.filter(_.id != id)
                    val maxPage = math.max(0, (newMems.size - 1) / s.rowsPerPage)
                    s.copy(memories = newMems, page = math.min(s.page, maxPage))
                  }.asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                .runNow()
            }

          def markShared(id: MemoryRecordId): Callback =
            Callback {
              AsyncCallbackRepositories.memory
                .updateScope(id, MemoryScope.Shared)
                .flatMap { count =>
                  if (count > 0L)
                    state
                      .modState(s =>
                        s.copy(
                          memories = s.memories.map(m => if (m.id == id) m.copy(scope = MemoryScope.Shared) else m),
                        ),
                      )
                      .asAsyncCallback
                  else AsyncCallback.unit
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.modState(_.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }

          def markPrivate(id: MemoryRecordId): Callback =
            Callback {
              AsyncCallbackRepositories.memory
                .updateScope(id, MemoryScope.Private)
                .flatMap { count =>
                  if (count > 0L)
                    state
                      .modState(s =>
                        s.copy(
                          memories = s.memories.map(m => if (m.id == id) m.copy(scope = MemoryScope.Private) else m),
                        ),
                      )
                      .asAsyncCallback
                  else AsyncCallback.unit
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.modState(_.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }

          def storeMemory(): Callback = {
            val f = state.value.storeForm
            Callback {
              AsyncCallbackRepositories.memory
                .upsert(
                  MemoryRecord(
                    id = MemoryRecordId.empty,
                    scope = f.scope,
                    userId = None,
                    workspaceId = None,
                    agentId = None,
                    recordKey = f.key,
                    value = Json.Str(f.text),
                    ttl = None,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                  ),
                )
                .flatMap { stored =>
                  state
                    .modState(s =>
                      s.copy(
                        memories = s.memories :+ stored,
                        showStore = false,
                        storeForm = StoreForm("", "", MemoryScope.User),
                      ),
                    )
                    .asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.modState(_.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }
          }

          val pageItems = state.value.memories
            .slice(state.value.page * state.value.rowsPerPage, (state.value.page + 1) * state.value.rowsPerPage)

          <.div(
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", alignItems = "center", mb = 2, gap = 2).asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography.withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])("Memory"),
              MuiButton
                .variant("contained")
                .size("small")
                .onClick(() => state.modState(_.copy(showStore = true)).runNow())("+ Remember"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            MuiTextField
              .label("Search")
              .value(state.value.search)
              .variant("outlined")
              .size("small")
              .sx(js.Dynamic.literal(mb = 2, width = 300))
              .onChange(e => {
                val q = e.target.value.asInstanceOf[String]
                (state.modState(_.copy(search = q, loading = true)) >> runSearch(q)).runNow()
              }),
            if (state.value.loading) CircularProgress()
            else if (state.value.memories.isEmpty)
              Alert.severity("info")("No memories found.")
            else
              <.div(
                TableContainer()(
                  Table()(
                    TableHead()(
                      TableRow()(
                        TableCell()("Key"),
                        TableCell()("Scope"),
                        TableCell()("Value"),
                        TableCell()("Created"),
                        TableCell()("Actions"),
                      ),
                    ),
                    TableBody()(
                      pageItems.map { mem =>
                        val v = valueString(mem.value)
                        TableRow.withKey(mem.id.value.toString)(
                          TableCell()(mem.recordKey),
                          TableCell()(
                            Chip.withProps(
                              ChipOwnProps().setLabel(mem.scope.toString).setSize("small").asInstanceOf[Chip.Props],
                            )(),
                          ),
                          TableCell()(
                            <.span(^.title := v)(
                              if (v.length > 60) v.take(60) + "…" else v,
                            ),
                          ),
                          TableCell()(mem.createdAt.toString.take(19)),
                          TableCell()(
                            Box.withProps(
                              BoxOwnProps[Theme]()
                                .setSx(
                                  js.Dynamic.literal(display = "flex", gap = 1).asInstanceOf[SxProps[Theme]],
                                ).asInstanceOf[Box.Props],
                            )(
                              if (mem.scope != MemoryScope.Shared)
                                MuiButton
                                  .variant("outlined")
                                  .size("small")
                                  .onClick(() => markShared(mem.id).runNow())("Share")
                              else
                                MuiButton
                                  .variant("outlined")
                                  .size("small")
                                  .onClick(() => markPrivate(mem.id).runNow())("Privatize"),
                              MuiButton
                                .variant("outlined")
                                .color("error")
                                .size("small")
                                .onClick(() => forget(mem.id).runNow())("Forget"),
                            ),
                          ),
                        )
                      }*,
                    ),
                  ),
                ),
                MuiTablePagination
                  .component("div")
                  .count(state.value.memories.size)
                  .page(state.value.page)
                  .rowsPerPage(state.value.rowsPerPage)
                  .rowsPerPageOptions(js.Array(5, 10, 25, 50))
                  .onPageChange(
                    (
                      _,
                      p,
                    ) => state.modState(_.copy(page = p)).runNow(),
                  )
                  .onRowsPerPageChange(e =>
                    state
                      .modState(_.copy(rowsPerPage = e.target.value.asInstanceOf[String].toInt, page = 0))
                      .runNow(),
                  )(),
              ),
            // Store Memory dialog
            Dialog(state.value.showStore)(
              DialogTitle()("Remember"),
              DialogContent()(
                MuiTextField
                  .label("Key")
                  .value(state.value.storeForm.key)
                  .fullWidth(true)
                  .variant("outlined")
                  .size("small")
                  .sx(js.Dynamic.literal(mt = 1, mb = 2))
                  .onChange { e =>
                    val v = e.target.value.asInstanceOf[String]
                    state.modState(s => s.copy(storeForm = s.storeForm.copy(key = v))).runNow()
                  },
                MuiTextField
                  .label("Text")
                  .value(state.value.storeForm.text)
                  .fullWidth(true)
                  .multiline(true)
                  .rows(4)
                  .variant("outlined")
                  .size("small")
                  .sx(js.Dynamic.literal(mb = 2))
                  .onChange { e =>
                    val v = e.target.value.asInstanceOf[String]
                    state.modState(s => s.copy(storeForm = s.storeForm.copy(text = v))).runNow()
                  },
                FormControl.withProps(
                  js.Dynamic
                    .literal(fullWidth = true, size = "small", sx = js.Dynamic.literal(mb = 2))
                    .asInstanceOf[FormControl.Props],
                )(
                  InputLabel.withProps(
                    js.Dynamic.literal(id = "scope-label").asInstanceOf[InputLabel.Props],
                  )("Scope"),
                  MuiSelect
                    .value(state.value.storeForm.scope.toString)
                    .label("Scope")
                    .fullWidth(true)
                    .size("small")
                    .onChange { e =>
                      val scope = MemoryScope.values
                        .find(_.toString.equalsIgnoreCase(e.target.value.asInstanceOf[String].trim))
                        .getOrElse(MemoryScope.User)
                      state
                        .modState(s => s.copy(storeForm = s.storeForm.copy(scope = scope)))
                        .runNow()
                    }(
                      MuiMenuItem.value(MemoryScope.User.toString)("User (permanent — private to you)"),
                      MuiMenuItem.value(MemoryScope.Shared.toString)("Shared (permanent — visible to all agents)"),
                      MuiMenuItem.value(MemoryScope.Workspace.toString)("Workspace (permanent — this workspace only)"),
                      MuiMenuItem.value(MemoryScope.Private.toString)(
                        "Private (ephemeral — this agent session only)",
                      ),
                    ),
                ),
              ),
              DialogActions()(
                MuiButton.onClick(() => state.modState(_.copy(showStore = false)).runNow())("Cancel"),
                MuiButton
                  .variant("contained")
                  .disabled(state.value.storeForm.key.trim.isEmpty || state.value.storeForm.text.trim.isEmpty)
                  .onClick(() => storeMemory().runNow())("Remember"),
              ),
            ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
