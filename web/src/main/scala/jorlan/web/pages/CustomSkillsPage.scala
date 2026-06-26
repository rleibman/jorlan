/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.pages

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
                state.setState(state.value.copy(pending = p, allCustom = a, loading = false)).asAsyncCallback
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
                  state.setState(state.value.copy(pending = p, allCustom = a)).asAsyncCallback
                }
                .completeWith { case _ => Callback.empty }
                .runNow()
            }

          def approve(versionId: Long): Callback =
            Callback {
              state.setState(state.value.copy(approving = Some(versionId))).runNow()
              AsyncCallbackRepositories.skillLifecycle
                .approveSkillVersion(versionId)
                .flatMap { _ =>
                  state
                    .setState(
                      state.value.copy(
                        approving = None,
                        toast = Some(ToastMessage("Skill approved and activated.", ToastSeverity.Success)),
                      ),
                    )
                    .asAsyncCallback >> reload().asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) =>
                    state.setState(state.value.copy(approving = None, error = Some(ex.getMessage)))
                  case _ => Callback.empty
                }
                .runNow()
            }

          def reject(): Callback =
            state.value.rejectTarget.fold(Callback.empty) { versionId =>
              Callback {
                state.setState(state.value.copy(rejecting = true)).runNow()
                AsyncCallbackRepositories.skillLifecycle
                  .rejectSkillVersion(versionId, state.value.rejectReason)
                  .flatMap { _ =>
                    state
                      .setState(
                        state.value.copy(
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
                      state.setState(state.value.copy(rejecting = false, error = Some(ex.getMessage)))
                    case _ => Callback.empty
                  }
                  .runNow()
              }
            }

          def statusChipColor(status: SkillStatus): String =
            status match {
              case SkillStatus.Draft | SkillStatus.Validated | SkillStatus.PermissionReviewed |
                  SkillStatus.SandboxTested =>
                "default"
              case SkillStatus.AwaitingApproval                 => "warning"
              case SkillStatus.Active                           => "success"
              case SkillStatus.Deprecated | SkillStatus.Revoked => "error"
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
              Typography.withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])(
                "Custom Skills",
              ),
              MuiButton
                .variant("contained")
                .onClick(() => state.setState(state.value.copy(showWizard = true)).runNow())(
                  "+ Create Custom Skill",
                ),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            if (state.value.loading) {
              CircularProgress()
            } else {
              <.div(
                if (state.value.pending.nonEmpty) {
                  <.div(
                    Typography.withProps(TypographyOwnProps().setVariant("h6").asInstanceOf[Typography.Props])(
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
                              TableCell()(sv.createdAt.toString.take(19)),
                              TableCell()(
                                Box.withProps(
                                  BoxOwnProps[Theme]()
                                    .setSx(
                                      js.Dynamic
                                        .literal(display = "flex", gap = 1)
                                        .asInstanceOf[SxProps[Theme]],
                                    ).asInstanceOf[Box.Props],
                                )(
                                  MuiButton
                                    .variant("contained")
                                    .color("success")
                                    .size("small")
                                    .disabled(state.value.approving.isDefined)
                                    .onClick(() => approve(sv.id).runNow())("Approve"),
                                  MuiButton
                                    .variant("outlined")
                                    .color("error")
                                    .size("small")
                                    .onClick(() =>
                                      state
                                        .setState(
                                          state.value.copy(rejectTarget = Some(sv.id), rejectReason = ""),
                                        )
                                        .runNow(),
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
                Typography.withProps(TypographyOwnProps().setVariant("h6").asInstanceOf[Typography.Props])(
                  "All Custom Skills",
                ),
                if (state.value.allCustom.isEmpty) {
                  Alert.severity("info")("No custom skills yet. Create one to get started.")
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
                                  .setSize("small")
                                  .asInstanceOf[Chip.Props],
                              )(),
                            ),
                            TableCell()(sv.createdAt.toString.take(19)),
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
                MuiTextField
                  .label("Rejection Reason")
                  .value(state.value.rejectReason)
                  .fullWidth(true)
                  .multiline(true)
                  .rows(3)
                  .onChange(e => state.setState(state.value.copy(rejectReason = e.target.value.toString)).runNow()),
              ),
              DialogActions()(
                MuiButton.onClick(() =>
                  state.setState(state.value.copy(rejectTarget = None, rejectReason = "")).runNow(),
                )("Cancel"),
                MuiButton
                  .variant("contained")
                  .color("error")
                  .disabled(state.value.rejecting || state.value.rejectReason.trim.isEmpty)
                  .onClick(() => reject().runNow())("Reject"),
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
