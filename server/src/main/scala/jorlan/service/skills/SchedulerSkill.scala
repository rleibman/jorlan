/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.skills

import jorlan.*
import jorlan.connector.{InvocationContext, Skill, SkillDescriptor, ToolDescriptor}
import jorlan.*
import jorlan.service.JobManager
import just.semver.SemVer
import zio.*
import zio.json.ast.Json

/** Tier 0 scheduler skill — agent-directed job management.
  *
  * Exposes [[JobManager]] operations as named tools callable from the ReAct tool-calling loop via [[SkillRegistry]].
  */
class SchedulerSkill(jobManager: JobManager) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name = "scheduler",
    tier = SkillTier.BuiltIn,
    skillVersion = SemVer.parse(skill.BuildInfo.version).getOrElse(skill.BuildInfo.version),
    keywords = List(
      "schedule",
      "cron",
      "job",
      "timer",
      "recurring",
      "automation",
      "trigger",
      "task",
      "reminder",
      "periodic",
      "interval",
      "delay",
      "run at",
      "every",
      "daily",
      "weekly",
      "hourly",
    ),
    tools = List(
      ToolDescriptor(
        name = "scheduler.create_job",
        description = "Create a new scheduled job that runs on a cron expression. Returns the created job ID.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"name":{"type":"string","description":"Human-readable job name"},"cronExpression":{"type":"string","description":"Cron expression (e.g. '0 10 * * *' for daily at 10am)"},"input":{"type":"string","description":"Optional JSON input to pass to the job"}},"required":["name","cronExpression"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("object")),
        requiredCapabilities = List(CapabilityName("scheduler.manage")),
        examplePrompts = List(
          "Remind me every morning at 9am to check my email",
          "Schedule a daily summary report at 6pm",
          "Run a cleanup job every Sunday at midnight",
        ),
      ),
      ToolDescriptor(
        name = "scheduler.list_jobs",
        description = "List all scheduled jobs for the current agent. Each job has a `status` field reflecting its LAST EXECUTION outcome (Pending=waiting to run, Running=currently executing, Success=last run succeeded, Failed=last run failed, Paused=manually paused, Cancelled=cancelled). A Failed status means the job's last execution encountered an error — the job itself still exists and may have a next run scheduled. Use this tool to report the list of scheduled jobs, including any that failed.",
        inputSchema = Json.decoder.decodeJson("""{"type":"object","properties":{}}""").getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("array")),
        requiredCapabilities = List(CapabilityName("scheduler.manage")),
        examplePrompts = List(
          "What scheduled jobs do I have?",
          "Show me all my recurring tasks",
          "List my active reminders",
        ),
      ),
      ToolDescriptor(
        name = "scheduler.pause_job",
        description = "Pause a scheduled job so it stops firing until resumed.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"id":{"type":"string","description":"Scheduler job ID"}},"required":["id"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("boolean")),
        requiredCapabilities = List(CapabilityName("scheduler.manage")),
        examplePrompts = List(
          "Pause the daily report job",
          "Stop sending me the morning reminder for now",
          "Pause job 42 while I'm on vacation",
        ),
      ),
      ToolDescriptor(
        name = "scheduler.resume_job",
        description = "Resume a previously paused scheduled job.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"id":{"type":"string","description":"Scheduler job ID"}},"required":["id"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("boolean")),
        requiredCapabilities = List(CapabilityName("scheduler.manage")),
        examplePrompts = List(
          "Resume the daily summary job",
          "Start sending the morning reminders again",
          "Unpause job 42",
        ),
      ),
      ToolDescriptor(
        name = "scheduler.cancel_job",
        description = "Permanently cancel and delete a scheduled job.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"id":{"type":"string","description":"Scheduler job ID"}},"required":["id"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("boolean")),
        requiredCapabilities = List(CapabilityName("scheduler.manage")),
        examplePrompts = List(
          "Cancel the weekly report job permanently",
          "Delete the morning reminder, I don't need it anymore",
          "Remove job 42",
        ),
      ),
      ToolDescriptor(
        name = "scheduler.trigger_now",
        description = "Trigger an immediate run of an existing scheduled job outside its cron schedule.",
        inputSchema = Json.decoder
          .decodeJson(
            """{"type":"object","properties":{"id":{"type":"string","description":"Scheduler job ID"}},"required":["id"]}""",
          ).getOrElse(Json.Obj()),
        outputSchema = Json.Obj("type" -> Json.Str("boolean")),
        requiredCapabilities = List(CapabilityName("scheduler.manage")),
        examplePrompts = List(
          "Run the report job right now",
          "Trigger the cleanup job immediately",
          "Don't wait for the schedule, run job 42 now",
        ),
      ),
    ),
  )

  override def invoke(
    ctx:  InvocationContext,
    tool: String,
    args: Json,
  ): IO[JorlanError, Json] = {
    def field(name: String): IO[JorlanError, String] =
      args match {
        case Json.Obj(fields) =>
          fields
            .collectFirst { case (`name`, Json.Str(v)) => v }
            .fold(ZIO.fail(ValidationError(s"missing field '$name'")): IO[JorlanError, String])(ZIO.succeed(_))
        case _ => ZIO.fail(ValidationError("args must be a JSON object"))
      }

    def optField(name: String): Option[String] =
      args match {
        case Json.Obj(fields) => fields.collectFirst { case (`name`, Json.Str(v)) => v }
        case _                => None
      }

    def parseJobId(idStr: String): IO[JorlanError, SchedulerJobId] =
      ZIO
        .fromOption(idStr.toLongOption.map(SchedulerJobId(_)))
        .orElseFail(ValidationError(s"invalid job id: $idStr"))

    tool match {
      case "scheduler.create_job" =>
        for {
          name     <- field("name")
          cronExpr <- field("cronExpression")
          agentId = ctx.agentId.getOrElse(AgentId.empty)
          prompt = optField("prompt").getOrElse(optField("input").getOrElse(""))
          job <- createJob(agentId, ctx.actorId, name, prompt, optField("input"))
          // Add the cron trigger after job creation
          now <- Clock.instant
          _   <- jobManager
            .addTrigger(
              job.id,
              SchedulerTrigger(
                id = SchedulerTriggerId.empty,
                jobId = job.id,
                triggerType = TriggerType.Cron,
                expression = cronExpr,
                enabled = true,
                createdAt = now,
              ),
            ).tapError(e =>
              jobManager.cancelJob(job.id).ignore *>
                ZIO.logWarning(s"scheduler.create_job: trigger creation failed, job ${job.id} cancelled: ${e.msg}"),
            )
        } yield Json.Obj(
          "id"   -> Json.Str(job.id.value.toString),
          "name" -> Json.Str(job.name),
        )

      case "scheduler.list_jobs" =>
        for {
          agentId <- ZIO.succeed(ctx.agentId.getOrElse(AgentId.empty))
          jobs    <- listJobs(agentId)
        } yield Json.Arr(jobs.map { j =>
          val base = List(
            "id"          -> Json.Str(j.id.value.toString),
            "name"        -> Json.Str(j.name),
            "status"      -> Json.Str(j.status.toString),
            "scheduledAt" -> Json.Str(j.scheduledAt.toString),
          )
          val withResult = j.resultJson match {
            case Some(r) => base :+ ("lastResult" -> Json.Str(r))
            case None    => base
          }
          Json.Obj(withResult*)
        }*)

      case "scheduler.pause_job" =>
        for {
          idStr <- field("id")
          id    <- parseJobId(idStr)
          _     <- pauseJob(id)
        } yield Json.Bool(true)

      case "scheduler.resume_job" =>
        for {
          idStr <- field("id")
          id    <- parseJobId(idStr)
          _     <- resumeJob(id)
        } yield Json.Bool(true)

      case "scheduler.cancel_job" =>
        for {
          idStr <- field("id")
          id    <- parseJobId(idStr)
          _     <- cancelJob(id)
        } yield Json.Bool(true)

      case "scheduler.trigger_now" =>
        for {
          idStr <- field("id")
          id    <- parseJobId(idStr)
          _     <- triggerNow(id)
        } yield Json.Bool(true)

      case other =>
        ZIO.fail(ValidationError(s"unknown tool '$other'"))
    }
  }

  def createJob(
    agentId:         AgentId,
    userId:          UserId,
    name:            String,
    prompt:          String,
    inputJson:       Option[String],
    maxRetries:      Int = 0,
    backoffSeconds:  Int = 60,
    backoffPolicy:   RetryBackoffPolicy = RetryBackoffPolicy.Fixed,
    missedRunPolicy: MissedRunPolicy = MissedRunPolicy.Skip,
  ): IO[JorlanError, SchedulerJob] =
    jobManager.createJob(
      agentId,
      userId,
      name,
      prompt,
      inputJson,
      maxRetries,
      backoffSeconds,
      backoffPolicy,
      missedRunPolicy,
    )

  def listJobs(agentId: AgentId): IO[JorlanError, List[SchedulerJob]] =
    jobManager.listJobs(Some(agentId))

  def pauseJob(id: SchedulerJobId): IO[JorlanError, Unit] =
    jobManager.pauseJob(id)

  def resumeJob(id: SchedulerJobId): IO[JorlanError, Unit] =
    jobManager.resumeJob(id)

  def cancelJob(id: SchedulerJobId): IO[JorlanError, Unit] =
    jobManager.cancelJob(id)

  def triggerNow(id: SchedulerJobId): IO[JorlanError, Unit] =
    jobManager.triggerNow(id)

}

object SchedulerSkill
