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

object ApprovalsPage {

  case class State(
    approvals: List[JorlanClient.ApprovalRequest.ApprovalRequestView],
    loading:   Boolean,
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(Nil, loading = true))
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
                JorlanClient.Queries.listApprovals(JorlanClient.ApprovalRequest.view),
              )
              .flatMap(approvals => state.setState(State(approvals.getOrElse(Nil), loading = false)).asAsyncCallback)
              .completeWith(_ => Callback.empty)
              .runNow()
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
                  JorlanClient.Mutations.decideApproval(id, approve, None),
                )
                .flatMap { _ =>
                  state
                    .setState(
                      state.value.copy(approvals = state.value.approvals.filterNot(_.id == id)),
                    )
                    .asAsyncCallback
                }
                .completeWith(_ => Callback.empty)
                .runNow()
            }

          <.div(
            Typography.set("variant", "h5").set("sx", js.Dynamic.literal(mb = 2))("Pending Approvals"),
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
                                  case RiskClass.ReadOnly | RiskClass.WorkspaceWrite => "success"
                                  case RiskClass.Destructive                         => "warning"
                                  case _                                             => "error"
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
