/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.service.skills.declarative

import zio.json.*
import zio.json.ast.Json

/** Pure validation for declarative skill manifests.
  *
  * @return
  *   `Right(manifest)` if valid, `Left(errors)` with one or more human-readable error messages otherwise.
  */
object ManifestValidator {

  private val skillNamePattern = "^[a-z][a-z0-9_]*$".r

  def validate(manifestJson: Json): Either[List[String], DeclarativeSkillManifest] =
    manifestJson.as[DeclarativeSkillManifest] match {
      case Left(err) => Left(List(s"Manifest JSON parse error: $err"))
      case Right(m)  =>
        val errors = validateManifest(m)
        if (errors.isEmpty) Right(m) else Left(errors)
    }

  private def require(
    cond: Boolean,
    msg:  => String,
  ): List[String] = if (cond) Nil else List(msg)

  private def validateManifest(m: DeclarativeSkillManifest): List[String] = {
    val nameErrors = require(
      skillNamePattern.matches(m.name),
      s"Skill name '${m.name}' must match [a-z][a-z0-9_]* (lowercase, no spaces)",
    )
    val versionErrors = require(m.version.nonEmpty, "version must not be empty")
    val descErrors = require(m.description.nonEmpty, "description must not be empty")
    val toolErrors =
      if (m.tools.isEmpty) List("tools list must not be empty")
      else m.tools.flatMap(validateTool(m.name, _))

    nameErrors ++ versionErrors ++ descErrors ++ toolErrors
  }

  private def validateTool(
    skillName: String,
    t:         DeclarativeToolDef,
  ): List[String] = {
    val prefix = s"$skillName."
    val nameErrors = require(
      t.name.startsWith(prefix) && t.name.length > prefix.length,
      s"Tool name '${t.name}' must be prefixed with skill name: '$prefix<toolName>'",
    )
    val descErrors = require(t.description.nonEmpty, s"Tool '${t.name}': description must not be empty")
    val schemaErrors = validateSchema(t.name, "inputSchema", t.inputSchema)
    val executorErrors = validateExecutor(t.name, t.executor)

    nameErrors ++ descErrors ++ schemaErrors ++ executorErrors
  }

  private def validateSchema(
    toolName:   String,
    schemaName: String,
    schema:     Json,
  ): List[String] =
    schema match {
      case Json.Obj(_) => Nil
      case _           => List(s"Tool '$toolName': $schemaName must be a JSON object")
    }

  private def validateExecutor(
    toolName: String,
    executor: ExecutorConfig,
  ): List[String] =
    executor match {
      case ExecutorConfig.HttpApi(cfg) =>
        require(
          List("GET", "POST", "PUT", "DELETE", "PATCH").contains(cfg.method.toUpperCase),
          s"Tool '$toolName': HTTP method '${cfg.method}' is not supported",
        ) ++
          require(cfg.url.nonEmpty, s"Tool '$toolName': URL must not be empty")

      case ExecutorConfig.PromptTemplate(cfg) =>
        require(cfg.systemPrompt.nonEmpty, s"Tool '$toolName': systemPrompt must not be empty") ++
          require(cfg.userPromptTemplate.nonEmpty, s"Tool '$toolName': userPromptTemplate must not be empty")
    }

}
