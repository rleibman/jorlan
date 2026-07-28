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
import net.leibman.jorlan.muiMaterial.listItemListItemMod.ListItemOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

/** Tree-style tool picker: one expandable node per skill with a tri-state checkbox, individual tool checkboxes
  * underneath, and Select all / Select none shortcuts.
  *
  * The `selected` value uses the same compressed representation as `PipelineStep.tools` and `submitMessage`'s
  * `allowedTools`: a fully-selected skill collapses to its name (a namespace prefix covering every tool in the skill);
  * partially-selected skills contribute exact tool names. An empty list means no tools. The component fetches the
  * skill/tool catalog itself, so callers only hold the selection.
  */
object ToolSelector {

  case class Props(
    selected: List[String],
    onChange: List[String] => Callback,
  )

  /** Expands compressed entries (skill names / namespace prefixes) to the exact tool names present in `skills`. */
  private def expand(
    selected: List[String],
    skills:   List[SkillInfo],
  ): Set[String] = {
    val allTools = skills.flatMap(_.tools.map(_.name)).toSet
    selected.flatMap { entry =>
      if (allTools.contains(entry)) Set(entry)
      else {
        val bySkill = skills.filter(_.name == entry).flatMap(_.tools.map(_.name)).toSet
        if (bySkill.nonEmpty) bySkill else allTools.filter(_.startsWith(entry + "."))
      }
    }.toSet
  }

  /** Compresses a set of exact tool names: fully-selected skills become their skill name. */
  private def compress(
    selected: Set[String],
    skills:   List[SkillInfo],
  ): List[String] =
    skills.flatMap { skill =>
      val toolNames = skill.tools.map(_.name)
      if (toolNames.nonEmpty && toolNames.forall(selected.contains)) List(skill.name)
      else toolNames.filter(selected.contains)
    }

  val component =
    ScalaFnComponent
      .withHooks[Props]
      .useState(List.empty[SkillInfo])
      .useState(Set.empty[String]) // expanded skill nodes
      .useEffectOnMountBy {
        (
          _,
          skills,
          _,
        ) =>
          AsyncCallbackRepositories.skill
            .listSkills()
            .flatMap(s => skills.setState(s.filter(s => s.enabled && s.tools.nonEmpty).sortBy(_.name)).asAsyncCallback)
            .toCallback
      }
      .render {
        (
          props,
          skillsState,
          expandedState,
        ) =>
          val skills = skillsState.value
          val selected = expand(props.selected, skills)
          val allToolNames = skills.flatMap(_.tools.map(_.name)).toSet

          def setSelected(newSelection: Set[String]): Callback =
            props.onChange(compress(newSelection, skills))

          if (skills.isEmpty) CircularProgress(): VdomElement
          else
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(border = "1px solid #ccc", borderRadius = 1, p = 1, maxHeight = 360, overflowY = "auto")
                    .asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Box.withProps(
                BoxOwnProps[Theme]()
                  .setSx(
                    js.Dynamic
                      .literal(display = "flex", alignItems = "center", gap = 1)
                      .asInstanceOf[SxProps[Theme]],
                  ).asInstanceOf[Box.Props],
              )(
                MuiButton
                  .size("small")
                  .variant("outlined")
                  .onClick(() => setSelected(allToolNames).runNow())("Select all"),
                MuiButton
                  .size("small")
                  .variant("outlined")
                  .onClick(() => setSelected(Set.empty).runNow())("Select none"),
                Typography.withProps(
                  TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props],
                )(s"${selected.size} of ${allToolNames.size} tools selected"),
              ),
              MuiList()(
                skills.flatMap { skill =>
                  val toolNames = skill.tools.map(_.name)
                  val selectedCount = toolNames.count(selected.contains)
                  val allChecked = selectedCount == toolNames.size && toolNames.nonEmpty
                  val isExpanded = expandedState.value.contains(skill.name)

                  val skillRow: VdomNode = ListItem
                    .withProps(
                      ListItemOwnProps().setDisablePadding(true).asInstanceOf[ListItem.Props],
                    ).withKey(s"skill-${skill.name}")(
                      Checkbox
                        .checked(allChecked)
                        .indeterminate(selectedCount > 0 && !allChecked)
                        .onChange {
                          (
                            _,
                            checked,
                          ) =>
                            if (checked) setSelected(selected ++ toolNames)
                            else setSelected(selected -- toolNames)
                        }(),
                      MuiListItemButton
                        .onClick(() =>
                          expandedState
                            .modState(e => if (e.contains(skill.name)) e - skill.name else e + skill.name)
                            .runNow(),
                        )(
                          ListItemText.primary(s"${skill.name} ($selectedCount/${toolNames.size})")(),
                          <.span(if (isExpanded) "▲" else "▼"),
                        ),
                    )

                  val toolRows: VdomNode = Collapse
                    .in(isExpanded)
                    .withKey(s"tools-${skill.name}")(
                      MuiList()(
                        skill.tools.map { tool =>
                          ListItem
                            .withProps(
                              ListItemOwnProps().setDisablePadding(true).asInstanceOf[ListItem.Props],
                            ).withKey(s"tool-${tool.name}")(
                              Box.withProps(
                                BoxOwnProps[Theme]()
                                  .setSx(js.Dynamic.literal(pl = 4).asInstanceOf[SxProps[Theme]])
                                  .asInstanceOf[Box.Props],
                              )(
                                Checkbox
                                  .size("small")
                                  .checked(selected.contains(tool.name))
                                  .onChange {
                                    (
                                      _,
                                      checked,
                                    ) =>
                                      if (checked) setSelected(selected + tool.name)
                                      else setSelected(selected - tool.name)
                                  }(),
                                tool.name,
                              ),
                            ).build
                        }*,
                      ),
                    )

                  List(skillRow, toolRows)
                }*,
              ),
            )
      }

  def apply(
    selected: List[String],
    onChange: List[String] => Callback,
  ): VdomElement = component(Props(selected, onChange))

}
