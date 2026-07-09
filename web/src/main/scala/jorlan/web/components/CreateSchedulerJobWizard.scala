/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.components

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.AsyncCallbackRepositories
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}
import net.leibman.jorlan.muiMaterial.stepperStepperMod.StepperOwnProps
import net.leibman.jorlan.muiMaterial.tableTableMod.TableOwnProps
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme

import scala.language.unsafeNulls
import scala.scalajs.js

/** 5-step wizard for creating a scheduled job. Every job is a pipeline — a single-step pipeline is the equivalent of a
  * traditional single-prompt job, so step 2 starts pre-filled with one step and an open inline editor, making the
  * common case "fill in this one step, click Next."
  */
object CreateSchedulerJobWizard {

  val stepLabels: List[String] = List(
    "Job Basics",
    "Steps",
    "Invariants & Personality",
    "Trigger",
    "Review & Create",
  )

  case class WizardState(
    step:            Int = 0,
    name:            String = "",
    maxRetries:      Int = 0,
    backoffSeconds:  Int = 60,
    backoffPolicy:   RetryBackoffPolicy = RetryBackoffPolicy.Fixed,
    missedRunPolicy: MissedRunPolicy = MissedRunPolicy.Skip,
    steps:           List[PipelineStep] = List(PipelineStepsEditor.defaultStep),
    editingStep:     Option[Int] = Some(0),
    invariants:      Map[String, String] = Map.empty,
    newKey:          String = "",
    newValue:        String = "",
    personality:     String = "",
    triggerType:     TriggerType = TriggerType.Cron,
    triggerExpr:     String = "",
    saving:          Boolean = false,
    error:           Option[String] = None,
  )

  case class Props(
    user:       User,
    editingJob: Option[SchedulerJob] = None,
    onClose:    Callback,
    onSaved:    SchedulerJob => Callback,
  )

  private def initState(editingJob: Option[SchedulerJob]): WizardState =
    editingJob match {
      case None      => WizardState()
      case Some(job) =>
        WizardState(
          name = job.name,
          maxRetries = job.maxRetries,
          backoffSeconds = job.backoffSeconds,
          backoffPolicy = job.backoffPolicy,
          missedRunPolicy = job.missedRunPolicy,
          steps = if (job.pipeline.steps.isEmpty) List(PipelineStepsEditor.defaultStep) else job.pipeline.steps,
          editingStep = None,
          invariants = job.pipeline.invariants,
          personality = job.pipeline.personality.getOrElse(""),
        )
    }

  private def columnFlex(children: VdomNode*): VdomElement =
    Box.withProps(
      BoxOwnProps[Theme]()
        .setSx(
          js.Dynamic
            .literal(display = "flex", flexDirection = "column", gap = 2)
            .asInstanceOf[SxProps[Theme]],
        ).asInstanceOf[Box.Props],
    )(children*)

