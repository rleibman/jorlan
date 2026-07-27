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
import net.leibman.jorlan.muiMaterial.tableTableMod.TableOwnProps
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

/** Editor for a [[Pipeline]]'s ordered step list: table of steps with reorder/edit/delete, "+ Add Step", and an inline
  * per-step editor. Shared between [[jorlan.web.pages.SchedulerPage]]'s "Edit Pipeline" dialog and
  * [[CreateSchedulerJobWizard]]'s step-building screen.
  */
object PipelineStepsEditor {

  val defaultStep: PipelineStep = PipelineStep(
    name = "",
    systemPrompt = "",
    userPrompt = "",
    tools = List.empty,
    mode = StepMode.ReactLoop,
    outputVar = "",
    retryOnFail = 0,
  )

  case class Props(
    steps:       List[PipelineStep],
    editingStep: Option[Int],
    onChange:    (List[PipelineStep], Option[Int]) => Callback,
  )

  val component =
    ScalaFnComponent[Props] { props =>
      val editingStep = props.editingStep.flatMap(i => props.steps.lift(i))

        <.div(
          Box.withProps(
            BoxOwnProps[Theme]()
              .setSx(
                js.Dynamic
                  .literal(display = "flex", alignItems = "center", gap = 1)
                  .asInstanceOf[SxProps[Theme]],
              ).asInstanceOf[Box.Props],
          )(
            Typography.withProps(
              TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props],
            )("Steps"),
            MuiButton
              .variant("outlined")
              .size("small")
              .onClick(() =>
                props
                  .onChange(props.steps :+ defaultStep, Some(props.steps.size))
                  .runNow(),
              )("+ Add Step"),
          ),
          if (props.steps.isEmpty)
            <.span("No steps yet. Add a step to define this pipeline.")
          else
            Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
              TableHead()(
                TableRow()(
                  TableCell()("#"),
                  TableCell()("Name"),
                  TableCell()("Mode"),
                  TableCell()("Output Var"),
                  TableCell()(""),
                ),
              ),
              TableBody()(
                props.steps.zipWithIndex.map { case (step, idx) =>
                  TableRow
                    .withKey(s"step-$idx")(
                      TableCell()(s"${idx + 1}"),
                      TableCell()(step.name),
                      TableCell()(step.mode.toString),
                      TableCell()(<.code(step.outputVar)),
                      TableCell()(
                        Box.withProps(
                          BoxOwnProps[Theme]()
                            .setSx(
                              js.Dynamic.literal(display = "flex", gap = 1).asInstanceOf[SxProps[Theme]],
                            ).asInstanceOf[Box.Props],
                        )(
                          MuiButton
                            .size("small")
                            .variant("outlined")
                            .disabled(idx == 0)
                            .onClick(() => {
                              val swapped = props.steps.updated(idx, props.steps(idx - 1)).updated(idx - 1, step)
                              props.onChange(swapped, props.editingStep).runNow()
                            })("▲"),
                          MuiButton
                            .size("small")
                            .variant("outlined")
                            .disabled(idx == props.steps.size - 1)
                            .onClick(() => {
                              val swapped = props.steps.updated(idx, props.steps(idx + 1)).updated(idx + 1, step)
                              props.onChange(swapped, props.editingStep).runNow()
                            })("▼"),
                          MuiButton
                            .size("small")
                            .variant("outlined")
                            .onClick(() => props.onChange(props.steps, Some(idx)).runNow())("Edit"),
                          MuiButton
                            .size("small")
                            .variant("outlined")
                            .color("error")
                            .onClick(() => {
                              val remaining = props.steps.patch(idx, Nil, 1)
                              props.onChange(remaining, None).runNow()
                            })("Delete"),
                        ),
                      ),
                    ).build
                }*,
              ),
            ),
          editingStep.fold(EmptyVdom) { step =>
            val idx = props.editingStep.getOrElse(0)
            def updateStep(ns: PipelineStep): Callback =
              props.onChange(props.steps.updated(idx, ns), props.editingStep)

            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(
                      border = "1px solid #ddd",
                      borderRadius = 1,
                      p = 2,
                      display = "flex",
                      flexDirection = "column",
                      gap = 2,
                    )
                    .asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography.withProps(
                TypographyOwnProps().setVariant("subtitle2").asInstanceOf[Typography.Props],
              )(s"Editing Step ${idx + 1}"),
              MuiTextField
                .label("Step Name")
                .value(step.name)
                .fullWidth(true)
                .onChange { e =>
                  val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                  updateStep(step.copy(name = v)).runNow()
                }(),
              MuiTextField
                .label("Output Variable")
                .value(step.outputVar)
                .fullWidth(true)
                .onChange { e =>
                  val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                  updateStep(step.copy(outputVar = v)).runNow()
                }(),
              Typography.withProps(
                TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props],
              )("Step Mode"),
              MuiSelect
                .value(step.mode.toString)
                .fullWidth(true)
                .onChange { e =>
                  val v = e.target.asInstanceOf[org.scalajs.dom.html.Select].value
                  val m = StepMode.values.find(_.toString == v).getOrElse(StepMode.ReactLoop)
                  updateStep(step.copy(mode = m)).runNow()
                }(
                  MuiMenuItem.value("ReactLoop")("ReactLoop — full tool-using ReAct loop"): VdomNode,
                  MuiMenuItem.value("SingleCall")(
                    "SingleCall — single LLM call, no tools (pure reasoning)",
                  ): VdomNode,
                ),
              MuiTextField
                .label("System Prompt")
                .value(step.systemPrompt)
                .fullWidth(true)
                .multiline(true)
                .rows(4)
                .onChange { e =>
                  val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                  updateStep(step.copy(systemPrompt = v)).runNow()
                }(),
              MuiTextField
                .label(
                  "User Prompt (supports {{invariants.KEY}}, {{steps.NAME.output}}, {{now}}, {{run.context}})",
                )
                .value(step.userPrompt)
                .fullWidth(true)
                .multiline(true)
                .rows(4)
                .onChange { e =>
                  val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                  updateStep(step.copy(userPrompt = v)).runNow()
                }(),
              Typography.withProps(
                TypographyOwnProps().setVariant("caption").asInstanceOf[Typography.Props],
              )("Tools available to this step (none selected = the step runs with no tools)"),
              ToolSelector(step.tools, ts => updateStep(step.copy(tools = ts))),
              MuiTextField
                .label("Retry on Fail")
                .value(step.retryOnFail.toString)
                .`type`("number")
                .fullWidth(true)
                .onChange { e =>
                  val v = e.target.asInstanceOf[org.scalajs.dom.html.Input].value
                  updateStep(step.copy(retryOnFail = v.toIntOption.getOrElse(0))).runNow()
                }(),
              MuiButton
                .size("small")
                .variant("outlined")
                .onClick(() => props.onChange(props.steps, None).runNow())("Done editing step"),
            )
          },
        )
      }

  def apply(
    steps:       List[PipelineStep],
    editingStep: Option[Int],
    onChange:    (List[PipelineStep], Option[Int]) => Callback,
  ): VdomElement = component(Props(steps, editingStep, onChange))

}
