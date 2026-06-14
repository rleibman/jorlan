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
import jorlan.web.components.MuiButton
import caliban.WebSocketHandler
import net.leibman.jorlan.muiMaterial.components.*

import scala.language.unsafeNulls
import scala.scalajs.js

object ApprovalsPage {

  case class State(
    approvals: List[JorlanClient.ApprovalRequest.ApprovalRequestView],
    loading:   Boolean,
    wsHandler: Option[WebSocketHandler],
    error:     Option[String],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(Nil, loading = true, wsHandler = None, error = None))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          CallbackTo {
            // Initial load of pending approvals
            JorlanWebApp
              .makeAdapter()
              .asyncCalibanCallWithAuth(
                JorlanClient.Queries.listApprovals(JorlanClient.ApprovalRequest.view),
              )
              .flatMap { approvals =>
                state
                  .setState(state.value.copy(approvals = approvals.getOrElse(Nil), loading = false))
                  .asAsyncCallback
              }
              .completeWith {
                case scala.util.Failure(ex) =>
                  state.setState(state.value.copy(loading = false, error = Some(ex.getMessage)))
                case _ => Callback.empty
              }
              .runNow()

            // Subscribe to real-time approval notifications
            val handler = JorlanWebApp
              .makeAdapter().makeWebSocketClient(
                webSocket = None,
                query = JorlanClient.Subscriptions.approvalNotifications(JorlanClient.ApprovalRequest.view),
                operationId = "approvals-subscription",
                socketConnectionId = "approvals",
                onData = {
                  (
                    _,
                    dataOpt,
                  ) =>
                    dataOpt.flatten.fold(Callback.empty) { newApproval =>
                      // Append new pending approval if not already present
                      val existing = state.value.approvals
                      if (existing.exists(_.id == newApproval.id)) Callback.empty
                      else
                        state.setState(state.value.copy(approvals = existing :+ newApproval))
                    }
                },
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
          def decide(
            id:      ApprovalRequestId,
            approve: Boolean,
          ): Callback =
            Callback {
              JorlanWebApp
                .makeAdapter()
                .asyncCalibanCallWithAuth(
                  JorlanClient.Mutations.decideApproval(id, approve, None),
                )
                .flatMap { result =>
                  // Only remove from local state if the server confirmed success
                  if (result.getOrElse(false))
                    state
                      .setState(
                        state.value.copy(approvals = state.value.approvals.filterNot(_.id == id)),
                      )
                      .asAsyncCallback
                  else
                    AsyncCallback.unit
                }
                .completeWith {
                  case scala.util.Failure(ex) =>
                    state.setState(state.value.copy(error = Some(ex.getMessage)))
                  case _ => Callback.empty
                }
                .runNow()
            }

          <.div(
            Typography.set("variant", "h5").set("sx", js.Dynamic.literal(mb = 2))("Pending Approvals"),
            state.value.error.fold(EmptyVdom)(err => Alert.set("severity", "error")(err)),
            if (state.value.loading)
              CircularProgress()
            else if (state.value.approvals.isEmpty)
              Alert.set("severity", "info")("No pending approvals.")
            else
              TableContainer()(
                Table()(
                  TableHead()(
                    TableRow()(
                      TableCell()("Capability"),
                      TableCell()("Risk"),
                      TableCell()("Agent"),
                      TableCell()("Requested"),
                      TableCell()("Actions"),
                    ),
                  ),
                  TableBody()(
                    state.value.approvals
                      .filter(_.status == ApprovalStatus.Pending)
                      .map { approval =>
                        TableRow.withKey(approval.id.value.toString)(
                          TableCell()(approval.capability.value),
                          TableCell()(
                            Chip
                              .set(
                                "label",
                                approval.riskClass.toString,
                              )
                              .set(
                                "color",
                                approval.riskClass match {
                                  case RiskClass.ReadOnly | RiskClass.WorkspaceWrite      => "success"
                                  case RiskClass.Destructive | RiskClass.ExternalEffect   => "warning"
                                  case RiskClass.Privileged | RiskClass.SecuritySensitive => "error"
                                },
                              )
                              .set("size", "small")(),
                          ),
                          TableCell()(approval.agentId.map(_.value.toString).getOrElse("—")),
                          TableCell()(approval.createdAt.toString.take(19)),
                          TableCell()(
                            Box.set("sx", js.Dynamic.literal(display = "flex", gap = 1))(
                              MuiButton
                                .variant("contained")
                                .color("success")
                                .size("small")
                                .onClick(() => decide(approval.id, approve = true).runNow())("Approve"),
                              MuiButton
                                .variant("outlined")
                                .color("error")
                                .size("small")
                                .onClick(() => decide(approval.id, approve = false).runNow())("Deny"),
                            ),
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
