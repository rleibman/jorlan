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
import jorlan.web.components.{MuiButton, MuiTextField}
import jorlan.web.graphql.ScalaJSClientAdapter
import jorlan.web.graphql.client.JorlanClient
import jorlan.web.graphql.client.JorlanClientDecoders._
import net.leibman.jorlan.muiMaterial.components.*
import sttp.model.Uri

import scala.language.unsafeNulls
import scala.scalajs.js

object MemoryPage {

  case class State(
    memories: List[JorlanClient.MemoryRecord.MemoryRecordView],
    search:   String,
    loading:  Boolean,
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(Nil, "", loading = true))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            val adapter = ScalaJSClientAdapter(
              Uri
                .parse(
                  s"${if (org.scalajs.dom.window.location.protocol == "https:") "https" else "http"}://${org.scalajs.dom.window.location.host}/api/jorlan",
                )
                .fold(_ => throw new Exception("bad uri"), identity),
              JorlanWebApp.connectionId,
            )
            adapter
              .asyncCalibanCallWithAuth(
                JorlanClient.Queries.listMemory()(JorlanClient.MemoryRecord.view),
              )
              .flatMap(memories => state.setState(State(memories.getOrElse(Nil), "", loading = false)).asAsyncCallback)
              .completeWith(_ => Callback.empty)
              .runNow()
          }
      }
      .render {
        (
          _,
          state,
        ) =>
          def forget(id: MemoryRecordId): Callback =
            Callback {
              val adapter = ScalaJSClientAdapter(
                Uri
                  .parse(
                    s"${if (org.scalajs.dom.window.location.protocol == "https:") "https" else "http"}://${org.scalajs.dom.window.location.host}/api/jorlan",
                  )
                  .fold(_ => throw new Exception("bad uri"), identity),
                JorlanWebApp.connectionId,
              )
              adapter
                .asyncCalibanCallWithAuth(JorlanClient.Mutations.forgetMemory(id))
                .flatMap(_ =>
                  state.setState(state.value.copy(memories = state.value.memories.filter(_.id != id))).asAsyncCallback,
                )
                .completeWith(_ => Callback.empty)
                .runNow()
            }

          val filtered = state.value.memories.filter { m =>
            val q = state.value.search.toLowerCase
            q.isEmpty || m.value.toLowerCase.contains(q) || m.recordKey.toLowerCase.contains(q)
          }

          <.div(
            Typography.set("variant", "h5").set("sx", js.Dynamic.literal(mb = 2))("Memory"),
            MuiTextField
              .label("Search")
              .value(state.value.search)
              .variant("outlined")
              .size("small")
              .sx(js.Dynamic.literal(mb = 2, width = 300))
              .onChange(e => state.setState(state.value.copy(search = e.target.value.asInstanceOf[String])).runNow()),
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
                    filtered.map { mem =>
                      TableRow.withKey(mem.id.value.toString)(
                        TableCell()(mem.recordKey),
                        TableCell()(
                          Chip.set("label", mem.scope).set("size", "small")(),
                        ),
                        TableCell()(
                          <.span(^.title := mem.value)(
                            if (mem.value.length > 60) mem.value.take(60) + "…" else mem.value,
                          ),
                        ),
                        TableCell()(mem.createdAt.toString.take(19)),
                        TableCell()(
                          MuiButton
                            .variant("outlined")
                            .color("error")
                            .size("small")
                            .onClick(() => forget(mem.id).runNow())("Forget"),
                        ),
                      )
                    }*,
                  ),
                ),
              ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
