/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
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
                  .modState(_.copy(approvals = approvals, loading = false))
                  .asAsyncCallback
              }
              .completeWith {
                case scala.util.Failure(ex) =>
                  state.modState(_.copy(loading = false, error = Some(ex.getMessage)))
                case _ => Callback.empty
              }
              .runNow()

            val handler = AsyncCallbackRepositories.subscribeToApprovals(
              onData = { newApproval =>
                // Dedup decision must happen inside the updater: reading state.value first and
                // appending later can clobber updates that land in between.
                state.modState(s =>
                  if (s.approvals.exists(_.id == newApproval.id)) s
                  else s.copy(approvals = s.approvals :+ newApproval),
                )
              },
            )
            state.modState(_.copy(wsHandler = Some(handler))).runNow()
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
                      .modState(s => s.copy(approvals = s.approvals.filterNot(_.id == id)))
                      .asAsyncCallback
                  else
                    AsyncCallback.unit
                }
                .completeWith {
                  case scala.util.Failure(ex) =>
                    state.modState(_.copy(error = Some(ex.getMessage)))
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
                          TableCell()(PageUtils.formatTimestamp(approval.createdAt)),
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
