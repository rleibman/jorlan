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

import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

object ApprovalsPage {

  case class State(
    approvals: List[ApprovalRequest],
    loading:   Boolean,
    wsHandler: Option[WebSocketHandler],
    error:     Option[String],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(List.empty, loading = true, wsHandler = None, error = None))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          CallbackTo {
            AsyncCallbackRepositories.permission
              .listApprovals()
              .flatMap { approvals =>
                state
                  .setState(state.value.copy(approvals = approvals, loading = false))
                  .asAsyncCallback
              }
              .completeWith {
                case scala.util.Failure(ex) =>
                  state.setState(state.value.copy(loading = false, error = Some(ex.getMessage)))
                case _ => Callback.empty
              }
              .runNow()

            val handler = AsyncCallbackRepositories.subscribeToApprovals(
              onData = { newApproval =>
                val existing = state.value.approvals
                if (existing.exists(_.id == newApproval.id)) Callback.empty
                else state.setState(state.value.copy(approvals = existing :+ newApproval))
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
              AsyncCallbackRepositories.permission
                .decideApproval(id, approve)
                .flatMap { result =>
                  if (result)
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
            Typography.withProps(
              TypographyOwnProps()
                .setVariant("h5").setSx(js.Dynamic.literal(mb = 2).asInstanceOf[SxProps[Theme]]).asInstanceOf[
                  Typography.Props,
                ],
            )("Pending Approvals"),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            if (state.value.loading)
              CircularProgress()
            else if (state.value.approvals.isEmpty)
              Alert.severity("info")("No pending approvals.")
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
                            Chip.withProps(
                              ChipOwnProps()
                                .setLabel(approval.riskClass.toString)
                                .setColor(
                                  approval.riskClass match {
                                    case RiskClass.ReadOnly | RiskClass.WorkspaceWrite      => "success"
                                    case RiskClass.Destructive | RiskClass.ExternalEffect   => "warning"
                                    case RiskClass.Privileged | RiskClass.SecuritySensitive => "error"
                                  },
                                )
                                .setSize("small")
                                .asInstanceOf[Chip.Props],
                            )(),
                          ),
                          TableCell()(approval.agentId.map(_.value.toString).getOrElse("—")),
                          TableCell()(approval.createdAt.toString.take(19)),
                          TableCell()(
                            Box.withProps(
                              BoxOwnProps[Theme]()
                                .setSx(
                                  js.Dynamic.literal(display = "flex", gap = 1).asInstanceOf[SxProps[Theme]],
                                ).asInstanceOf[Box.Props],
                            )(
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
