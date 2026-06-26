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
import net.leibman.jorlan.muiMaterial.stepperStepperMod.StepperOwnProps
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import zio.json.*

import scala.language.unsafeNulls
import scala.scalajs.js

/** 5-step wizard for creating a declarative custom skill. */
object CreateSkillWizard {

  val stepLabels: List[String] = List(
    "Executor Type",
    "Skill Info",
    "Tool Definition",
    "Permissions",
    "Review & Submit",
  )

  case class WizardState(
    step:             Int = 0,
    executorType:     String = "http_api",
    skillName:        String = "",
    skillVersion:     String = "1.0.0",
    description:      String = "",
    keywords:         String = "",
    toolName:         String = "",
    toolDescription:  String = "",
    httpMethod:       String = "GET",
    httpUrl:          String = "",
    httpHeaders:      String = "",
    httpBody:         String = "",
    systemPrompt:     String = "",
    userPrompt:       String = "",
    capabilities:     String = "",
    examplePrompts:   String = "",
    createdVersionId: Option[Long] = None,
    lifecycleStatus:  Option[SkillStatus] = None,
    lifecycleErrors:  List[String] = List.empty,
    lifecycleInfo:    List[String] = List.empty,
    saving:           Boolean = false,
    advancing:        Boolean = false,
    error:            Option[String] = None,
    toast:            Option[ToastMessage] = None,
  )

  case class Props(
    user:      User,
    onClose:   Callback,
    onCreated: Callback,
  )

  private def buildManifestJson(s: WizardState): String = {
    val qualifiedToolName =
      if (s.toolName.startsWith(s.skillName + ".")) s.toolName
      else s.skillName + "." + s.toolName

    val executor = if (s.executorType == "http_api") {
      val headersMap = s.httpHeaders.linesIterator
        .map(_.trim)
        .filter(_.contains(":"))
        .map { line =>
          val idx = line.indexOf(':')
          (line.take(idx).trim, line.drop(idx + 1).trim)
        }
        .toMap

      s"""|{"HttpApi":{"config":{"method":${s.httpMethod.toJson},"url":${s.httpUrl.toJson},"headers":${headersMap.toJson},"bodyTemplate":${
          if (s.httpBody.nonEmpty) s.httpBody.toJson + "" else "null"
        },"responseJsonPath":null}}}"""
    } else {
      s"""|{"PromptTemplate":{"config":{"systemPrompt":${s.systemPrompt.toJson},"userPromptTemplate":${s.userPrompt.toJson}}}}"""
    }

    val caps = s.capabilities.split(",").map(_.trim).filter(_.nonEmpty)
    val prompts = s.examplePrompts.split("\n").map(_.trim).filter(_.nonEmpty)

    s"""|{
        |  "name": ${s.skillName.toJson},
        |  "version": ${s.skillVersion.toJson},
        |  "description": ${s.description.toJson},
        |  "keywords": ${s.keywords.split(",").map(_.trim).filter(_.nonEmpty).toList.toJson},
        |  "tools": [{
        |    "name": ${qualifiedToolName.toJson},
        |    "description": ${s.toolDescription.toJson},
        |    "requiredCapabilities": ${caps.toList.toJson},
        |    "examplePrompts": ${prompts.toList.toJson},
        |    "inputSchema": {"type":"object","properties":{}},
        |    "outputSchema": {"type":"string"},
        |    "executor": $executor
        |  }]
        |}""".stripMargin
  }

  val component =
    ScalaFnComponent
      .withHooks[Props]
      .useState(WizardState())
      .render {
        (
          props,
          state,
        ) =>
          def setField(f: WizardState => WizardState): Callback =
            state.modState(f)

          def nextStep(): Callback = state.modState(s => s.copy(step = (s.step + 1).min(stepLabels.length - 1)))
          def prevStep(): Callback = state.modState(s => s.copy(step = (s.step - 1).max(0)))

          def createDraft(): Callback =
            Callback {
              state.modState(_.copy(saving = true)).runNow()
              val manifest = buildManifestJson(state.value)
              AsyncCallbackRepositories.skillLifecycle
                .createSkillDraft(manifest)
                .flatMap {
                  case Some(sv) =>
                    state
                      .modState(
                        _.copy(
                          saving = false,
                          createdVersionId = Some(sv.id),
                          lifecycleStatus = Some(sv.status),
                          lifecycleErrors = List.empty,
                        ),
                      )
                      .asAsyncCallback
                  case None =>
                    state
                      .modState(_.copy(saving = false, error = Some("Failed to create skill draft.")))
                      .asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) =>
                    state.modState(_.copy(saving = false, error = Some(ex.getMessage)))
                  case _ => Callback.empty
                }
                .runNow()
            }

