/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.pages

import jorlan.web.components.MuiExtensions.*
import net.leibman.jorlan.muiMaterial.muiMaterialStrings as MuiStrings
import net.leibman.jorlan.muiMaterial.components.{Button, TextField}
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.AsyncCallbackRepositories
import jorlan.web.components.*
import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme

import scala.language.unsafeNulls
import scala.scalajs.js

object CustomSkillsPage {

  case class State(
    pending:      List[SkillVersionInfo],
    allCustom:    List[SkillVersionInfo],
    loading:      Boolean,
    error:        Option[String],
    showWizard:   Boolean,
    rejectTarget: Option[Long],
    rejectReason: String,
    rejecting:    Boolean,
    approving:    Option[Long],
    toast:        Option[ToastMessage],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(
          pending = List.empty,
          allCustom = List.empty,
          loading = true,
          error = None,
          showWizard = false,
          rejectTarget = None,
          rejectReason = "",
          rejecting = false,
          approving = None,
          toast = None,
        ),
      )
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories.skillLifecycle
              .pendingSkillVersions()
              .zipWith(AsyncCallbackRepositories.skillLifecycle.allCustomSkills()) {
                (
                  p,
                  a,
                ) => (p, a)
              }
              .flatMap { case (p, a) =>
                state.modState(_.copy(pending = p, allCustom = a, loading = false)).asAsyncCallback
              }
              .completeWith {
                case scala.util.Failure(ex) =>
                  state.modState(_.copy(loading = false, error = Some(ex.getMessage)))
                case _ => Callback.empty
              }
              .runNow()
          }
      }
      .render {
        (
          user,
          state,
        ) =>
          def reload(): Callback =
            Callback {
              AsyncCallbackRepositories.skillLifecycle
                .pendingSkillVersions()
                .zipWith(AsyncCallbackRepositories.skillLifecycle.allCustomSkills()) {
                  (
                    p,
                    a,
                  ) => (p, a)
                }
                .flatMap { case (p, a) =>
                  state.modState(_.copy(pending = p, allCustom = a)).asAsyncCallback
                }
                .completeWith { case _ => Callback.empty }
                .runNow()
            }

          def approve(versionId: Long): Callback =
            Callback {
              state.modState(_.copy(approving = Some(versionId))).runNow()
              AsyncCallbackRepositories.skillLifecycle
                .approveSkillVersion(versionId)
                .flatMap { _ =>
                  state
                    .modState(
                      _.copy(
                        approving = None,
                        toast = Some(ToastMessage("Skill approved and activated.", ToastSeverity.Success)),
                      ),
                    )
                    .asAsyncCallback >> reload().asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) =>
                    state.modState(_.copy(approving = None, error = Some(ex.getMessage)))
                  case _ => Callback.empty
                }
                .runNow()
            }

          def reject(): Callback =
            state.value.rejectTarget.fold(Callback.empty) { versionId =>
              Callback {
                state.modState(_.copy(rejecting = true)).runNow()
                AsyncCallbackRepositories.skillLifecycle
                  .rejectSkillVersion(versionId, state.value.rejectReason)
                  .flatMap { _ =>
                    state
                      .modState(
                        _.copy(
                          rejecting = false,
                          rejectTarget = None,
                          rejectReason = "",
                          toast = Some(ToastMessage("Skill rejected.", ToastSeverity.Info)),
                        ),
                      )
                      .asAsyncCallback >> reload().asAsyncCallback
                  }
                  .completeWith {
                    case scala.util.Failure(ex) =>
                      state.modState(_.copy(rejecting = false, error = Some(ex.getMessage)))
                    case _ => Callback.empty
                  }
                  .runNow()
              }
            }

          def statusChipColor(status: SkillStatus)
            : MuiStrings.default | MuiStrings.warning | MuiStrings.success | MuiStrings.error =
            status match {
              case SkillStatus.Draft | SkillStatus.Validated | SkillStatus.PermissionReviewed |
                  SkillStatus.SandboxTested =>
                MuiStrings.default
              case SkillStatus.AwaitingApproval                 => MuiStrings.warning
              case SkillStatus.Active                           => MuiStrings.success
              case SkillStatus.Deprecated | SkillStatus.Revoked => MuiStrings.error
            }

          <.div(
            Toast(message = state.value.toast, onClose = state.modState(_.copy(toast = None))),
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", justifyContent = "space-between", alignItems = "center", mb = 2)
                    .asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography.withProps(TypographyOwnProps().setVariant(MuiStrings.h5).asInstanceOf[Typography.Props])(
                "Custom Skills",
              ),
              Button
                .variant(MuiStrings.contained)
                .onClick(_ => state.modState(_.copy(showWizard = true)))(
                  "+ Create Custom Skill",
                ),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity(MuiStrings.error)(err)),
            if (state.value.loading) {
              CircularProgress()
            } else {
              <.div(
                if (state.value.pending.nonEmpty) {
                  <.div(
                    Typography.withProps(TypographyOwnProps().setVariant(MuiStrings.h6).asInstanceOf[Typography.Props])(
                      "Pending Approval",
                    ),
                    TableContainer()(
                      Table()(
                        TableHead()(
                          TableRow()(
                            TableCell()("Skill Name"),
                            TableCell()("Version"),
                            TableCell()("Tier"),
                            TableCell()("Created"),
                            TableCell()("Actions"),
                          ),
                        ),
                        TableBody()(
                          state.value.pending.map { sv =>
                            TableRow.withKey(sv.id.toString)(
                              TableCell()(sv.skillName),
                              TableCell()(sv.version),
                              TableCell()(sv.tier),
                              TableCell()(PageUtils.formatTimestamp(sv.createdAt)),
                              TableCell()(
                                Box.withProps(
                                  BoxOwnProps[Theme]()
                                    .setSx(
                                      js.Dynamic
                                        .literal(display = "flex", gap = 1)
                                        .asInstanceOf[SxProps[Theme]],
                                    ).asInstanceOf[Box.Props],
                                )(
                                  Button
                                    .variant(MuiStrings.contained)
                                    .color(MuiStrings.success)
                                    .size(MuiStrings.small)
                                    .disabled(state.value.approving.isDefined)
                                    .onClick(_ => approve(sv.id))("Approve"),
                                  Button
                                    .variant(MuiStrings.outlined)
                                    .color(MuiStrings.error)
                                    .size(MuiStrings.small)
                                    .onClick(_ =>
                                      state
                                        .modState(
                                          _.copy(rejectTarget = Some(sv.id), rejectReason = ""),
                                        ),
                                    )("Reject"),
                                ),
                              ),
                            )
                          }*,
                        ),
                      ),
                    ),
                  )
                } else EmptyVdom,
                Typography.withProps(TypographyOwnProps().setVariant(MuiStrings.h6).asInstanceOf[Typography.Props])(
                  "All Custom Skills",
                ),
                if (state.value.allCustom.isEmpty) {
                  Alert.severity(MuiStrings.info)("No custom skills yet. Create one to get started.")
                } else {
                  TableContainer()(
                    Table()(
                      TableHead()(
                        TableRow()(
                          TableCell()("Skill Name"),
                          TableCell()("Version"),
                          TableCell()("Tier"),
                          TableCell()("Status"),
                          TableCell()("Created"),
                        ),
                      ),
                      TableBody()(
                        state.value.allCustom.map { sv =>
                          TableRow.withKey(sv.id.toString)(
                            TableCell()(sv.skillName),
                            TableCell()(sv.version),
                            TableCell()(sv.tier),
                            TableCell()(
                              Chip.withProps(
                                ChipOwnProps()
                                  .setLabel(sv.status.toString)
                                  .setColor(statusChipColor(sv.status))
                                  .setSize(MuiStrings.small)
                                  .asInstanceOf[Chip.Props],
                              )(),
                            ),
                            TableCell()(PageUtils.formatTimestamp(sv.createdAt)),
                          )
                        }*,
                      ),
                    ),
                  )
                },
              )
            },
            Dialog(state.value.rejectTarget.isDefined)(
              DialogTitle()("Reject Skill Version"),
              DialogContent()(
                TextField
                  .label("Rejection Reason")
                  .value(state.value.rejectReason)
                  .fullWidth(true)
                  .multiline(true)
                  .rows(3)
                  .onChange(e => state.modState(_.copy(rejectReason = e.target.value.toString))),
              ),
              DialogActions()(
                Button
                  .onClick(_ => state.modState(_.copy(rejectTarget = None, rejectReason = "")))("Cancel"),
                Button
                  .variant(MuiStrings.contained)
                  .color(MuiStrings.error)
                  .disabled(state.value.rejecting || state.value.rejectReason.trim.isEmpty)
                  .onClick(_ => reject())("Reject"),
              ),
            ),
            if (state.value.showWizard) {
              CreateSkillWizard(
                user = user,
                onClose = state.modState(_.copy(showWizard = false)),
                onCreated = state.modState(_.copy(showWizard = false)) >> reload(),
              )
            } else EmptyVdom,
          )
      }

  def apply(user: User): VdomElement = component(user)

}
