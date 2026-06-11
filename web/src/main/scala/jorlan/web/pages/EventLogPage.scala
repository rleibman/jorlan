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
import jorlan.web.components.MuiButton
import jorlan.web.graphql.ScalaJSClientAdapter
import jorlan.web.graphql.client.JorlanClient
import jorlan.web.graphql.client.JorlanClientDecoders._
import net.leibman.jorlan.muiMaterial.components.*
import sttp.model.Uri

import scala.language.unsafeNulls
import scala.scalajs.js

/** Live event log tail via WebSocket subscription. */
object EventLogPage {

  case class State(
    events:   List[JorlanClient.EventLogJson.EventLogJsonView],
    running:  Boolean,
    expanded: Set[EventLogId],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(Nil, running = false, Set.empty))
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
            adapter.makeWebSocketClient(
              webSocket = None,
              query = JorlanClient.Subscriptions.eventLogTail(JorlanClient.EventLogJson.view),
              operationId = "event-log-tail",
              socketConnectionId = "event-log",
              onData = {
                (
                  _,
                  dataOpt,
                ) =>
                  dataOpt.flatten.fold(Callback.empty) { event =>
                    state.setState(
                      state.value.copy(
                        events = (event :: state.value.events).take(200),
                        running = true,
                      ),
                    )
                  }
              },
            )
            state.setState(state.value.copy(running = true)).runNow()
          }
      }
      .render {
        (
          _,
          state,
        ) =>
          <.div(
            Box.set("sx", js.Dynamic.literal(display = "flex", alignItems = "center", mb = 2, gap = 2))(
              Typography.set("variant", "h5")("Event Log"),
              if (state.value.running)
                Chip.set("label", "Live").set("color", "success").set("size", "small")()
              else
                Chip.set("label", "Disconnected").set("color", "default").set("size", "small")(),
            ),
            if (state.value.events.isEmpty)
              Alert.set("severity", "info")("Waiting for events…")
            else
              TableContainer()(
                Table()(
                  TableHead()(
                    TableRow()(
                      TableCell()("Time"),
                      TableCell()("Type"),
                      TableCell()("Actor"),
                      TableCell()("Session"),
                      TableCell()(""),
                    ),
                  ),
                  TableBody()(
                    state.value.events.flatMap { event =>
                      val isExpanded = state.value.expanded.contains(event.id)
                      scala.List[VdomElement](
                        TableRow
                          .withKey(event.id.value.toString)(
                            TableCell()(event.occurredAt.toString.take(19)),
                            TableCell()(
                              Chip.set("label", event.eventType.toString).set("size", "small")(),
                            ),
                            TableCell()(event.actorId.map(_.value.toString).getOrElse("—")),
                            TableCell()(event.sessionId.map(_.value.toString).getOrElse("—")),
                            TableCell()(
                              if (event.payloadJson.isDefined)
                                MuiButton
                                  .size("small")
                                  .onClick(() =>
                                    state
                                      .setState(
                                        state.value.copy(
                                          expanded =
                                            if (isExpanded) state.value.expanded - event.id
                                            else state.value.expanded + event.id,
                                        ),
                                      )
                                      .runNow(),
                                  )(if (isExpanded) "▲" else "▼")
                              else EmptyVdom,
                            ),
                          ).build,
                      ) ++ (if (isExpanded && event.payloadJson.isDefined) {
                              scala.List[VdomElement](
                                TableRow
                                  .withKey(s"${event.id.value}-detail")(
                                    TableCell
                                      .colSpan(5)(
                                        <.pre(
                                          ^.style := js.Dynamic.literal(
                                            fontFamily = "monospace",
                                            fontSize = "0.8rem",
                                            overflow = "auto",
                                            maxHeight = "200px",
                                          ),
                                        )(event.payloadJson.getOrElse("")),
                                      ),
                                  ).build,
                              )
                            } else scala.Nil)
                    }*,
                  ),
                ),
              ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
