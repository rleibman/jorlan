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

import caliban.client.SelectionBuilder
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.graphql.client.JorlanClient
import jorlan.graphql.client.JorlanClient.SchedulerJob.SchedulerJobView
import jorlan.graphql.client.JorlanClient.SchedulerTrigger.SchedulerTriggerView
import jorlan.graphql.client.JorlanClientDecoders.given
import jorlan.web.JorlanWebApp
import jorlan.web.components.MuiButton
import net.leibman.jorlan.muiMaterial.components.*

import scala.language.unsafeNulls
import scala.scalajs.js

object SchedulerPage {

  case class State(
    jobs:     scala.List[SchedulerJob],
    triggers: Map[SchedulerJobId, scala.List[SchedulerTrigger]],
    expanded: Set[SchedulerJobId],
    loading:  Boolean,
    error:    Option[String],
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
      .useState(State(Nil, Map.empty, Set.empty, loading = true, error = None))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            JorlanWebApp
              .makeAdapter()
              .asyncCalibanCallWithAuth(
                JorlanClient.Queries.jobs(None)(JorlanClient.SchedulerJob.view),
              )
              .flatMap {
                case Some(jobs) =>
                  state
                    .setState(
                      state.value
                        .copy(jobs = jobs.map(summon[Conversion[SchedulerJobView, SchedulerJob]]), loading = false),
                    ).asAsyncCallback
                case None =>
                  state
                    .setState(state.value.copy(loading = false, error = Some("Failed to load jobs")))
                    .asAsyncCallback
              }
              .completeWith {
                case scala.util.Success(_)  => Callback.empty
                case scala.util.Failure(ex) =>
                  state.setState(state.value.copy(loading = false, error = Some(ex.getMessage))).runNow()
                  Callback.empty
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

          def loadTriggers(jobId: SchedulerJobId): Callback =
            if (state.value.triggers.contains(jobId)) Callback.empty
            else
              Callback {
                adapter
                  .asyncCalibanCallWithAuth(
                    JorlanClient.Queries.triggers(jobId)(JorlanClient.SchedulerTrigger.view),
                  )
                  .flatMap {
                    case Some(ts) =>
                      state
                        .setState(
                          state.value.copy(triggers =
                            state.value.triggers + (jobId -> ts
                              .map(summon[Conversion[SchedulerTriggerView, SchedulerTrigger]])),
                          ),
                        )
                        .asAsyncCallback
                    case None => AsyncCallback.unit
                  }
                  .completeWith(_ => Callback.empty)
                  .runNow()
              }

          def toggleExpand(jobId: SchedulerJobId): Callback =
            if (state.value.expanded.contains(jobId))
              state.setState(state.value.copy(expanded = state.value.expanded - jobId))
            else
              state.setState(state.value.copy(expanded = state.value.expanded + jobId)) >> loadTriggers(jobId)

          def jobAction(
            mut:         SelectionBuilder[caliban.client.Operations.RootMutation, Option[Boolean]],
            updateState: State => State,
          ): Callback =
            Callback {
              adapter
                .asyncCalibanCallWithAuth(mut)
                .flatMap(_ => state.setState(updateState(state.value)).asAsyncCallback)
                .completeWith {
                  case scala.util.Failure(ex) =>
                    state.setState(state.value.copy(error = Some(ex.getMessage)))
                  case _ => Callback.empty
                }
                .runNow()
            }

          <.div(
            Box.set("sx", js.Dynamic.literal(display = "flex", alignItems = "center", mb = 2, gap = 2))(
              Typography.set("variant", "h5")("Scheduler"),
              MuiButton
                .variant("outlined")
                .size("small")
                .onClick { () =>
                  Callback {
                    adapter
                      .asyncCalibanCallWithAuth(
                        JorlanClient.Queries.jobs(None)(JorlanClient.SchedulerJob.view),
                      )
                      .flatMap {
                        case Some(jobs) =>
                          state
                            .setState(
                              state.value.copy(
                                jobs = jobs.map(summon[Conversion[SchedulerJobView, SchedulerJob]]),
                                loading = false,
                              ),
                            ).asAsyncCallback
                        case None => AsyncCallback.unit
                      }
                      .completeWith(_ => Callback.empty)
                      .runNow()
                  }
                }("Refresh"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.set("severity", "error")(err)),
            if (state.value.loading)
              CircularProgress()
            else if (state.value.jobs.isEmpty)
              Alert.set("severity", "info")("No scheduler jobs found.")
            else
              TableContainer()(
                Table.set("size", "small")(
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
                    state.value.jobs.flatMap { job =>
                      val isExpanded = state.value.expanded.contains(job.id)
                      scala.List[VdomElement](
                        TableRow
                          .withKey(job.id.value.toString)(
                            TableCell()(job.name),
                            TableCell()(
                              Chip
                                .set("label", job.status.toString)
                                .set("color", statusColor(job.status))
                                .set("size", "small")(),
                            ),
                            TableCell()(job.scheduledAt.toString.take(19)),
                            TableCell()(s"${job.retryCount}/${job.maxRetries}"),
                            TableCell()(
                              Box.set("sx", js.Dynamic.literal(display = "flex", gap = 1))(
                                job.status match {
                                  case JobStatus.Paused =>
                                    MuiButton
                                      .size("small")
                                      .variant("outlined")
                                      .onClick(() =>
                                        jobAction(
                                          JorlanClient.Mutations.resumeJob(job.id),
                                          s =>
                                            s.copy(jobs =
                                              s.jobs
                                                .map(j => if (j.id == job.id) j.copy(status = JobStatus.Pending) else j),
                                            ),
                                        ).runNow(),
                                      )("Resume")
                                  case JobStatus.Running | JobStatus.Pending =>
                                    MuiButton
                                      .size("small")
                                      .variant("outlined")
                                      .onClick(() =>
                                        jobAction(
                                          JorlanClient.Mutations.pauseJob(job.id),
                                          s =>
                                            s.copy(jobs =
                                              s.jobs
                                                .map(j => if (j.id == job.id) j.copy(status = JobStatus.Paused) else j),
                                            ),
                                        ).runNow(),
                                      )("Pause")
                                  case _ => EmptyVdom
                                },
                                if (job.status != JobStatus.Cancelled && job.status != JobStatus.Succeeded)
                                  MuiButton
                                    .size("small")
                                    .variant("outlined")
                                    .set("color", "error")
                                    .onClick(() =>
                                      jobAction(
                                        JorlanClient.Mutations.cancelJob(job.id),
                                        s =>
                                          s.copy(jobs =
                                            s.jobs
                                              .map(j => if (j.id == job.id) j.copy(status = JobStatus.Cancelled) else j),
                                          ),
                                      ).runNow(),
                                    )("Cancel")
                                else EmptyVdom,
                                MuiButton
                                  .size("small")
                                  .variant("outlined")
                                  .onClick(() =>
                                    jobAction(
                                      JorlanClient.Mutations.triggerNow(job.id),
                                      s =>
                                        s.copy(jobs =
                                          s.jobs.map(j => if (j.id == job.id) j.copy(status = JobStatus.Running) else j),
                                        ),
                                    ).runNow(),
                                  )("Run Now"),
                                MuiButton
                                  .size("small")
                                  .variant("outlined")
                                  .set("color", "error")
                                  .onClick(() =>
                                    jobAction(
                                      JorlanClient.Mutations.deleteJob(job.id),
                                      s =>
                                        s.copy(
                                          jobs = s.jobs.filterNot(_.id == job.id),
                                          triggers = s.triggers - job.id,
                                          expanded = s.expanded - job.id,
                                        ),
                                    ).runNow(),
                                  )("Delete"),
                              ),
                            ),
                            TableCell()(
                              MuiButton
                                .size("small")
                                .onClick(() => toggleExpand(job.id).runNow())(if (isExpanded) "▲" else "▼"),
                            ),
                          ).build,
                      ) ++ (if (isExpanded) {
                              val ts = state.value.triggers.getOrElse(job.id, scala.Nil)
                              scala.List[VdomElement](
                                TableRow
                                  .withKey(s"${job.id.value}-triggers")(
                                    TableCell.colSpan(6)(
                                      Box.set("sx", js.Dynamic.literal(pl = 4, pt = 1, pb = 1))(
                                        Typography
                                          .set("variant", "subtitle2")
                                          .set("sx", js.Dynamic.literal(mb = 1))("Triggers"),
                                        if (ts.isEmpty)
                                          <.span("No triggers configured.")
                                        else
                                          Table.set("size", "small")(
                                            TableHead()(
                                              TableRow()(
                                                TableCell()("Type"),
                                                TableCell()("Expression"),
                                                TableCell()("Enabled"),
                                              ),
                                            ),
                                            TableBody()(
                                              ts.map { t =>
                                                TableRow
                                                  .withKey(t.id.value.toString)(
                                                    TableCell()(t.triggerType.toString),
                                                    TableCell()(<.code(t.expression)),
                                                    TableCell()(if (t.enabled) "✓" else "✗"),
                                                  ).build
                                              }*,
                                            ),
                                          ),
                                      ),
                                    ),
                                  ).build,
                              )
                            } else scala.Nil)
                    }*,
                  ),
                ),
              ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