  val component =
    ScalaFnComponent
      .withHooks[Props]
      .useStateBy(props => initState(props.editingJob))
      .render {
        (
          props,
          state,
        ) =>
          def setField(f: WizardState => WizardState): Callback = state.modState(f)

          def nextStep(): Callback =
            state.modState(s =>
              s.copy(
                step = (s.step + 1).min(stepLabels.length - 1),
                // Leaving the Steps page implicitly finishes editing the open step, same as "Done editing step".
                editingStep = if (s.step == 1) None else s.editingStep,
              ),
            )
          def prevStep(): Callback = state.modState(s => s.copy(step = (s.step - 1).max(0)))

          def save(): Callback =
            Callback {
              val sv = state.value
              state.modState(_.copy(saving = true, error = None)).runNow()
              val pipeline = Pipeline(
                steps = sv.steps,
                invariants = sv.invariants,
                personality = Option(sv.personality.trim).filter(_.nonEmpty),
              )
              val jobAC: AsyncCallback[SchedulerJob] = props.editingJob match {
                case Some(existing) =>
                  AsyncCallbackRepositories
                    .updateJob(
                      id = existing.id,
                      name = sv.name,
                      maxRetries = sv.maxRetries,
                      backoffSeconds = sv.backoffSeconds,
                      backoffPolicy = sv.backoffPolicy,
                      missedRunPolicy = sv.missedRunPolicy,
                    )
                    .flatMap(_ => AsyncCallbackRepositories.updateJobPipeline(existing.id, pipeline))
                case None =>
                  AsyncCallbackRepositories.createJob(
                    name = sv.name,
                    pipeline = pipeline,
                    maxRetries = sv.maxRetries,
                    backoffSeconds = sv.backoffSeconds,
                    backoffPolicy = sv.backoffPolicy,
                    missedRunPolicy = sv.missedRunPolicy,
                  )
              }
              val full =
                if (sv.triggerExpr.trim.nonEmpty)
                  jobAC.flatMap { job =>
                    AsyncCallbackRepositories
                      .addTrigger(job.id, sv.triggerType, sv.triggerExpr.trim)
                      .map(_ => job)
                  }
                else jobAC
              full
                .flatMap(job => props.onSaved(job).asAsyncCallback)
                .completeWith {
                  case scala.util.Failure(ex) =>
                    state.modState(_.copy(saving = false, error = Some(ex.getMessage)))
                  case _ => Callback.empty
                }
                .runNow()
            }

          val sv = state.value

          def canAdvanceFromSteps: Boolean =
            sv.steps.nonEmpty && sv.steps.forall(s => s.name.nonEmpty && s.userPrompt.nonEmpty)

          def stepContent: VdomElement =
            sv.step match {
              case 0 =>
                columnFlex(
                  MuiTextField
                    .label("Job Name")
                    .value(sv.name)
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                      setField(_.copy(name = v)).runNow()
                    }(),
                  MuiTextField
                    .label("Max Retries")
                    .value(sv.maxRetries.toString)
                    .`type`("number")
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                      setField(_.copy(maxRetries = v.toIntOption.getOrElse(0))).runNow()
                    }(),
                  MuiTextField
                    .label("Backoff (seconds)")
                    .value(sv.backoffSeconds.toString)
                    .`type`("number")
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                      setField(_.copy(backoffSeconds = v.toIntOption.getOrElse(60))).runNow()
                    }(),
                  Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                    "Backoff Policy",
                  ),
                  MuiSelect
                    .value(sv.backoffPolicy.toString)
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                      val policy = RetryBackoffPolicy.values.find(_.toString == v).getOrElse(RetryBackoffPolicy.Fixed)
                      setField(_.copy(backoffPolicy = policy)).runNow()
                    }(
                      MuiMenuItem.value("Fixed")("Fixed — retry after the same backoff interval each time"): VdomNode,
                      MuiMenuItem.value("Exponential")("Exponential — backoff doubles on each retry"):       VdomNode,
                    ),
                  Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                    "Missed Run Policy",
                  ),
                  MuiSelect
                    .value(sv.missedRunPolicy.toString)
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                      val policy = MissedRunPolicy.values.find(_.toString == v).getOrElse(MissedRunPolicy.Skip)
                      setField(_.copy(missedRunPolicy = policy)).runNow()
                    }(
                      MuiMenuItem
                        .value("Skip")("Skip — ignore missed windows, resume at next scheduled time"): VdomNode,
                      MuiMenuItem
                        .value("RunOnce")("Run Once — execute once immediately for all missed windows"): VdomNode,
                      MuiMenuItem
                        .value("RunAllMissed")("Run All Missed — queue one run per missed window (max 10)"): VdomNode,
                    ),
                )

              case 1 =>
                PipelineStepsEditor(
                  sv.steps,
                  sv.editingStep,
                  (
                    newSteps,
                    newEditingStep,
                  ) => setField(_.copy(steps = newSteps, editingStep = newEditingStep)),
                )

              case 2 =>
                columnFlex(
                  Typography.withProps(
                    TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props],
                  )("Pipeline Invariants"),
                  Typography.withProps(
                    TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props],
                  )(
                    "Key-value facts always injected into every step. Optional — agent-level invariants still apply.",
                  ),
                  if (sv.invariants.isEmpty)
                    <.span("No pipeline-level invariants.")
                  else
                    Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
                      TableHead()(
                        TableRow()(
                          TableCell()("Key"),
                          TableCell()("Value"),
                          TableCell()(""),
                        ),
                      ),
                      TableBody()(
                        sv.invariants.toList.sortBy(_._1).map { case (k, v) =>
                          TableRow
                            .withKey(k)(
                              TableCell()(<.code(k)),
                              TableCell()(
                                MuiTextField
                                  .value(v)
                                  .size("small")
                                  .fullWidth(true)
                                  .onChange { e =>
                                    val nv = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                                    setField(_.copy(invariants = sv.invariants + (k -> nv))).runNow()
                                  }(),
                              ),
                              TableCell()(
                                MuiButton
                                  .size("small")
                                  .color("error")
                                  .onClick(() => setField(_.copy(invariants = sv.invariants - k)).runNow())("×"),
                              ),
                            ).build
                        }*,
                      ),
                    ),
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", gap = 1, alignItems = "center")
                          .asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Box.Props],
                  )(
                    MuiTextField
                      .label("Key")
                      .value(sv.newKey)
                      .size("small")
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                        setField(_.copy(newKey = v)).runNow()
                      }(),
                    MuiTextField
                      .label("Value")
                      .value(sv.newValue)
                      .size("small")
                      .fullWidth(true)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                        setField(_.copy(newValue = v)).runNow()
                      }(),
                    MuiButton
                      .variant("outlined")
                      .size("small")
                      .onClick(() => {
                        val k = sv.newKey.trim
                        val v = sv.newValue.trim
                        if (k.nonEmpty)
                          setField(s => s.copy(invariants = s.invariants + (k -> v), newKey = "", newValue = ""))
                            .runNow()
                      })("+ Add"),
                  ),
                  Divider()(),
                  MuiTextField
                    .label("Personality (optional — blank uses accuracy mode)")
                    .value(sv.personality)
                    .fullWidth(true)
                    .multiline(true)
                    .rows(2)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                      setField(_.copy(personality = v)).runNow()
                    }(),
                )

              case 3 =>
                columnFlex(
                  Typography.withProps(
                    TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props],
                  )("Optional — a trigger can also be added later from the job row."),
                  Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                    "Trigger Type",
                  ),
                  MuiSelect
                    .value(sv.triggerType.toString)
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                      val tt = TriggerType.values.find(_.toString == v).getOrElse(TriggerType.Cron)
                      setField(_.copy(triggerType = tt)).runNow()
                    }(
                      MuiMenuItem
                        .value("Cron")("Cron — schedule with a cron expression (e.g. 0 0 9 ? * 1-5)"): VdomNode,
                      MuiMenuItem
                        .value("Interval")("Interval — repeat on an ISO 8601 duration (e.g. PT1H, PT30M)"): VdomNode,
                      MuiMenuItem.value("OneShot")(
                        "One Shot — run once at a specific datetime (e.g. 2026-07-01T09:00:00Z)",
                      ): VdomNode,
                      MuiMenuItem
                        .value("Event")("Event — fire on a named system event (e.g. agent.completed)"): VdomNode,
                    ),
                  MuiTextField
                    .label(
                      sv.triggerType match {
                        case TriggerType.Cron =>
                          "Cron Expression (optional, e.g. 0 0 9 ? * 1-5 — 6 fields, use ? for unused dom or dow)"
                        case TriggerType.Interval => "Interval (optional, ISO 8601 duration, e.g. PT1H)"
                        case TriggerType.OneShot  => "Run At (optional, ISO 8601 datetime, e.g. 2026-07-01T09:00:00Z)"
                        case TriggerType.Event    => "Event Name (optional, e.g. agent.completed)"
                      },
                    )
                    .value(sv.triggerExpr)
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                      setField(_.copy(triggerExpr = v)).runNow()
                    }(),
                )

              case _ =>
                columnFlex(
                  sv.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
                  Typography.withProps(
                    TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props],
                  )("Review"),
                  <.div(s"Name: ${sv.name}"),
                  <.div(s"Retries: ${sv.maxRetries} (${sv.backoffPolicy}, ${sv.backoffSeconds}s backoff)"),
                  <.div(s"Missed run policy: ${sv.missedRunPolicy}"),
                  <.div(s"Steps: ${sv.steps.size}"),
                  <.ul(
                    sv.steps.zipWithIndex.map { case (step, idx) =>
                      <.li(^.key := s"review-step-$idx", s"${step.name} (${step.mode}) → ${step.outputVar}")
                    }*,
                  ),
                  <.div(s"Invariants: ${sv.invariants.size}"),
                  <.div(
                    if (sv.personality.trim.isEmpty) "Personality: accuracy mode" else s"Personality: ${sv.personality}",
                  ),
                  <.div(
                    if (sv.triggerExpr.trim.isEmpty) "Trigger: none (add later from the job row)"
                    else s"Trigger: ${sv.triggerType} — ${sv.triggerExpr}",
                  ),
                )
            }

          val verb = if (props.editingJob.isDefined) "Edit" else "New"

          Dialog(true)
            .fullWidth(true)
            .maxWidth(net.leibman.jorlan.muiSystem.muiSystemStrings.lg)(
              DialogTitle()(s"$verb Scheduler Job — ${stepLabels(sv.step)}"),
              DialogContent()(
                Stepper.withProps(
                  StepperOwnProps().setActiveStep(sv.step.toDouble).asInstanceOf[Stepper.Props],
                )(
                  stepLabels.zipWithIndex.map { case (label, idx) =>
                    Step.withKey(idx.toString)(
                      StepLabel()(label),
                    )
                  }*,
                ),
                <.div(^.style := js.Dynamic.literal(marginTop = "24px"))(
                  stepContent,
                ),
              ),
              DialogActions()(
                MuiButton.disabled(sv.saving).onClick(() => props.onClose.runNow())("Cancel"),
                if (sv.step > 0) {
                  MuiButton.disabled(sv.saving).onClick(() => prevStep().runNow())("Back")
                } else EmptyVdom,
                if (sv.step < stepLabels.length - 1) {
                  val disabled = sv.step == 1 && !canAdvanceFromSteps
                  MuiButton
                    .variant("contained")
                    .disabled(disabled)
                    .onClick(() => nextStep().runNow())("Next")
                } else {
                  MuiButton
                    .variant("contained")
                    .disabled(sv.saving || sv.steps.isEmpty)
                    .onClick(() => save().runNow())(if (props.editingJob.isDefined) "Save" else "Create")
                },
              ),
            )
      }

  def apply(
    user:       User,
    editingJob: Option[SchedulerJob] = None,
    onClose:    Callback,
    onSaved:    SchedulerJob => Callback,
  ): VdomElement = component(Props(user, editingJob, onClose, onSaved))

}
