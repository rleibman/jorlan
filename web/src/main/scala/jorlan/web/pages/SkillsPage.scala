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
import jorlan.web.components.MuiButton
import jorlan.web.pages.PageUtils
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}
import net.leibman.jorlan.muiMaterial.internalSwitchBaseMod.SwitchBaseProps
import net.leibman.jorlan.muiMaterial.switchSwitchMod.SwitchProps
import org.scalajs.dom
import org.scalajs.dom.html

import scala.language.unsafeNulls
import scala.scalajs.js

object SkillsPage {

  case class State(
    skills:        List[SkillInfo],
    loading:       Boolean,
    error:         Option[String],
    expanded:      Set[String],
    toggling:      Set[String],
    configuring:   Set[String],
    loadedModules: Set[String],
    configJson:    Map[String, Option[String]],
    configSaving:  Set[String],
    configError:   Map[String, String],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(
          skills = List.empty,
          loading = true,
          error = None,
          expanded = Set.empty,
          toggling = Set.empty,
          configuring = Set.empty,
          loadedModules = Set.empty,
          configJson = Map.empty,
          configSaving = Set.empty,
          configError = Map.empty,
        ),
      )
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

          def openConfigure(skill: SkillInfo): Callback =
            Callback {
              skill.configJsModule.foreach { jsModule =>
                state
                  .modState(s =>
                    s.copy(
                      configuring = s.configuring + skill.name,
                      expanded = s.expanded + skill.name,
                    ),
                  )
                  .runNow()
                val onModuleLoaded: Callback =
                  state.modState(s => s.copy(loadedModules = s.loadedModules + jsModule))
                AsyncCallbackRepositories.skill
                  .getSkillConfig(skill.name)
                  .flatMap { jsonOpt =>
                    state
                      .modState(s => s.copy(configJson = s.configJson + (skill.name -> jsonOpt)))
                      .asAsyncCallback
                      .flatMap { _ =>
                        injectSkillScript(jsModule, onModuleLoaded).asAsyncCallback
                      }
                  }
                  .completeWith(
                    PageUtils.onError(err =>
                      state.modState(s =>
                        s.copy(configError = s.configError + (skill.name -> err.getOrElse("Unknown error"))),
                      ),
                    ),
                  )
                  .runNow()
              }
            }

          def saveConfig(
            skill: SkillInfo,
            json:  String,
          ): Callback =
            Callback {
              state.modState(s => s.copy(configSaving = s.configSaving + skill.name)).runNow()
              AsyncCallbackRepositories.skill
                .updateSkillConfig(skill.name, json)
                .flatMap { _ =>
                  state
                    .modState(s =>
                      s.copy(
                        configSaving = s.configSaving - skill.name,
                        configJson = s.configJson + (skill.name -> Some(json)),
                        configError = s.configError - skill.name,
                      ),
                    )
                    .asAsyncCallback
                }
                .completeWith(
                  PageUtils.onError(err =>
                    state.modState(s =>
                      s.copy(
                        configSaving = s.configSaving - skill.name,
                        configError = s.configError + (skill.name -> err.getOrElse("Unknown error")),
                      ),
                    ),
                  ),
                )
                .runNow()
            }

