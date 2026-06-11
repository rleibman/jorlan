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
import jorlan.web.graphql.client.JorlanClient
import jorlan.web.graphql.client.JorlanClientDecoders._
import net.leibman.jorlan.muiMaterial.components.*

import scala.language.unsafeNulls
import scala.scalajs.js

object SessionsPage {

  case class State(
    sessions:        List[JorlanClient.AgentSession.AgentSessionView],
    models:          List[JorlanClient.ModelInfoGql.ModelInfoView],
    loading:         Boolean,
    error:           Option[String],
    showCreate:      Boolean,
    selectedModelId: Option[ModelId],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(Nil, Nil, loading = true, error = None, showCreate = false, selectedModelId = None))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            JorlanWebApp
              .makeAdapter()
              .asyncCalibanCallWithAuth(
                JorlanClient.Queries.listSessions()(JorlanClient.AgentSession.view),
              )
              .flatMap(sessions =>
                state.setState(state.value.copy(sessions = sessions.getOrElse(Nil), loading = false)).asAsyncCallback,
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
          val adapter = JorlanWebApp.makeAdapter()

          def terminate(sessionId: AgentSessionId): Callback =
            Callback {
              adapter
                .asyncCalibanCallWithAuth(JorlanClient.Mutations.terminateSession(sessionId))
                .flatMap { _ =>
                  state
                    .setState(state.value.copy(sessions = state.value.sessions.filterNot(_.id == sessionId)))
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
              // Fetch available models when the dialog opens
              adapter
                .asyncCalibanCallWithAuth(
                  JorlanClient.Queries.availableModels(JorlanClient.ModelInfoGql.view),
                )
                .flatMap { models =>
                  state.setState(state.value.copy(models = models.getOrElse(Nil), showCreate = true)).asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }

          def createSession(): Callback =
            Callback {
              adapter
                .asyncCalibanCallWithAuth(
                  JorlanClient.Mutations.createSession(state.value.selectedModelId)(JorlanClient.AgentSession.view),
                )
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

          <.div(
            Box.set("sx", js.Dynamic.literal(display = "flex", alignItems = "center", mb = 2, gap = 2))(
              Typography.set("variant", "h5")("Sessions"),
              MuiButton
                .variant("contained")
                .size("small")
                .onClick(() => openCreateDialog().runNow())("+ New Session"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.set("severity", "error")(err)),
            if (state.value.loading)
              CircularProgress()
            else
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
                    state.value.sessions.map { session =>
                      TableRow.withKey(session.id.value.toString)(
                        TableCell()(
                          <.span(^.title := session.id.value.toString)(
                            session.id.value.toString.take(8) + "…",
                          ),
                        ),
                        TableCell()(
                          Chip.set("label", session.status.toString).set("size", "small")(),
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
            // Create Session dialog
            Dialog(state.value.showCreate)(
              DialogTitle()("New Session"),
              DialogContent()(
                Typography.set("variant", "body2").set("sx", js.Dynamic.literal(mb = 1))("Model (optional):"),
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