          def advance(): Callback =
            state.value.createdVersionId.fold(Callback.empty) { versionId =>
              Callback {
                state.modState(_.copy(advancing = true)).runNow()
                AsyncCallbackRepositories.skillLifecycle
                  .advanceSkillLifecycle(versionId)
                  .flatMap {
                    case Some(result) =>
                      val done = result.newStatus == SkillStatus.AwaitingApproval
                      state
                        .modState(
                          _.copy(
                            advancing = false,
                            lifecycleStatus = Some(result.newStatus),
                            lifecycleErrors = result.errors,
                            lifecycleInfo = result.info,
                            toast =
                              if (done)
                                Some(ToastMessage("Skill submitted for approval.", ToastSeverity.Success))
                              else None,
                          ),
                        )
                        .asAsyncCallback >> (if (done) props.onCreated.asAsyncCallback else AsyncCallback.unit)
                    case None =>
                      state
                        .modState(_.copy(advancing = false, error = Some("Advance returned no result.")))
                        .asAsyncCallback
                  }
                  .completeWith {
                    case scala.util.Failure(ex) =>
                      state.modState(_.copy(advancing = false, error = Some(ex.getMessage)))
                    case _ => Callback.empty
                  }
                  .runNow()
              }
            }

          val sv = state.value

          def stepContent: VdomElement =
            sv.step match {
              case 0 =>
                <.div(
                  Typography.withProps(TypographyOwnProps().setVariant("body1").asInstanceOf[Typography.Props])(
                    "Choose how the skill executes its tools:",
                  ),
                  <.div(
                    ^.style := js.Dynamic.literal(marginTop = "16px"),
                    <.label(
                      <.input(
                        ^.`type`  := "radio",
                        ^.name    := "executorType",
                        ^.value   := "http_api",
                        ^.checked := sv.executorType == "http_api",
                        ^.onChange --> setField(_.copy(executorType = "http_api")),
                      ),
                      " HTTP / API call — call an external REST endpoint",
                    ),
                    <.br(),
                    <.label(
                      <.input(
                        ^.`type`  := "radio",
                        ^.name    := "executorType",
                        ^.value   := "prompt_template",
                        ^.checked := sv.executorType == "prompt_template",
                        ^.onChange --> setField(_.copy(executorType = "prompt_template")),
                      ),
                      " LLM Prompt Template — ask the model with a structured prompt",
                    ),
                  ),
                )

              case 1 =>
                <.div(
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", flexDirection = "column", gap = 2)
                          .asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Box.Props],
                  )(
                    MuiTextField
                      .label("Skill Name (lowercase, e.g. weather)")
                      .value(sv.skillName)
                      .fullWidth(true)
                      .onChange(e => setField(_.copy(skillName = e.target.value.toString)).runNow()),
                    MuiTextField
                      .label("Version")
                      .value(sv.skillVersion)
                      .fullWidth(true)
                      .onChange(e => setField(_.copy(skillVersion = e.target.value.toString)).runNow()),
                    MuiTextField
                      .label("Description")
                      .value(sv.description)
                      .fullWidth(true)
                      .multiline(true)
                      .rows(2)
                      .onChange(e => setField(_.copy(description = e.target.value.toString)).runNow()),
                    MuiTextField
                      .label("Keywords (comma-separated)")
                      .value(sv.keywords)
                      .fullWidth(true)
                      .onChange(e => setField(_.copy(keywords = e.target.value.toString)).runNow()),
                  ),
                )

              case 2 =>
                <.div(
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", flexDirection = "column", gap = 2)
                          .asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Box.Props],
                  )(
                    MuiTextField
                      .label(s"Tool Name (auto-prefixed with ${sv.skillName}.)")
                      .value(sv.toolName)
                      .fullWidth(true)
                      .onChange(e => setField(_.copy(toolName = e.target.value.toString)).runNow()),
                    MuiTextField
                      .label("Tool Description")
                      .value(sv.toolDescription)
                      .fullWidth(true)
                      .multiline(true)
                      .rows(2)
                      .onChange(e => setField(_.copy(toolDescription = e.target.value.toString)).runNow()),
                    if (sv.executorType == "http_api") {
                      <.div(
                        Box.withProps(
                          BoxOwnProps[Theme]()
                            .setSx(
                              js.Dynamic
                                .literal(display = "flex", flexDirection = "column", gap = 2)
                                .asInstanceOf[SxProps[Theme]],
                            ).asInstanceOf[Box.Props],
                        )(
                          MuiTextField
                            .label("HTTP Method (GET, POST, PUT, DELETE)")
                            .value(sv.httpMethod)
                            .fullWidth(true)
                            .onChange(e => setField(_.copy(httpMethod = e.target.value.toString)).runNow()),
                          MuiTextField
                            .label("URL (use {{param}} for substitution)")
                            .value(sv.httpUrl)
                            .fullWidth(true)
                            .onChange(e => setField(_.copy(httpUrl = e.target.value.toString)).runNow()),
                          MuiTextField
                            .label("Headers (one per line: Key: Value)")
                            .value(sv.httpHeaders)
                            .fullWidth(true)
                            .multiline(true)
                            .rows(3)
                            .onChange(e => setField(_.copy(httpHeaders = e.target.value.toString)).runNow()),
                          MuiTextField
                            .label("Body Template (optional, use {{param}})")
                            .value(sv.httpBody)
                            .fullWidth(true)
                            .multiline(true)
                            .rows(3)
                            .onChange(e => setField(_.copy(httpBody = e.target.value.toString)).runNow()),
                        ),
                      )
                    } else {
                      <.div(
                        Box.withProps(
                          BoxOwnProps[Theme]()
                            .setSx(
                              js.Dynamic
                                .literal(display = "flex", flexDirection = "column", gap = 2)
                                .asInstanceOf[SxProps[Theme]],
                            ).asInstanceOf[Box.Props],
                        )(
                          MuiTextField
                            .label("System Prompt")
                            .value(sv.systemPrompt)
                            .fullWidth(true)
                            .multiline(true)
                            .rows(3)
                            .onChange(e => setField(_.copy(systemPrompt = e.target.value.toString)).runNow()),
                          MuiTextField
                            .label("User Prompt Template (use {{param}})")
                            .value(sv.userPrompt)
                            .fullWidth(true)
                            .multiline(true)
                            .rows(3)
                            .onChange(e => setField(_.copy(userPrompt = e.target.value.toString)).runNow()),
                        ),
                      )
                    },
                  ),
                )

              case 3 =>
                <.div(
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", flexDirection = "column", gap = 2)
                          .asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Box.Props],
                  )(
                    MuiTextField
                      .label("Required Capabilities (comma-separated)")
                      .value(sv.capabilities)
                      .fullWidth(true)
                      .onChange(e => setField(_.copy(capabilities = e.target.value.toString)).runNow()),
                    MuiTextField
                      .label("Example Prompts (one per line)")
                      .value(sv.examplePrompts)
                      .fullWidth(true)
                      .multiline(true)
                      .rows(4)
                      .onChange(e => setField(_.copy(examplePrompts = e.target.value.toString)).runNow()),
                  ),
                )

              case 4 =>
                val manifest = buildManifestJson(sv)
                <.div(
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", flexDirection = "column", gap = 2)
                          .asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Box.Props],
                  )(
                    Typography.withProps(TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props])(
                      "Manifest Preview:",
                    ),
                    <.pre(
                      ^.style := js.Dynamic.literal(
                        background = "#f5f5f5",
                        padding = "8px",
                        overflow = "auto",
                        maxHeight = "200px",
                        fontSize = "0.8em",
                      ),
                      manifest,
                    ),
                    sv.error.fold(EmptyVdom)(e => Alert.severity("error")(e)),
                    sv.lifecycleErrors.toTagMod(e => Alert.severity("error")(e)),
                    sv.lifecycleInfo.toTagMod(i => Alert.severity("info")(i)),
                    sv.lifecycleStatus.fold(EmptyVdom) { status =>
                      Alert.severity("success")(s"Status: $status")
                    },
                    Box.withProps(
                      BoxOwnProps[Theme]()
                        .setSx(
                          js.Dynamic
                            .literal(display = "flex", gap = 1)
                            .asInstanceOf[SxProps[Theme]],
                        ).asInstanceOf[Box.Props],
                    )(
                      if (sv.createdVersionId.isEmpty) {
                        MuiButton
                          .variant("contained")
                          .disabled(sv.saving)
                          .onClick(() => createDraft().runNow())("Create Draft")
                      } else {
                        val canAdvance = sv.lifecycleStatus.exists(s =>
                          s == SkillStatus.Draft || s == SkillStatus.Validated ||
                            s == SkillStatus.PermissionReviewed || s == SkillStatus.SandboxTested,
                        )
                        MuiButton
                          .variant("contained")
                          .disabled(sv.advancing || !canAdvance)
                          .onClick(() => advance().runNow())("Advance")
                      },
                    ),
                  ),
                )

              case _ => <.span()
            }

          Dialog(true)(
            DialogTitle()(s"Create Custom Skill — ${stepLabels(sv.step.min(stepLabels.length - 1))}"),
            DialogContent()(
              Stepper.withProps(
                StepperOwnProps().setActiveStep(sv.step.toDouble).asInstanceOf[Stepper.Props],
              )(
                stepLabels.zipWithIndex.map { case (label, idx) =>
                  Step.withKey(idx.toString)(
                    StepLabel()(label),
                  )
                }*,
              ),
              <.div(^.style := js.Dynamic.literal(marginTop = "24px"))(
                stepContent,
              ),
            ),
            DialogActions()(
              MuiButton.onClick(() => props.onClose.runNow())("Cancel"),
              if (sv.step > 0 && sv.step < stepLabels.length - 1) {
                MuiButton.onClick(() => prevStep().runNow())("Back")
              } else EmptyVdom,
              if (sv.step < stepLabels.length - 1) {
                MuiButton
                  .variant("contained")
                  .onClick(() => nextStep().runNow())("Next")
              } else EmptyVdom,
            ),
          )
      }

  def apply(
    user:      User,
    onClose:   Callback,
    onCreated: Callback,
  ): VdomElement = component(Props(user, onClose, onCreated))

}