          def renderConfigPanel(skill: SkillInfo): VdomElement =
            skill.configJsModule match {
              case None           => <.span()
              case Some(jsModule) =>
                if (!state.value.configuring.contains(skill.name)) {
                  MuiButton
                    .size("small")
                    .onClick(() => openConfigure(skill).runNow())("Configure")
                } else {
                  val loadedReg: js.UndefOr[js.Dynamic] =
                    if (state.value.loadedModules.contains(jsModule))
                      js.Dynamic.global.skillRegistrations.selectDynamic(jsModule)
                    else
                      js.undefined
                  loadedReg.toOption match {
                    case None =>
                      Box.set("sx", js.Dynamic.literal(display = "flex", alignItems = "center", gap = 1, mt = 1))(
                        CircularProgress.set("size", 20)(),
                        Typography
                          .set("variant", "body2").set("sx", js.Dynamic.literal(color = "text.secondary"))(
                            "Loading configuration UI...",
                          ),
                      )
                    case Some(reg) =>
                      val initialStr = state.value.configJson.get(skill.name).flatten.getOrElse("{}")
                      val onSaveFn: js.Function1[String, Unit] = (json: String) => saveConfig(skill, json).runNow()
                      val props = js.Dynamic.literal(
                        initialConfigStr = initialStr,
                        onSave = onSaveFn,
                      )
                      val element = ReactDOM.createPortalLike(reg.component, props)
                      Box.set(
                        "sx",
                        js.Dynamic
                          .literal(mt = 1, p = 1.5, border = "1px solid", borderColor = "divider", borderRadius = 1),
                      )(
                        Box.set(
                          "sx",
                          js.Dynamic
                            .literal(display = "flex", justifyContent = "space-between", alignItems = "center", mb = 1),
                        )(
                          Typography.set("variant", "subtitle2")("Configuration"),
                          if (state.value.configSaving.contains(skill.name))
                            CircularProgress.set("size", 18)()
                          else <.span(),
                        ),
                        state.value.configError
                          .get(skill.name)
                          .fold(EmptyVdom)(err =>
                            Alert.set("severity", "error").set("sx", js.Dynamic.literal(mb = 1))(err),
                          ),
                        element,
                      )
                  }
                }
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
                      TableCell()(""),
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
                                Switch.withProps(
                                  SwitchProps()
                                    .asInstanceOf[SwitchBaseProps]
                                    .setChecked(skill.enabled)
                                    .setOnChange(
                                      (
                                        _,
                                        _,
                                      ) => toggleEnabled(skill),
                                    )
                                    .asInstanceOf[SwitchProps],
                                )(),
                            ),
                            TableCell()(
                              skill.configJsModule.fold[VdomNode](EmptyVdom) { _ =>
                                MuiButton
                                  .size("small")
                                  .variant("outlined")
                                  .onClick(() => openConfigure(skill).runNow())("Configure")
                              },
                            ),
                          ),
                      ) ++ (
                        if (isExpanded) {
                          val keywordsRow: List[VdomElement] =
                            if (skill.keywords.nonEmpty)
                              List(
                                Box
                                  .set(
                                    "sx",
                                    js.Dynamic
                                      .literal(
                                        display = "flex",
                                        flexWrap = "wrap",
                                        gap = 0.5,
                                        mb = 1.5,
                                        alignItems = "center",
                                      ),
                                  )(
                                    Typography
                                      .set("variant", "caption")
                                      .set(
                                        "sx",
                                        js.Dynamic.literal(color = "text.secondary", mr = 0.5, alignSelf = "center"),
                                      )(
                                        "Keywords:",
                                      ),
                                    <.span(
                                      skill.keywords.map(kw =>
                                        Chip
                                          .withKey(kw)
                                          .set("label", kw)
                                          .set("size", "small")
                                          .set("variant", "outlined")
                                          .set("sx", js.Dynamic.literal(fontFamily = "monospace"))(),
                                      )*,
                                    ),
                                  ),
                              )
                            else List.empty
                          val configRow: List[VdomElement] =
                            if (skill.configJsModule.isDefined)
                              List(
                                Box
                                  .set("sx", js.Dynamic.literal(mt = 1, mb = 1))(
                                    renderConfigPanel(skill),
                                  ),
                              )
                            else List.empty
                          val detailChildren: List[VdomElement] =
                            keywordsRow ++ configRow ++ skill.tools.map(renderToolCard)
                          scala.List[VdomElement](
                            TableRow
                              .withKey(s"${skill.name}-detail")(
                                TableCell.colSpan(6)(
                                  Box.set("sx", js.Dynamic.literal(p = 1.5))(detailChildren*),
                                ),
                              ),
                          )
                        } else scala.List.empty
                      )
                    }*,
                  ),
                ),
              ),
          )
      }

  private def injectSkillScript(
    jsModule: String,
    onLoaded: Callback,
  ): Callback =
    Callback {
      val scriptId = s"skill-script-$jsModule"
      if (dom.document.getElementById(scriptId) != null) {
        // Script tag already in DOM — module was loaded in a previous session; fire callback immediately.
        onLoaded.runNow()
      } else {
        val script = dom.document.createElement("script").asInstanceOf[html.Script]
        script.id = scriptId
        script.src = s"/skills/$jsModule-skill.js"
        script.onload = (_: dom.Event) => onLoaded.runNow()
        dom.document.body.appendChild(script)
      }
    }

  private object ReactDOM {

    def createPortalLike(
      rawComponent: js.Dynamic,
      props:        js.Dynamic,
    ): VdomElement =
      // ScalaJS-React 4.x boxes props as { a: propsValue } for all ScalaFnComponent types.
      // The raw component function reads b.a to get props, so we must pass { a: props }.
      VdomElement(
        js.Dynamic.global.React
          .createElement(rawComponent, js.Dynamic.literal(a = props))
          .asInstanceOf[japgolly.scalajs.react.facade.React.Element],
      )

  }

  def apply(user: User): VdomElement = component(user)

}
