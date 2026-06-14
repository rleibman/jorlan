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
import jorlan.graphql.client.JorlanClient
import jorlan.graphql.client.JorlanClientDecoders.given
import jorlan.web.JorlanWebApp
import jorlan.web.components.{MuiButton, MuiTextField}
import net.leibman.jorlan.muiMaterial.components.*
import zio.IsSubtypeOfError.impl

import scala.language.unsafeNulls
import scala.scalajs.js

object MemoryPage {

  case class StoreForm(
    key:   String,
    text:  String,
    scope: MemoryScope = MemoryScope.User,
  )

  case class State(
    memories:  List[JorlanClient.MemoryRecord.MemoryRecordView],
    search:    String,
    loading:   Boolean,
    error:     Option[String],
    showStore: Boolean,
    storeForm: StoreForm,
  )

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
            JorlanWebApp
              .makeAdapter()
              .asyncCalibanCallWithAuth(
                JorlanClient.Queries.listMemory(MemoryScope.User)(JorlanClient.MemoryRecord.view),
              )
              .flatMap { memories =>
                state.setState(state.value.copy(memories = memories.getOrElse(Nil), loading = false)).asAsyncCallback
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
              JorlanWebApp
                .makeAdapter()
                .asyncCalibanCallWithAuth(
                  JorlanClient.Queries.listMemory(MemoryScope.User, search)(JorlanClient.MemoryRecord.view),
                )
                .flatMap { memories =>
                  state.setState(state.value.copy(memories = memories.getOrElse(Nil), loading = false)).asAsyncCallback
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
              JorlanWebApp
                .makeAdapter()
                .asyncCalibanCallWithAuth(JorlanClient.Mutations.forgetMemory(id))
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
              JorlanWebApp
                .makeAdapter()
                .asyncCalibanCallWithAuth(JorlanClient.Mutations.markMemoryShared(id)(JorlanClient.MemoryRecord.view))
                .flatMap { updated =>
                  updated.fold(AsyncCallback.unit) { mem =>
                    state
                      .setState(state.value.copy(memories = state.value.memories.map(m => if (m.id == id) mem else m)))
                      .asAsyncCallback
                  }
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }

          def markPrivate(id: MemoryRecordId): Callback =
            Callback {
              JorlanWebApp
                .makeAdapter()
                .asyncCalibanCallWithAuth(
                  JorlanClient.Mutations.markMemoryPrivate(id)(JorlanClient.MemoryRecord.view),
                )
                .flatMap { updated =>
                  updated.fold(AsyncCallback.unit) { mem =>
                    state
                      .setState(state.value.copy(memories = state.value.memories.map(m => if (m.id == id) mem else m)))
                      .asAsyncCallback
                  }
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
              JorlanWebApp
                .makeAdapter()
                .asyncCalibanCallWithAuth(
                  JorlanClient.Mutations.storeMemory(f.key, f.text, f.scope)(
                    JorlanClient.MemoryRecord.view,
                  ),
                )
                .flatMap { stored =>
                  val updated = stored.fold(state.value.memories)(m => state.value.memories :+ m)
                  state
                    .setState(
                      state.value.copy(
                        memories = updated,
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
                      TableRow.withKey(mem.id.value.toString)(
                        TableCell()(mem.recordKey),
                        TableCell()(
                          Chip.set("label", mem.scope.toString).set("size", "small")(),
                        ),
                        TableCell()(
                          <.span(^.title := mem.value)(
                            if (mem.value.length > 60) mem.value.take(60) + "…" else mem.value,
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
