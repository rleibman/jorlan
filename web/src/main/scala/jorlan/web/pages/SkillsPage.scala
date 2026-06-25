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
import jorlan.web.components.{MuiButton, Toast, ToastMessage, ToastSeverity}
import jorlan.web.pages.PageUtils
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}
import net.leibman.jorlan.muiMaterial.internalSwitchBaseMod.SwitchBaseProps
import net.leibman.jorlan.muiMaterial.switchSwitchMod.SwitchProps
import org.scalajs.dom
import org.scalajs.dom.html

import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.tableTableMod.TableOwnProps
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

object SkillsPage {

  case class State(
    skills:           List[SkillInfo],
    loading:          Boolean,
    error:            Option[String],
    expanded:         Set[String],
    toggling:         Set[String],
    configuring:      Set[String],
    loadedModules:    Set[String],
    configJson:       Map[String, Option[String]],
    configSaving:     Set[String],
    configError:      Map[String, String],
    validating:       Set[String],
    validationResult: Map[String, SkillValidationResult],
    toast:            Option[ToastMessage],
    oauthConnected:   Map[OAuthProvider, Boolean],
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
          validating = Set.empty,
          validationResult = Map.empty,
          toast = None,
          oauthConnected = Map.empty[OAuthProvider, Boolean],
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
                val providers = skills.flatMap(_.oauthProvider).distinct
                val checkProviders: AsyncCallback[Map[OAuthProvider, Boolean]] =
                  AsyncCallback
                    .traverse(providers) { provider =>
                      AsyncCallbackRepositories.extCredential
                        .oauthStatus(provider.toString)
                        .map(statusOpt => provider -> statusOpt.exists(_.connected))
                    }.map(_.toMap)
                checkProviders.flatMap { connected =>
                  state
                    .setState(state.value.copy(skills = skills, loading = false, oauthConnected = connected))
                    .asAsyncCallback
                }
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

          def validateSkill(skill: SkillInfo): Callback =
            Callback {
              state.modState(s => s.copy(validating = s.validating + skill.name)).runNow()
              AsyncCallbackRepositories
                .skillValidate(skill.name)
                .flatMap { resultOpt =>
                  val result = resultOpt.getOrElse(
                    SkillValidationResult(ok = false, message = "No response from server"),
                  )
                  state
                    .modState(s =>
                      s.copy(
                        validating = s.validating - skill.name,
                        validationResult = s.validationResult + (skill.name -> result),
                      ),
                    )
                    .asAsyncCallback
                }
                .completeWith(
                  PageUtils.onError(err =>
                    state.modState(s =>
                      s.copy(
                        validating = s.validating - skill.name,
                        validationResult = s.validationResult +
                          (skill.name -> SkillValidationResult(ok = false, message = err.getOrElse("Unknown error"))),
                      ),
                    ),
                  ),
                )
                .runNow()
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
                        toast = Some(ToastMessage(s"Configuration saved for '${skill.name}'", ToastSeverity.Success)),
                      ),
                    )
                    .asAsyncCallback
                    .flatMap(_ => validateSkill(skill).asAsyncCallback)
                }
                .completeWith(
                  PageUtils.onError(err =>
                    state.modState(s =>
                      s.copy(
                        configSaving = s.configSaving - skill.name,
                        configError = s.configError + (skill.name -> err.getOrElse("Unknown error")),
                        toast = Some(ToastMessage(err.getOrElse("Failed to save configuration"), ToastSeverity.Error)),
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
                      Box.withProps(
                        BoxOwnProps[Theme]()
                          .setSx(
                            js.Dynamic
                              .literal(display = "flex", alignItems = "center", gap = 1, mt = 1).asInstanceOf[SxProps[
                                Theme,
                              ]],
                          ).asInstanceOf[Box.Props],
                      )(
                        CircularProgress.size(20)(),
                        Typography.withProps(
                          TypographyOwnProps()
                            .setVariant("body2").setSx(
                              js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                            ).asInstanceOf[Typography.Props],
                        )(
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
                      Box.withProps(
                        BoxOwnProps[Theme]()
                          .setSx(
                            js.Dynamic
                              .literal(
                                mt = 1,
                                p = 1.5,
                                border = "1px solid",
                                borderColor = "divider",
                                borderRadius = 1,
                              ).asInstanceOf[SxProps[Theme]],
                          ).asInstanceOf[Box.Props],
                      )(
                        Box.withProps(
                          BoxOwnProps[Theme]()
                            .setSx(
                              js.Dynamic
                                .literal(
                                  display = "flex",
                                  justifyContent = "space-between",
                                  alignItems = "center",
                                  mb = 1,
                                ).asInstanceOf[SxProps[Theme]],
                            ).asInstanceOf[Box.Props],
                        )(
                          Typography.withProps(
                            TypographyOwnProps().setVariant("subtitle2").asInstanceOf[Typography.Props],
                          )("Configuration"),
                          if (state.value.configSaving.contains(skill.name))
                            CircularProgress.size(18)()
                          else <.span(),
                        ),
                        state.value.configError
                          .get(skill.name)
                          .fold(EmptyVdom)(err =>
                            Alert.severity("error").sx(js.Dynamic.literal(mb = 1).asInstanceOf[SxProps[Theme]])(err),
                          ),
                        element,
                        Box.withProps(
                          BoxOwnProps[Theme]()
                            .setSx(
                              js.Dynamic
                                .literal(display = "flex", alignItems = "center", gap = 1, mt = 1.5)
                                .asInstanceOf[SxProps[Theme]],
                            ).asInstanceOf[Box.Props],
                        )(
                          if (state.value.validating.contains(skill.name) || state.value.configSaving.contains(skill.name))
                            CircularProgress.size(18)()
                          else
                            MuiButton
                              .size("small")
                              .variant("outlined")
                              .onClick { () =>
                                val json = state.value.configJson.get(skill.name).flatten.getOrElse("{}")
                                saveConfig(skill, json).runNow()
                              }("Validate"),
                          state.value.validationResult
                            .get(skill.name)
                            .fold(EmptyVdom) { r =>
                              Chip.withProps(
                                js.Dynamic
                                  .literal(
                                    label = r.message,
                                    color = if (r.ok) "success" else "error",
                                    size = "small",
                                    variant = "outlined",
                                  ).asInstanceOf[Chip.Props],
                              )()
                            },
                        ),
                      )
                  }
                }
            }

          def renderToolCard(tool: SkillToolInfo): VdomElement = {
            val capSection: VdomElement =
              if (tool.requiredCapabilities.nonEmpty)
                Box.withProps(
                  BoxOwnProps[Theme]()
                    .setSx(
                      js.Dynamic
                        .literal(display = "flex", flexWrap = "wrap", gap = 0.5, mb = 1).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Box.Props],
                )(
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("caption").setSx(
                        js.Dynamic
                          .literal(mr = 0.5, alignSelf = "center", color = "text.secondary").asInstanceOf[SxProps[
                            Theme,
                          ]],
                      ).asInstanceOf[Typography.Props],
                  )(
                    "Requires:",
                  ),
                  <.span(
                    tool.requiredCapabilities.map(cap =>
                      Chip
                        .withProps(
                          ChipOwnProps().setLabel(cap).setSize("small").setVariant("outlined").asInstanceOf[Chip.Props],
                        ).withKey(cap)(),
                    )*,
                  ),
                )
              else <.span()

            val exampleSection: VdomElement =
              if (tool.examplePrompts.nonEmpty)
                Box.withProps(
                  BoxOwnProps[Theme]()
                    .setSx(js.Dynamic.literal(mt = 0.5).asInstanceOf[SxProps[Theme]]).asInstanceOf[Box.Props],
                )(
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("caption").setSx(
                        js.Dynamic
                          .literal(color = "text.secondary", display = "block", mb = 0.5).asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )(
                    "Example prompts:",
                  ),
                  <.div(
                    tool.examplePrompts.map(prompt =>
                      Typography
                        .withProps(
                          TypographyOwnProps()
                            .setVariant("body2").setSx(
                              js.Dynamic
                                .literal(fontStyle = "italic", color = "text.secondary", pl = 1).asInstanceOf[SxProps[
                                  Theme,
                                ]],
                            ).asInstanceOf[Typography.Props],
                        ).withKey(prompt)(
                          prompt,
                        ),
                    )*,
                  ),
                )
              else <.span()

            Box
              .withProps(
                BoxOwnProps[Theme]()
                  .setSx(
                    js.Dynamic
                      .literal(
                        border = "1px solid",
                        borderColor = "divider",
                        borderRadius = 1,
                        p = 1.5,
                        mb = 1,
                      ).asInstanceOf[SxProps[Theme]],
                  ).asInstanceOf[Box.Props],
              ).withKey(tool.name)(
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("subtitle2").setSx(
                      js.Dynamic.literal(fontFamily = "monospace", mb = 0.5).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Typography.Props],
                )(tool.name),
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("body2").setSx(
                      js.Dynamic.literal(mb = 1, color = "text.secondary").asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Typography.Props],
                )(tool.description),
                capSection,
                exampleSection,
              )
          }

          <.div(
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", alignItems = "center", mb = 2, gap = 2).asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography
                .withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])("Skill Registry"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            if (state.value.loading)
              CircularProgress()
            else if (state.value.skills.isEmpty)
              Alert.severity("info")("No skills registered.")
            else
              TableContainer()(
                Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
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
                      val providerConnected =
                        skill.oauthProvider.forall(p => state.value.oauthConnected.getOrElse(p, false))

                      scala.List[VdomElement](
                        TableRow
                          .withKey(skill.name)(
                            TableCell.sx(js.Dynamic.literal(width = "40px").asInstanceOf[SxProps[Theme]])(
                              MuiButton
                                .size("small")
                                .onClick(() => toggleExpand(skill.name).runNow())(if (isExpanded) "▲" else "▼"),
                            ),
                            TableCell()(
                              Box.withProps(
                                BoxOwnProps[Theme]()
                                  .setSx(
                                    js.Dynamic
                                      .literal(display = "flex", alignItems = "center", gap = 1)
                                      .asInstanceOf[SxProps[Theme]],
                                  ).asInstanceOf[Box.Props],
                              )(
                                Typography.withProps(
                                  TypographyOwnProps()
                                    .setVariant("body2")
                                    .setSx(
                                      js.Dynamic
                                        .literal(
                                          color = if (skill.enabled) "text.primary" else "text.disabled",
                                          fontStyle = if (skill.enabled) "normal" else "italic",
                                        ).asInstanceOf[SxProps[Theme]],
                                    )
                                    .asInstanceOf[Typography.Props],
                                )(skill.name),
                                if (!providerConnected)
                                  Chip.withProps(
                                    ChipOwnProps()
                                      .setLabel(
                                        s"Connect ${skill.oauthProvider.fold("account")(_.toString)} to enable",
                                      )
                                      .setSize("small")
                                      .setColor("warning")
                                      .setVariant("outlined")
                                      .asInstanceOf[Chip.Props],
                                  )()
                                else EmptyVdom,
                              ),
                            ),
                            TableCell()(
                              Chip.withProps(
                                ChipOwnProps().setLabel(skill.tier.toString).setSize("small").asInstanceOf[Chip.Props],
                              )(),
                            ),
                            TableCell()(
                              Typography.withProps(
                                TypographyOwnProps()
                                  .setVariant("body2").setSx(
                                    js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                                  ).asInstanceOf[Typography.Props],
                              )(
                                s"${skill.tools.size} tool${if (skill.tools.size == 1) "" else "s"}",
                              ),
                            ),
                            TableCell()(
                              if (isToggling)
                                CircularProgress.size(20)()
                              else
                                Switch.withProps(
                                  SwitchProps()
                                    .asInstanceOf[SwitchBaseProps]
                                    .setChecked(skill.enabled)
                                    .setDisabled(!providerConnected)
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
                                Box.withProps(
                                  BoxOwnProps[Theme]()
                                    .setSx(
                                      js.Dynamic
                                        .literal(
                                          display = "flex",
                                          flexWrap = "wrap",
                                          gap = 0.5,
                                          mb = 1.5,
                                          alignItems = "center",
                                        ).asInstanceOf[SxProps[Theme]],
                                    ).asInstanceOf[Box.Props],
                                )(
                                  Typography.withProps(
                                    TypographyOwnProps()
                                      .setVariant("caption").setSx(
                                        js.Dynamic
                                          .literal(
                                            color = "text.secondary",
                                            mr = 0.5,
                                            alignSelf = "center",
                                          ).asInstanceOf[SxProps[Theme]],
                                      ).asInstanceOf[Typography.Props],
                                  )(
                                    "Keywords:",
                                  ),
                                  <.span(
                                    skill.keywords.map(kw =>
                                      Chip
                                        .withProps(
                                          ChipOwnProps()
                                            .setLabel(kw).setSize("small").setVariant("outlined").setSx(
                                              js.Dynamic.literal(fontFamily = "monospace").asInstanceOf[SxProps[Theme]],
                                            ).asInstanceOf[Chip.Props],
                                        ).withKey(kw)(),
                                    )*,
                                  ),
                                ),
                              )
                            else List.empty
                          val configRow: List[VdomElement] =
                            if (skill.configJsModule.isDefined)
                              List(
                                Box.withProps(
                                  BoxOwnProps[Theme]()
                                    .setSx(
                                      js.Dynamic.literal(mt = 1, mb = 1).asInstanceOf[SxProps[Theme]],
                                    ).asInstanceOf[Box.Props],
                                )(
                                  renderConfigPanel(skill),
                                ),
                              )
                            else List.empty
                          val detailChildren: List[VdomElement] =
                            keywordsRow ++ configRow ++ skill.tools.map(renderToolCard)
                          scala
                            .List[VdomElement](
                              TableRow
                                .withKey(s"${skill.name}-detail")(
                                  TableCell
                                    .colSpan(6)(
                                      Box
                                        .withProps(
                                          BoxOwnProps[Theme]()
                                            .setSx(
                                              js.Dynamic.literal(p = 1.5).asInstanceOf[SxProps[Theme]],
                                            ).asInstanceOf[Box.Props],
                                        )(detailChildren*),
                                    ),
                                ),
                            )
                        } else scala.List.empty
                      )
                    }*,
                  ),
                ),
              ),
            Toast(
              message = state.value.toast,
              onClose = state.modState(_.copy(toast = None)),
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
