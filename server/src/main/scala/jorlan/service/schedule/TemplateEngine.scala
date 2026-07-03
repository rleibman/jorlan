/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.schedule

import jorlan.*

import java.time.Instant
import scala.language.unsafeNulls

/** Substitutes `{{...}}` template variables in pipeline step prompts and builds the full prompt with injected blocks.
  *
  * Recognized template variables:
  *   - `{{invariants.KEY}}` — value from the merged invariants map
  *   - `{{steps.NAME.output}}` — output of the step whose `outputVar == NAME`
  *   - `{{steps.NAME.status}}` — status of that step (`"success"` | `"failed"` | `"skipped"`)
  *   - `{{pipeline.run_id}}` — the `PipelineRunId` as a string
  *   - `{{now}}` — current ISO-8601 timestamp
  *   - `{{run.context}}` — the free-form run context provided at trigger time
  */
object TemplateEngine {

  /** Substitute all `{{...}}` variables in `template` using the supplied resolution function. */
  private def substitute(
    template: String,
    resolve:  String => Option[String],
  ): String = {
    val pattern = """\{\{([^}]+)\}\}""".r
    pattern.replaceAllIn(
      template,
      m => {
        val key = m.group(1).trim
        resolve(key).getOrElse(m.matched)
      },
    )
  }

  /** Render the complete user prompt for a pipeline step:
    *
    *   1. Substitute template variables in `step.userPrompt`.
    *   2. Prepend the `=== INVARIANTS ===` block if the merged invariants map is non-empty.
    *   3. Insert the `=== THIS RUN ONLY ===` block after invariants if `runContext` is non-blank.
    *
    * @param step
    *   The pipeline step whose `userPrompt` is to be rendered.
    * @param invariants
    *   Merged agent + pipeline invariants (pipeline values override agent values).
    * @param stepContext
    *   Accumulated step outputs: `outputVar -> output text`.
    * @param runId
    *   The pipeline run ID (substituted into `{{pipeline.run_id}}`).
    * @param runContext
    *   Optional free-form instructions for this run; injected as the `THIS RUN ONLY` block.
    * @param now
    *   Current timestamp (substituted into `{{now}}`).
    */
  def renderUserPrompt(
    step:        PipelineStep,
    invariants:  Map[String, String],
    stepContext: Map[String, String],
    runId:       PipelineRunId,
    runContext:  Option[String],
    now:         Instant,
  ): String = {
    def resolve(key: String): Option[String] =
      if (key.startsWith("invariants."))
        invariants.get(key.stripPrefix("invariants."))
      else if (key.startsWith("steps.") && key.endsWith(".output"))
        stepContext.get(key.stripPrefix("steps.").stripSuffix(".output"))
      else if (key.startsWith("steps.") && key.endsWith(".status")) {
        val varName = key.stripPrefix("steps.").stripSuffix(".status")
        Some(if (stepContext.contains(varName)) "success" else "not_run")
      } else if (key == "pipeline.run_id") Some(runId.value.toString)
      else if (key == "now") Some(now.toString)
      else if (key == "run.context") runContext
      else None

    val renderedPrompt = substitute(step.userPrompt, resolve)

    val invariantsBlock =
      if (invariants.isEmpty) ""
      else {
        val lines = invariants.map { case (k, v) => s"$k: $v" }.mkString("\n")
        s"=== INVARIANTS ===\n$lines\n=== END INVARIANTS ===\n\n"
      }

    val runContextBlock = runContext.filter(_.trim.nonEmpty).fold("") { ctx =>
      s"=== THIS RUN ONLY ===\n$ctx\n=== END THIS RUN ONLY ===\n\n"
    }

    invariantsBlock + runContextBlock + renderedPrompt
  }

  /** Accuracy-mode system prompt: minimal, instruction-following, no personality. */
  val accuracySystemPrompt: String =
    """You are a precise, reliable assistant. Follow instructions exactly.
      |Produce only what is asked. If a constraint says "no X", never include X.
      |When outputting structured data, produce valid JSON matching the requested schema.
      |Do not explain your reasoning unless explicitly asked.
      |Do not add unsolicited commentary.""".stripMargin

}
