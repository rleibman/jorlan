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

import caliban.WebSocketHandler
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.AsyncCallbackRepositories
import jorlan.web.components.MuiButton
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}
import zio.json.ast.Json

import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js
import scala.scalajs.js.timers

/** Live event log tail via WebSocket subscription. */
object EventLogPage {

  case class State(
    events:    List[EventLog[Json]],
    running:   Boolean,
    expanded:  Set[EventLogId],
    wsHandler: Option[WebSocketHandler],
    error:     Option[String],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(List.empty, running = false, Set.empty, None, error = None))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          CallbackTo {
            var pendingBatch:   List[EventLog[Json]] = List.empty
            var flushScheduled: Boolean = false

            def scheduledFlush(): Unit = {
              val batch = pendingBatch
              pendingBatch = List.empty
              flushScheduled = false
              if (batch.nonEmpty) {
                state
                  .setState(
                    state.value.copy(events = (batch.reverse ::: state.value.events).take(200)),
                  ).runNow()
              }
            }

            val handler = AsyncCallbackRepositories.subscribeToEventLog(
              onData = { event =>
                Callback {
                  pendingBatch = event :: pendingBatch
                  if (!flushScheduled) {
                    flushScheduled = true
                    timers.setTimeout(100)(scheduledFlush())
                  }
                }
              },
              onConnected = state.setState(state.value.copy(running = true, error = None)),
              onDisconnected = state.setState(state.value.copy(running = false)),
              onClientError = ex => state.setState(state.value.copy(error = Some(ex.getMessage), running = false)),
            )
            state.setState(state.value.copy(wsHandler = Some(handler))).runNow()
            // cleanup: close the subscription when the component unmounts
            handler.close()
          }
      }
      .render {
        (
          _,
          state,
        ) =>
          <.div(
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", alignItems = "center", mb = 2, gap = 2).asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography.withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])("Event Log"),
              if (state.value.running)
                Chip.withProps(
                  ChipOwnProps().setLabel("Live").setColor("success").setSize("small").asInstanceOf[Chip.Props],
                )()
              else
                Chip.withProps(
                  ChipOwnProps().setLabel("Disconnected").setColor("default").setSize("small").asInstanceOf[Chip.Props],
                )(),
              state.value.wsHandler.fold[VdomNode](EmptyVdom) { handler =>
                MuiButton
                  .size("small")
                  .variant("outlined")
                  .color("warning")
                  .onClick { () =>
                    handler
                      .close()
                      .flatMap(_ => state.setState(state.value.copy(running = false, wsHandler = None)))
                      .runNow()
                  }("Disconnect")
              },
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            if (state.value.events.isEmpty)
              Alert.severity("info")("Waiting for events…")
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
                              Chip.withProps(
                                ChipOwnProps()
                                  .setLabel(event.eventType.toString).setSize("small").asInstanceOf[Chip.Props],
                              )(),
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
                                        )(event.payloadJson.fold("")(_.toString)),
                                      ),
                                  ).build,
                              )
                            } else scala.List.empty)
                    }*,
                  ),
                ),
              ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
