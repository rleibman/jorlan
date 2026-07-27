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
import jorlan.web.pages.PageUtils
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}

import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.tableTableMod.TableOwnProps
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

object SchedulerPage {

  case class AddTriggerForm(
    jobId:       SchedulerJobId,
    triggerType: TriggerType,
    expression:  String,
  )

  case class RunNowForm(
    jobId:      SchedulerJobId,
    runContext: String,
  )

  case class State(
    jobs:             scala.List[SchedulerJob],
    triggers:         Map[SchedulerJobId, scala.List[SchedulerTrigger]],
    pipelineRuns:     Map[SchedulerJobId, scala.List[PipelineRun]],
    expanded:         Set[SchedulerJobId],
    loading:          Boolean,
    error:            Option[String],
    page:             Int,
    rowsPerPage:      Int,
    showCreateWizard: Boolean,
    editingJob:       Option[SchedulerJob],
    addTriggerForm:   Option[AddTriggerForm],
    runNowForm:       Option[RunNowForm],
  )

  private def statusColor(s: JobStatus): String =
    s match {
      case JobStatus.Pending   => "primary"
      case JobStatus.Running   => "info"
      case JobStatus.Succeeded => "success"
      case JobStatus.Failed    => "error"
      case JobStatus.Cancelled => "default"
      case JobStatus.Paused    => "warning"
    }

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(
          List.empty,
          Map.empty,
          Map.empty,
          Set.empty,
          loading = true,
          error = None,
          page = 0,
          rowsPerPage = 10,
          showCreateWizard = false,
          editingJob = None,
          addTriggerForm = None,
          runNowForm = None,
        ),
      )
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories.scheduler
              .listJobs(None, 200)
              .flatMap { jobs =>
                state.modState(_.copy(jobs = jobs, loading = false, page = 0)).asAsyncCallback
              }
              .completeWith(PageUtils.onError(err => state.modState(_.copy(loading = false, error = err))))
              .runNow()
          }
      }
      .render {
        (
          user,
          state,
        ) =>
          def loadJobs(): Callback =
            Callback {
              AsyncCallbackRepositories.scheduler
                .listJobs(None, 200)
                .flatMap { jobs =>
                  state.modState(_.copy(jobs = jobs, loading = false, page = 0)).asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.modState(_.copy(loading = false, error = err))))
                .runNow()
            }

          def loadTriggers(jobId: SchedulerJobId): Callback =
            if (state.value.triggers.contains(jobId)) Callback.empty
            else
              Callback {
                AsyncCallbackRepositories.scheduler
                  .searchTriggers(TriggerSearch(jobId))
                  .flatMap { ts =>
                    state
                      .modState(s => s.copy(triggers = s.triggers + (jobId -> ts)))
                      .asAsyncCallback
                  }
                  .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                  .runNow()
              }

          def loadPipelineRuns(jobId: SchedulerJobId): Callback =
            Callback {
              AsyncCallbackRepositories.scheduler
                .listPipelineRuns(jobId)
                .flatMap { runs =>
                  state
                    .modState(s => s.copy(pipelineRuns = s.pipelineRuns + (jobId -> runs)))
                    .asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                .runNow()
            }

          def toggleExpand(jobId: SchedulerJobId): Callback =
            if (state.value.expanded.contains(jobId))
              state.modState(s => s.copy(expanded = s.expanded - jobId))
            else
              state.modState(s => s.copy(expanded = s.expanded + jobId)) >> loadTriggers(
                jobId,
              ) >> loadPipelineRuns(jobId)

          def jobAction(
            call:        AsyncCallback[Boolean],
            updateState: State => State,
          ): Callback =
            Callback {
              call
                .flatMap(_ => state.modState(updateState).asAsyncCallback)
                .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                .runNow()
            }

          def deleteJob(jobId: SchedulerJobId): Callback =
            Callback {
              AsyncCallbackRepositories.scheduler
                .deleteJob(jobId)
                .flatMap { _ =>
                  state.modState { s =>
                    val newJobs = s.jobs.filterNot(_.id == jobId)
                    val maxPage = math.max(0, (newJobs.size - 1) / s.rowsPerPage)
                    s.copy(
                      jobs = newJobs,
                      triggers = s.triggers - jobId,
                      expanded = s.expanded - jobId,
                      page = math.min(s.page, maxPage),
                    )
                  }.asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                .runNow()
            }

          def doAddTrigger(): Callback =
            state.value.addTriggerForm.fold(Callback.empty) { af =>
              Callback {
                AsyncCallbackRepositories
                  .addTrigger(af.jobId, af.triggerType, af.expression.trim)
                  .flatMap { trigger =>
                    state
                      .modState(s =>
                        s.copy(
                          triggers =
                            s.triggers + (af.jobId -> (s.triggers.getOrElse(af.jobId, scala.List.empty) :+ trigger)),
                          addTriggerForm = None,
                        ),
                      )
                      .asAsyncCallback
                  }
                  .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                  .runNow()
              }
            }

          def doDeleteTrigger(
            jobId:     SchedulerJobId,
            triggerId: SchedulerTriggerId,
          ): Callback =
            Callback {
              AsyncCallbackRepositories.scheduler
                .deleteTrigger(triggerId)
                .flatMap { _ =>
                  state
                    .modState(s =>
                      s.copy(triggers =
                        s.triggers + (jobId -> s.triggers
                          .getOrElse(jobId, scala.List.empty).filterNot(
                            _.id == triggerId,
                          )),
                      ),
                    )
                    .asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                .runNow()
            }

          def doRunNow(): Callback =
            state.value.runNowForm.fold(Callback.empty) { rf =>
              Callback {
                val ctx = Option(rf.runContext.trim).filter(_.nonEmpty)
                AsyncCallbackRepositories
                  .triggerPipeline(rf.jobId, ctx)
                  .flatMap { _ =>
                    state
                      .modState(s =>
                        s.copy(
                          runNowForm = None,
                          jobs = s.jobs.map(j => if (j.id == rf.jobId) j.copy(status = JobStatus.Pending) else j),
                          pipelineRuns = s.pipelineRuns - rf.jobId,
                        ),
                      )
                      .asAsyncCallback
                      .flatMap(_ => loadPipelineRuns(rf.jobId).asAsyncCallback)
                  }
                  .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                  .runNow()
              }
            }

          val pageJobs = state.value.jobs
            .slice(state.value.page * state.value.rowsPerPage, (state.value.page + 1) * state.value.rowsPerPage)

          <.div(
            if (state.value.showCreateWizard || state.value.editingJob.isDefined)
              CreateSchedulerJobWizard(
                user = user,
                editingJob = state.value.editingJob,
                onClose = state.modState(_.copy(showCreateWizard = false, editingJob = None)),
                onSaved = job =>
                  state.modState(s =>
                    s.copy(
                      showCreateWizard = false,
                      editingJob = None,
                      jobs =
                        if (s.jobs.exists(_.id == job.id)) s.jobs.map(j => if (j.id == job.id) job else j)
                        else s.jobs :+ job,
                    ),
                  ),
              )
            else EmptyVdom,
            state.value.addTriggerForm.fold(EmptyVdom) { af =>
              Dialog(true)(
                DialogTitle()("Add Trigger"),
                DialogContent()(
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", flexDirection = "column", gap = 2, pt = 1).asInstanceOf[SxProps[
                            Theme,
                          ]],
                      ).asInstanceOf[Box.Props],
                  )(
                    Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                      "Trigger Type",
                    ),
                    MuiSelect
                      .value(af.triggerType.toString)
                      .fullWidth(true)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                        val tt = TriggerType.values.find(_.toString == v).getOrElse(TriggerType.Cron)
                        state.modState(_.copy(addTriggerForm = Some(af.copy(triggerType = tt)))).runNow()
                      }(
                        MuiMenuItem.value("Cron")("Cron — cron expression (e.g. 0 0 9 ? * 1-5)"):        VdomNode,
                        MuiMenuItem.value("Interval")("Interval — ISO 8601 duration (e.g. PT1H)"):       VdomNode,
                        MuiMenuItem.value("OneShot")("One Shot — datetime (e.g. 2026-07-01T09:00:00Z)"): VdomNode,
                        MuiMenuItem.value("Event")("Event — system event name (e.g. agent.completed)"):  VdomNode,
                      ),
                    MuiTextField
                      .label(
                        af.triggerType match {
                          case TriggerType.Cron =>
                            "Cron Expression (e.g. 0 0 9 ? * 1-5 — 6 fields, use ? for unused dom or dow)"
                          case TriggerType.Interval => "Interval (ISO 8601, e.g. PT1H)"
                          case TriggerType.OneShot  => "Run At (ISO 8601, e.g. 2026-07-01T09:00:00Z)"
                          case TriggerType.Event    => "Event Name (e.g. agent.completed)"
                        },
                      )
                      .value(af.expression)
                      .fullWidth(true)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                        state.modState(_.copy(addTriggerForm = Some(af.copy(expression = v)))).runNow()
                      }(),
                  ),
                ),
                DialogActions()(
                  MuiButton
                    .onClick(() => state.modState(_.copy(addTriggerForm = None)).runNow())("Cancel"),
                  MuiButton
                    .variant("contained")
                    .onClick(() => doAddTrigger().runNow())("Add"),
                ),
              )
            },
            state.value.runNowForm.fold(EmptyVdom) { rf =>
              Dialog(true)(
                DialogTitle()("Run Now"),
                DialogContent()(
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", flexDirection = "column", gap = 2, pt = 1).asInstanceOf[SxProps[
                            Theme,
                          ]],
                      ).asInstanceOf[Box.Props],
                  )(
                    Typography.withProps(
                      TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props],
                    )("Optionally provide run context instructions (passed to the agent for this run only):"),
                    MuiTextField
                      .label("Run Context (optional)")
                      .value(rf.runContext)
                      .fullWidth(true)
                      .multiline(true)
                      .rows(4)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                        state.modState(_.copy(runNowForm = Some(rf.copy(runContext = v)))).runNow()
                      }(),
                  ),
                ),
                DialogActions()(
                  MuiButton
                    .onClick(() => state.modState(_.copy(runNowForm = None)).runNow())("Cancel"),
                  MuiButton
                    .variant("contained")
                    .onClick(() => doRunNow().runNow())("Run"),
                ),
              )
            },
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", alignItems = "center", mb = 2, gap = 2).asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography.withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])("Scheduler"),
              MuiButton
                .variant("contained")
                .size("small")
                .onClick(() => state.modState(_.copy(showCreateWizard = true, error = None)).runNow())("+ New Job"),
              MuiButton
                .variant("outlined")
                .size("small")
                .onClick { () =>
                  Callback {
                    AsyncCallbackRepositories.scheduler
                      .listJobs(None, 200)
                      .flatMap { jobs =>
                        state
                          .modState(
                            _.copy(
                              jobs = jobs,
                              loading = false,
                            ),
                          ).asAsyncCallback
                      }
                      .completeWith(_ => Callback.empty)
                      .runNow()
                  }
                }("Refresh"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            if (state.value.loading)
              CircularProgress()
            else if (state.value.jobs.isEmpty)
              Alert.severity("info")("No scheduler jobs found.")
            else
              <.div(
                TableContainer()(
                  Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
                    TableHead()(
                      TableRow()(
                        TableCell()("Name"),
                        TableCell()("Status"),
                        TableCell()("Scheduled"),
                        TableCell()("Retries"),
                        TableCell()("Actions"),
                        TableCell()(""),
                      ),
                    ),
                    TableBody()(
                      pageJobs.flatMap { job =>
                        val isExpanded = state.value.expanded.contains(job.id)
                        scala.List[VdomElement](
                          TableRow
                            .withKey(job.id.value.toString)(
                              TableCell()(job.name),
                              TableCell()(
                                Chip
                                  .withProps(
                                    ChipOwnProps()
                                      .setLabel(job.status.toString).setColor(statusColor(job.status)).setSize(
                                        "small",
                                      ).asInstanceOf[Chip.Props],
                                  )(),
                              ),
                              TableCell()(PageUtils.formatTimestamp(job.scheduledAt)),
                              TableCell()(s"${job.retryCount}/${job.maxRetries}"),
                              TableCell()(
                                Box.withProps(
                                  BoxOwnProps[Theme]()
                                    .setSx(
                                      js.Dynamic.literal(display = "flex", gap = 1).asInstanceOf[SxProps[Theme]],
                                    ).asInstanceOf[Box.Props],
                                )(
                                  job.status match {
                                    case JobStatus.Paused =>
                                      MuiButton
                                        .size("small")
                                        .variant("outlined")
                                        .onClick(() =>
                                          jobAction(
                                            AsyncCallbackRepositories.scheduler.resumeJob(job.id),
                                            s =>
                                              s.copy(jobs =
                                                s.jobs
                                                  .map(j =>
                                                    if (j.id == job.id) j.copy(status = JobStatus.Pending) else j,
                                                  ),
                                              ),
                                          ).runNow(),
                                        )("Resume")
                                    case JobStatus.Running | JobStatus.Pending =>
                                      MuiButton
                                        .size("small")
                                        .variant("outlined")
                                        .onClick(() =>
                                          jobAction(
                                            AsyncCallbackRepositories.scheduler.pauseJob(job.id),
                                            s =>
                                              s.copy(jobs =
                                                s.jobs
                                                  .map(j =>
                                                    if (j.id == job.id) j.copy(status = JobStatus.Paused) else j,
                                                  ),
                                              ),
                                          ).runNow(),
                                        )("Pause")
                                    case _ => EmptyVdom
                                  },
                                  if (job.status != JobStatus.Cancelled && job.status != JobStatus.Succeeded)
                                    MuiButton
                                      .size("small")
                                      .variant("outlined")
                                      .color("error")
                                      .onClick(() =>
                                        jobAction(
                                          AsyncCallbackRepositories.scheduler.cancelJob(job.id),
                                          s =>
                                            s.copy(jobs =
                                              s.jobs.map(j =>
                                                if (j.id == job.id) j.copy(status = JobStatus.Cancelled) else j,
                                              ),
                                            ),
                                        ).runNow(),
                                      )("Cancel")
                                  else EmptyVdom,
                                  MuiButton
                                    .size("small")
                                    .variant("outlined")
                                    .onClick(() =>
                                      state
                                        .modState(
                                          _.copy(
                                            error = None,
                                            runNowForm = Some(RunNowForm(job.id, "")),
                                          ),
                                        )
                                        .runNow(),
                                    )("Run Now"),
                                  MuiButton
                                    .size("small")
                                    .variant("outlined")
                                    .onClick(() =>
                                      state
                                        .modState(_.copy(error = None, editingJob = Some(job)))
                                        .runNow(),
                                    )("Edit"),
                                  MuiButton
                                    .size("small")
                                    .variant("outlined")
                                    .color("error")
                                    .onClick(() => deleteJob(job.id).runNow())("Delete"),
                                ),
                              ),
                              TableCell()(
                                MuiButton
                                  .size("small")
                                  .onClick(() => toggleExpand(job.id).runNow())(if (isExpanded) "▲" else "▼"),
                              ),
                            ).build,
                        ) ++ (if (isExpanded) {
                                val ts = state.value.triggers.getOrElse(job.id, scala.List.empty)
                                scala.List[VdomElement](
                                  TableRow
                                    .withKey(s"${job.id.value}-triggers")(
                                      TableCell.colSpan(6)(
                                        Box.withProps(
                                          BoxOwnProps[Theme]()
                                            .setSx(
                                              js.Dynamic.literal(pl = 4, pt = 1, pb = 1).asInstanceOf[SxProps[Theme]],
                                            ).asInstanceOf[Box.Props],
                                        )(
                                          job.resultJson.fold(EmptyVdom) { r =>
                                            val color =
                                              if (job.status == JobStatus.Failed) "error.main" else "text.secondary"
                                            Box.withProps(
                                              BoxOwnProps[Theme]()
                                                .setSx(
                                                  js.Dynamic.literal(mb = 1).asInstanceOf[SxProps[Theme]],
                                                ).asInstanceOf[Box.Props],
                                            )(
                                              Typography.withProps(
                                                TypographyOwnProps()
                                                  .setVariant("subtitle2").asInstanceOf[Typography.Props],
                                              )(
                                                if (job.status == JobStatus.Failed) "Error" else "Result",
                                              ),
                                              Typography.withProps(
                                                TypographyOwnProps()
                                                  .setVariant("body2")
                                                  .setSx(
                                                    js.Dynamic
                                                      .literal(
                                                        color = color,
                                                        whiteSpace = "pre-wrap",
                                                        fontFamily = "monospace",
                                                        fontSize = "0.8em",
                                                      ).asInstanceOf[SxProps[Theme]],
                                                  )
                                                  .asInstanceOf[Typography.Props],
                                              )(r),
                                            )
                                          },
                                          Box.withProps(
                                            BoxOwnProps[Theme]()
                                              .setSx(
                                                js.Dynamic
                                                  .literal(
                                                    display = "flex",
                                                    alignItems = "center",
                                                    gap = 1,
                                                    mb = 1,
                                                  ).asInstanceOf[SxProps[Theme]],
                                              ).asInstanceOf[Box.Props],
                                          )(
                                            Typography.withProps(
                                              TypographyOwnProps()
                                                .setVariant("subtitle2").asInstanceOf[Typography.Props],
                                            )("Triggers"),
                                            MuiButton
                                              .size("small")
                                              .variant("outlined")
                                              .onClick(() =>
                                                state
                                                  .modState(s =>
                                                    s.copy(
                                                      addTriggerForm = Some(
                                                        AddTriggerForm(job.id, TriggerType.Cron, ""),
                                                      ),
                                                      triggers =
                                                        if (!s.triggers.contains(job.id))
                                                          s.triggers + (job.id -> scala.List.empty)
                                                        else s.triggers,
                                                    ),
                                                  )
                                                  .runNow(),
                                              )("+ Add Trigger"),
                                          ),
                                          if (ts.isEmpty)
                                            <.span("No triggers configured.")
                                          else
                                            Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
                                              TableHead()(
                                                TableRow()(
                                                  TableCell()("Type"),
                                                  TableCell()("Expression"),
                                                  TableCell()("Enabled"),
                                                  TableCell()(""),
                                                ),
                                              ),
                                              TableBody()(
                                                ts.map { t =>
                                                  TableRow
                                                    .withKey(t.id.value.toString)(
                                                      TableCell()(t.triggerType.toString),
                                                      TableCell()(<.code(t.expression)),
                                                      TableCell()(if (t.enabled) "✓" else "✗"),
                                                      TableCell()(
                                                        MuiButton
                                                          .size("small")
                                                          .variant("outlined")
                                                          .color("error")
                                                          .onClick(() => doDeleteTrigger(job.id, t.id).runNow())(
                                                            "Delete",
                                                          ),
                                                      ),
                                                    ).build
                                                }*,
                                              ),
                                            ),
                                        ),
                                      ),
                                    ).build,
                                )
                              } else scala.List.empty) ++ (if (isExpanded) {
                                                             val runs = state.value.pipelineRuns
                                                               .getOrElse(job.id, scala.List.empty)
                                                             scala.List[VdomElement](
                                                               TableRow
                                                                 .withKey(s"${job.id.value}-pipeline-runs")(
                                                                   TableCell.colSpan(6)(
                                                                     Box.withProps(
                                                                       BoxOwnProps[Theme]()
                                                                         .setSx(
                                                                           js.Dynamic
                                                                             .literal(pl = 4, pt = 1, pb = 1)
                                                                             .asInstanceOf[SxProps[Theme]],
                                                                         ).asInstanceOf[Box.Props],
                                                                     )(
                                                                       Typography.withProps(
                                                                         TypographyOwnProps()
                                                                           .setVariant("subtitle2")
                                                                           .setSx(
                                                                             js.Dynamic
                                                                               .literal(mb = 1)
                                                                               .asInstanceOf[SxProps[Theme]],
                                                                           ).asInstanceOf[
                                                                             Typography.Props,
                                                                           ],
                                                                       )("Pipeline Runs"),
                                                                       if (runs.isEmpty)
                                                                         <.span("No pipeline runs yet.")
                                                                       else
                                                                         Table.withProps(
                                                                           TableOwnProps()
                                                                             .setSize("small").asInstanceOf[Table.Props],
                                                                         )(
                                                                           TableHead()(
                                                                             TableRow()(
                                                                               TableCell()("Run ID"),
                                                                               TableCell()("Status"),
                                                                               TableCell()("Started"),
                                                                               TableCell()("Finished"),
                                                                               TableCell()("Failed Step"),
                                                                             ),
                                                                           ),
                                                                           TableBody()(
                                                                             runs.map { run =>
                                                                               TableRow
                                                                                 .withKey(run.id.value.toString)(
                                                                                   TableCell()(run.id.value.toString),
                                                                                   TableCell()(run.status.toString),
                                                                                   TableCell()(
                                                                                     PageUtils.formatTimestamp(run.startedAt),
                                                                                   ),
                                                                                   TableCell()(
                                                                                     run.finishedAt
                                                                                       .fold("-")(PageUtils.formatTimestamp(_)),
                                                                                   ),
                                                                                   TableCell()(
                                                                                     run.failedStep.getOrElse("-"),
                                                                                   ),
                                                                                 ).build
                                                                             }*,
                                                                           ),
                                                                         ),
                                                                     ),
                                                                   ),
                                                                 ).build,
                                                             )
                                                           } else scala.List.empty)
                      }*,
                    ),
                  ),
                ),
                MuiTablePagination
                  .component("div")
                  .count(state.value.jobs.size)
                  .page(state.value.page)
                  .rowsPerPage(state.value.rowsPerPage)
                  .rowsPerPageOptions(js.Array(5, 10, 25))
                  .onPageChange(
                    (
                      _,
                      p,
                    ) => state.modState(_.copy(page = p)).runNow(),
                  )
                  .onRowsPerPageChange(e =>
                    state
                      .modState(_.copy(rowsPerPage = e.target.value.asInstanceOf[String].toInt, page = 0))
                      .runNow(),
                  )(),
              ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
