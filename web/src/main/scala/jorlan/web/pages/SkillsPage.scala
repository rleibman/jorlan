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

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.AsyncCallbackRepositories
import jorlan.web.pages.PageUtils
import jorlan.web.components.MuiButton
import net.leibman.jorlan.muiMaterial.components.*

import scala.language.unsafeNulls
import scala.scalajs.js

object SkillsPage {

  case class State(
    skills:   List[SkillInfo],
    loading:  Boolean,
    error:    Option[String],
    expanded: Set[String],
    toggling: Set[String],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(Nil, loading = true, error = None, expanded = Set.empty, toggling = Set.empty))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories.skill
              .listSkills()
              .flatMap { skills =>
                state.setState(state.value.copy(skills = skills, loading = false)).asAsyncCallback
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
          def toggleExpand(name: String): Callback =
            if (state.value.expanded.contains(name))
              state.setState(state.value.copy(expanded = state.value.expanded - name))
            else
              state.setState(state.value.copy(expanded = state.value.expanded + name))

          def toggleEnabled(skill: SkillInfo): Callback =
            Callback {
              state.modState(s => s.copy(toggling = s.toggling + skill.name)).runNow()
              val action =
                if (skill.enabled) AsyncCallbackRepositories.skill.disableSkill(skill.name)
                else AsyncCallbackRepositories.skill.enableSkill(skill.name)
              action
                .flatMap { _ =>
                  val updated =
                    state.value.skills.map(s => if (s.name == skill.name) s.copy(enabled = !skill.enabled) else s)
                  state
                    .modState(s =>
                      s.copy(
                        skills = s.skills.map(existing =>
                          if (existing.name == skill.name) existing.copy(enabled = !existing.enabled) else existing,
                        ),
                        toggling = s.toggling - skill.name,
                      ),
                    )
                    .asAsyncCallback
                }
                .completeWith(
                  PageUtils.onError(err => state.modState(s => s.copy(toggling = s.toggling - skill.name, error = err))),
                )
                .runNow()
            }

          def renderToolCard(tool: SkillToolInfo): VdomElement = {
            val capSection: VdomElement =
              if (tool.requiredCapabilities.nonEmpty)
                Box.set("sx", js.Dynamic.literal(display = "flex", flexWrap = "wrap", gap = 0.5, mb = 1))(
                  Typography
                    .set("variant", "caption")
                    .set("sx", js.Dynamic.literal(mr = 0.5, alignSelf = "center", color = "text.secondary"))(
                      "Requires:",
                    ),
                  <.span(
                    tool.requiredCapabilities.map(cap =>
                      Chip
                        .withKey(cap)
                        .set("label", cap)
                        .set("size", "small")
                        .set("variant", "outlined")(),
                    )*,
                  ),
                )
              else <.span()

            val exampleSection: VdomElement =
              if (tool.examplePrompts.nonEmpty)
                Box.set("sx", js.Dynamic.literal(mt = 0.5))(
                  Typography
                    .set("variant", "caption")
                    .set("sx", js.Dynamic.literal(color = "text.secondary", display = "block", mb = 0.5))(
                      "Example prompts:",
                    ),
                  <.div(
                    tool.examplePrompts.map(prompt =>
                      Typography
                        .withKey(prompt)
                        .set("variant", "body2")
                        .set("sx", js.Dynamic.literal(fontStyle = "italic", color = "text.secondary", pl = 1))(
                          prompt,
                        ),
                    )*,
                  ),
                )
              else <.span()

            Box
              .withKey(tool.name)
              .set(
                "sx",
                js.Dynamic.literal(border = "1px solid", borderColor = "divider", borderRadius = 1, p = 1.5, mb = 1),
              )(
                Typography
                  .set("variant", "subtitle2")
                  .set("sx", js.Dynamic.literal(fontFamily = "monospace", mb = 0.5))(tool.name),
                Typography
                  .set("variant", "body2")
                  .set("sx", js.Dynamic.literal(mb = 1, color = "text.secondary"))(tool.description),
                capSection,
                exampleSection,
              )
          }

          <.div(
            Box.set("sx", js.Dynamic.literal(display = "flex", alignItems = "center", mb = 2, gap = 2))(
              Typography.set("variant", "h5")("Skill Registry"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.set("severity", "error")(err)),
            if (state.value.loading)
              CircularProgress()
            else if (state.value.skills.isEmpty)
              Alert.set("severity", "info")("No skills registered.")
            else
              TableContainer()(
                Table.set("size", "small")(
                  TableHead()(
                    TableRow()(
                      TableCell()(),
                      TableCell()("Skill"),
                      TableCell()("Tier"),
                      TableCell()("Tools"),
                      TableCell()("Enabled"),
                    ),
                  ),
                  TableBody()(
                    state.value.skills.flatMap { skill =>
                      val isExpanded = state.value.expanded.contains(skill.name)
                      val isToggling = state.value.toggling.contains(skill.name)
                      scala.List[VdomElement](
                        TableRow
                          .withKey(skill.name)(
                            TableCell.set("sx", js.Dynamic.literal(width = "40px"))(
                              MuiButton
                                .size("small")
                                .onClick(() => toggleExpand(skill.name).runNow())(if (isExpanded) "▲" else "▼"),
                            ),
                            TableCell()(
                              Typography
                                .set("variant", "body2")
                                .set(
                                  "sx",
                                  js.Dynamic.literal(
                                    color = if (skill.enabled) "text.primary" else "text.disabled",
                                    fontStyle = if (skill.enabled) "normal" else "italic",
                                  ),
                                )(skill.name),
                            ),
                            TableCell()(
                              Chip.set("label", skill.tier.toString).set("size", "small")(),
                            ),
                            TableCell()(
                              Typography
                                .set("variant", "body2")
                                .set("sx", js.Dynamic.literal(color = "text.secondary"))(
                                  s"${skill.tools.size} tool${if (skill.tools.size == 1) "" else "s"}",
                                ),
                            ),
                            TableCell()(
                              if (isToggling)
                                CircularProgress.set("size", 20)()
                              else
                                Switch
                                  .set("checked", skill.enabled)
                                  .set("size", "small")
                                  .set("onChange", (_: js.Any) => toggleEnabled(skill).runNow())(),
                            ),
                          ),
                      ) ++ (
                        if (isExpanded)
                          scala.List[VdomElement](
                            TableRow
                              .withKey(s"${skill.name}-detail")(
                                TableCell.colSpan(5)(
                                  Box.set("sx", js.Dynamic.literal(p = 1.5))(
                                    skill.tools.map(renderToolCard)*,
                                  ),
                                ),
                              ),
                          )
                        else scala.Nil
                      )
                    }*,
                  ),
                ),
              ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
