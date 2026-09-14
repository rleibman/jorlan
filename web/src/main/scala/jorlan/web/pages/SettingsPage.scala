/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.pages

import jorlan.web.components.MuiExtensions.*
import net.leibman.jorlan.muiMaterial.muiMaterialStrings as MuiStrings
import net.leibman.jorlan.muiMaterial.components.{Button, MenuItem, TextField}
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.AsyncCallbackRepositories
import jorlan.web.components.OutlinedSelect
import net.leibman.jorlan.muiMaterial.components.*

import net.leibman.jorlan.muiMaterial.formControlFormControlMod.FormControlOwnProps
import net.leibman.jorlan.muiMaterial.stackStackMod.StackOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

object SettingsPage {

  private val availableLanguages: Seq[String] = Seq(
    "English",
    "Spanish",
    "Esperanto",
    "French",
    "German",
    "Portuguese",
    "Japanese",
    "Chinese",
    "Korean",
  )

  private val languageCodeToName: Map[String, String] = Map(
    "en" -> "English",
    "es" -> "Spanish",
    "eo" -> "Esperanto",
    "fr" -> "French",
    "de" -> "German",
    "pt" -> "Portuguese",
    "ja" -> "Japanese",
    "zh" -> "Chinese",
    "ko" -> "Korean",
  )

  private def normalizeLanguages(langs: List[String]): List[String] =
    langs.map(l => languageCodeToName.getOrElse(l, l))

  case class State(
    personality: Option[Personality],
    loading:     Boolean,
    saved:       Boolean,
    error:       Option[String],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(None, loading = true, saved = false, error = None))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories.setting
              .serverPersonality()
              .flatMap { personality =>
                val normalized = personality.map(p => p.copy(languages = normalizeLanguages(p.languages)))
                state.setState(State(normalized, loading = false, saved = false, error = None)).asAsyncCallback
              }
              .completeWith {
                case scala.util.Failure(ex) =>
                  state.modState(_.copy(loading = false, error = Some(ex.getMessage)))
                case _ => Callback.empty
              }
              .runNow()
          }
      }
      .render {
        (
          _,
          state,
        ) =>
          def update(): Callback =
            Callback {
              state.value.personality.foreach { p =>
                AsyncCallbackRepositories.setting
                  .updatePersonality(p.name, p.formality, p.languages, p.expertise, p.prompt)
                  .flatMap { saved =>
                    state.modState(_.copy(personality = saved, saved = true, error = None)).asAsyncCallback
                  }
                  .completeWith {
                    case scala.util.Failure(ex) => state.modState(_.copy(error = Some(ex.getMessage)))
                    case _                      => Callback.empty
                  }
                  .runNow()
              }
            }

          <.div(
            Typography.withProps(
              TypographyOwnProps()
                .setVariant(MuiStrings.h5).setSx(js.Dynamic.literal(mb = 3).asInstanceOf[SxProps[Theme]]).asInstanceOf[
                  Typography.Props,
                ],
            )("Settings"),
            state.value.error.fold(EmptyVdom)(err => Alert.severity(MuiStrings.error)(err)),
            if (state.value.loading) CircularProgress()
            else
              state.value.personality.fold(<.div("No personality configured")) { p =>
                Stack.withProps(
                  js.Dynamic.literal(direction = "column", spacing = 3).asInstanceOf[Stack.Props],
                )(
                  Typography
                    .withProps(TypographyOwnProps().setVariant(MuiStrings.h6).asInstanceOf[Typography.Props])(
                      "Personality",
                    ),
                  FormControl.withProps(FormControlOwnProps().setFullWidth(true).asInstanceOf[FormControl.Props])(
                    <.label("Formality"),
                    OutlinedSelect
                      .value(p.formality.toString)
                      .label("Formality")
                      .onChange(
                        (
                          e,
                          _,
                        ) =>
                          state
                            .modState(
                              _.copy(
                                personality = Some(
                                  p.copy(formality = Formality.valueOf(e.target.value.asInstanceOf[String])),
                                ),
                                saved = false,
                              ),
                            ),
                      )(
                        Formality.values.toList.map { f =>
                          MenuItem.value(f.toString)(f.toString)
                        }*,
                      ),
                  ),
                  TextField
                    .label("Personality Description (auto-generated, read-only)")
                    .value(
                      if (p.formality == Formality.Custom) ""
                      else Personality.buildSystemPrompt(p.copy(prompt = "")),
                    )
                    .multiline(true)
                    .rows(4)
                    .fullWidth(true)
                    .variant(MuiStrings.outlined)
                    .slotProps(js.Dynamic.literal(input = js.Dynamic.literal(readOnly = true)))
                    .sxStyle(js.Dynamic.literal(backgroundColor = "action.hover")), {
                    val langItems: Seq[VdomElement] = availableLanguages.map { lang =>
                      MenuItem
                        .value(lang)
                        .sxStyle(js.Dynamic.literal(fontWeight = if (p.languages.contains(lang)) "bold" else "normal"))(
                          lang,
                        ): VdomElement
                    }
                    FormControl.withProps(FormControlOwnProps().setFullWidth(true).asInstanceOf[FormControl.Props])(
                      <.label("Languages"),
                      OutlinedSelect
                        .label("Languages")
                        .multiple(true)
                        .value(p.languages.toJSArray)
                        .renderValue((selected: Any) => selected.asInstanceOf[js.Array[String]].toList.mkString(", "))
                        .onChange(
                          (
                            e,
                            _,
                          ) => {
                            val arr = e.target.value.asInstanceOf[js.Array[String]].toList
                            state
                              .modState(
                                _.copy(
                                  personality = Some(p.copy(languages = arr)),
                                  saved = false,
                                ),
                              )
                          },
                        )(langItems*),
                    )
                  },
                  TextField
                    .label("Expertise (comma-separated)")
                    .value(p.expertise.mkString(", "))
                    .fullWidth(true)
                    .variant(MuiStrings.outlined)
                    .placeholder("e.g. Scala, functional programming, distributed systems")
                    .onChange(e => {
                      val raw = e.target.value.asInstanceOf[String]
                      val items = raw.split(",").map(_.trim).filter(_.nonEmpty).toList
                      state
                        .modState(
                          _.copy(
                            personality = Some(p.copy(expertise = items)),
                            saved = false,
                          ),
                        )
                    }),
                  TextField
                    .label("Additional Personality Notes")
                    .value(p.prompt)
                    .multiline(true)
                    .rows(4)
                    .fullWidth(true)
                    .variant(MuiStrings.outlined)
                    .onChange(e =>
                      state
                        .modState(
                          _.copy(
                            personality = Some(p.copy(prompt = e.target.value.asInstanceOf[String])),
                            saved = false,
                          ),
                        ),
                    ),
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", gap = 2, alignItems = "center").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Box.Props],
                  )(
                    Button
                      .variant(MuiStrings.contained)
                      .onClick(_ => update())("Save"),
                    if (state.value.saved) Alert.severity(MuiStrings.success)("Saved!") else EmptyVdom,
                  ),
                )
              },
          )
      }

  def apply(user: User): VdomElement = component(user)

}
