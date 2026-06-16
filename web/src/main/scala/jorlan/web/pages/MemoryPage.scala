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
import jorlan.web.components.{MuiButton, MuiTextField}
import net.leibman.jorlan.muiMaterial.components.*
import zio.json.ast.Json

import java.time.Instant
import scala.language.unsafeNulls
import scala.scalajs.js

object MemoryPage {

  case class StoreForm(
    key:   String,
    text:  String,
    scope: MemoryScope = MemoryScope.User,
  )

  case class State(
    memories:  List[MemoryRecord],
    search:    String,
    loading:   Boolean,
    error:     Option[String],
    showStore: Boolean,
    storeForm: StoreForm,
  )

  private def valueString(v: Json): String =
    v match {
      case Json.Str(s) => s
      case other       => other.toString
    }

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(Nil, "", loading = true, error = None, showStore = false, StoreForm("", "", MemoryScope.User)))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories.memory
              .search(MemorySearch(MemoryScope.User))
              .flatMap { memories =>
                state.setState(state.value.copy(memories = memories, loading = false)).asAsyncCallback
              }
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
          def runSearch(q: String): Callback =
            Callback {
              val search = if (q.trim.isEmpty) None else Some(q.trim)
              AsyncCallbackRepositories.memory
                .search(MemorySearch(MemoryScope.User, textSearch = search))
                .flatMap { memories =>
                  state.setState(state.value.copy(memories = memories, loading = false)).asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) =>
                    state.setState(state.value.copy(loading = false, error = Some(ex.getMessage)))
                  case _ => Callback.empty
                }
                .runNow()
            }

          def forget(id: MemoryRecordId): Callback =
            Callback {
              AsyncCallbackRepositories.memory
                .delete(id)
                .flatMap(_ =>
                  state.setState(state.value.copy(memories = state.value.memories.filter(_.id != id))).asAsyncCallback,
                )
                .completeWith {
                  case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }

          def markShared(id: MemoryRecordId): Callback =
            Callback {
              AsyncCallbackRepositories.memory
                .updateScope(id, MemoryScope.Shared)
                .flatMap { count =>
                  if (count > 0L)
                    state
                      .setState(
                        state.value.copy(
                          memories =
                            state.value.memories.map(m => if (m.id == id) m.copy(scope = MemoryScope.Shared) else m),
                        ),
                      )
                      .asAsyncCallback
                  else AsyncCallback.unit
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
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
                      .setState(
                        state.value.copy(
                          memories =
                            state.value.memories.map(m => if (m.id == id) m.copy(scope = MemoryScope.Private) else m),
                        ),
                      )
                      .asAsyncCallback
                  else AsyncCallback.unit
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
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
                    .setState(
                      state.value.copy(
                        memories = state.value.memories :+ stored,
                        showStore = false,
                        storeForm = StoreForm("", "", MemoryScope.User),
                      ),
                    )
                    .asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }
          }

          <.div(
            Box.set("sx", js.Dynamic.literal(display = "flex", alignItems = "center", mb = 2, gap = 2))(
              Typography.set("variant", "h5")("Memory"),
              MuiButton
                .variant("contained")
                .size("small")
                .onClick(() => state.setState(state.value.copy(showStore = true)).runNow())("+ Remember"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.set("severity", "error")(err)),
            MuiTextField
              .label("Search")
              .value(state.value.search)
              .variant("outlined")
              .size("small")
              .sx(js.Dynamic.literal(mb = 2, width = 300))
              .onChange(e => {
                val q = e.target.value.asInstanceOf[String]
                (state.setState(state.value.copy(search = q, loading = true)) >> runSearch(q)).runNow()
              }),
            if (state.value.loading) CircularProgress()
            else
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
                    state.value.memories.map { mem =>
                      val v = valueString(mem.value)
                      TableRow.withKey(mem.id.value.toString)(
                        TableCell()(mem.recordKey),
                        TableCell()(
                          Chip.set("label", mem.scope.toString).set("size", "small")(),
                        ),
                        TableCell()(
                          <.span(^.title := v)(
                            if (v.length > 60) v.take(60) + "…" else v,
                          ),
                        ),
                        TableCell()(mem.createdAt.toString.take(19)),
                        TableCell()(
                          Box.set("sx", js.Dynamic.literal(display = "flex", gap = 1))(
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
                  .onChange(e =>
                    state
                      .setState(
                        state.value
                          .copy(storeForm = state.value.storeForm.copy(key = e.target.value.asInstanceOf[String])),
                      )
                      .runNow(),
                  ),
                MuiTextField
                  .label("Text")
                  .value(state.value.storeForm.text)
                  .fullWidth(true)
                  .multiline(true)
                  .rows(4)
                  .variant("outlined")
                  .size("small")
                  .sx(js.Dynamic.literal(mb = 2))
                  .onChange(e =>
                    state
                      .setState(
                        state.value
                          .copy(storeForm = state.value.storeForm.copy(text = e.target.value.asInstanceOf[String])),
                      )
                      .runNow(),
                  ),
                MuiTextField
                  .label("Scope (optional)")
                  .value(state.value.storeForm.scope.toString)
                  .fullWidth(true)
                  .variant("outlined")
                  .size("small")
                  .onChange(e =>
                    state
                      .setState(
                        state.value
                          .copy(storeForm =
                            state.value.storeForm
                              .copy(scope =
                                MemoryScope.values
                                  .find(
                                    _.toString.equalsIgnoreCase(e.target.value.asInstanceOf[String].trim),
                                  ).getOrElse(
                                    MemoryScope.User,
                                  ),
                              ),
                          ),
                      )
                      .runNow(),
                  ),
              ),
              DialogActions()(
                MuiButton.onClick(() => state.setState(state.value.copy(showStore = false)).runNow())("Cancel"),
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
