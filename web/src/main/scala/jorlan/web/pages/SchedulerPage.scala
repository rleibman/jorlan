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

  case class CreateJobForm(
    name:            String,
    prompt:          String,
    maxRetries:      Int,
    backoffSeconds:  Int,
    backoffPolicy:   RetryBackoffPolicy,
    missedRunPolicy: MissedRunPolicy,
    triggerExpr:     String,
    triggerType:     TriggerType,
  )

  case class EditJobForm(
    id:              SchedulerJobId,
    name:            String,
    prompt:          String,
    maxRetries:      Int,
    backoffSeconds:  Int,
    backoffPolicy:   RetryBackoffPolicy,
    missedRunPolicy: MissedRunPolicy,
  )

  case class AddTriggerForm(
    jobId:       SchedulerJobId,
    triggerType: TriggerType,
    expression:  String,
  )

  case class State(
    jobs:           scala.List[SchedulerJob],
    triggers:       Map[SchedulerJobId, scala.List[SchedulerTrigger]],
    expanded:       Set[SchedulerJobId],
    loading:        Boolean,
    error:          Option[String],
    page:           Int,
    rowsPerPage:    Int,
    showCreate:     Boolean,
    createForm:     CreateJobForm,
    editForm:       Option[EditJobForm],
    addTriggerForm: Option[AddTriggerForm],
  )

  private val defaultForm = CreateJobForm(
    name = "",
    prompt = "",
    maxRetries = 0,
    backoffSeconds = 60,
    backoffPolicy = RetryBackoffPolicy.Fixed,
    missedRunPolicy = MissedRunPolicy.Skip,
    triggerExpr = "",
    triggerType = TriggerType.Cron,
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
          Set.empty,
          loading = true,
          error = None,
          page = 0,
          rowsPerPage = 10,
          showCreate = false,
          createForm = defaultForm,
          editForm = None,
          addTriggerForm = None,
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
                state.setState(state.value.copy(jobs = jobs, loading = false, page = 0)).asAsyncCallback
              }
              .completeWith(PageUtils.onError(err => state.setState(state.value.copy(loading = false, error = err))))
              .runNow()
          }
      }
      .render {
        (
          _,
          state,
        ) =>
          def loadJobs(): Callback =
            Callback {
              AsyncCallbackRepositories.scheduler
                .listJobs(None, 200)
                .flatMap { jobs =>
                  state.setState(state.value.copy(jobs = jobs, loading = false, page = 0)).asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(loading = false, error = err))))
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
                      .setState(
                        state.value.copy(triggers = state.value.triggers + (jobId -> ts)),
                      )
                      .asAsyncCallback
                  }
                  .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                  .runNow()
              }

          def toggleExpand(jobId: SchedulerJobId): Callback =
            if (state.value.expanded.contains(jobId))
              state.setState(state.value.copy(expanded = state.value.expanded - jobId))
            else
              state.setState(state.value.copy(expanded = state.value.expanded + jobId)) >> loadTriggers(jobId)

          def jobAction(
            call:        AsyncCallback[Boolean],
            updateState: State => State,
          ): Callback =
            Callback {
              call
                .flatMap(_ => state.setState(updateState(state.value)).asAsyncCallback)
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          def createJob(): Callback = {
            val f = state.value.createForm
            Callback {
              val jobAC = AsyncCallbackRepositories.createJob(
                name = f.name,
                prompt = f.prompt,
                maxRetries = f.maxRetries,
                backoffSeconds = f.backoffSeconds,
                backoffPolicy = f.backoffPolicy,
                missedRunPolicy = f.missedRunPolicy,
              )
              val full: AsyncCallback[Unit] =
                if (f.triggerExpr.trim.nonEmpty) {
                  jobAC.flatMap { job =>
                    AsyncCallbackRepositories
                      .addTrigger(job.id, f.triggerType, f.triggerExpr.trim)
                      .flatMap { trigger =>
                        val updatedTriggers = state.value.triggers + (job.id -> scala.List(trigger))
                        state
                          .setState(
                            state.value.copy(
                              jobs = state.value.jobs :+ job,
                              triggers = updatedTriggers,
                              showCreate = false,
                              createForm = defaultForm,
                            ),
                          )
                          .asAsyncCallback
                      }
                  }
                } else {
                  jobAC.flatMap { job =>
                    state
                      .setState(
                        state.value.copy(
                          jobs = state.value.jobs :+ job,
                          showCreate = false,
                          createForm = defaultForm,
                        ),
                      )
                      .asAsyncCallback
                  }
                }
              full
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }
          }

          def deleteJob(jobId: SchedulerJobId): Callback =
            Callback {
              AsyncCallbackRepositories.scheduler
                .deleteJob(jobId)
                .flatMap { _ =>
                  val newJobs = state.value.jobs.filterNot(_.id == jobId)
                  val maxPage = math.max(0, (newJobs.size - 1) / state.value.rowsPerPage)
                  state
                    .setState(
                      state.value.copy(
                        jobs = newJobs,
                        triggers = state.value.triggers - jobId,
                        expanded = state.value.expanded - jobId,
                        page = math.min(state.value.page, maxPage),
                      ),
                    )
                    .asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          def saveEdit(): Callback =
            state.value.editForm.fold(Callback.empty) { ef =>
              Callback {
                AsyncCallbackRepositories
                  .updateJob(
                    id = ef.id,
                    name = ef.name,
                    prompt = ef.prompt,
                    maxRetries = ef.maxRetries,
                    backoffSeconds = ef.backoffSeconds,
                    backoffPolicy = ef.backoffPolicy,
                    missedRunPolicy = ef.missedRunPolicy,
                  )
                  .flatMap { updated =>
                    state
                      .setState(
                        state.value.copy(
                          jobs = state.value.jobs.map(j => if (j.id == updated.id) updated else j),
                          editForm = None,
                        ),
                      )
                      .asAsyncCallback
                  }
                  .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                  .runNow()
              }
            }

          def doAddTrigger(): Callback =
            state.value.addTriggerForm.fold(Callback.empty) { af =>
              Callback {
                AsyncCallbackRepositories
                  .addTrigger(af.jobId, af.triggerType, af.expression.trim)
                  .flatMap { trigger =>
                    val existing = state.value.triggers.getOrElse(af.jobId, scala.List.empty)
                    state
                      .setState(
                        state.value.copy(
                          triggers = state.value.triggers + (af.jobId -> (existing :+ trigger)),
                          addTriggerForm = None,
                        ),
                      )
                      .asAsyncCallback
                  }
                  .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
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
                  val remaining = state.value.triggers.getOrElse(jobId, scala.List.empty).filterNot(_.id == triggerId)
                  state
                    .setState(state.value.copy(triggers = state.value.triggers + (jobId -> remaining)))
                    .asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          val pageJobs = state.value.jobs
            .slice(state.value.page * state.value.rowsPerPage, (state.value.page + 1) * state.value.rowsPerPage)

          val f = state.value.createForm

          <.div(
            Dialog(state.value.showCreate)(
              DialogTitle()("New Scheduler Job"),
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
                  MuiTextField
                    .label("Job Name")
                    .value(f.name)
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                      state.setState(state.value.copy(createForm = f.copy(name = v))).runNow()
                    }(),
                  MuiTextField
                    .label("Prompt (sent to LLM on each trigger)")
                    .value(f.prompt)
                    .fullWidth(true)
                    .multiline(true)
                    .rows(3)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                      state.setState(state.value.copy(createForm = f.copy(prompt = v))).runNow()
                    }(),
                  MuiTextField
                    .label("Max Retries")
                    .value(f.maxRetries.toString)
                    .`type`("number")
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                      state
                        .setState(state.value.copy(createForm = f.copy(maxRetries = v.toIntOption.getOrElse(0))))
                        .runNow()
                    }(),
                  MuiTextField
                    .label("Backoff (seconds)")
                    .value(f.backoffSeconds.toString)
                    .`type`("number")
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                      state
                        .setState(state.value.copy(createForm = f.copy(backoffSeconds = v.toIntOption.getOrElse(60))))
                        .runNow()
                    }(),
                  Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                    "Backoff Policy",
                  ),
                  MuiSelect
                    .value(f.backoffPolicy.toString)
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                      val policy = RetryBackoffPolicy.values.find(_.toString == v).getOrElse(RetryBackoffPolicy.Fixed)
                      state.setState(state.value.copy(createForm = f.copy(backoffPolicy = policy))).runNow()
                    }(
                      MuiMenuItem.value("Fixed")("Fixed — retry after the same backoff interval each time"): VdomNode,
                      MuiMenuItem.value("Exponential")("Exponential — backoff doubles on each retry"):       VdomNode,
                    ),
                  Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                    "Missed Run Policy",
                  ),
                  MuiSelect
                    .value(f.missedRunPolicy.toString)
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                      val policy = MissedRunPolicy.values.find(_.toString == v).getOrElse(MissedRunPolicy.Skip)
                      state.setState(state.value.copy(createForm = f.copy(missedRunPolicy = policy))).runNow()
                    }(
                      MuiMenuItem
                        .value("Skip")("Skip — ignore missed windows, resume at next scheduled time"): VdomNode,
                      MuiMenuItem
                        .value("RunOnce")("Run Once — execute once immediately for all missed windows"): VdomNode,
                      MuiMenuItem
                        .value("RunAllMissed")("Run All Missed — queue one run per missed window (max 10)"): VdomNode,
                    ),
                  Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                    "Trigger Type",
                  ),
                  MuiSelect
                    .value(f.triggerType.toString)
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                      val tt = TriggerType.values.find(_.toString == v).getOrElse(TriggerType.Cron)
                      state.setState(state.value.copy(createForm = f.copy(triggerType = tt))).runNow()
                    }(
                      MuiMenuItem.value("Cron")("Cron — schedule with a cron expression (e.g. 0 9 * * 1-5)"): VdomNode,
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
                      f.triggerType match {
                        case TriggerType.Cron     => "Cron Expression (optional, e.g. 0 9 * * 1-5)"
                        case TriggerType.Interval => "Interval (optional, ISO 8601 duration, e.g. PT1H)"
                        case TriggerType.OneShot  => "Run At (optional, ISO 8601 datetime, e.g. 2026-07-01T09:00:00Z)"
                        case TriggerType.Event    => "Event Name (optional, e.g. agent.completed)"
                      },
                    )
                    .value(f.triggerExpr)
                    .fullWidth(true)
                    .onChange { e =>
                      val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                      state.setState(state.value.copy(createForm = f.copy(triggerExpr = v))).runNow()
                    }(),
                ),
              ),
              DialogActions()(
                MuiButton
                  .onClick(() =>
                    state.setState(state.value.copy(showCreate = false, createForm = defaultForm)).runNow(),
                  )("Cancel"),
                MuiButton
                  .variant("contained")
                  .onClick(() => createJob().runNow())("Create"),
              ),
            ),
            state.value.editForm.fold(EmptyVdom) { ef =>
              Dialog(true)(
                DialogTitle()("Edit Scheduler Job"),
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
                    MuiTextField
                      .label("Job Name")
                      .value(ef.name)
                      .fullWidth(true)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                        state.setState(state.value.copy(editForm = Some(ef.copy(name = v)))).runNow()
                      }(),
                    MuiTextField
                      .label("Prompt (sent to LLM on each trigger)")
                      .value(ef.prompt)
                      .fullWidth(true)
                      .multiline(true)
                      .rows(3)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                        state.setState(state.value.copy(editForm = Some(ef.copy(prompt = v)))).runNow()
                      }(),
                    MuiTextField
                      .label("Max Retries")
                      .value(ef.maxRetries.toString)
                      .`type`("number")
                      .fullWidth(true)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                        state
                          .setState(
                            state.value.copy(editForm = Some(ef.copy(maxRetries = v.toIntOption.getOrElse(0)))),
                          )
                          .runNow()
                      }(),
                    MuiTextField
                      .label("Backoff (seconds)")
                      .value(ef.backoffSeconds.toString)
                      .`type`("number")
                      .fullWidth(true)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                        state
                          .setState(
                            state.value.copy(editForm = Some(ef.copy(backoffSeconds = v.toIntOption.getOrElse(60)))),
                          )
                          .runNow()
                      }(),
                    Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                      "Backoff Policy",
                    ),
                    MuiSelect
                      .value(ef.backoffPolicy.toString)
                      .fullWidth(true)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                        val policy = RetryBackoffPolicy.values.find(_.toString == v).getOrElse(RetryBackoffPolicy.Fixed)
                        state.setState(state.value.copy(editForm = Some(ef.copy(backoffPolicy = policy)))).runNow()
                      }(
                        MuiMenuItem.value("Fixed")("Fixed — retry after the same backoff interval each time"): VdomNode,
                        MuiMenuItem.value("Exponential")("Exponential — backoff doubles on each retry"):       VdomNode,
                      ),
                    Typography.withProps(TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props])(
                      "Missed Run Policy",
                    ),
                    MuiSelect
                      .value(ef.missedRunPolicy.toString)
                      .fullWidth(true)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                        val policy = MissedRunPolicy.values.find(_.toString == v).getOrElse(MissedRunPolicy.Skip)
                        state.setState(state.value.copy(editForm = Some(ef.copy(missedRunPolicy = policy)))).runNow()
                      }(
                        MuiMenuItem
                          .value("Skip")("Skip — ignore missed windows, resume at next scheduled time"): VdomNode,
                        MuiMenuItem
                          .value("RunOnce")("Run Once — execute once immediately for all missed windows"): VdomNode,
                        MuiMenuItem
                          .value("RunAllMissed")("Run All Missed — queue one run per missed window (max 10)"): VdomNode,
                      ),
                  ),
                ),
                DialogActions()(
                  MuiButton
                    .onClick(() => state.setState(state.value.copy(editForm = None)).runNow())("Cancel"),
                  MuiButton
                    .variant("contained")
                    .onClick(() => saveEdit().runNow())("Save"),
                ),
              )
            },
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
                        state.setState(state.value.copy(addTriggerForm = Some(af.copy(triggerType = tt)))).runNow()
                      }(
                        MuiMenuItem.value("Cron")("Cron — cron expression (e.g. 0 9 * * 1-5)"):          VdomNode,
                        MuiMenuItem.value("Interval")("Interval — ISO 8601 duration (e.g. PT1H)"):       VdomNode,
                        MuiMenuItem.value("OneShot")("One Shot — datetime (e.g. 2026-07-01T09:00:00Z)"): VdomNode,
                        MuiMenuItem.value("Event")("Event — system event name (e.g. agent.completed)"):  VdomNode,
                      ),
                    MuiTextField
                      .label(
                        af.triggerType match {
                          case TriggerType.Cron     => "Cron Expression (e.g. 0 9 * * 1-5)"
                          case TriggerType.Interval => "Interval (ISO 8601, e.g. PT1H)"
                          case TriggerType.OneShot  => "Run At (ISO 8601, e.g. 2026-07-01T09:00:00Z)"
                          case TriggerType.Event    => "Event Name (e.g. agent.completed)"
                        },
                      )
                      .value(af.expression)
                      .fullWidth(true)
                      .onChange { e =>
                        val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                        state.setState(state.value.copy(addTriggerForm = Some(af.copy(expression = v)))).runNow()
                      }(),
                  ),
                ),
                DialogActions()(
                  MuiButton
                    .onClick(() => state.setState(state.value.copy(addTriggerForm = None)).runNow())("Cancel"),
                  MuiButton
                    .variant("contained")
                    .onClick(() => doAddTrigger().runNow())("Add"),
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
                .onClick(() => state.setState(state.value.copy(showCreate = true)).runNow())("+ New Job"),
              MuiButton
                .variant("outlined")
                .size("small")
                .onClick { () =>
                  Callback {
                    AsyncCallbackRepositories.scheduler
                      .listJobs(None, 200)
                      .flatMap { jobs =>
                        state
                          .setState(
                            state.value.copy(
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
                              TableCell()(job.scheduledAt.toString.take(19)),
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
                                      jobAction(
                                        AsyncCallbackRepositories.scheduler.triggerNow(job.id),
                                        s =>
                                          s.copy(jobs =
                                            s.jobs
                                              .map(j => if (j.id == job.id) j.copy(status = JobStatus.Running) else j),
                                          ),
                                      ).runNow(),
                                    )("Run Now"),
                                  MuiButton
                                    .size("small")
                                    .variant("outlined")
                                    .onClick(() =>
                                      state
                                        .setState(
                                          state.value.copy(editForm =
                                            Some(
                                              EditJobForm(
                                                id = job.id,
                                                name = job.name,
                                                prompt = job.prompt,
                                                maxRetries = job.maxRetries,
                                                backoffSeconds = job.backoffSeconds,
                                                backoffPolicy = job.backoffPolicy,
                                                missedRunPolicy = job.missedRunPolicy,
                                              ),
                                            ),
                                          ),
                                        )
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
                                          if (job.prompt.nonEmpty)
                                            Box.withProps(
                                              BoxOwnProps[Theme]()
                                                .setSx(
                                                  js.Dynamic.literal(mb = 1).asInstanceOf[SxProps[Theme]],
                                                ).asInstanceOf[Box.Props],
                                            )(
                                              Typography.withProps(
                                                TypographyOwnProps()
                                                  .setVariant("subtitle2").asInstanceOf[Typography.Props],
                                              )("Prompt"),
                                              Typography.withProps(
                                                TypographyOwnProps()
                                                  .setVariant("body2").setSx(
                                                    js.Dynamic
                                                      .literal(fontStyle = "italic").asInstanceOf[SxProps[Theme]],
                                                  ).asInstanceOf[Typography.Props],
                                              )(job.prompt),
                                            )
                                          else EmptyVdom,
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
                                                  .setState(
                                                    state.value.copy(
                                                      addTriggerForm = Some(
                                                        AddTriggerForm(job.id, TriggerType.Cron, ""),
                                                      ),
                                                      triggers =
                                                        if (!state.value.triggers.contains(job.id))
                                                          state.value.triggers + (job.id -> scala.List.empty)
                                                        else state.value.triggers,
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
                    ) => state.setState(state.value.copy(page = p)).runNow(),
                  )
                  .onRowsPerPageChange(e =>
                    state
                      .setState(state.value.copy(rowsPerPage = e.target.value.asInstanceOf[String].toInt, page = 0))
                      .runNow(),
                  )(),
              ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
